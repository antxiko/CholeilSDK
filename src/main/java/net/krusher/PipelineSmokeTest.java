package net.krusher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Smoke test of the text + balloon stage of the insertion pipeline. Runs
 * TextInserter and MapBalloonInserter against the base ROM into a scratch
 * file, then byte-verifies everything the map-balloon fix promises:
 * the three code patches are in place, the producer at 0x3102 still stores
 * the game's own $d806 node id (repurposing that field is what used to kill
 * the town balloon on landing), every enabled marker's width matches its
 * placed name, the names follow the data rules from CARTELES.md, and the
 * header checksum is consistent.
 *
 * Plain main(), no JUnit: the project builds with javac alone. Exit 0 on
 * success, 1 on any failed assertion, 2 when the base ROM is missing
 * (the ROM is not part of the repo; drop "Soleil (Spain).md" next to the
 * sources to make the suite runnable).
 */
public final class PipelineSmokeTest {

    static int failures = 0;
    static int passed = 0;

    public static void main(String[] args) throws Exception {
        String baseRom = args.length > 0 ? args[0] : "Soleil (Spain).md";
        if (!Files.exists(Paths.get(baseRom))) {
            System.err.println("SMOKE: base ROM '" + baseRom + "' not found -- cannot verify anything. "
                    + "Place the original ROM in the working directory (it is gitignored on purpose).");
            System.exit(2);
        }
        Path tmp = Paths.get("target", "smoke-rom.md");
        Files.createDirectories(tmp.getParent());

        TextInserter.run(baseRom, "script.txt", "soleil.tbl", "free_space.txt", tmp.toString());
        MapBalloonInserter.run(tmp.toString(), "soleil.tbl", tmp.toString());

        byte[] rom = Files.readAllBytes(tmp);

        // --- the three code patches, byte for byte ---
        expectBytes(rom, 0x3102, new int[]{0x30,0xf8,0xd8,0x06},
                "producer 0x3102 keeps the game's $d806 node id (regression guard: stealing it kills the landed balloon)");
        expectBytes(rom, 0x315a, new int[]{0x31,0xe8,0x00,0x06,0xd8,0xd4},
                "consumer 0x315a starts with the attr copy (rewritten block)");
        expectBytes(rom, 0x3164, new int[]{0x31,0xc7,0xd9,0x7e},
                "consumer stashes width|parity into $ffd97e");
        expectBytes(rom, 0x316c, new int[]{0x61,0x00,0x00,0x10},
                "consumer keeps the original bsr.w to the renderer entry");
        expectBytes(rom, 0x317e, new int[]{0x4e,0xf9,0x00,0x04,0x19,0xb0},
                "0x317e jumps to the RTL renderer");
        expectBytes(rom, 0x1fffe0, new int[]{0x08,0x38,0x00,0x00,0xd9,0x7f},
                "half-tile thunk present at 0x1fffe0");
        expectBytes(rom, 0x0419b0, new int[]{0x94,0x78,0xa4,0xf2},
                "RTL renderer body present at 0x0419b0");
        for (int addr : new int[]{0x31ac, 0x31fc, 0x3256}) {
            expectBytes(rom, addr, new int[]{0x4e,0xb9,0x00,0x1f,0xff,0xe0},
                    String.format("text sprite call at 0x%x detours through the thunk", addr));
        }

        // --- town names and marker widths ---
        // (counts read from the BASE ROM: an indirect str=0 slot in the
        // output no longer doubles as the table-size hint)
        byte[] orig = Files.readAllBytes(Paths.get(baseRom));
        int scriptBase = TextInserter.SCRIPT_BASE + MapBalloonInserter.readS32(rom, TextInserter.SCRIPT_BASE);
        int tableBase = scriptBase + MapBalloonInserter.readU16(rom, scriptBase + 6);
        int count = MapBalloonInserter.readU16(orig, tableBase) / 2;
        int[] lengths = new int[count];
        for (int k = 0; k < count; k++) {
            int addr = TextInserter.resolveStringAddr(rom, tableBase, k);
            int len = 0;
            while ((rom[addr + len] & 0xFF) < 0xFE) len++;
            lengths[k] = len;
            check(len > 0, "town name " + k + " is non-empty");
            check((rom[addr + len] & 0xFF) == 0xFF,
                    "town name " + k + " ends in a bare 0xFF (no 0xFE newline), so the courtesy blank backs its rounding tile");
        }
        int markers = 0, patchedWidths = 0;
        for (int off = MapBalloonInserter.MARKER_TABLE;
             MapBalloonInserter.readU16(rom, off) < 0x8000;
             off += MapBalloonInserter.REC_SIZE) {
            markers++;
            boolean enabled = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_BALLOON_X) != 0
                    || MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_BALLOON_Y) != 0;
            int town = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_TOWN_ID);
            if (!enabled || town >= count) continue;
            int width = MapBalloonInserter.readU16(rom, off + MapBalloonInserter.REC_WIDTH);
            int want = ((lengths[town] + 1) & ~1) | (lengths[town] & 1);
            check(width == want, String.format(
                    "marker 0x%x (town %d, %d chars) width is %d (expected %d)", off, town, lengths[town], width, want));
            patchedWidths++;
        }
        check(markers >= 20, "marker table walk found a sane record count (" + markers + ")");
        check(patchedWidths >= 10, "at least 10 enabled markers verified (" + patchedWidths + ")");

        // --- absolute-pointer fetch helper and the nine site detours ---
        expectBytes(rom, TextInserter.FETCH_HELPER_ADDR,
                new int[]{0x70,0x00, 0xd0,0x40, 0x30,0x30,0x00,0x00, 0x6b,0x04},
                "fetch helper head (moveq/add/move/bmi) present at 0xf6000");
        for (int[][] p : TextInserter.FETCH_SITE_PATCHES) {
            expectBytes(rom, p[0][0], p[2],
                    String.format("table-walk site 0x%x detours through the fetch helper", p[0][0]));
        }
        int ptrTable = TextExtractor.readU32(rom, TextInserter.FETCH_LEA_D0_OPERAND);
        check(ptrTable == TextExtractor.readU32(rom, TextInserter.FETCH_LEA_D2_OPERAND),
                "both helper lea operands agree on the pointer table address");
        check(ptrTable > 0 && ptrTable < rom.length && (ptrTable & 1) == 0,
                String.format("pointer table address 0x%x is inside the ROM and even", ptrTable));

        // --- every script slot resolves to a terminated string ---
        int roomCount = TextExtractor.readU32(rom, TextInserter.SCRIPT_BASE) / 4 - 1;
        int resolved = 0, indirectSlots = 0;
        boolean allTerminated = true;
        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            int npcTableAddr = TextInserter.SCRIPT_BASE + TextExtractor.readU32(rom, TextInserter.SCRIPT_BASE + roomIndex * 4);
            int npcCount = TextExtractor.readU16(rom, npcTableAddr) / 2;
            for (int npcIndex = 0; npcIndex < npcCount; npcIndex++) {
                int strTableAddr = npcTableAddr + TextExtractor.readU16(rom, npcTableAddr + npcIndex * 2);
                int strCount = TextExtractor.readU16(orig, strTableAddr) / 2;
                for (int str = 0; str < strCount; str++) {
                    if (TextExtractor.readU16(rom, strTableAddr + 2 * str) >= 0x8000) indirectSlots++;
                    int addr = TextInserter.resolveStringAddr(rom, strTableAddr, str);
                    int len = 0;
                    while (addr + len < rom.length && (rom[addr + len] & 0xFF) != 0xFF && len < 4096) len++;
                    if (addr <= 0 || addr + len >= rom.length || (rom[addr + len] & 0xFF) != 0xFF) {
                        fail(String.format("room=%d npc=%d str=%d resolves to 0x%x but finds no 0xFF terminator",
                                roomIndex, npcIndex, str, addr));
                        allTerminated = false;
                    }
                    resolved++;
                }
            }
        }
        if (allTerminated) ok("all " + resolved + " script slots resolve to 0xFF-terminated strings ("
                + indirectSlots + " indirect)");

        // --- header checksum ---
        int sum = 0;
        for (int i = 0x200; i + 1 < rom.length; i += 2) {
            sum = (sum + (((rom[i] & 0xFF) << 8) | (rom[i + 1] & 0xFF))) & 0xFFFF;
        }
        int stored = MapBalloonInserter.readU16(rom, 0x18e);
        check(sum == stored, String.format("header checksum consistent (stored %04x, computed %04x)", stored, sum));

        System.out.println();
        System.out.println("SMOKE: " + passed + " passed, " + failures + " failed ("
                + patchedWidths + " markers, " + count + " names).");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void expectBytes(byte[] rom, int addr, int[] want, String what) {
        for (int i = 0; i < want.length; i++) {
            if ((rom[addr + i] & 0xFF) != want[i]) {
                fail(what + String.format(" -- mismatch at 0x%x (got %02x, want %02x)", addr + i, rom[addr + i] & 0xFF, want[i]));
                return;
            }
        }
        ok(what);
    }

    static void check(boolean cond, String what) {
        if (cond) ok(what); else fail(what);
    }

    static void ok(String what)   { passed++; System.out.println("  ok    " + what); }
    static void fail(String what) { System.out.println("  FAIL  " + what); failures++; }
}
