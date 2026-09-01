package net.krusher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rooms 23 and 24 hold the same room-table offset in the stock ROM, so
 * they share one NPC table and every string slot in it. Give room 24's
 * npc=1 str=0 its own text and it has to end up on its own tables,
 * without disturbing room 23 or the strings the two still have in common.
 */
@DisplayName("TextInserter: forking a room off a shared NPC table")
final class TextInserterForkTest {

    static final String PROBE = "SMOKE FORK PROBE";
    static final int ANCHOR = 23;
    static final int FORKED = 24;

    static byte[] fr;
    static int npcTableAnchor;
    static int npcTableForked;
    static int strTableAnchor;
    static int strTableForked;

    @BeforeAll
    static void buildForkedRom() throws Exception {
        SmokeRom.require();
        Path script = SmokeRom.scriptWith("smoke-fork-script.txt", FORKED, 1, 0, PROBE);
        Path out = Paths.get("target", "smoke-fork-rom.md");
        TextInserter.run(SmokeRom.BASE, script.toString(), DefaultPaths.TBL, DefaultPaths.FREE_SPACE, out.toString());
        fr = Files.readAllBytes(out);

        npcTableAnchor = SmokeRom.npcTableAddr(fr, ANCHOR);
        npcTableForked = SmokeRom.npcTableAddr(fr, FORKED);
        strTableAnchor = npcTableAnchor + TextExtractor.readU16(fr, npcTableAnchor + 2);
        strTableForked = npcTableForked + TextExtractor.readU16(fr, npcTableForked + 2);
    }

    @Test
    @DisplayName("the diverged room gets its own NPC table")
    void divergedRoomGetsItsOwnNpcTable() {
        Problems p = new Problems();
        p.check(npcTableForked != npcTableAnchor, String.format(
                "room %d forked onto its own NPC table (0x%x) away from room %d's (0x%x)",
                FORKED, npcTableForked, ANCHOR, npcTableAnchor));
        p.assertNone();
    }

    @Test
    @DisplayName("the forked tables stay self-describing")
    void forkedTablesStaySelfDescribing() {
        Problems p = new Problems();
        p.check(TextExtractor.readU16(fr, npcTableForked) == TextExtractor.readU16(fr, npcTableAnchor),
                "the forked NPC table keeps the anchor's NPC count in slot 0");
        p.check(SmokeRom.strCountOf(fr, strTableForked) == 2,
                "the forked npc=1 string table still describes 2 strings");
        p.assertNone();
    }

    @Test
    @DisplayName("the forked room reads back its own text")
    void forkedRoomReadsBackItsOwnText() throws Exception {
        Problems p = new Problems();
        byte[] probeBytes = TblTable.load(DefaultPaths.TBL).encode(PROBE);
        int probe = TextInserter.resolveStringAddr(fr, strTableForked, 0);
        p.check(TextInserter.resolveStringAddr(fr, strTableAnchor, 0) != probe,
                "the two rooms now resolve npc=1 str=0 to different addresses");
        p.check(SmokeRom.startsWith(fr, probe, probeBytes),
                "the forked room's npc=1 str=0 reads back as the probe text");
        p.assertNone();
    }

    @Test
    @DisplayName("the anchor room is untouched by the fork")
    void anchorRoomIsUntouched() throws Exception {
        Problems p = new Problems();
        byte[] probeBytes = TblTable.load(DefaultPaths.TBL).encode(PROBE);
        p.check(!SmokeRom.startsWith(fr, TextInserter.resolveStringAddr(fr, strTableAnchor, 0), probeBytes),
                "the anchor room's npc=1 str=0 still holds its own text");
        p.assertNone();
    }

    @Test
    @DisplayName("strings that did not diverge still share one copy")
    void unchangedStringsStillShareOneCopy() {
        Problems p = new Problems();
        p.check(TextInserter.resolveStringAddr(fr, strTableAnchor, 1)
                        == TextInserter.resolveStringAddr(fr, strTableForked, 1),
                "npc=1 str=1, unchanged in both rooms, still shares one copy across the fork");
        p.assertNone();
    }

    @Test
    @DisplayName("every index table stays word-aligned and resolvable")
    void everyIndexTableStaysWordAligned() {
        // The game reads table slots with move.w, and an odd table address
        // is an address error on a 68000.
        Problems p = new Problems();
        for (int room = 0; room < SmokeRom.roomCount(fr); room++) {
            int npcTable = SmokeRom.npcTableAddr(fr, room);
            p.check((npcTable & 1) == 0,
                    String.format("room=%d's NPC table is at odd address 0x%x", room, npcTable));
            int npcCount = TextExtractor.readU16(fr, npcTable) / 2;
            for (int npc = 0; npc < npcCount; npc++) {
                int strTable = npcTable + TextExtractor.readU16(fr, npcTable + npc * 2);
                p.check((strTable & 1) == 0, String.format(
                        "room=%d npc=%d's string table is at odd address 0x%x", room, npc, strTable));
                int strCount = SmokeRom.strCountOf(fr, strTable);
                for (int str = 0; str < strCount; str++) {
                    int addr = TextInserter.resolveStringAddr(fr, strTable, str);
                    p.check(SmokeRom.terminatedStringAt(fr, addr), String.format(
                            "with a fork present, room=%d npc=%d str=%d resolves to 0x%x with no terminator",
                            room, npc, str, addr));
                }
            }
        }
        p.assertNone();
    }
}
