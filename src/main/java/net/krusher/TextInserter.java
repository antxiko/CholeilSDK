package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reinserts a (possibly translated) script.txt back into the ROM, producing
 * Choleil.md. Rebuilds every string's bytes from scratch and repacks them
 * sequentially (in original address order) into a combined writable pool:
 * the original script region (fully reusable, since all of it is being
 * rewritten) followed by known/scanned free space further in the ROM.
 *
 * Room, NPC, and string INDEX tables never move (their sizes are fixed by
 * counts that don't change) -- only the 2-byte offset VALUES inside the
 * existing string-index tables are rewritten, to point at each string's new
 * location.
 *
 * Slot values are dual-mode, backed by a small fetch helper this inserter
 * installs at FETCH_HELPER_ADDR and jsr-patches into all nine of the game's
 * inlined table-walk sites (found by scanning the ROM for every $1C0000
 * constant and disassembling each hit with capstone):
 *
 *   slot &lt; 0x8000  classic offset relative to the string-index table,
 *                     byte-identical to the game's native behavior. The
 *                     original code reads it with adda.w, which SIGN-EXTENDS,
 *                     so 0x8000-0xFFFF never worked on real hardware anyway
 *                     -- the high bit was always free for the taking.
 *   slot &gt;= 0x8000 bits 0-14 index a global table of 4-byte absolute
 *                     addresses (allocated from the free pool, address baked
 *                     into the helper's lea), so the string can live anywhere
 *                     in the ROM at any length that fits the pool.
 *
 * Strings are still placed relatively whenever they fit within 0x7FFF bytes
 * of their table (keeping ROM layout close to stock); only overflow -- and
 * str=0 entries whose mandatory adjacent slot collides with the next fixed
 * table -- falls back to an indirect slot.
 */
public final class TextInserter {

    static final int SCRIPT_BASE = 0x1C0000;
    static final int DEFAULT_GAP_END = 0x1D8000; // known plausible-safe boundary, see soleil.tbl/graphics notes
    static final Pattern HEADER = Pattern.compile(
            "^==== room=(\\d+) npc=(\\d+) str=(\\d+) textAddr=0x([0-9a-fA-F]+) ptrFieldAddr=0x([0-9a-fA-F]+) ====$");
    static final Pattern SAME_REF = Pattern.compile(
            "^<SAME room=(\\d+) npc=(\\d+) str=(\\d+)>$");

    // Dialogue box width, in characters, for a single displayed line (one
    // segment between 0xFE line breaks). Confirmed against the game's text
    // box rendering; a line over this length would overflow the box.
    static final int MAX_LINE_LENGTH = 28;

    // The dialogue box holds three lines at a time, and the Yes/No prompt
    // (0xF2) has to be reached on a page boundary -- i.e. after a whole number
    // of three-line pages -- or it is drawn against a partially-filled box.
    // Strings that break the rule are padded with blank lines rather than
    // rejected; see alignYesNoPrompts.
    static final int LINES_PER_BOX = 3;
    static final String YESNO = "<YESNO>";
    static final String NAME = "<NAME>";

    // The 0xFE line break, as TextExtractor decodes it. Spelled numerically so
    // the byte it stands for is unmistakable.
    static final char NEWLINE = 0x0A;

    // One decoded byte of extracted text: a {XX} placeholder for a code with no
    // TBL glyph, a <NAME>/<YESNO> marker, or a single glyph character (every
    // mapping in soleil.tbl is one character wide once <SPACE> is expanded).
    static final Pattern TOKEN = Pattern.compile("\\{[0-9A-Fa-f]{2}\\}|" + NAME + "|" + YESNO + "|[\\s\\S]");

    // Opcodes that consume raw operand bytes following the placeholder. The
    // operands are data, but TextExtractor has no way to know that and renders
    // whatever glyph each byte happens to map to, so "{e0}0^" is one opcode
    // plus two data bytes -- not a printed line of three characters. Counting
    // them as text made alignYesNoPrompts pad one line short.
    //
    // 0xE0 is the only one identified so far, and firmly: all 84 occurrences in
    // the script are {e0} followed by exactly two further bytes, the first
    // always the glyph for 0x30 and the second ranging over the whole byte
    // space. Add an entry here as other operand-taking opcodes are identified.
    static final Map<String, Integer> OPCODE_OPERANDS = new HashMap<String, Integer>();
    static {
        OPCODE_OPERANDS.put("{E0}", 2);
    }

    // Longest slot value still read as a relative offset by the fetch helper
    // (and by the game's original adda.w, which sign-extends -- offsets with
    // the high bit set were never usable). Anything farther goes indirect.
    static final int MAX_RELATIVE_REACH = 0x7FFF;

    // The dual-mode fetch helper lives in the manually-verified free gap in
    // front of FreeSpaceScanner.VERIFIED_GAPS (which starts at 0xf6060
    // precisely to keep every free-space consumer off this block).
    static final int FETCH_HELPER_ADDR = 0xF6000;
    // lea TABLE operands inside the helper, patched with the real pointer-
    // table address once it's allocated. Tools resolve indirect slots by
    // reading the d0 variant's operand back (see resolveStringAddr).
    static final int FETCH_LEA_D0_OPERAND = FETCH_HELPER_ADDR + 0x18;
    static final int FETCH_LEA_D2_OPERAND = FETCH_HELPER_ADDR + 0x38;

    // helper_str0 (entry for the two sites with a constant str index of 0),
    // falling through into helper_d0; helper_d2 is the same routine for the
    // one site that keeps the index in d2. In: a0 = string-index table,
    // dN = str index. Out: a0 = string address. Clobbers dN (the original
    // 6-byte sequences clobbered it too -- verified dead at all nine sites).
    static final byte[] FETCH_HELPER_CODE = {
        (byte)0x70, 0x00,                                     // f6000 moveq  #0,d0
        (byte)0xD0, 0x40,                                     // f6002 add.w  d0,d0
        (byte)0x30, 0x30, 0x00, 0x00,                         // f6004 move.w (a0,d0.w),d0
        (byte)0x6B, 0x04,                                     // f6008 bmi.s  .ind
        (byte)0xD0, (byte)0xC0,                               // f600a adda.w d0,a0
        (byte)0x4E, 0x75,                                     // f600c rts
        0x02, (byte)0x80, 0x00, 0x00, 0x7F, (byte)0xFF,       // f600e andi.l #$7fff,d0
        (byte)0xE5, (byte)0x88,                               // f6014 lsl.l  #2,d0
        0x41, (byte)0xF9, 0x00, 0x00, 0x00, 0x00,             // f6016 lea    TABLE,a0
        0x20, 0x70, 0x08, 0x00,                               // f601c movea.l (0,a0,d0.l),a0
        (byte)0x4E, 0x75,                                     // f6020 rts
        (byte)0xD4, 0x42,                                     // f6022 add.w  d2,d2
        0x34, 0x30, 0x20, 0x00,                               // f6024 move.w (a0,d2.w),d2
        (byte)0x6B, 0x04,                                     // f6028 bmi.s  .ind2
        (byte)0xD0, (byte)0xC2,                               // f602a adda.w d2,a0
        (byte)0x4E, 0x75,                                     // f602c rts
        0x02, (byte)0x82, 0x00, 0x00, 0x7F, (byte)0xFF,       // f602e andi.l #$7fff,d2
        (byte)0xE5, (byte)0x8A,                               // f6034 lsl.l  #2,d2
        0x41, (byte)0xF9, 0x00, 0x00, 0x00, 0x00,             // f6036 lea    TABLE,a0
        0x20, 0x70, 0x28, 0x00,                               // f603c movea.l (0,a0,d2.l),a0
        (byte)0x4E, 0x75,                                     // f6040 rts
    };

    /**
     * The nine inlined table-walk sites, each ending in the same 6-byte
     * string step (add.w dN,dN / adda.w (a0,dN.w),a0 -- or moveq #0,d0 for
     * the two constant-str=0 sites), each replaced by an exact-fit 6-byte
     * jsr to the matching helper entry. {address}, {expected original
     * bytes}, {replacement bytes}, same contract as
     * MapBalloonInserter.CODE_PATCHES.
     */
    static final int[][][] FETCH_SITE_PATCHES = {
        { {0x03326}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // town-popup list (0x331a)
        { {0x04900}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // town-popup (0x48f4)
        { {0x3210E}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // room0/npc3 (0x32102)
        { {0x3212C}, {0xD4,0x42, 0xD0,0xF0,0x20,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x22} }, // general dialogue (0x32118), index in d2
        { {0x32150}, {0x70,0x00, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x00} }, // room1/npc0/str0 (0x3213e)
        { {0x33220}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // $b62e/$b628/$b62a dialogue (0x331f8)
        { {0x333C6}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // same vars, address-only variant (0x333a2)
        { {0x33478}, {0x70,0x00, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x00} }, // room0/npc0/str0 (0x3346a)
        { {0x3B8F0}, {0xD0,0x40, 0xD0,0xF0,0x00,0x00}, {0x4E,0xB9,0x00,0x0F,0x60,0x02} }, // signs, room 0x62 (0x3b8d4)
    };

    static final class Entry {
        int room, npc, str;
        int textAddr;
        int ptrFieldAddr;
        String text;
        byte[] encoded;
        int strTableAddr;
        int newAddr = -1; // used only for str=0 entries, placed at a mandatory fixed address
        Group group;
    }

    /**
     * A set of entries whose text encodes to byte-identical content, stored
     * once and shared by all their pointers (mirrors the original data's own
     * space-saving deduplication, which some strings rely on to fit).
     */
    static final class Group {
        byte[] encoded;
        List<Entry> members = new ArrayList<Entry>();
        int representativeAddr;
        int newAddr = -1;
        boolean indirect;  // placed anywhere, referenced via the absolute-pointer table
        int indirectIndex = -1;
    }

    /** usage: TextInserter [romPath] [scriptPath] [tblPath] [freeSpacePath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : "Soleil (Spain).md";
        String scriptPath = args.length > 1 ? args[1] : "script.txt";
        String tblPath = args.length > 2 ? args[2] : "soleil.tbl";
        String freeSpacePath = args.length > 3 ? args[3] : "free_space.txt";
        String outPath = args.length > 4 ? args[4] : "Choleil.md";
        run(romPath, scriptPath, tblPath, freeSpacePath, outPath);
    }

    public static void run(String romPath, String scriptPath, String tblPath,
                            String freeSpacePath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        TblTable table = TblTable.load(tblPath);
        List<Entry> entries = parseScript(scriptPath);
        System.out.println("Parsed " + entries.size() + " entries from " + scriptPath);
        resolveSameRefs(entries, scriptPath);

        for (Entry e : entries) {
            e.strTableAddr = e.ptrFieldAddr - e.str * 2;
        }

        int realigned = alignYesNoPrompts(entries);
        if (realigned > 0) {
            System.out.println(realigned + " string(s) had their <YESNO> prompt padded onto a "
                    + LINES_PER_BOX + "-line box boundary.");
        }

        // Lines longer than the dialogue box can hold are logged, but the
        // string is still encoded and inserted normally -- see MAX_LINE_LENGTH.
        int overLength = 0;
        for (Entry e : entries) {
            String[] displayLines = e.text.split("\n", -1);
            for (String line : displayLines) {
                if (line.length() > MAX_LINE_LENGTH) {
                    overLength++;
                    System.out.println("WARN: room=" + e.room + " npc=" + e.npc + " str=" + e.str
                            + " (textAddr=0x" + Integer.toHexString(e.textAddr) + ") has a line over "
                            + MAX_LINE_LENGTH + " characters [" + line.length() + " chars]: " + line);
                }
            }
        }
        if (overLength > 0) {
            System.out.println(overLength + " line(s) exceed " + MAX_LINE_LENGTH + " characters.");
        }

        int encodeFailures = 0;
        for (Entry e : entries) {
            try {
                byte[] body = table.encode(e.text);
                e.encoded = new byte[body.length + 1];
                System.arraycopy(body, 0, e.encoded, 0, body.length);
                e.encoded[body.length] = (byte) 0xFF;
            } catch (IllegalArgumentException ex) {
                System.out.println("ENCODE FAILURE room=" + e.room + " npc=" + e.npc + " str=" + e.str
                        + " (textAddr=0x" + Integer.toHexString(e.textAddr) + "): " + ex.getMessage());
                encodeFailures++;
            }
        }
        if (encodeFailures > 0) {
            System.out.println(encodeFailures + " string(s) failed to encode. Fix soleil.tbl or the offending "
                    + "text in " + scriptPath + " and re-run. " + outPath + " was NOT written.");
            throw new IllegalStateException(encodeFailures + " string(s) failed to encode; " + outPath + " was NOT written.");
        }

        int minTextAddr = Integer.MAX_VALUE;
        int maxTextAddr = 0;
        Entry lastEntry = null;
        for (Entry e : entries) {
            if (e.textAddr < minTextAddr) minTextAddr = e.textAddr;
            if (e.textAddr > maxTextAddr) { maxTextAddr = e.textAddr; lastEntry = e; }
        }
        int trueScriptEnd = findOriginalTerminator(rom, lastEntry.textAddr) + 1;
        System.out.println("Original script span: 0x" + Integer.toHexString(minTextAddr)
                + " - 0x" + Integer.toHexString(trueScriptEnd));

        // Room/NPC/string INDEX tables are interspersed with the text throughout
        // the script region (an NPC's string-index-table sits right between two
        // other strings' text, not segregated into one block). Those bytes must
        // never be touched -- only the text itself is free to repack.
        List<int[]> tableRanges = computeTableRanges(rom);

        // In the original data every str=0 sits at EXACTLY strTableAddr+
        // strCount*2 (immediately after its own index table), making its slot
        // value double as a self-describing table-size encoding. A full
        // disassembly pass found no game code that actually derives a count
        // from it (all nine fetch sites just add the offset -- the historical
        // breakage when relocating str=0 is better explained by adda.w's sign
        // extension), but the adjacent slot is still reserved first as
        // belt-and-braces; only a str=0 that no longer fits there falls back
        // to an indirect slot.
        Map<Integer, Integer> strCountByTable = new LinkedHashMap<Integer, Integer>();
        for (Entry e : entries) {
            Integer cur = strCountByTable.get(e.strTableAddr);
            strCountByTable.put(e.strTableAddr, cur == null ? e.str + 1 : Math.max(cur, e.str + 1));
        }
        Map<Integer, Entry> str0ByTable = new LinkedHashMap<Integer, Entry>();
        for (Entry e : entries) {
            if (e.str == 0) str0ByTable.put(e.strTableAddr, e);
        }

        // str=0's slot value doubles as a table-size hint in the original data
        // (offset == strCount*2 when the text sits right after its own table),
        // so the adjacent slot stays the first choice as belt-and-braces. When
        // the translated text no longer fits there -- it would collide with
        // the next fixed table -- the string simply falls back to an indirect
        // slot like any other, since no game code was found that actually
        // derives a count from it (all nine fetch sites just add the offset).
        List<int[]> reservations = new ArrayList<int[]>();
        List<Group> indirectGroups = new ArrayList<Group>();
        for (Map.Entry<Integer, Entry> me : str0ByTable.entrySet()) {
            int strTableAddr = me.getKey();
            Entry str0 = me.getValue();
            int strCount = strCountByTable.get(strTableAddr);
            int requiredAddr = strTableAddr + strCount * 2;
            int requiredEnd = requiredAddr + str0.encoded.length;

            boolean collision = false;
            for (int[] r : tableRanges) {
                if (r[0] == strTableAddr) continue; // this table's own (already-included) range
                if (requiredAddr < r[1] && requiredEnd > r[0]) { collision = true; break; }
            }
            if (collision) {
                Group g = new Group();
                g.encoded = str0.encoded;
                g.representativeAddr = str0.strTableAddr;
                g.indirect = true;
                g.members.add(str0);
                str0.group = g;
                indirectGroups.add(g);
                System.out.println("INFO: room=" + str0.room + " npc=" + str0.npc + " str=0 (" + str0.encoded.length
                        + " bytes) doesn't fit after its table (0x" + Integer.toHexString(strTableAddr)
                        + ") -- using an indirect slot.");
                continue;
            }
            str0.newAddr = requiredAddr;
            reservations.add(new int[]{strTableAddr, requiredEnd});
        }

        List<int[]> pool = new ArrayList<int[]>();
        pool.add(new int[]{minTextAddr, trueScriptEnd});
        pool.add(new int[]{trueScriptEnd, DEFAULT_GAP_END});
        if (freeSpacePath != null && Files.exists(Paths.get(freeSpacePath))) {
            for (FreeSpaceScanner.Region r : FreeSpaceScanner.readRegionsFile(freeSpacePath)) {
                pool.add(new int[]{r.start, r.end()});
            }
        }
        List<int[]> excluded = new ArrayList<int[]>(tableRanges);
        excluded.addAll(reservations);
        // Never place anything over the fetch helper block, even when a stale
        // free_space.txt (from before the 0xf6060 gap start) still offers it.
        excluded.add(new int[]{FETCH_HELPER_ADDR, 0xF6060});
        List<int[]> ranges = subtractRanges(mergeRanges(pool), mergeRanges(excluded));
        System.out.println("Writable pool ranges (after reserving " + reservations.size() + " str=0 slots):");
        long totalCapacity = 0;
        for (int[] r : ranges) {
            System.out.println("  0x" + Integer.toHexString(r[0]) + " - 0x" + Integer.toHexString(r[1])
                    + " (" + (r[1] - r[0]) + " bytes)");
            totalCapacity += (r[1] - r[0]);
        }
        System.out.println("Total capacity: " + totalCapacity + " bytes");

        // Preserve ONLY sharing that already existed in the original data (entries
        // that already pointed at the same original textAddr) -- deduping by
        // encoded-content equality alone would risk merging unrelated entries that
        // simply translate to the same short text (e.g. "no") across far-apart
        // rooms, creating a shared-storage requirement that never existed and can
        // be structurally impossible to satisfy (a single address reachable from
        // every referencing table). Within an original sharing set, if translation
        // made some members diverge, they're split into their own sub-group.
        // str=0 entries never participate: their position is mandatory and already
        // fixed above, never shared.
        // representativeAddr is a PLACEMENT FLOOR, not a sort convenience: since
        // offsets are unsigned/forward-only, a shared string can only go at or
        // after the LATEST (highest-address) of all the string-index tables
        // that reference it -- otherwise the earliest-placed member's offset
        // would need to be negative.
        Map<Integer, List<Entry>> byOriginalTextAddr = new LinkedHashMap<Integer, List<Entry>>();
        for (Entry e : entries) {
            if (e.str == 0) continue;
            byOriginalTextAddr.computeIfAbsent(e.textAddr, k -> new ArrayList<Entry>()).add(e);
        }
        List<Group> groups = new ArrayList<Group>();
        for (List<Entry> sameOrigin : byOriginalTextAddr.values()) {
            Map<String, Group> subGroups = new LinkedHashMap<String, Group>();
            for (Entry e : sameOrigin) {
                String key = bytesToHex(e.encoded);
                Group g = subGroups.get(key);
                if (g == null) {
                    g = new Group();
                    g.encoded = e.encoded;
                    g.representativeAddr = e.strTableAddr;
                    subGroups.put(key, g);
                }
                g.representativeAddr = Math.max(g.representativeAddr, e.strTableAddr);
                g.members.add(e);
                e.group = g;
            }
            groups.addAll(subGroups.values());
        }
        Collections.sort(groups, (a, b) -> Integer.compare(a.representativeAddr, b.representativeAddr));
        System.out.println(entries.size() + " strings, " + reservations.size() + " placed at mandatory str=0 slots, "
                + groups.size() + " unique flexible locations for the rest ("
                + ((entries.size() - str0ByTable.size()) - groups.size()) + " shared copies avoided).");

        // First pass: relative placement, exactly like the game's stock layout.
        // A group that can't sit within MAX_RELATIVE_REACH of every member's
        // table (or that this floor-constrained cursor can't fit anywhere)
        // falls back to an indirect slot instead of failing the build.
        int rangeIndex = 0;
        int cursor = ranges.get(0)[0];
        long totalBytesUsed = 0;
        for (int[] r : reservations) totalBytesUsed += str0ByTable.get(r[0]).encoded.length;

        for (Group g : groups) {
            int tryCursor = Math.max(cursor, g.representativeAddr);
            int tryRange = rangeIndex;
            while (tryRange < ranges.size() && tryCursor >= ranges.get(tryRange)[1]) tryRange++;
            if (tryRange < ranges.size() && tryCursor < ranges.get(tryRange)[0]) tryCursor = ranges.get(tryRange)[0];
            while (tryRange < ranges.size() && tryCursor + g.encoded.length > ranges.get(tryRange)[1]) {
                tryRange++;
                if (tryRange < ranges.size()) tryCursor = Math.max(ranges.get(tryRange)[0], tryCursor);
            }
            boolean placeable = tryRange < ranges.size();
            if (placeable) {
                for (Entry e : g.members) {
                    int reach = tryCursor - e.strTableAddr;
                    if (reach < 0 || reach > MAX_RELATIVE_REACH) { placeable = false; break; }
                }
            }
            if (!placeable) {
                g.indirect = true;
                indirectGroups.add(g);
                continue; // doesn't consume pool space here
            }
            g.newAddr = tryCursor;
            cursor = tryCursor + g.encoded.length;
            rangeIndex = tryRange;
            totalBytesUsed += g.encoded.length;
        }

        // Second pass: indirect groups (and then the pointer table itself) go
        // into whatever the relative pass left over, anywhere, no reach or
        // floor constraint -- the floor-skipping above leaves usable holes,
        // so repack from the true remaining space rather than the cursor.
        if (indirectGroups.size() > 0x8000) {
            throw new IllegalStateException(indirectGroups.size()
                    + " indirect strings exceed the 15-bit slot index limit (32768); " + outPath + " was NOT written.");
        }
        List<int[]> placed = new ArrayList<int[]>();
        for (Group g : groups) {
            if (!g.indirect) placed.add(new int[]{g.newAddr, g.newAddr + g.encoded.length});
        }
        List<int[]> remaining = subtractRanges(ranges, mergeRanges(placed));
        int irRange = 0;
        int irCursor = remaining.isEmpty() ? Integer.MAX_VALUE : remaining.get(0)[0];
        List<Group> unplaced = new ArrayList<Group>();
        for (Group g : indirectGroups) {
            while (irRange < remaining.size() && irCursor + g.encoded.length > remaining.get(irRange)[1]) {
                irRange++;
                if (irRange < remaining.size()) irCursor = remaining.get(irRange)[0];
            }
            if (irRange >= remaining.size()) { unplaced.add(g); continue; }
            g.newAddr = irCursor;
            irCursor += g.encoded.length;
            totalBytesUsed += g.encoded.length;
        }

        // The absolute-pointer table: 4 bytes per indirect group, even-aligned.
        int tableSize = indirectGroups.size() * 4;
        int tableAddr = -1;
        if (unplaced.isEmpty()) {
            if (irRange < remaining.size()) irCursor = (irCursor + 1) & ~1;
            while (irRange < remaining.size() && irCursor + tableSize > remaining.get(irRange)[1]) {
                irRange++;
                if (irRange < remaining.size()) irCursor = (remaining.get(irRange)[0] + 1) & ~1;
            }
            if (irRange < remaining.size()) {
                tableAddr = irCursor;
                totalBytesUsed += tableSize;
            }
        }

        if (!unplaced.isEmpty() || tableAddr < 0) {
            System.out.println();
            int failCount = 0;
            for (Group g : unplaced) failCount += g.members.size();
            System.out.println((tableAddr < 0 && unplaced.isEmpty()
                    ? "The absolute-pointer table (" + tableSize + " bytes)"
                    : failCount + " string(s)") + " ran out of writable space. " + outPath + " was NOT written.");
            for (Group g : unplaced) {
                for (Entry e : g.members) {
                    System.out.println("  room=" + e.room + " npc=" + e.npc + " str=" + e.str
                            + ": ran out of writable space (needs " + g.encoded.length + " bytes)");
                }
            }
            throw new IllegalStateException("out of writable space; " + outPath + " was NOT written.");
        }

        System.out.println();
        System.out.println("All " + entries.size() + " strings placed successfully ("
                + totalBytesUsed + " / " + (totalCapacity + reservations.stream().mapToLong(r -> r[1] - r[0]).sum())
                + " bytes used); " + indirectGroups.size() + " indirect slot(s), pointer table at 0x"
                + Integer.toHexString(tableAddr) + ".");

        int moved = 0;
        for (Entry str0 : str0ByTable.values()) {
            if (str0.newAddr < 0) continue; // fell back to an indirect slot, handled below
            for (int i = 0; i < str0.encoded.length; i++) {
                rom[str0.newAddr + i] = str0.encoded[i];
            }
            int newOffset = str0.newAddr - str0.strTableAddr;
            rom[str0.ptrFieldAddr] = (byte) ((newOffset >> 8) & 0xFF);
            rom[str0.ptrFieldAddr + 1] = (byte) (newOffset & 0xFF);
            if (str0.newAddr != str0.textAddr) moved++;
        }
        for (Group g : groups) {
            if (g.indirect) continue;
            for (int i = 0; i < g.encoded.length; i++) {
                rom[g.newAddr + i] = g.encoded[i];
            }
            for (Entry e : g.members) {
                int newOffset = g.newAddr - e.strTableAddr;
                rom[e.ptrFieldAddr] = (byte) ((newOffset >> 8) & 0xFF);
                rom[e.ptrFieldAddr + 1] = (byte) (newOffset & 0xFF);
                if (g.newAddr != e.textAddr) moved++;
            }
        }
        for (int idx = 0; idx < indirectGroups.size(); idx++) {
            Group g = indirectGroups.get(idx);
            g.indirectIndex = idx;
            for (int i = 0; i < g.encoded.length; i++) {
                rom[g.newAddr + i] = g.encoded[i];
            }
            writeS32(rom, tableAddr + 4 * idx, g.newAddr);
            int slot = 0x8000 | idx;
            for (Entry e : g.members) {
                rom[e.ptrFieldAddr] = (byte) ((slot >> 8) & 0xFF);
                rom[e.ptrFieldAddr + 1] = (byte) (slot & 0xFF);
                moved++;
            }
        }
        System.out.println(moved + " string(s) relocated, " + (entries.size() - moved) + " stayed at their original address.");

        // Install the dual-mode fetch helper and detour the game's nine
        // table-walk sites through it. Applied unconditionally (even with an
        // empty pointer table) so every build exercises the same code path.
        System.arraycopy(FETCH_HELPER_CODE, 0, rom, FETCH_HELPER_ADDR, FETCH_HELPER_CODE.length);
        writeS32(rom, FETCH_LEA_D0_OPERAND, tableAddr);
        writeS32(rom, FETCH_LEA_D2_OPERAND, tableAddr);
        applyCodePatches(rom, FETCH_SITE_PATCHES);
        System.out.println("Fetch helper installed at 0x" + Integer.toHexString(FETCH_HELPER_ADDR)
                + ", 9 table-walk sites patched.");

        fixChecksum(rom);

        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }

    /**
     * Recomputes the Genesis header checksum (big-endian sum of all 16-bit
     * words from 0x200 to the end of the ROM, mod 0x10000) and writes it to
     * 0x18E-0x18F. Real Genesis hardware doesn't verify this at boot, but
     * some emulators/flashcarts do, and every legitimate ROM patcher fixes
     * it as a matter of course.
     */
    public static void fixChecksum(byte[] rom) {
        int sum = 0;
        for (int i = 0x200; i + 1 < rom.length; i += 2) {
            int word = ((rom[i] & 0xFF) << 8) | (rom[i + 1] & 0xFF);
            sum = (sum + word) & 0xFFFF;
        }
        rom[0x18e] = (byte) ((sum >> 8) & 0xFF);
        rom[0x18f] = (byte) (sum & 0xFF);
        System.out.println("Checksum fixed: 0x" + Integer.toHexString(sum));
    }

    static void writeS32(byte[] rom, int off, int value) {
        rom[off] = (byte) (value >>> 24);
        rom[off + 1] = (byte) (value >>> 16);
        rom[off + 2] = (byte) (value >>> 8);
        rom[off + 3] = (byte) value;
    }

    /**
     * Applies {address}, {expected original bytes}, {replacement bytes}
     * patches: verifies the ROM still holds the expected bytes before
     * touching anything (already-applied patches are skipped, so re-running
     * on a patched ROM is a no-op), and fails loudly on anything else.
     */
    static void applyCodePatches(byte[] rom, int[][][] patches) {
        for (int[][] p : patches) {
            int addr = p[0][0];
            if (patchMatches(rom, addr, p[2])) continue; // already applied
            if (!patchMatches(rom, addr, p[1])) {
                throw new IllegalStateException(String.format(
                        "code patch at 0x%x: ROM bytes match neither the original nor the patch -- wrong ROM?", addr));
            }
            for (int i = 0; i < p[2].length; i++) rom[addr + i] = (byte) p[2][i];
        }
    }

    static boolean patchMatches(byte[] rom, int addr, int[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if ((rom[addr + i] & 0xFF) != bytes[i]) return false;
        }
        return true;
    }

    /**
     * Resolves a string-index-table slot the same way the patched game does:
     * values below 0x8000 are offsets relative to the table, values with the
     * high bit set index the absolute-pointer table (whose address is read
     * back from the fetch helper's lea operand). Works on unpatched ROMs
     * too, where every slot is relative.
     */
    static int resolveStringAddr(byte[] rom, int strTableAddr, int index) {
        int slot = TextExtractor.readU16(rom, strTableAddr + 2 * index);
        if (slot < 0x8000) return strTableAddr + slot;
        int tableBase = TextExtractor.readU32(rom, FETCH_LEA_D0_OPERAND);
        return TextExtractor.readU32(rom, tableBase + 4 * (slot & 0x7FFF));
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static int findOriginalTerminator(byte[] rom, int addr) {
        int pos = addr;
        while ((rom[pos] & 0xFF) != 0xFF) pos++;
        return pos;
    }

    /**
     * Walks the original ROM's room/NPC/string index tables (same traversal
     * as TextExtractor) and returns their exact byte ranges, so they can be
     * excluded from the writable text pool.
     */
    static List<int[]> computeTableRanges(byte[] rom) {
        List<int[]> ranges = new ArrayList<int[]>();

        int firstRoomOffset = TextExtractor.readU32(rom, SCRIPT_BASE);
        int roomCount = (firstRoomOffset / 4) - 1;
        ranges.add(new int[]{SCRIPT_BASE, SCRIPT_BASE + roomCount * 4});

        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            int roomOffset = TextExtractor.readU32(rom, SCRIPT_BASE + roomIndex * 4);
            int npcTableAddr = SCRIPT_BASE + roomOffset;

            int firstNpcOffset = TextExtractor.readU16(rom, npcTableAddr);
            int npcCount = firstNpcOffset / 2;
            ranges.add(new int[]{npcTableAddr, npcTableAddr + npcCount * 2});

            for (int npcIndex = 0; npcIndex < npcCount; npcIndex++) {
                int npcOffset = TextExtractor.readU16(rom, npcTableAddr + npcIndex * 2);
                int strTableAddr = npcTableAddr + npcOffset;

                int firstStrOffset = TextExtractor.readU16(rom, strTableAddr);
                if (firstStrOffset >= 0x8000) {
                    // An indirect slot no longer encodes the table size, so
                    // this walk (and the whole repack, which reassembles the
                    // script from the ORIGINAL layout) can't run on its own
                    // output. The pipeline always passes a fresh ROM here.
                    throw new IllegalStateException(String.format(
                            "string table at 0x%x already holds an indirect slot -- the input ROM was already "
                            + "text-inserted. Run against a ROM with the original script instead.", strTableAddr));
                }
                int strCount = firstStrOffset / 2;
                ranges.add(new int[]{strTableAddr, strTableAddr + strCount * 2});
            }
        }
        return ranges;
    }

    static List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<int[]>(ranges);
        Collections.sort(sorted, (a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<int[]>();
        for (int[] r : sorted) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        return merged;
    }

    /** Both inputs must already be sorted, non-overlapping (via mergeRanges). */
    static List<int[]> subtractRanges(List<int[]> from, List<int[]> remove) {
        List<int[]> result = new ArrayList<int[]>();
        int ri = 0;
        for (int[] f : from) {
            int cur = f[0];
            while (ri < remove.size() && remove.get(ri)[1] <= cur) ri++;
            int scan = ri;
            while (scan < remove.size() && remove.get(scan)[0] < f[1]) {
                int[] r = remove.get(scan);
                if (r[0] > cur) result.add(new int[]{cur, Math.min(r[0], f[1])});
                cur = Math.max(cur, r[1]);
                scan++;
            }
            if (cur < f[1]) result.add(new int[]{cur, f[1]});
        }
        return result;
    }

    /**
     * Pads the text ahead of every &lt;YESNO&gt; with blank lines so the prompt
     * is always reached on a LINES_PER_BOX boundary, i.e. after a whole number
     * of full dialogue pages. Text that already sits on a boundary is left
     * byte-identical. Line counting restarts after each prompt.
     *
     * Counting is done over tokenized bytes rather than characters, because
     * only some of what TextExtractor prints is actually drawn in the box:
     * opcode placeholders ({e0} and friends) are control data, and so are the
     * operand bytes an opcode consumes -- even though those render as ordinary
     * glyphs ("{e0}0^" is one opcode and two data bytes, not a line of text).
     * A line made of nothing but those takes up no room in the box, which also
     * means a prompt sharing its line with only opcodes is already at the start
     * of a line and needs no break inserted ahead of it.
     *
     * A &lt;YESNO&gt; that is itself an operand byte is not a prompt at all and
     * is skipped; both cases are warned about.
     *
     * @return how many entries had to be changed (each is warned about individually)
     */
    static int alignYesNoPrompts(List<Entry> entries) {
        int changed = 0;
        // Entries that came in as <SAME> references share one original
        // textAddr and therefore one string: warn about it once, not once per
        // NPC that happens to say it.
        Set<Integer> warned = new HashSet<Integer>();
        Set<Integer> opWarned = new HashSet<Integer>();
        for (Entry e : entries) {
            if (e.text.indexOf(YESNO) < 0) continue;

            List<Tok> toks = tokenize(e.text);
            if (opWarned.add(e.textAddr)) reportOperandBytes(e, toks);

            StringBuilder out = new StringBuilder();
            List<Tok> line = new ArrayList<Tok>(); // current line, as far as it's been written
            int lines = 0;      // counted display lines since the last page boundary
            int inserted = 0;
            for (Tok t : toks) {
                if (YESNO.equals(t.text) && !t.operand) {
                    if (hasVisibleText(line)) { // close the partial line first
                        out.append(NEWLINE);
                        line.clear();
                        lines++;
                        inserted++;
                    }
                    while (lines % LINES_PER_BOX != 0) {
                        out.append(NEWLINE);
                        lines++;
                        inserted++;
                    }
                    out.append(YESNO);
                    line.add(t);
                    lines = 0; // the prompt closes the page
                    continue;
                }
                out.append(t.text);
                if (t.isNewline() && !t.operand) {
                    if (countsAsLine(line)) lines++;
                    line.clear();
                } else {
                    line.add(t);
                }
            }

            if (inserted > 0) {
                if (warned.add(e.textAddr)) {
                    System.out.println("WARN: room=" + e.room + " npc=" + e.npc + " str=" + e.str
                            + " (textAddr=0x" + Integer.toHexString(e.textAddr) + ") reaches <YESNO> mid-box; "
                            + "inserted " + inserted + " blank line(s) to land it on a "
                            + LINES_PER_BOX + "-line boundary.");
                    changed++;
                }
                e.text = out.toString();
            }
        }
        return changed;
    }

    /**
     * Warns about operand bytes that don't look like operands: ones that render
     * as ordinary glyphs (and so would read as printed text), and &lt;YESNO&gt;
     * markers that are really an opcode's operand rather than a prompt.
     */
    static void reportOperandBytes(Entry e, List<Tok> toks) {
        int disguised = 0;
        int falsePrompts = 0;
        for (Tok t : toks) {
            if (!t.operand) continue;
            if (YESNO.equals(t.text)) falsePrompts++;
            else if (!t.isPlaceholder() && !t.isNewline()) disguised++;
        }
        if (disguised == 0 && falsePrompts == 0) return;

        String where = "room=" + e.room + " npc=" + e.npc + " str=" + e.str
                + " (textAddr=0x" + Integer.toHexString(e.textAddr) + ")";
        if (disguised > 0) {
            System.out.println("WARN: " + where + ": " + disguised
                    + " operand byte(s) of a text opcode render as glyphs; counted as data, not as a printed line.");
        }
        if (falsePrompts > 0) {
            System.out.println("WARN: " + where + ": " + falsePrompts
                    + " <YESNO> marker(s) are operand bytes of a text opcode, not a Yes/No prompt; not aligned.");
        }
    }

    /** One decoded byte of extracted script text; see TOKEN. */
    static final class Tok {
        final String text;
        boolean operand; // consumed as an operand byte of a preceding opcode

        Tok(String text) { this.text = text; }

        boolean isPlaceholder() { return text.length() == 4 && text.charAt(0) == '{'; }

        boolean isNewline() { return text.length() == 1 && text.charAt(0) == NEWLINE; }
    }

    /**
     * Splits extracted text into one token per encoded byte, flagging those
     * that are an operand of a preceding opcode rather than text of their own.
     * An opcode that is itself an operand consumes nothing, since tokens are
     * flagged left to right before they are examined.
     */
    static List<Tok> tokenize(String text) {
        List<Tok> toks = new ArrayList<Tok>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) toks.add(new Tok(m.group()));

        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.operand || !t.isPlaceholder()) continue;
            Integer operands = OPCODE_OPERANDS.get(t.text.toUpperCase());
            if (operands == null) continue;
            for (int k = 1; k <= operands.intValue() && i + k < toks.size(); k++) {
                toks.get(i + k).operand = true;
            }
        }
        return toks;
    }

    /** True if the line prints anything once opcodes and their operands are dropped. */
    static boolean hasVisibleText(List<Tok> line) {
        for (Tok t : line) {
            if (t.operand || t.isPlaceholder()) continue;
            if (t.text.trim().length() > 0) return true;
        }
        return false;
    }

    /**
     * True if the line takes up a line of the dialogue box. A genuinely empty
     * line does (it's a deliberate blank); a line that carries only opcodes and
     * their operands does not.
     */
    static boolean countsAsLine(List<Tok> line) {
        if (hasVisibleText(line)) return true;
        for (Tok t : line) {
            if (t.isPlaceholder() || t.operand) return false;
        }
        return true;
    }

    /**
     * Resolves TextExtractor's "<SAME room=R npc=N str=S>" dedup references
     * (see its class doc) back into real text, in place, before encoding.
     * Follows chains defensively (normal extractor output never chains --
     * references always point straight at the first-seen entry -- but a
     * hand-edit could create one) and fails loudly on a dangling or circular
     * reference rather than silently encoding the literal marker text.
     */
    static void resolveSameRefs(List<Entry> entries, String scriptPath) {
        Map<String, Entry> byKey = new LinkedHashMap<String, Entry>();
        for (Entry e : entries) byKey.put(e.room + "," + e.npc + "," + e.str, e);

        for (Entry e : entries) {
            Matcher m = SAME_REF.matcher(e.text.trim());
            if (!m.matches()) continue;

            Set<Entry> visited = new HashSet<Entry>();
            Entry cur = e;
            Matcher curMatch = m;
            while (true) {
                visited.add(cur);
                String targetKey = curMatch.group(1) + "," + curMatch.group(2) + "," + curMatch.group(3);
                Entry target = byKey.get(targetKey);
                if (target == null) {
                    throw new IllegalArgumentException("room=" + e.room + " npc=" + e.npc + " str=" + e.str
                            + " in " + scriptPath + " references room=" + curMatch.group(1) + " npc=" + curMatch.group(2)
                            + " str=" + curMatch.group(3) + ", which doesn't exist.");
                }
                Matcher targetMatch = SAME_REF.matcher(target.text.trim());
                if (!targetMatch.matches()) {
                    e.text = target.text;
                    break;
                }
                if (visited.contains(target)) {
                    throw new IllegalArgumentException("circular <SAME ...> reference chain involving room=" + e.room
                            + " npc=" + e.npc + " str=" + e.str + " in " + scriptPath);
                }
                cur = target;
                curMatch = targetMatch;
            }
        }
    }

    static List<Entry> parseScript(String path) throws IOException {
        List<String> rawLines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<String>();
        for (String l : rawLines) {
            if (l.startsWith("; ")) continue; // skip WARNING/comment lines
            lines.add(l);
        }

        List<Entry> entries = new ArrayList<Entry>();
        int i = 0;
        while (i < lines.size()) {
            Matcher m = HEADER.matcher(lines.get(i));
            if (!m.matches()) {
                i++;
                continue;
            }
            Entry e = new Entry();
            e.room = Integer.parseInt(m.group(1));
            e.npc = Integer.parseInt(m.group(2));
            e.str = Integer.parseInt(m.group(3));
            e.textAddr = (int) Long.parseLong(m.group(4), 16);
            e.ptrFieldAddr = (int) Long.parseLong(m.group(5), 16);

            int bodyStart = i + 1;
            int bodyEnd = bodyStart;
            while (bodyEnd < lines.size() && !HEADER.matcher(lines.get(bodyEnd)).matches()) {
                bodyEnd++;
            }
            List<String> bodyLines = new ArrayList<String>(lines.subList(bodyStart, bodyEnd));
            if (!bodyLines.isEmpty() && bodyLines.get(bodyLines.size() - 1).isEmpty()) {
                bodyLines.remove(bodyLines.size() - 1);
            }
            e.text = String.join("\n", bodyLines);

            entries.add(e);
            i = bodyEnd;
        }
        return entries;
    }
}
