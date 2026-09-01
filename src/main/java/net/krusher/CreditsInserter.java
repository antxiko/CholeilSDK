package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Relocates the staff-credits text -- unlike the rest of stray_text.txt,
 * this turns out to be genuinely pointer-based (see credits_pointers.txt):
 * a table of absolute 4-byte pointers, each pointing at the start of one
 * "card" (a run of fields -- role label, subtitle, staff name(s) -- each
 * separated by a single 0x0d or 0x00 byte, rendered as a unit). Because
 * these are full 32-bit absolute addresses (not the main script's 16-bit
 * forward-only offsets), a card can be placed anywhere in the ROM with no
 * proximity constraint, so cards are simply packed into whatever space is
 * available -- no representativeAddr/reach bookkeeping needed, unlike
 * TextInserter's Group placement.
 *
 * The cards' original combined region is reused as a first-choice pool,
 * exactly like TextInserter reuses the original script region; overflow
 * spills into free_space.txt, which is shrunk by whatever this consumes so
 * TextInserter (which reads it next in the pipeline) doesn't double-book the
 * same bytes. A card that still can't be placed is left untouched at its
 * original address/content rather than aborting the whole build.
 */
public final class CreditsInserter {

    static final class TableInfo {
        int tableAddr;
        int count;
        int dataEnd;
    }

    static TableInfo loadRegistry(String path) throws IOException {
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            TableInfo info = new TableInfo();
            info.tableAddr = parseHex(parts[0]);
            info.count = Integer.parseInt(parts[1].trim());
            info.dataEnd = parseHex(parts[2]);
            return info;
        }
        throw new IOException("No table row found in " + path);
    }

    static int parseHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return (int) Long.parseLong(s, 16);
    }

    static final class Field {
        int addr, len;
        String originalText;
        int sepAfter = -1; // separator byte following this field (0x0d/0x00), or -1 if last field in card
    }

    static final class Card {
        int index;
        int origStart, origEnd;
        List<Field> fields = new ArrayList<Field>();
        byte[] encoded;
        int newAddr = -1;
    }

    /**
     * Re-derives card boundaries and their internal field/separator layout
     * directly from the ROM (not from stray_text.txt), since that's the only
     * place the exact 0x0d-vs-0x00 separator choice per field is recorded.
     */
    public static List<Card> readCards(byte[] rom, TableInfo info) {
        List<Card> cards = new ArrayList<Card>();
        int[] starts = new int[info.count];
        for (int i = 0; i < info.count; i++) {
            starts[i] = readU32(rom, info.tableAddr + i * 4);
        }
        for (int i = 0; i < info.count; i++) {
            Card c = new Card();
            c.index = i;
            c.origStart = starts[i];
            c.origEnd = (i + 1 < info.count) ? starts[i + 1] : info.dataEnd;

            int pos = c.origStart;
            while (pos < c.origEnd) {
                int b = rom[pos] & 0xFF;
                if (b < 0x20 || b > 0x7e) { pos++; continue; }
                int fieldStart = pos;
                StringBuilder sb = new StringBuilder();
                while (pos < c.origEnd && (rom[pos] & 0xFF) >= 0x20 && (rom[pos] & 0xFF) <= 0x7e) {
                    sb.append((char) (rom[pos] & 0xFF));
                    pos++;
                }
                Field f = new Field();
                f.addr = fieldStart;
                f.len = sb.length();
                f.originalText = sb.toString();
                c.fields.add(f);
                if (pos < c.origEnd) {
                    f.sepAfter = rom[pos] & 0xFF;
                    pos++;
                }
            }
            cards.add(c);
        }
        return cards;
    }

    static int readU32(byte[] rom, int addr) {
        return ((rom[addr] & 0xFF) << 24) | ((rom[addr + 1] & 0xFF) << 16)
             | ((rom[addr + 2] & 0xFF) << 8) | (rom[addr + 3] & 0xFF);
    }

    /** [origStart,origEnd) per card -- used by StrayTextInserter to skip these blocks (handled here instead). */
    public static List<int[]> cardRanges(byte[] rom, String registryPath) throws IOException {
        TableInfo info = loadRegistry(registryPath);
        List<Card> cards = readCards(rom, info);
        List<int[]> ranges = new ArrayList<int[]>();
        for (Card c : cards) ranges.add(new int[]{c.origStart, c.origEnd});
        return ranges;
    }

    /** usage: CreditsInserter [romPath] [strayTextPath] [tblPath] [freeSpacePath] [registryPath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String strayTextPath = args.length > 1 ? args[1] : DefaultPaths.STRAY_TEXT;
        String tblPath = args.length > 2 ? args[2] : DefaultPaths.TBL;
        String freeSpacePath = args.length > 3 ? args[3] : DefaultPaths.FREE_SPACE;
        String registryPath = args.length > 4 ? args[4] : DefaultPaths.CREDITS_POINTERS;
        String outPath = args.length > 5 ? args[5] : DefaultPaths.OUT_ROM;
        run(romPath, strayTextPath, tblPath, freeSpacePath, registryPath, outPath);
    }

    public static void run(String romPath, String strayTextPath, String tblPath,
                            String freeSpacePath, String registryPath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        TableInfo info = loadRegistry(registryPath);
        List<Card> cards = readCards(rom, info);

        Map<Integer, StrayTextInserter.Block> blocksByAddr = new HashMap<Integer, StrayTextInserter.Block>();
        for (StrayTextInserter.Block b : StrayTextInserter.parse(strayTextPath)) {
            blocksByAddr.put(b.addr, b);
        }

        for (Card c : cards) {
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < c.fields.size(); i++) {
                Field f = c.fields.get(i);
                StrayTextInserter.Block b = blocksByAddr.get(f.addr);
                // A field missing from stray_text.txt (deleted by the translator, or
                // never scanned) falls back to its original ROM text, so the card's
                // field count/order stays intact rather than silently dropping a line.
                String text = (b != null) ? b.text : f.originalText;
                combined.append(text);
                // Every field is terminated by its separator byte, INCLUDING the last
                // field of a card (confirmed: in the original ROM each field, even a
                // card's final one, is followed by 0x00 before the next card begins).
                // Omitting this for a card's last field would let the renderer read
                // straight into whatever comes next at the new address instead of
                // stopping. The one field with no recorded separator (the very last
                // field of the very last card, whose dataEnd boundary sits right at
                // its own end in the original ROM) still gets a synthesized 0x00,
                // since after relocation it's followed by unpredictable bytes rather
                // than the 68k code that used to sit right there.
                combined.append((char) (f.sepAfter != -1 ? f.sepAfter : 0x00));
            }
            byte[] bytes = new byte[combined.length()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) combined.charAt(i);
            c.encoded = bytes;
        }

        List<int[]> pool = new ArrayList<int[]>();
        pool.add(new int[]{cards.get(0).origStart, info.dataEnd});
        List<FreeSpaceScanner.Region> freeRegions = new ArrayList<FreeSpaceScanner.Region>();
        if (freeSpacePath != null && Files.exists(Paths.get(freeSpacePath))) {
            freeRegions = FreeSpaceScanner.readRegionsFile(freeSpacePath);
            for (FreeSpaceScanner.Region r : freeRegions) pool.add(new int[]{r.start, r.end()});
        }
        List<int[]> ranges = TextInserter.mergeRanges(pool);

        int rangeIndex = 0;
        int cursor = ranges.get(0)[0];
        List<Card> failures = new ArrayList<Card>();
        List<int[]> occupied = new ArrayList<int[]>();

        for (Card c : cards) {
            while (rangeIndex < ranges.size() && cursor >= ranges.get(rangeIndex)[1]) rangeIndex++;
            if (rangeIndex < ranges.size() && cursor < ranges.get(rangeIndex)[0]) cursor = ranges.get(rangeIndex)[0];
            while (rangeIndex < ranges.size() && cursor + c.encoded.length > ranges.get(rangeIndex)[1]) {
                rangeIndex++;
                if (rangeIndex < ranges.size()) cursor = Math.max(ranges.get(rangeIndex)[0], cursor);
            }
            if (rangeIndex >= ranges.size()) {
                failures.add(c);
                continue;
            }
            c.newAddr = cursor;
            occupied.add(new int[]{cursor, cursor + c.encoded.length});
            cursor += c.encoded.length;
        }

        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("WARN: " + failures.size() + " credits card(s) ran out of space to relocate; "
                    + "left untouched at their original address:");
            for (Card c : failures) {
                System.out.println("  card index=" + c.index + " (orig 0x" + Integer.toHexString(c.origStart)
                        + "): needs " + c.encoded.length + " bytes, none of the writable pool had room.");
            }
        }

        for (Card c : cards) {
            if (c.newAddr < 0) continue;
            System.arraycopy(c.encoded, 0, rom, c.newAddr, c.encoded.length);
            int entryAddr = info.tableAddr + c.index * 4;
            rom[entryAddr] = (byte) ((c.newAddr >> 24) & 0xFF);
            rom[entryAddr + 1] = (byte) ((c.newAddr >> 16) & 0xFF);
            rom[entryAddr + 2] = (byte) ((c.newAddr >> 8) & 0xFF);
            rom[entryAddr + 3] = (byte) (c.newAddr & 0xFF);
        }

        int placed = 0, moved = 0;
        for (Card c : cards) {
            if (c.newAddr < 0) continue;
            placed++;
            if (c.newAddr != c.origStart) moved++;
        }
        System.out.println(placed + "/" + cards.size() + " credits card(s) placed (" + moved + " relocated, "
                + (placed - moved) + " stayed at their original address).");

        // Shrink free_space.txt by whatever this run consumed, so TextInserter
        // (which reads it next in the pipeline) doesn't double-book the same bytes.
        if (!freeRegions.isEmpty() && !occupied.isEmpty()) {
            List<FreeSpaceScanner.Region> shrunk = new ArrayList<FreeSpaceScanner.Region>();
            for (FreeSpaceScanner.Region r : freeRegions) {
                List<int[]> remaining = TextInserter.subtractRanges(
                        Collections.singletonList(new int[]{r.start, r.end()}), occupied);
                for (int[] piece : remaining) {
                    shrunk.add(new FreeSpaceScanner.Region(piece[0], piece[1] - piece[0], r.fillByte));
                }
            }
            FreeSpaceScanner.writeRegionsFile(shrunk, freeSpacePath);
        }

        TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }
}
