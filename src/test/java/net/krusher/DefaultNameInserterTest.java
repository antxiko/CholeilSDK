package net.krusher;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What DefaultNameInserter promises: the hero gets a real name when the
 * player confirms the entry screen without typing anything, written over the
 * game's own empty-name branch without moving a single instruction.
 *
 * Patches a clone rather than the shared ROM: this is the pipeline's last
 * step and fixes the checksum itself, so it must not disturb the copy the
 * other suites are reading.
 */
@DisplayName("DefaultNameInserter")
final class DefaultNameInserterTest {

    private static TblTable table;
    private static byte[] named;

    @BeforeAll
    static void patchAClone() throws Exception {
        SmokeRom.require();
        table = TblTable.load(DefaultPaths.TBL);
        named = SmokeRom.built().clone();
        DefaultNameInserter.patch(named, table);
    }

    @Test
    @DisplayName("the empty-name branch is where we think it is")
    void emptyNameBranchIsWhereWeThinkItIs() {
        Problems p = new Problems();
        p.bytesAt(named, DefaultNameInserter.LEA_ADDR, DefaultNameInserter.LEA_BYTES,
                "the branch still opens with `lea $fe4a.w, a0`");
        for (int i = 0; i < DefaultNameInserter.MOVE_COUNT; i++) {
            int at = DefaultNameInserter.FIRST_MOVE_ADDR + i * 4;
            p.bytesAt(named, at, DefaultNameInserter.MOVE_OPCODE,
                    String.format("`move.w #imm, (a0)+` still at 0x%x", at));
        }
        p.assertNone();
    }

    @Test
    @DisplayName("the name is written as the branch's own immediates")
    void writesTheNameAsTheBranchesImmediates() {
        byte[] want = DefaultNameInserter.encodeName(table, DefaultNameInserter.DEFAULT_NAME);
        Problems p = new Problems();
        for (int i = 0; i < DefaultNameInserter.MOVE_COUNT; i++) {
            int at = DefaultNameInserter.FIRST_MOVE_ADDR + i * 4;
            p.check(named[at + 2] == want[i * 2] && named[at + 3] == want[i * 2 + 1],
                    String.format("immediate %d at 0x%x carries \"%s\"", i, at, DefaultNameInserter.DEFAULT_NAME));
        }
        p.assertNone();
    }

    @Test
    @DisplayName("the encoded name is terminated inside the bytes the branch writes")
    void theEncodedNameIsTerminatedWithinTheBuffer() {
        byte[] want = DefaultNameInserter.encodeName(table, DefaultNameInserter.DEFAULT_NAME);
        assertEquals(DefaultNameInserter.BUFFER_SIZE, want.length,
                "the payload is exactly what the branch writes");
        assertEquals((byte) 0xFF, want[DefaultNameInserter.DEFAULT_NAME.length()],
                "the name is 0xFF-terminated, as the text engine's {F1} handler expects");
    }

    @Test
    @DisplayName("the name fits the entry screen's own character cap")
    void theNameFitsTheEntryScreensCap() {
        assertTrue(DefaultNameInserter.DEFAULT_NAME.length() <= DefaultNameInserter.MAX_NAME_LENGTH,
                "\"" + DefaultNameInserter.DEFAULT_NAME + "\" is longer than the "
                        + DefaultNameInserter.MAX_NAME_LENGTH + " characters the player can type");
    }

    @Test
    @DisplayName("nothing outside the branch's immediates is touched")
    void nothingOutsideTheImmediatesIsTouched() {
        // The whole point of this patch is that no instruction moves: the
        // branch keeps its length and writes the same twelve bytes it always did.
        byte[] before = SmokeRom.built();
        Problems p = new Problems();
        for (int i = 0; i < before.length; i++) {
            if (before[i] == named[i]) continue;
            int off = i - DefaultNameInserter.FIRST_MOVE_ADDR;
            boolean isImmediate = off >= 0
                    && off < DefaultNameInserter.MOVE_COUNT * 4
                    && (off % 4) >= 2;
            boolean isChecksum = i == 0x18e || i == 0x18f;
            p.check(isImmediate || isChecksum,
                    String.format("byte 0x%x changed but is neither an immediate nor the header checksum", i));
        }
        p.assertNone();
    }

    @Test
    @DisplayName("re-patching an already-named ROM is a no-op")
    void patchingIsIdempotent() {
        byte[] before = named.clone();
        DefaultNameInserter.patch(named, table);
        assertArrayEquals(before, named);
    }

    @Test
    @DisplayName("a name longer than the cap is rejected")
    void rejectsANameTheEntryScreenCouldNotProduce() {
        String tooLong = "X".repeat(DefaultNameInserter.MAX_NAME_LENGTH + 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DefaultNameInserter.encodeName(table, tooLong));
        assertTrue(e.getMessage().contains(String.valueOf(DefaultNameInserter.MAX_NAME_LENGTH)),
                "the message says what the cap is: " + e.getMessage());
    }

    @Test
    @DisplayName("a ROM without the empty-name branch is rejected")
    void rejectsARomThatDoesNotHoldTheBranch() {
        byte[] wrongRom = SmokeRom.built().clone();
        Arrays.fill(wrongRom, DefaultNameInserter.LEA_ADDR, DefaultNameInserter.LEA_ADDR + 4, (byte) 0);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DefaultNameInserter.patch(wrongRom, table));
        assertTrue(e.getMessage().contains("wrong ROM"), "the message says what went wrong: " + e.getMessage());
    }
}
