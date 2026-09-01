package net.krusher.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.krusher.DefaultPaths;
import net.krusher.FreeSpaceScanner;

/**
 * Recompresses and reinserts graphics back into the ROM, from either the
 * bulk gfx_out/ directory (16 columns, default grayscale palette -- see
 * {@link #main}) or a single hand-picked block with custom rendering
 * parameters (see {@link #single}, for blocks like the title logo that were
 * extracted at a different column count/real palette via GraphicsExtractor's
 * `single` command rather than the bulk `extract`).
 *
 * Per-block outcome, in both modes:
 *   - PNG deleted -> that block is left completely untouched.
 *   - PNG present but decodes to the same tile bytes as the original
 *     (compared against a fresh decompress of the original block) -> also
 *     left untouched, since re-encoding unchanged content is pure risk with
 *     no benefit.
 *   - PNG changed and the recompressed block fits in its original space (or
 *     smaller) -> written in place.
 *   - PNG changed and the recompressed block is BIGGER: PointerLocator finds
 *     what references the block's address; if found, the block is written
 *     to freshly-scanned free space and the reference is patched to point
 *     at it. If no reference can be found, the block is left untouched and
 *     a warning is printed -- per instruction, no partial/corrupt writes.
 */
public final class GraphicsInserter {

    static final int[] DEFAULT_PALETTE = TileRenderer.defaultGrayscalePalette();
    static final int COLUMNS = 16;
    static final int SCALE = TileRenderer.SCALE;

    static final class Block {
        int offset, encSize, decSize;
        Block(int offset, int encSize, int decSize) { this.offset = offset; this.encSize = encSize; this.decSize = decSize; }
    }

    enum Outcome { DELETED, UNCHANGED, IN_PLACE, RELOCATED, WARNED }

    /** Shared free-space allocation state across every block processed in one run. */
    static final class Context {
        final byte[] rom;
        final String graphicsOffsetsPath;
        List<FreeSpaceScanner.Region> freePool; // lazily scanned only if a relocation is actually needed
        final List<int[]> usedThisRun = new ArrayList<int[]>();

        Context(byte[] rom, String graphicsOffsetsPath) {
            this.rom = rom;
            this.graphicsOffsetsPath = graphicsOffsetsPath;
        }
    }

    /**
     * usage:
     *   GraphicsInserter [romPath] [gfxOutDir] [graphicsOffsetsPath] [outPath]
     *   GraphicsInserter single <romPath> <hexOffset> <pngPath> <paletteHexOffset> <columns> [graphicsOffsetsPath] [outPath]
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("single")) {
            String romPath = args[1];
            int offset = (int) Long.parseLong(strip0x(args[2]), 16);
            String pngPath = args[3];
            int paletteOffset = (int) Long.parseLong(strip0x(args[4]), 16);
            int columns = Integer.parseInt(args[5]);
            String graphicsOffsetsPath = args.length > 6 ? args[6] : DefaultPaths.GRAPHICS_OFFSETS;
            String outPath = args.length > 7 ? args[7] : romPath;
            single(romPath, offset, pngPath, paletteOffset, columns, graphicsOffsetsPath, outPath);
            return;
        }

        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String gfxOutDir = args.length > 1 ? args[1] : DefaultPaths.GFX_OUT;
        String graphicsOffsetsPath = args.length > 2 ? args[2] : DefaultPaths.GRAPHICS_OFFSETS;
        String outPath = args.length > 3 ? args[3] : DefaultPaths.OUT_ROM;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<Block> blocks = loadBlocks(graphicsOffsetsPath);
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);
        System.out.println("Loaded " + blocks.size() + " cataloged graphics block(s).");

        Context ctx = new Context(rom, graphicsOffsetsPath);
        int deleted = 0, unchanged = 0, inPlace = 0, relocated = 0, warned = 0;

        for (Block blk : blocks) {
            String pngPath = Paths.get(gfxOutDir, String.format("gfx_%06x.png", blk.offset)).toString();
            // Must match whatever palette GraphicsExtractor used to render this PNG,
            // or decoding pixels back to indices silently picks the wrong ones.
            Integer paletteAddr = knownPalettes.get(blk.offset);
            int[] palette = paletteAddr != null ? TileRenderer.readGenesisPalette(rom, paletteAddr) : DEFAULT_PALETTE;
            Outcome outcome = processBlock(ctx, blk, pngPath, palette, COLUMNS);
            switch (outcome) {
                case DELETED: deleted++; break;
                case UNCHANGED: unchanged++; break;
                case IN_PLACE: inPlace++; break;
                case RELOCATED: relocated++; break;
                case WARNED: warned++; break;
            }
        }

        System.out.println();
        System.out.println("Deleted (skipped): " + deleted);
        System.out.println("Unchanged (skipped): " + unchanged);
        System.out.println("Updated in place: " + inPlace);
        System.out.println("Relocated: " + relocated);
        System.out.println("Warned/left untouched: " + warned);

        net.krusher.TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }

    /**
     * usage: GraphicsInserter single <romPath> <hexOffset> <pngPath> <paletteHexOffset> <columns> <graphicsOffsetsPath> <outPath>
     * For a block extracted with GraphicsExtractor's `single` command (custom
     * columns/palette), not the bulk `extract` defaults.
     */
    public static void single(String romPath, int offset, String pngPath, int paletteOffset, int columns,
                               String graphicsOffsetsPath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<Block> blocks = loadBlocks(graphicsOffsetsPath);
        Block blk = null;
        for (Block b : blocks) if (b.offset == offset) { blk = b; break; }
        if (blk == null) {
            System.out.println("0x" + Integer.toHexString(offset) + " is not in " + graphicsOffsetsPath
                    + " -- not a cataloged block, nothing to do.");
            return;
        }

        int[] palette = TileRenderer.readGenesisPalette(rom, paletteOffset);
        Context ctx = new Context(rom, graphicsOffsetsPath);
        Outcome outcome = processBlock(ctx, blk, pngPath, palette, columns);
        System.out.println("Outcome: " + outcome);

        if (outcome == Outcome.UNCHANGED || outcome == Outcome.WARNED) {
            System.out.println(outPath + " was not written (nothing changed on disk).");
            return;
        }

        net.krusher.TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }

    /** Core per-block logic, shared by the bulk loop and single-block mode. Mutates ctx.rom in place. */
    static Outcome processBlock(Context ctx, Block blk, String pngPath, int[] palette, int columns) throws IOException {
        byte[] rom = ctx.rom;

        if (!Files.exists(Paths.get(pngPath))) {
            return Outcome.DELETED;
        }

        LzToshio.Result original = LzToshio.tryDecompress(rom, blk.offset, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (original == null) {
            System.out.println("WARNING: 0x" + Integer.toHexString(blk.offset)
                    + " no longer decodes as a valid block (something upstream already changed it?), skipping.");
            return Outcome.WARNED;
        }

        BufferedImage img = TileRenderer.readPng(pngPath);
        if (isWrongResolution(img, blk.decSize, columns)) {
            System.out.println("WARNING: 0x" + Integer.toHexString(blk.offset) + " (" + pngPath
                    + ") has been resized -- PNG resolution must stay as extracted, skipping.");
            return Outcome.WARNED;
        }

        int tileCount = blk.decSize / TileRenderer.TILE_BYTES;
        byte[] decodedTiles = TileRenderer.decodeTileSheet(img, palette, columns, SCALE, tileCount);

        byte[] newTileData;
        if (decodedTiles.length == original.data.length) {
            newTileData = decodedTiles;
        } else {
            // decSize wasn't a whole number of tiles: the trailing remainder
            // bytes were never rendered into the PNG (TileRenderer truncates
            // via integer division) -- not user-editable, so carry them
            // through from the original verbatim rather than losing them.
            newTileData = new byte[original.data.length];
            System.arraycopy(decodedTiles, 0, newTileData, 0, decodedTiles.length);
            System.arraycopy(original.data, decodedTiles.length, newTileData, decodedTiles.length,
                    original.data.length - decodedTiles.length);
        }

        if (Arrays.equals(newTileData, original.data)) {
            return Outcome.UNCHANGED;
        }

        byte[] recompressed = LzToshio.compress(newTileData);

        if (recompressed.length <= blk.encSize) {
            System.arraycopy(recompressed, 0, rom, blk.offset, recompressed.length);
            return Outcome.IN_PLACE;
        }

        List<PointerLocator.Reference> refs = PointerLocator.findAll(rom, blk.offset);
        if (refs.isEmpty()) {
            System.out.println("WARNING: 0x" + Integer.toHexString(blk.offset) + " grew from " + blk.encSize
                    + " to " + recompressed.length + " bytes and no pointer to it could be found -- "
                    + "leaving the original graphics in place, this block was NOT updated.");
            return Outcome.WARNED;
        }

        if (ctx.freePool == null) {
            List<int[]> excluded = FreeSpaceScanner.loadExcludedRanges(ctx.graphicsOffsetsPath);
            excluded.add(new int[]{0x1C0000, 0x1E0000}); // dialogue script + gap, claimed by TextInserter
            ctx.freePool = FreeSpaceScanner.scan(rom, 0x30000, rom.length, 16, excluded);
        }
        int newAddr = allocate(ctx.freePool, ctx.usedThisRun, recompressed.length);
        if (newAddr < 0) {
            System.out.println("WARNING: 0x" + Integer.toHexString(blk.offset) + " grew from " + blk.encSize
                    + " to " + recompressed.length + " bytes and no free space large enough was found -- "
                    + "leaving the original graphics in place, this block was NOT updated.");
            return Outcome.WARNED;
        }

        System.arraycopy(recompressed, 0, rom, newAddr, recompressed.length);
        for (PointerLocator.Reference ref : refs) {
            int newValue = ref.kind == PointerLocator.Reference.Kind.TABLE ? (newAddr - ref.tableBase) : newAddr;
            writeU32(rom, ref.pointerFieldAddr, newValue);
        }
        ctx.usedThisRun.add(new int[]{newAddr, newAddr + recompressed.length});
        System.out.println("Relocated 0x" + Integer.toHexString(blk.offset) + " -> 0x" + Integer.toHexString(newAddr)
                + " (" + recompressed.length + " bytes, " + refs.size() + " pointer(s) patched)");
        return Outcome.RELOCATED;
    }

    /** First-fit allocation from the free pool, tracking what's already been claimed this run. */
    static int allocate(List<FreeSpaceScanner.Region> pool, List<int[]> used, int size) {
        for (FreeSpaceScanner.Region region : pool) {
            int cursor = region.start;
            while (cursor + size <= region.end()) {
                int[] candidate = {cursor, cursor + size};
                boolean collides = false;
                for (int[] u : used) {
                    if (candidate[0] < u[1] && candidate[1] > u[0]) { collides = true; cursor = u[1]; break; }
                }
                if (!collides) return candidate[0];
            }
        }
        return -1;
    }

    static String strip0x(String s) {
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }

    /**
     * PNG resolution must never change -- tile count always comes from the
     * original decSize, not image dimensions (a rectangular PNG can't tell
     * "trailing blank padding on the last row" from "genuinely that many
     * tiles" by pixel content alone). This just validates the image wasn't
     * accidentally resized before trusting that assumption.
     */
    static boolean isWrongResolution(BufferedImage img, int originalDecSize, int columns) {
        int originalTileCount = originalDecSize / TileRenderer.TILE_BYTES;
        int expectedRows = Math.max(1, (originalTileCount + columns - 1) / columns);
        int expectedWidth = columns * TileRenderer.TILE_SIZE * SCALE;
        int expectedHeight = expectedRows * TileRenderer.TILE_SIZE * SCALE;
        return img.getWidth() != expectedWidth || img.getHeight() != expectedHeight;
    }

    static List<Block> loadBlocks(String path) throws IOException {
        List<Block> blocks = new ArrayList<Block>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            String hexPart = parts[0].trim();
            if (hexPart.startsWith("0x") || hexPart.startsWith("0X")) hexPart = hexPart.substring(2);
            int offset = (int) Long.parseLong(hexPart, 16);
            int encSize = Integer.parseInt(parts[1].trim());
            int decSize = Integer.parseInt(parts[2].trim());
            blocks.add(new Block(offset, encSize, decSize));
        }
        return blocks;
    }

    static void writeU32(byte[] rom, int addr, int value) {
        rom[addr] = (byte) ((value >> 24) & 0xFF);
        rom[addr + 1] = (byte) ((value >> 16) & 0xFF);
        rom[addr + 2] = (byte) ((value >> 8) & 0xFF);
        rom[addr + 3] = (byte) (value & 0xFF);
    }
}
