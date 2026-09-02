package net.krusher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What TextInserter promises about the ROM it writes: the dual-mode string
 * slots resolve -- the fetch helper is installed, every inlined table-walk site
 * detours through it, and every slot in the script reaches a terminated
 * string. Forking a diverged room is covered by TextInserterForkTest.
 */
@DisplayName("TextInserter")
final class TextInserterTest {

    private static byte[] rom;

    @BeforeAll
    static void buildRom() throws Exception {
        SmokeRom.require();
        rom = SmokeRom.built();
    }

    @Test
    @DisplayName("the absolute-pointer fetch helper is installed")
    void fetchHelperIsInstalled() {
        Problems p = new Problems();
        p.bytesAt(rom, TextInserter.FETCH_HELPER_ADDR,
                new int[]{0x70, 0x00, 0xd0, 0x40, 0x30, 0x30, 0x00, 0x00, 0x6b, 0x04},
                "fetch helper head (moveq/add/move/bmi) present at 0xf6000");
        p.assertNone();
    }

    @Test
    @DisplayName("every inlined table-walk site detours through the helper")
    void everyTableWalkSiteDetoursThroughTheHelper() {
        Problems p = new Problems();
        for (int[][] site : TextInserter.FETCH_SITE_PATCHES) {
            p.bytesAt(rom, site[0][0], site[2],
                    String.format("table-walk site 0x%x detours through the fetch helper", site[0][0]));
        }
        p.assertNone();
    }

    @Test
    @DisplayName("both helper lea operands point at one even, in-ROM pointer table")
    void helperLeaOperandsAgreeOnThePointerTable() {
        Problems p = new Problems();
        int ptrTable = TextExtractor.readU32(rom, TextInserter.FETCH_LEA_D0_OPERAND);
        p.check(ptrTable == TextExtractor.readU32(rom, TextInserter.FETCH_LEA_D2_OPERAND),
                "both helper lea operands agree on the pointer table address");
        p.check(ptrTable > 0 && ptrTable < rom.length && (ptrTable & 1) == 0,
                String.format("pointer table address 0x%x is inside the ROM and even", ptrTable));
        p.assertNone();
    }

    @Test
    @DisplayName("every script slot resolves to a 0xFF-terminated string")
    void everyScriptSlotResolvesToATerminatedString() {
        Problems p = new Problems();
        for (int room = 0; room < SmokeRom.roomCount(rom); room++) {
            int npcTable = SmokeRom.npcTableAddr(rom, room);
            int npcCount = TextExtractor.readU16(rom, npcTable) / 2;
            for (int npc = 0; npc < npcCount; npc++) {
                int strTable = npcTable + TextExtractor.readU16(rom, npcTable + npc * 2);
                int strCount = SmokeRom.strCountOf(rom, strTable);
                for (int str = 0; str < strCount; str++) {
                    int addr = TextInserter.resolveStringAddr(rom, strTable, str);
                    p.check(SmokeRom.terminatedStringAt(rom, addr), String.format(
                            "room=%d npc=%d str=%d resolves to 0x%x but finds no 0xFF terminator",
                            room, npc, str, addr));
                }
            }
        }
        p.assertNone();
    }

    /**
     * The bug this exists for: a tenth string-table step at 0x32170 was missing
     * from FETCH_SITE_PATCHES for a long time. It kept reading slots with the
     * original adda.w, which SIGN-EXTENDS, so an indirect slot (high bit set)
     * became a large negative offset -- 0x801c read as -32740 -- and the game
     * drew whatever text happened to sit there, mid-word, from an unrelated
     * room. 84 of 1036 slots were affected.
     *
     * everyTableWalkSiteDetoursThroughTheHelper only checks the sites already
     * in the table, so it passed throughout. This checks the other direction:
     * that no string step is left OUT of it.
     *
     * The game walks room -> NPC -> string, and each step is the same shape
     * (add.w dX,dX or moveq #0,dX, then adda.w (aN,dX.w),aN). Only the string
     * step needs the helper, so an unpatched one is legitimate exactly when it
     * is an NPC step -- which is recognisable because the string step that
     * follows it in the same routine is patched, a few bytes later.
     */
    @Test
    @DisplayName("no inlined string step is missing from the patch table")
    void noInlinedStringStepIsMissingFromThePatchTable() {
        Problems p = new Problems();
        for (int at = 4; at + 4 <= rom.length; at += 2) {
            int word = ((rom[at] & 0xFF) << 8) | (rom[at + 1] & 0xFF);
            int dest = (word >> 9) & 7;
            if ((word & 0xF1F8) != 0xD0F0 || dest != (word & 7)) continue; // adda.w (aN,dX.w),aN
            int ext = ((rom[at + 2] & 0xFF) << 8) | (rom[at + 3] & 0xFF);
            if ((ext & 0x8FFF) != 0) continue;                             // dX.w, no displacement
            int idx = (ext >> 12) & 7;
            int prev = ((rom[at - 2] & 0xFF) << 8) | (rom[at - 1] & 0xFF);
            if (prev != (0xD040 | (idx << 9) | idx) && prev != (0x7000 | (idx << 9))) continue;

            p.check(patchedSiteWithin(at + 1, at + 16), String.format(
                    "the table step at 0x%x is not patched and no patched string step follows it "
                            + "within 16 bytes, so it is a string step missing from FETCH_SITE_PATCHES "
                            + "-- indirect slots read through it will sign-extend into garbage", at));
        }
        p.assertNone();
    }

    /** True if any patched fetch site starts in (from, to]. */
    private static boolean patchedSiteWithin(int from, int to) {
        for (int[][] site : TextInserter.FETCH_SITE_PATCHES) {
            if (site[0][0] > from && site[0][0] <= to) return true;
        }
        return false;
    }
}
