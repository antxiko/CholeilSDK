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
}
