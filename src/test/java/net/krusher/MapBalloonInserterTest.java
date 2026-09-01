package net.krusher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What MapBalloonInserter promises about the world-map town balloons: its
 * code patches survive text insertion, every enabled marker's width matches
 * the name actually placed in the ROM, and the names still follow the data
 * rules from CARTELES.md.
 */
@DisplayName("MapBalloonInserter")
final class MapBalloonInserterTest {

    private static byte[] rom;

    @BeforeAll
    static void buildRom() throws Exception {
        SmokeRom.require();
        rom = SmokeRom.built();
    }

    @Test
    @DisplayName("the producer still stores the game's own node id")
    void producerKeepsTheGamesNodeId() {
        // Regression guard: repurposing $d806 is what used to kill the town
        // balloon the moment you landed.
        Problems p = new Problems();
        p.bytesAt(rom, 0x3102, new int[]{0x30, 0xf8, 0xd8, 0x06},
                "producer 0x3102 keeps the game's $d806 node id");
        p.assertNone();
    }

    @Test
    @DisplayName("the rewritten consumer block is in place")
    void consumerBlockIsRewritten() {
        Problems p = new Problems();
        p.bytesAt(rom, 0x315a, new int[]{0x31, 0xe8, 0x00, 0x06, 0xd8, 0xd4},
                "consumer 0x315a starts with the attr copy (rewritten block)");
        p.bytesAt(rom, 0x3164, new int[]{0x31, 0xc7, 0xd9, 0x7e},
                "consumer stashes width|parity into $ffd97e");
        p.bytesAt(rom, 0x316c, new int[]{0x61, 0x00, 0x00, 0x10},
                "consumer keeps the original bsr.w to the renderer entry");
        p.bytesAt(rom, 0x317e, new int[]{0x4e, 0xf9, 0x00, 0x04, 0x19, 0xb0},
                "0x317e jumps to the RTL renderer");
        p.assertNone();
    }

    @Test
    @DisplayName("the half-tile thunk and the RTL renderer are installed")
    void halfTileThunkAndRendererAreInstalled() {
        Problems p = new Problems();
        p.bytesAt(rom, 0x1fffe0, new int[]{0x08, 0x38, 0x00, 0x00, 0xd9, 0x7f},
                "half-tile thunk present at 0x1fffe0");
        p.bytesAt(rom, 0x0419b0, new int[]{0x94, 0x78, 0xa4, 0xf2},
                "RTL renderer body present at 0x0419b0");
        p.assertNone();
    }

    @Test
    @DisplayName("every text-sprite call detours through the thunk")
    void textSpriteCallsDetourThroughTheThunk() {
        Problems p = new Problems();
        for (int addr : new int[]{0x31ac, 0x31fc, 0x3256}) {
            p.bytesAt(rom, addr, new int[]{0x4e, 0xb9, 0x00, 0x1f, 0xff, 0xe0},
                    String.format("text sprite call at 0x%x detours through the thunk", addr));
        }
        p.assertNone();
    }

    @Test
    @DisplayName("every town name is non-empty and ends in a bare 0xFF")
    void townNamesEndInABare0xFF() {
        // An odd name terminated 0xFF gets a courtesy blank glyph from the
        // engine, which backs its rounding tile; a trailing 0xFE newline does
        // not, and leaves stale VRAM showing.
        Problems p = new Problems();
        int[] lengths = townNameLengths();
        for (int town = 0; town < lengths.length; town++) {
            int addr = TextInserter.resolveStringAddr(rom, tableBase(), town);
            p.check(lengths[town] > 0, "town name " + town + " is non-empty");
            p.check((rom[addr + lengths[town]] & 0xFF) == 0xFF, "town name " + town
                    + " ends in a bare 0xFF (no 0xFE newline), so the courtesy blank backs its rounding tile");
        }
        p.assertNone();
    }

    @Test
    @DisplayName("every enabled marker's width matches its placed name")
    void markerWidthsMatchThePlacedNames() {
        Problems p = new Problems();
        int[] lengths = townNameLengths();
        int markers = 0;
        int verified = 0;
        for (int off = MapBalloonInserter.MARKER_TABLE;
             MapBalloonInserter.readU16(rom, off) < 0x8000;
             off += MapBalloonInserter.REC_SIZE) {
            markers++;
            boolean enabled = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_BALLOON_X) != 0
                    || MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_BALLOON_Y) != 0;
            int town = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_TOWN_ID);
            if (!enabled || town >= lengths.length) continue;
            int width = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_WIDTH);
            // Even width for the sprite builder, with the odd-name flag in bit 0.
            int want = ((lengths[town] + 1) & ~1) | (lengths[town] & 1);
            p.check(width == want, String.format("marker 0x%x (town %d, %d chars) width is %d (expected %d)",
                    off, town, lengths[town], width, want));
            verified++;
        }
        p.check(markers >= 20, "marker table walk found a sane record count (" + markers + ")");
        p.check(verified >= 10, "at least 10 enabled markers verified (" + verified + ")");
        p.assertNone();
    }

    /** The town-name string table, which is npc 3 of the script's first room. */
    private static int tableBase() {
        int scriptBase = TextInserter.SCRIPT_BASE + MapBalloonInserter.readS32(rom, TextInserter.SCRIPT_BASE);
        return scriptBase + MapBalloonInserter.readU16(rom, scriptBase + 6);
    }

    /** Each town name's length in glyphs, as actually placed in the ROM. */
    private static int[] townNameLengths() {
        // The count comes from the BASE ROM: an indirect str=0 slot in the
        // output no longer doubles as the table-size hint.
        int count = MapBalloonInserter.readU16(SmokeRom.base(), tableBase()) / 2;
        assertTrue(count > 0, "the town-name table describes at least one name");
        int[] lengths = new int[count];
        for (int town = 0; town < count; town++) {
            int addr = TextInserter.resolveStringAddr(rom, tableBase(), town);
            int len = 0;
            while ((rom[addr + len] & 0xFF) < 0xFE) len++;
            lengths[town] = len;
        }
        return lengths;
    }
}
