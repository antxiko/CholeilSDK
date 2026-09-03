package net.krusher;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What IntroInserter promises: the intro goes in without the ROM growing,
 * without a byte landing outside the filler it declared, and with a boot
 * chain that actually starts at our stub.
 *
 * It is run here on the STOCK ROM rather than the pipeline's output, so these
 * checks say something about the step itself and not about whatever the text
 * and graphics stages happened to do that day. The collision guard is
 * exercised separately, against a ROM with a byte deliberately dirtied.
 */
@DisplayName("IntroInserter")
final class IntroInserterTest {

    private static byte[] base;
    private static byte[] out;

    @BeforeAll
    static void buildRom() throws Exception {
        Assumptions.assumeTrue(Files.exists(Paths.get(SmokeRom.BASE)),
                "base ROM '" + SmokeRom.BASE + "' not present -- it is gitignored on purpose");
        Assumptions.assumeTrue(Files.exists(Paths.get(DefaultPaths.INTRO)),
                "intro ROM '" + DefaultPaths.INTRO + "' not present");
        base = Files.readAllBytes(Paths.get(SmokeRom.BASE));
        out = IntroInserter.inject(base, Files.readAllBytes(Paths.get(DefaultPaths.INTRO)),
                base, new ArrayList<IntroInserter.Region>());
    }

    @Test
    @DisplayName("the ROM does not grow: every piece fits in the game's own filler")
    void theRomDoesNotGrow() {
        Problems p = new Problems();
        p.check(out.length == base.length, String.format(
                "the ROM grew from %d to %d bytes -- a piece spilled past the end instead of "
                        + "landing in filler", base.length, out.length));
        p.assertNone();
    }

    /**
     * The one that matters. The intro scatters ~203 KB through regions that
     * were declared unread; if a piece ever lands anywhere else, it is writing
     * over live game data, and the only visible symptom would be a crash deep
     * into a playthrough.
     */
    @Test
    @DisplayName("nothing is written outside the declared filler")
    void nothingIsWrittenOutsideTheDeclaredFiller() {
        Problems p = new Problems();
        List<int[]> allowed = new ArrayList<>();
        allowed.addAll(Arrays.asList(IntroInserter.HUECOS));
        allowed.addAll(Arrays.asList(IntroInserter.LOGO_FREED));
        allowed.add(new int[]{0x04, 4});                    // the RESET vector
        allowed.add(new int[]{0x18E, 2});                   // the header checksum
        allowed.add(new int[]{IntroInserter.LOGO_SLOT, 4}); // the boot jump table

        // Report the run a stray byte belongs to, not each byte: one piece in
        // the wrong place would otherwise be thousands of failures.
        int runStart = -1;
        for (int i = 0; i <= base.length; i++) {
            boolean stray = i < base.length && out[i] != base[i] && !covered(allowed, i);
            if (stray && runStart < 0) runStart = i;
            if (!stray && runStart >= 0) {
                p.check(false, String.format(
                        "0x%06X..0x%06X was changed but lies outside every declared free region",
                        runStart, i - 1));
                runStart = -1;
            }
        }
        p.assertNone();
    }

    private static boolean covered(List<int[]> regions, int at) {
        for (int[] r : regions) {
            if (at >= r[0] && at < r[0] + r[1]) return true;
        }
        return false;
    }

    @Test
    @DisplayName("the console boots into our stub, and the boot logo is gone")
    void theConsoleBootsIntoOurStub() {
        Problems p = new Problems();
        int reset = IntroInserter.readU32(out, 0x04);
        p.check(reset != IntroInserter.readU32(base, 0x04),
                "the RESET vector still points at the game's own entry");
        p.check(reset > 0 && reset < out.length && (reset & 1) == 0, String.format(
                "the RESET vector 0x%06X is not an even address inside the ROM", reset));
        p.bytesAt(out, reset, new int[]{0x46, 0xFC, 0x27, 0x00},
                "the RESET vector lands on the stub's move.w #0x2700,sr");
        p.check(IntroInserter.readU32(out, IntroInserter.LOGO_SLOT) == IntroInserter.LOGO_NEW,
                "the boot jump table's state 0 was not pointed at state 6");
        p.assertNone();
    }

    /**
     * Soleil checksums itself at boot against the header field, and hangs on a
     * red screen when they disagree -- which is exactly what writing 203 KB
     * into the game's own address space does if the field is not redone.
     */
    @Test
    @DisplayName("the header checksum matches what the game will compute")
    void theHeaderChecksumMatches() {
        Problems p = new Problems();
        p.check(IntroInserter.readU16(out, 0x18E) == IntroInserter.checksumSega(out, base.length),
                "the header checksum at 0x18E does not match the ROM's contents");
        p.check(IntroInserter.readU32(out, 0x1A4) == IntroInserter.readU32(base, 0x1A4),
                "the 'ROM end' field at 0x1A4 was touched -- the game checksums against it "
                        + "and hangs on a red screen if it changes");
        p.assertNone();
    }

    @Test
    @DisplayName("a byte an earlier pipeline step changed stops the build")
    void aByteAnEarlierStepChangedStopsTheBuild() throws Exception {
        byte[] intro = Files.readAllBytes(Paths.get(DefaultPaths.INTRO));
        byte[] dirty = base.clone();
        // Right where the code piece goes -- as if a relocated string had been
        // parked in that hole by TextInserter.
        dirty[0x043C00] ^= 0xFF;
        assertThrows(IllegalStateException.class,
                () -> IntroInserter.inject(dirty, intro, base, new ArrayList<IntroInserter.Region>()));
    }

    @Test
    @DisplayName("inserting into a ROM that already has an intro is refused")
    void insertingTwiceIsRefused() throws Exception {
        byte[] intro = Files.readAllBytes(Paths.get(DefaultPaths.INTRO));
        assertThrows(IllegalStateException.class,
                () -> IntroInserter.inject(out, intro, base, new ArrayList<IntroInserter.Region>()));
    }

    /**
     * The decompressor for this lives in the stub as hand-assembled 68000, so
     * a compressor that is not exactly reversible would corrupt a frame with
     * no way to notice short of watching the intro. inject() round-trips the
     * 16 real frames; this covers the shapes those frames may not contain --
     * runs at the very start and end, a run longer than one block, and noise.
     */
    @Test
    @DisplayName("the RLE compressor round-trips the awkward cases")
    void theRleCompressorRoundTrips() {
        List<byte[]> cases = new ArrayList<>();
        cases.add(new byte[0]);
        cases.add(new byte[]{0x12, 0x34});
        cases.add(new byte[64]);                              // all one value
        cases.add(bytes(0x11, 0x22, 0x00, 0x00, 0x00, 0x00, 0x33, 0x44));
        cases.add(bytes(0x00, 0x00, 0x00, 0x00, 0x11, 0x22)); // run at the start
        cases.add(bytes(0x11, 0x22, 0x00, 0x00, 0x00, 0x00)); // run at the end

        byte[] longRun = new byte[0x20000];                   // past one block's count
        Arrays.fill(longRun, (byte) 0xA5);
        cases.add(longRun);

        byte[] noise = new byte[0x20000];                     // nothing to compress
        new Random(1).nextBytes(noise);
        cases.add(noise);

        for (byte[] c : cases) {
            assertArrayEquals(c, IntroInserter.rleExpand(IntroInserter.rleCompress(c)),
                    "RLE round-trip failed on a " + c.length + "-byte case");
        }
    }

    private static byte[] bytes(int... vs) {
        byte[] out = new byte[vs.length];
        for (int i = 0; i < vs.length; i++) out[i] = (byte) vs[i];
        return out;
    }
}
