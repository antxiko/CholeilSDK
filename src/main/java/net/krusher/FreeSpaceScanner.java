package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a ROM region for runs of a single repeated byte value, which is a
 * heuristic for unused/padding space (as opposed to genuine game data, which
 * essentially never contains long uniform byte runs -- compressed data in
 * particular would never decompress to a long uniform run without itself
 * being tiny). Candidates are written to an editable file so they can be
 * reviewed/pruned before being trusted as safe to overwrite.
 *
 * Only scans forward of a given start address: the text-insertion pointer
 * format can only reference addresses via small forward offsets, so space
 * before the script region is never usable regardless of whether it's free.
 */
public final class FreeSpaceScanner {

    private FreeSpaceScanner() {}

    static final int DEFAULT_MIN_RUN = 500;

    /**
     * Regions manually confirmed safe by cross-referencing against every LEA-
     * absolute instruction and every known relative-offset table base in the
     * ROM (the same technique PointerLocator uses for graphics blocks) --
     * unlike the heuristic scan below, these aren't uniform byte runs, so
     * they'd never be found automatically.
     *
     * 0xf6060-0xfd000: sits between the font glyph table (0xf5000-0xf5fff,
     * 256 reserved 16-byte slots, only up to index 0xaf ever populated --
     * confirmed live via the `lea $f5000,a3` in the character-tile renderer)
     * and the next real compressed graphics block (0xfd000, per
     * graphics_offsets.txt). The first ~0xf90 bytes are leftover Windows
     * developer-machine data that leaked into the ROM (readable strings:
     * printer-driver error text, MSVCRT runtime error messages like "R6000 -
     * stack overflow", and mmsystem.dll/audio-driver names like
     * "wavemapper") -- clearly not game data. The rest, out to 0xfd000, is
     * unreferenced padding/noise. No code or pointer table in the ROM
     * targets any address in this range. The first 0x60 bytes (0xf6000-
     * 0xf6060) are excluded: TextInserter installs its absolute-pointer
     * fetch helper there (see TextInserter.FETCH_HELPER_ADDR), and keeping
     * the block out of this list keeps every free-space consumer off it.
     */
    static final int[][] VERIFIED_GAPS = {
            {0xf6060, 0xfd000},
    };

    /**
     * usage: FreeSpaceScanner [romPath] [fromHexAddr] [outPath] [graphicsOffsetsPath] [minRunLength]
     * fromHexAddr defaults to the byte right after TextInserter's known script/gap region (0x1D8000),
     * since anything before that is already covered by TextInserter's default writable pool.
     */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String fromHex = args.length > 1 ? args[1] : "1d8000";
        String outPath = args.length > 2 ? args[2] : DefaultPaths.FREE_SPACE;
        String graphicsOffsetsPath = args.length > 3 ? args[3] : DefaultPaths.GRAPHICS_OFFSETS;
        int minRun = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_MIN_RUN;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        int fromAddr = (int) Long.parseLong(fromHex.startsWith("0x") ? fromHex.substring(2) : fromHex, 16);
        List<int[]> excluded = loadExcludedRanges(graphicsOffsetsPath);

        List<Region> regions = scan(rom, fromAddr, rom.length, minRun, excluded);
        addVerifiedGaps(regions, rom.length);
        writeRegionsFile(regions, outPath);

        long total = 0;
        for (Region r : regions) total += r.length;
        System.out.println("Scanned 0x" + Integer.toHexString(fromAddr) + " - 0x" + Integer.toHexString(rom.length)
                + ", found " + regions.size() + " candidate region(s), " + total + " bytes total.");
        System.out.println("Written to: " + outPath);
    }

    public static final class Region {
        public final int start;
        public final int length;
        public final int fillByte;

        Region(int start, int length, int fillByte) {
            this.start = start;
            this.length = length;
            this.fillByte = fillByte;
        }

        public int end() { return start + length; }
    }

    /**
     * @param minRunLength  minimum run length (bytes) to be considered a candidate
     * @param excluded      known-used byte ranges (e.g. validated compressed graphics
     *                      blocks) to skip; a run overlapping any of these is dropped
     */
    public static List<Region> scan(byte[] rom, int fromAddr, int toAddr, int minRunLength, List<int[]> excluded) {
        List<int[]> allExcluded = new ArrayList<int[]>();
        if (excluded != null) allExcluded.addAll(excluded);
        for (int[] gap : VERIFIED_GAPS) allExcluded.add(gap);

        List<Region> regions = new ArrayList<Region>();
        int i = fromAddr;
        while (i < toAddr) {
            int runStart = i;
            int b = rom[i] & 0xFF;
            int j = i + 1;
            while (j < toAddr && (rom[j] & 0xFF) == b) j++;
            int runLen = j - runStart;
            if (runLen >= minRunLength && !overlapsAny(runStart, j, allExcluded)) {
                regions.add(new Region(runStart, runLen, b));
            }
            i = j;
        }
        return regions;
    }

    /**
     * Adds every VERIFIED_GAPS entry, clipped only to the ROM's actual size --
     * unlike the heuristic scan, these aren't bounded by fromAddr. fromAddr's
     * forward-only rationale exists for the main script's 16-bit relative
     * offsets specifically; verified gaps may sit before it and still be
     * perfectly usable by absolute-pointer content (credits, etc.) that has
     * no such reach limit, so excluding them here would just hide real space
     * from every consumer that doesn't need the restriction.
     */
    static void addVerifiedGaps(List<Region> regions, int romLength) {
        for (int[] gap : VERIFIED_GAPS) {
            int start = Math.max(gap[0], 0);
            int end = Math.min(gap[1], romLength);
            if (start >= end) continue; // outside the ROM entirely
            // Placeholder fill byte: this isn't a uniform run, so there's no
            // single real fill value -- 0x00 just documents "not a real run".
            regions.add(new Region(start, end - start, 0x00));
        }
    }

    private static boolean overlapsAny(int start, int end, List<int[]> excluded) {
        if (excluded == null) return false;
        for (int[] range : excluded) {
            if (start < range[1] && end > range[0]) return true;
        }
        return false;
    }

    /** Loads {offset,encSize} ranges from a graphics_offsets.txt-style file, for exclusion. */
    public static List<int[]> loadExcludedRanges(String graphicsOffsetsPath) throws IOException {
        List<int[]> ranges = new ArrayList<int[]>();
        if (graphicsOffsetsPath == null || !Files.exists(Paths.get(graphicsOffsetsPath))) {
            return ranges;
        }
        for (String line : Files.readAllLines(Paths.get(graphicsOffsetsPath), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            if (parts.length < 2) continue;
            String hexPart = parts[0].trim();
            if (hexPart.startsWith("0x") || hexPart.startsWith("0X")) hexPart = hexPart.substring(2);
            int offset = (int) Long.parseLong(hexPart, 16);
            int encSize = Integer.parseInt(parts[1].trim());
            ranges.add(new int[]{offset, offset + encSize});
        }
        return ranges;
    }

    public static void writeRegionsFile(List<Region> regions, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("; Candidate free-space regions. Most are auto-detected by scanning for long\n");
        sb.append("; runs of a single repeated byte value -- a heuristic, not a guarantee, so\n");
        sb.append("; review before trusting and delete any line you're not sure is safe to\n");
        sb.append("; overwrite. A few (currently just 0xf6060-0xfd000, fill byte 0x00 as a\n");
        sb.append("; placeholder) are FreeSpaceScanner.VERIFIED_GAPS entries instead: manually\n");
        sb.append("; confirmed unreferenced by cross-checking every LEA-absolute instruction and\n");
        sb.append("; known table base in the ROM, not by the byte-run heuristic -- see that\n");
        sb.append("; constant's comment for what's actually there.\n");
        sb.append("; Format: 0xSTART,length,0xFILLBYTE\n");
        for (Region r : regions) {
            sb.append("0x").append(Integer.toHexString(r.start))
              .append(',').append(r.length)
              .append(",0x").append(String.format("%02x", r.fillByte))
              .append('\n');
        }
        Files.write(Paths.get(path), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<Region> readRegionsFile(String path) throws IOException {
        List<Region> regions = new ArrayList<Region>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            if (parts.length < 3) continue;
            String hexPart = parts[0].trim();
            if (hexPart.startsWith("0x") || hexPart.startsWith("0X")) hexPart = hexPart.substring(2);
            int start = (int) Long.parseLong(hexPart, 16);
            int length = Integer.parseInt(parts[1].trim());
            String fillHex = parts[2].trim();
            if (fillHex.startsWith("0x") || fillHex.startsWith("0X")) fillHex = fillHex.substring(2);
            int fillByte = Integer.parseInt(fillHex, 16);
            regions.add(new Region(start, length, fillByte));
        }
        return regions;
    }
}
