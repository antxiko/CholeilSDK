package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans the ROM for plain ASCII text that isn't part of the main dialogue
 * script (which TextExtractor already fully covers) or the cataloged
 * compressed graphics blocks. Menu/debug/credits text is often stored
 * uncompressed and unencoded, as the original romhacking.net thread noted
 * for the US ROM (e.g. "SELECT BB_" and "COMB..." found near 0x33ad0/0x32650
 * here). A companion scan for soleil.tbl-encoded runs outside the script was
 * tried too, but turned out to be almost entirely coincidental noise from
 * graphics/sound data with only marginal genuine hits, so it was dropped.
 *
 * This is a noisy heuristic search over 2MB of mixed code and data, so
 * results are written in the same editable block format as script.txt:
 * address and original byte length (the reinsertion budget -- these strings
 * are referenced by fixed absolute address in code, not a relocatable
 * pointer table, so new content must fit in the same space or shorter).
 * Delete whole blocks you don't want before running StrayTextInserter.
 */
public final class StrayTextScanner {

    static final int SCRIPT_BASE = 0x1C0000;

    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String scriptPath = args.length > 1 ? args[1] : DefaultPaths.SCRIPT;
        String graphicsOffsetsPath = args.length > 2 ? args[2] : DefaultPaths.GRAPHICS_OFFSETS;
        String outPath = args.length > 3 ? args[3] : DefaultPaths.STRAY_TEXT;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));

        List<int[]> excluded = buildExclusions(rom, scriptPath, graphicsOffsetsPath);
        System.out.println("Excluded " + excluded.size() + " known regions (script + graphics).");

        List<Hit> asciiHits = scanAscii(rom, excluded, 5);

        StringBuilder report = new StringBuilder();
        report.append("; Stray text scan results -- unreviewed, may contain false positives from\n")
              .append("; coincidental byte patterns in code/graphics/sound data. Delete any whole\n")
              .append("; block (from '====' to the next '====') you don't want reinserted, then run:\n")
              .append(";   java -cp target/classes net.krusher.StrayTextInserter\n")
              .append("; len= is the ORIGINAL byte budget at that address -- these strings are\n")
              .append("; referenced by fixed absolute address in code, not a relocatable pointer\n")
              .append("; table, so replacement text must fit in the same space or be shorter.\n\n");

        for (Hit h : asciiHits) {
            report.append("==== ascii addr=0x").append(Integer.toHexString(h.addr))
                  .append(" len=").append(h.length).append(" ====\n");
            report.append(h.text).append("\n\n");
        }

        Files.write(Paths.get(outPath), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("ASCII hits: " + asciiHits.size());
        System.out.println("Written to: " + outPath);
    }

    static final class Hit {
        int addr, length;
        String text;
        Hit(int addr, int length, String text) { this.addr = addr; this.length = length; this.text = text; }
    }

    static List<int[]> buildExclusions(byte[] rom, String scriptPath, String graphicsOffsetsPath) throws IOException {
        List<int[]> ranges = new ArrayList<int[]>();

        int firstRoomOffset = TextExtractor.readU32(rom, SCRIPT_BASE);
        int roomCount = (firstRoomOffset / 4) - 1;
        int maxTextEnd = SCRIPT_BASE;
        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            int roomOffset = TextExtractor.readU32(rom, SCRIPT_BASE + roomIndex * 4);
            int npcTableAddr = SCRIPT_BASE + roomOffset;
            int firstNpcOffset = TextExtractor.readU16(rom, npcTableAddr);
            int npcCount = firstNpcOffset / 2;
            for (int npcIndex = 0; npcIndex < npcCount; npcIndex++) {
                int npcOffset = TextExtractor.readU16(rom, npcTableAddr + npcIndex * 2);
                int strTableAddr = npcTableAddr + npcOffset;
                int firstStrOffset = TextExtractor.readU16(rom, strTableAddr);
                int strCount = firstStrOffset / 2;
                for (int strIndex = 0; strIndex < strCount; strIndex++) {
                    int strOffset = TextExtractor.readU16(rom, strTableAddr + strIndex * 2);
                    int textAddr = strTableAddr + strOffset;
                    int pos = textAddr;
                    while ((rom[pos] & 0xFF) != 0xFF) pos++;
                    maxTextEnd = Math.max(maxTextEnd, pos + 1);
                }
            }
        }
        ranges.add(new int[]{SCRIPT_BASE, maxTextEnd});

        if (Files.exists(Paths.get(graphicsOffsetsPath))) {
            for (String line : Files.readAllLines(Paths.get(graphicsOffsetsPath), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith(";")) continue;
                String[] parts = t.split(",");
                String hexPart = parts[0].trim();
                if (hexPart.startsWith("0x") || hexPart.startsWith("0X")) hexPart = hexPart.substring(2);
                int offset = (int) Long.parseLong(hexPart, 16);
                int encSize = Integer.parseInt(parts[1].trim());
                ranges.add(new int[]{offset, offset + encSize});
            }
        }
        return TextInserter.mergeRanges(ranges);
    }

    static boolean isExcluded(int addr, List<int[]> excluded) {
        for (int[] r : excluded) {
            if (addr >= r[0] && addr < r[1]) return true;
            if (r[0] > addr) break;
        }
        return false;
    }

    /**
     * Rejects low-diversity noise (long runs of the same char or two, which
     * dominate coincidental matches inside graphics/sound data): requires at
     * least one distinct letter per 2 LETTERS (not total run length) -- a
     * short real word padded with lots of spaces (e.g. "SYSTEMS." centered
     * in a 40-byte fixed field) must not get penalized for the padding.
     */
    static boolean hasEnoughDiversity(String s) {
        Set<Character> distinct = new HashSet<Character>();
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetter(c)) {
                distinct.add(c);
                letters++;
            }
        }
        if (letters == 0) return false;
        return distinct.size() * 2 >= letters;
    }

    /**
     * All observed genuine game text (menus, credits, debug/select screens)
     * is uppercase-only -- there is no lowercase text anywhere in the ROM.
     * Rejecting any hit containing a lowercase letter cuts a large amount of
     * code/disassembly noise (which mixes case or uses lowercase mnemonics)
     * without risking real text, since real text never has lowercase to lose.
     */
    static boolean isUppercaseOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLowerCase(s.charAt(i))) return false;
        }
        return true;
    }

    // Common 68k opcodes that happen to render as printable ASCII and often
    // pad right up against a following string in ROM: NOP (0x4E71 = "Nq") and
    // RTS (0x4E75 = "Nu"). Left untrimmed, these get swallowed as a false
    // prefix of the "text" -- reinserting over them would corrupt real code.
    static final String[] CODE_ASCII_PREFIXES = { "Nq", "Nu" };

    /**
     * Every genuine hit found so far (menu text, credits, enemy-name
     * fragments) contains at least one vowel -- no real word or abbreviation
     * is all-consonant. Coincidental noise from code/graphics/sound data
     * frequently is (e.g. "PWQF", "HBHCPBPCN"), so this alone rejects most
     * of it cheaply.
     */
    static boolean hasVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("AEIOUaeiou".indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /**
     * Genuine text only ever uses digits as an isolated single-digit token
     * (menu items like "DATA - 1" .. "DATA - 4"); it never contains a
     * multi-digit run. Noise from data/addresses frequently does
     * (e.g. "PB68", "E33A"), so reject any hit with 2+ consecutive digits.
     */
    static boolean hasNoDigitRun(String s) {
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                run++;
                if (run >= 2) return false;
            } else {
                run = 0;
            }
        }
        return true;
    }

    // Punctuation actually observed in confirmed real text (colon before a
    // credited name, dash for hyphenation/menu items, period, slash, "@" and
    // "_" as separators in credits/menu text, "&" joining two credited
    // roles e.g. "& GAME DESIGN"). Anything else printable (e.g. "#", "|",
    // "<", ">", "^") only ever showed up in coincidental noise, like
    // "#|SEGA/" next to the SEGA copyright string.
    static final String ALLOWED_PUNCTUATION = ":-./@_&";

    static boolean hasOnlyAllowedChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || ALLOWED_PUNCTUATION.indexOf(c) >= 0) continue;
            return false;
        }
        return true;
    }

    static List<Hit> scanAscii(byte[] rom, List<int[]> excluded, int minLen) {
        List<Hit> hits = new ArrayList<Hit>();
        int i = 0;
        while (i < rom.length) {
            if (isExcluded(i, excluded)) { i++; continue; }
            int b = rom[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) { i++; continue; }
            int start = i;
            StringBuilder sb = new StringBuilder();
            while (i < rom.length && !isExcluded(i, excluded)) {
                int c = rom[i] & 0xFF;
                if (c < 0x20 || c > 0x7E) break;
                sb.append((char) c);
                i++;
            }
            int trimStart = 0;
            boolean trimmed = true;
            while (trimmed) {
                trimmed = false;
                for (String prefix : CODE_ASCII_PREFIXES) {
                    if (sb.length() - trimStart >= prefix.length()
                            && sb.substring(trimStart, trimStart + prefix.length()).equals(prefix)) {
                        trimStart += prefix.length();
                        trimmed = true;
                    }
                }
            }
            String text = sb.substring(trimStart);
            int textStart = start + trimStart;
            if (text.length() >= minLen && isUppercaseOnly(text) && hasEnoughDiversity(text)
                    && hasVowel(text) && hasNoDigitRun(text) && hasOnlyAllowedChars(text)) {
                hits.add(new Hit(textStart, text.length(), text));
            }
        }
        return hits;
    }
}
