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
                int strCount = strCountOf(rom, orig, strTableAddr);
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

        // --- forking a room off a shared NPC table ---
        // Rooms 23 and 24 hold the same room-table offset in the stock ROM, so
        // they share every string slot. Give room 24's npc=1 str=0 its own text
        // and it must end up on its own tables, without disturbing room 23 or
        // the strings the two rooms still have in common.
        Path forkScript = Paths.get("target", "smoke-fork-script.txt");
        Path forkRom = Paths.get("target", "smoke-fork-rom.md");
        String marker = "SMOKE FORK PROBE";
        Files.write(forkScript, replaceEntryBody(
                new String(Files.readAllBytes(Paths.get("script.txt")), java.nio.charset.StandardCharsets.UTF_8),
                24, 1, 0, marker).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TextInserter.run(baseRom, forkScript.toString(), "soleil.tbl", "free_space.txt", forkRom.toString());
        byte[] fr = Files.readAllBytes(forkRom);

        int npcTable23 = TextInserter.SCRIPT_BASE + TextExtractor.readU32(fr, TextInserter.SCRIPT_BASE + 23 * 4);
        int npcTable24 = TextInserter.SCRIPT_BASE + TextExtractor.readU32(fr, TextInserter.SCRIPT_BASE + 24 * 4);
        check(npcTable23 != npcTable24, String.format(
                "room 24 forked onto its own NPC table (0x%x) away from room 23's (0x%x)", npcTable24, npcTable23));
        check(TextExtractor.readU16(fr, npcTable24) == TextExtractor.readU16(fr, npcTable23),
                "the forked NPC table keeps room 23's NPC count in slot 0 (self-describing size intact)");

        int strTable23 = npcTable23 + TextExtractor.readU16(fr, npcTable23 + 2);
        int strTable24 = npcTable24 + TextExtractor.readU16(fr, npcTable24 + 2);
        check(strCountOf(fr, orig, strTable24) == 2,
                "the forked npc=1 string table still describes 2 strings");
        int probe = TextInserter.resolveStringAddr(fr, strTable24, 0);
        check(TextInserter.resolveStringAddr(fr, strTable23, 0) != probe,
                "room 23 and room 24 now resolve npc=1 str=0 to different addresses");
        byte[] markerBytes = TblTable.load("soleil.tbl").encode(marker);
        check(startsWith(fr, probe, markerBytes),
                "room 24's npc=1 str=0 reads back as the probe text");
        check(!startsWith(fr, TextInserter.resolveStringAddr(fr, strTable23, 0), markerBytes),
                "room 23's npc=1 str=0 is untouched by the fork");
        check(TextInserter.resolveStringAddr(fr, strTable23, 1) == TextInserter.resolveStringAddr(fr, strTable24, 1),
                "npc=1 str=1, unchanged in both rooms, still shares one copy across the fork");

        // Every room's NPC table must stay self-describing and word-aligned,
        // forks included: the game reads table slots with move.w, and an odd
        // table address is an address error on a 68000.
        boolean tablesSane = true;
        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            int t = TextInserter.SCRIPT_BASE + TextExtractor.readU32(fr, TextInserter.SCRIPT_BASE + roomIndex * 4);
            int nc = TextExtractor.readU16(fr, t) / 2;
            if ((t & 1) != 0) {
                fail(String.format("room=%d's NPC table is at odd address 0x%x", roomIndex, t));
                tablesSane = false;
            }
            for (int npc = 0; npc < nc; npc++) {
                int st = t + TextExtractor.readU16(fr, t + npc * 2);
                int sc = strCountOf(fr, orig, st);
                if ((st & 1) != 0) {
                    fail(String.format("room=%d npc=%d's string table is at odd address 0x%x", roomIndex, npc, st));
                    tablesSane = false;
                }
                for (int str = 0; str < sc; str++) {
                    int a = TextInserter.resolveStringAddr(fr, st, str);
                    int len = 0;
                    while (a + len < fr.length && (fr[a + len] & 0xFF) != 0xFF && len < 4096) len++;
                    if (a <= 0 || a + len >= fr.length || (fr[a + len] & 0xFF) != 0xFF) {
                        fail(String.format("with a fork present, room=%d npc=%d str=%d resolves to 0x%x with no terminator",
                                roomIndex, npc, str, a));
                        tablesSane = false;
                    }
                }
            }
        }
        if (tablesSane) ok("with a fork present, every index table is word-aligned and every slot resolves to a terminated string");

        // --- default hero name ---
        // On a clone: the checksum below is MapBalloonInserter's, and this
        // step is the pipeline's last, so it fixes the checksum itself.
        byte[] named = rom.clone();
        DefaultNameInserter.patch(named, TblTable.load("soleil.tbl"));
        expectBytes(named, DefaultNameInserter.LEA_ADDR, DefaultNameInserter.LEA_BYTES,
                "empty-name branch still opens with `lea $fe4a.w, a0`");
        byte[] want = DefaultNameInserter.encodeName(TblTable.load("soleil.tbl"), DefaultNameInserter.DEFAULT_NAME);
        boolean nameOk = true;
        for (int i = 0; i < DefaultNameInserter.MOVE_COUNT; i++) {
            int at = DefaultNameInserter.FIRST_MOVE_ADDR + i * 4;
            if (!TextInserter.patchMatches(named, at, DefaultNameInserter.MOVE_OPCODE)
                    || named[at + 2] != want[i * 2] || named[at + 3] != want[i * 2 + 1]) {
                nameOk = false;
            }
        }
        check(nameOk, "empty-name branch writes \"" + DefaultNameInserter.DEFAULT_NAME
                + "\" as six move.w immediates");
        check(want[DefaultNameInserter.DEFAULT_NAME.length()] == (byte) 0xFF,
                "the default name is 0xFF-terminated inside the twelve bytes the branch writes");
        check(DefaultNameInserter.DEFAULT_NAME.length() <= DefaultNameInserter.MAX_NAME_LENGTH,
                "the default name fits the entry screen's own " + DefaultNameInserter.MAX_NAME_LENGTH + "-character cap");
        byte[] before = named.clone();
        DefaultNameInserter.patch(named, TblTable.load("soleil.tbl"));
        check(java.util.Arrays.equals(before, named), "re-patching an already-named ROM is a no-op");

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

    /**
     * A string table's size. Slot 0 doubles as the size hint while it holds a
     * relative offset (str=0 always sits right behind its table), which covers
     * forked tables the base ROM knows nothing about; only a str=0 that had to
     * go indirect needs the base ROM's copy.
     */
    static int strCountOf(byte[] rom, byte[] orig, int strTableAddr) {
        int slot0 = TextExtractor.readU16(rom, strTableAddr);
        return (slot0 < 0x8000 ? slot0 : TextExtractor.readU16(orig, strTableAddr)) / 2;
    }

    /** Replaces one entry's body in a script.txt, keeping its header line. */
    static String replaceEntryBody(String script, int room, int npc, int str, String body) {
        String header = String.format("==== room=%d npc=%d str=%d ", room, npc, str);
        int at = script.indexOf(header);
        if (at < 0) throw new IllegalStateException("no room=" + room + " npc=" + npc + " str=" + str + " in script.txt");
        int bodyStart = script.indexOf('\n', at) + 1;
        int bodyEnd = script.indexOf("\n====", bodyStart);
        if (bodyEnd < 0) bodyEnd = script.length();
        return script.substring(0, bodyStart) + body + "\n" + script.substring(bodyEnd + 1);
    }

    static boolean startsWith(byte[] rom, int addr, byte[] want) {
        if (addr < 0 || addr + want.length > rom.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (rom[addr + i] != want[i]) return false;
        }
        return true;
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
