package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reinserts whatever blocks remain in a (presumably pruned-down) stray_text.txt
 * -- see StrayTextScanner. Unlike TextInserter (and unlike CreditsInserter,
 * which handles the one region that turned out to be pointer-based), these
 * strings are referenced by fixed absolute address directly in 68k code, not
 * through a relocatable pointer table, so there is nowhere to relocate an
 * oversized replacement to. A block that's too long is warned about and
 * trimmed to fit rather than aborting the whole build.
 *   - "ascii" blocks are written as literal bytes, right-padded with spaces
 *     to fill the original length exactly (fixed-width display field, not
 *     terminator-based); oversized text is truncated to the original length.
 *   - "encoded" blocks go through TblTable, get a 0xFF terminator, and any
 *     leftover bytes before the original length are left untouched (harmless,
 *     since nothing reads past the terminator); oversized text has trailing
 *     characters dropped (one at a time, re-encoding each time to respect
 *     multi-byte glyph boundaries) until it fits.
 */
public final class StrayTextInserter {

    static final Pattern HEADER = Pattern.compile("^==== (ascii|encoded) addr=0x([0-9a-fA-F]+) len=(\\d+) ====$");

    static final class Block {
        String type;
        int addr, len;
        String text;
    }

    /** usage: StrayTextInserter [romPath] [strayTextPath] [tblPath] [outPath] [creditsRegistryPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String strayTextPath = args.length > 1 ? args[1] : DefaultPaths.STRAY_TEXT;
        String tblPath = args.length > 2 ? args[2] : DefaultPaths.TBL;
        String outPath = args.length > 3 ? args[3] : DefaultPaths.OUT_ROM;
        String creditsRegistryPath = args.length > 4 ? args[4] : DefaultPaths.CREDITS_POINTERS;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        TblTable table = TblTable.load(tblPath);
        List<Block> blocks = parse(strayTextPath);
        System.out.println("Parsed " + blocks.size() + " block(s) from " + strayTextPath);

        if (creditsRegistryPath != null && Files.exists(Paths.get(creditsRegistryPath))) {
            List<int[]> cardRanges = CreditsInserter.cardRanges(rom, creditsRegistryPath);
            List<Block> remaining = new ArrayList<Block>();
            int skipped = 0;
            for (Block b : blocks) {
                if (inAnyRange(b.addr, cardRanges)) { skipped++; continue; }
                remaining.add(b);
            }
            if (skipped > 0) {
                System.out.println(skipped + " block(s) fall inside the credits pointer table region "
                        + "-- handled by CreditsInserter instead, skipped here.");
                blocks = remaining;
            }
        }

        int trimmed = 0;
        for (Block b : blocks) {
            if (b.type.equals("ascii")) {
                if (b.text.length() > b.len) {
                    System.out.println("WARN: addr=0x" + Integer.toHexString(b.addr) + " text is " + b.text.length()
                            + " chars, budget is " + b.len + "; trimmed to fit: \"" + b.text.substring(0, b.len) + "\"");
                    b.text = b.text.substring(0, b.len);
                    trimmed++;
                }
            } else {
                byte[] body;
                try {
                    body = table.encode(b.text);
                } catch (IllegalArgumentException ex) {
                    System.out.println("ENCODE FAILURE addr=0x" + Integer.toHexString(b.addr) + ": " + ex.getMessage());
                    throw ex;
                }
                if (body.length + 1 > b.len) {
                    String original = b.text;
                    String candidate = b.text;
                    while (!candidate.isEmpty()) {
                        candidate = candidate.substring(0, candidate.length() - 1);
                        byte[] retry;
                        try {
                            retry = table.encode(candidate);
                        } catch (IllegalArgumentException ex) {
                            continue; // trimming mid-glyph can desync tokenization; keep shortening
                        }
                        if (retry.length + 1 <= b.len) break;
                    }
                    System.out.println("WARN: addr=0x" + Integer.toHexString(b.addr) + " encodes to "
                            + (body.length + 1) + " bytes, budget is " + b.len + "; trimmed \"" + original
                            + "\" to \"" + candidate + "\"");
                    b.text = candidate;
                    trimmed++;
                }
            }
        }
        if (trimmed > 0) {
            System.out.println(trimmed + " block(s) trimmed to fit their fixed original budget.");
        }

        List<byte[]> encodedBytes = new ArrayList<byte[]>();
        for (Block b : blocks) {
            byte[] bytes;
            if (b.type.equals("ascii")) {
                bytes = new byte[b.len];
                for (int i = 0; i < b.len; i++) {
                    bytes[i] = (byte) (i < b.text.length() ? b.text.charAt(i) : ' ');
                }
            } else {
                byte[] body = table.encode(b.text);
                bytes = new byte[body.length + 1];
                System.arraycopy(body, 0, bytes, 0, body.length);
                bytes[body.length] = (byte) 0xFF;
            }
            encodedBytes.add(bytes);
        }

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            byte[] bytes = encodedBytes.get(i);
            System.arraycopy(bytes, 0, rom, b.addr, bytes.length);
        }

        TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Patched " + blocks.size() + " block(s) into " + outPath);
    }

    static boolean inAnyRange(int addr, List<int[]> ranges) {
        for (int[] r : ranges) {
            if (addr >= r[0] && addr < r[1]) return true;
        }
        return false;
    }

    static List<Block> parse(String path) throws IOException {
        List<String> rawLines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<String>();
        for (String l : rawLines) {
            if (l.startsWith("; ")) continue;
            lines.add(l);
        }

        List<Block> blocks = new ArrayList<Block>();
        int i = 0;
        while (i < lines.size()) {
            Matcher m = HEADER.matcher(lines.get(i));
            if (!m.matches()) { i++; continue; }

            Block b = new Block();
            b.type = m.group(1);
            b.addr = (int) Long.parseLong(m.group(2), 16);
            b.len = Integer.parseInt(m.group(3));

            int bodyStart = i + 1;
            int bodyEnd = bodyStart;
            while (bodyEnd < lines.size() && !HEADER.matcher(lines.get(bodyEnd)).matches()) bodyEnd++;
            List<String> bodyLines = new ArrayList<String>(lines.subList(bodyStart, bodyEnd));
            if (!bodyLines.isEmpty() && bodyLines.get(bodyLines.size() - 1).isEmpty()) {
                bodyLines.remove(bodyLines.size() - 1);
            }
            b.text = String.join("\n", bodyLines);

            blocks.add(b);
            i = bodyEnd;
        }
        return blocks;
    }
}
