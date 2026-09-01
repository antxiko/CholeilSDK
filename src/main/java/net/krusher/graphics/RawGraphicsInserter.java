package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.krusher.DefaultPaths;

/**
 * Reinserts edited raw (uncompressed) graphics from raw_gfx_out/ back into
 * the ROM. Unlike the compressed pipeline, these blocks have a FIXED length
 * (no compression to shrink/grow, and no confirmed pointer to relocate via
 * even if we wanted to) -- so every block is always written in place at
 * its original address, same length in, same length out.
 *   - PNG deleted -> that block is left untouched.
 *   - PNG present but decodes to the same bytes as the ROM already has ->
 *     left untouched.
 *   - PNG present and different -> written in place.
 *
 * usage: RawGraphicsInserter [romPath] [rawGfxOutDir] [registryPath] [outPath]
 */
public final class RawGraphicsInserter {

    private RawGraphicsInserter() {}

    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String rawGfxOutDir = args.length > 1 ? args[1] : DefaultPaths.RAW_GFX_OUT;
        String registryPath = args.length > 2 ? args[2] : DefaultPaths.RAW_GRAPHICS;
        String outPath = args.length > 3 ? args[3] : DefaultPaths.OUT_ROM;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<RawGraphicsExtractor.Block> blocks = RawGraphicsExtractor.loadBlocks(registryPath);
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);
        int[] defaultPalette = TileRenderer.defaultGrayscalePalette();

        int deleted = 0, unchanged = 0, updated = 0;

        for (RawGraphicsExtractor.Block blk : blocks) {
            String pngPath = Paths.get(rawGfxOutDir, String.format("raw_%06x.png", blk.addr)).toString();
            if (!Files.exists(Paths.get(pngPath))) {
                deleted++;
                continue;
            }

            int[] palette = defaultPalette;
            Integer paletteAddr = knownPalettes.get(blk.addr);
            if (paletteAddr != null) palette = TileRenderer.readGenesisPalette(rom, paletteAddr);

            Bitmap img = TileRenderer.readPng(pngPath);
            int expectedWidth = blk.columns * TileRenderer.TILE_SIZE * TileRenderer.SCALE;
            int expectedRows = (blk.length / TileRenderer.TILE_BYTES + blk.columns - 1) / blk.columns;
            int expectedHeight = expectedRows * TileRenderer.TILE_SIZE * TileRenderer.SCALE;
            if (img.getWidth() != expectedWidth || img.getHeight() != expectedHeight) {
                System.out.println("WARNING: " + pngPath + " has been resized -- raw blocks have a fixed size, skipping.");
                continue;
            }

            int tileCount = blk.length / TileRenderer.TILE_BYTES;
            byte[] newData = TileRenderer.decodeTileSheet(img, palette, blk.columns, TileRenderer.SCALE, tileCount);

            byte[] original = Arrays.copyOfRange(rom, blk.addr, blk.addr + blk.length);
            if (Arrays.equals(newData, original)) {
                unchanged++;
                continue;
            }

            System.arraycopy(newData, 0, rom, blk.addr, newData.length);
            System.out.println("Updated 0x" + Integer.toHexString(blk.addr) + " in place (" + blk.length + " bytes)");
            updated++;
        }

        System.out.println();
        System.out.println("Deleted (skipped): " + deleted);
        System.out.println("Unchanged (skipped): " + unchanged);
        System.out.println("Updated in place: " + updated);

        net.krusher.TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }
}
