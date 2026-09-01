package net.krusher;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What IpsWriter promises: applying its patch to the base ROM reproduces the
 * built ROM exactly. Every case is checked by actually applying the patch
 * with the reader below, which follows the format spec rather than IpsWriter's
 * own idea of it -- a writer verified against itself would prove nothing.
 */
@DisplayName("IpsWriter")
final class IpsWriterTest {

    private static byte[] base;
    private static byte[] built;

    @BeforeAll
    static void buildRom() throws Exception {
        SmokeRom.require();
        base = SmokeRom.base();
        built = SmokeRom.built();
    }

    @Test
    @DisplayName("the patch turns the base ROM into the built one")
    void patchReproducesTheBuiltRom() {
        assertArrayEquals(built, apply(base, IpsWriter.build(base, built)));
    }

    @Test
    @DisplayName("it is far smaller than the ROM it patches")
    void thePatchIsSmallerThanTheRom() {
        byte[] ips = IpsWriter.build(base, built);
        assertTrue(ips.length < built.length / 2, "patch is " + ips.length
                + " bytes for a " + built.length + "-byte ROM -- something is emitting unchanged data");
    }

    @Test
    @DisplayName("it starts with PATCH and ends with EOF")
    void headerAndTerminatorAreInPlace() {
        byte[] ips = IpsWriter.build(base, built);
        assertArrayEquals(IpsWriter.MAGIC, Arrays.copyOf(ips, 5), "PATCH header");
        assertArrayEquals(IpsWriter.EOF_MARKER, Arrays.copyOfRange(ips, ips.length - 3, ips.length), "EOF marker");
    }

    @Test
    @DisplayName("an empty patch is recognised as empty")
    void anEmptyPatchIsRecognisedAsEmpty() {
        // Worth flagging at build time: it means the build changed nothing,
        // and some appliers look for the EOF marker only after reading a
        // record, so they reject a record-less patch as truncated.
        assertTrue(IpsWriter.isEmpty(IpsWriter.build(base, base.clone())), "no records");
        assertFalse(IpsWriter.isEmpty(IpsWriter.build(base, built)), "the real patch has records");
    }

    @Test
    @DisplayName("an unchanged ROM produces an empty patch")
    void anUnchangedRomProducesAnEmptyPatch() {
        byte[] ips = IpsWriter.build(base, base.clone());
        assertEquals(IpsWriter.MAGIC.length + IpsWriter.EOF_MARKER.length, ips.length,
                "nothing changed, so the patch should be header + terminator and nothing else");
        assertArrayEquals(base, apply(base, ips));
    }

    @Test
    @DisplayName("a run longer than the 16-bit size field is split across records")
    void aRunLongerThanTheSizeFieldIsSplit() {
        // Random bytes so nothing can be collapsed into an RLE record: the
        // only way to express this is several literal records.
        byte[] original = new byte[0x30000];
        byte[] patched = original.clone();
        new Random(1).nextBytes(patched);
        for (int i = 0; i < patched.length; i++) {
            if (patched[i] == 0) patched[i] = 1; // every byte must differ
        }
        byte[] ips = IpsWriter.build(original, patched);
        assertArrayEquals(patched, apply(original, ips));
        assertTrue(recordCount(ips) >= 3, "0x30000 changed bytes need at least three records, got " + recordCount(ips));
    }

    @Test
    @DisplayName("a long run of one byte is stored as RLE")
    void aLongRunOfOneByteIsStoredAsRle() {
        byte[] original = new byte[0x20000];
        byte[] patched = original.clone();
        Arrays.fill(patched, 0x1000, 0x1F000, (byte) 0xAB);
        byte[] ips = IpsWriter.build(original, patched);
        assertArrayEquals(patched, apply(original, ips));
        assertTrue(ips.length < 100, "an RLE run should cost a handful of bytes, not " + ips.length);
    }

    @Test
    @DisplayName("no record starts at the offset that encodes as EOF")
    void noRecordStartsAtTheEofOffset() {
        // A change right at 0x454f46 would otherwise write the bytes "EOF"
        // where the offset goes, and every applier would stop reading there.
        byte[] original = new byte[IpsWriter.EOF_OFFSET + 0x10];
        byte[] patched = original.clone();
        patched[IpsWriter.EOF_OFFSET] = 0x42;
        byte[] ips = IpsWriter.build(original, patched);
        assertArrayEquals(patched, apply(original, ips), "the change still lands, one byte earlier");
        for (int offset : recordOffsets(ips)) {
            assertTrue(offset != IpsWriter.EOF_OFFSET, "no record may start at 0x454f46");
        }
    }

    @Test
    @DisplayName("bytes appended past the end of the base ROM are patched in")
    void bytesAppendedPastTheBaseRomArePatchedIn() {
        byte[] original = new byte[0x100];
        byte[] patched = Arrays.copyOf(original, 0x180);
        Arrays.fill(patched, 0x100, 0x180, (byte) 0x7F);
        assertArrayEquals(patched, apply(original, IpsWriter.build(original, patched)));
    }

    @Test
    @DisplayName("a shrinking ROM is rejected")
    void aShrinkingRomIsRejected() {
        byte[] original = new byte[0x200];
        byte[] shorter = new byte[0x100];
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> IpsWriter.build(original, shorter));
        assertTrue(e.getMessage().contains("smaller"), "the message says what went wrong: " + e.getMessage());
    }

    @Test
    @DisplayName("a ROM too big for a 24-bit offset is rejected")
    void aRomTooBigForTheOffsetFieldIsRejected() {
        byte[] original = new byte[1];
        byte[] tooBig = new byte[IpsWriter.MAX_OFFSET + 2];
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> IpsWriter.build(original, tooBig));
        assertTrue(e.getMessage().contains("24-bit"), "the message says what went wrong: " + e.getMessage());
    }

    // --- a reader written from the format spec, not from IpsWriter ---

    /** Applies an IPS patch the way the spec says an applier should. */
    private static byte[] apply(byte[] rom, byte[] ips) {
        assertArrayEquals(IpsWriter.MAGIC, Arrays.copyOf(ips, 5), "PATCH header");
        byte[] out = rom.clone();
        int at = 5;
        while (true) {
            assertTrue(at + 3 <= ips.length, "patch ends without an EOF marker");
            if (Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsWriter.EOF_MARKER)) {
                assertEquals(ips.length, at + 3, "trailing bytes after the EOF marker");
                return out;
            }
            int offset = u24(ips, at);
            int size = u16(ips, at + 3);
            at += 5;
            if (size == 0) {
                int runLength = u16(ips, at);
                byte value = ips[at + 2];
                at += 3;
                out = grow(out, offset + runLength);
                Arrays.fill(out, offset, offset + runLength, value);
            } else {
                out = grow(out, offset + size);
                System.arraycopy(ips, at, out, offset, size);
                at += size;
            }
        }
    }

    /** An IPS record may write past the end of the file it patches. */
    private static byte[] grow(byte[] out, int needed) {
        return out.length >= needed ? out : Arrays.copyOf(out, needed);
    }

    private static int[] recordOffsets(byte[] ips) {
        int[] offsets = new int[recordCount(ips)];
        int at = 5;
        int n = 0;
        while (!Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsWriter.EOF_MARKER)) {
            offsets[n++] = u24(ips, at);
            int size = u16(ips, at + 3);
            at += size == 0 ? 8 : 5 + size;
        }
        return offsets;
    }

    private static int recordCount(byte[] ips) {
        int at = 5;
        int count = 0;
        while (!Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsWriter.EOF_MARKER)) {
            int size = u16(ips, at + 3);
            at += size == 0 ? 8 : 5 + size;
            count++;
        }
        return count;
    }

    private static int u24(byte[] b, int at) {
        return ((b[at] & 0xFF) << 16) | ((b[at + 1] & 0xFF) << 8) | (b[at + 2] & 0xFF);
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }
}
