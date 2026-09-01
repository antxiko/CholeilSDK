package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.krusher.DefaultPaths;

/**
 * Reinserts edited sprite-mosaic graphics from sprite_gfx_out/ back into the
 * ROM. Same fixed-length, in-place-only constraint as RawGraphicsInserter --
 * see sprite_graphics.txt for the format these blocks use.
 *
 * usage: SpriteGraphicsInserter [romPath] [spriteGfxOutDir] [registryPath] [outPath]
 */
public final class SpriteGraphicsInserter {

    private SpriteGraphicsInserter() {}

    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String spriteGfxOutDir = args.length > 1 ? args[1] : DefaultPaths.SPRITE_GFX_OUT;
        String registryPath = args.length > 2 ? args[2] : DefaultPaths.SPRITE_GRAPHICS;
        String outPath = args.length > 3 ? args[3] : DefaultPaths.OUT_ROM;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<SpriteGraphicsExtractor.Block> blocks = SpriteGraphicsExtractor.loadBlocks(registryPath);
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);

        int deleted = 0, unchanged = 0, updated = 0;

        for (SpriteGraphicsExtractor.Block blk : blocks) {
            String pngPath = Paths.get(spriteGfxOutDir, String.format("sprite_%06x.png", blk.addr)).toString();
            if (!Files.exists(Paths.get(pngPath))) {
                deleted++;
                continue;
            }

            int[] palette = SpriteGraphicsExtractor.guessedPaletteArgb();
            Integer paletteAddr = knownPalettes.get(blk.addr);
            if (paletteAddr != null) palette = TileRenderer.readGenesisPalette(rom, paletteAddr);

            Bitmap img = TileRenderer.readPng(pngPath);
            int tileCount = blk.length / TileRenderer.TILE_BYTES;
            int tilesPerSprite = blk.spriteTilesW * blk.spriteTilesH;
            int spriteCount = tileCount / tilesPerSprite;
            int macroRows = (spriteCount + blk.spritesPerRow - 1) / blk.spritesPerRow;
            int expectedWidth = blk.spritesPerRow * blk.spriteTilesW * TileRenderer.TILE_SIZE * TileRenderer.SCALE;
            int expectedHeight = macroRows * blk.spriteTilesH * TileRenderer.TILE_SIZE * TileRenderer.SCALE;
            if (img.getWidth() != expectedWidth || img.getHeight() != expectedHeight) {
                System.out.println("WARNING: " + pngPath + " has been resized -- sprite blocks have a fixed size, skipping.");
                continue;
            }

            byte[] newData = TileRenderer.decodeSpriteSheet(img, palette, blk.spriteTilesW, blk.spriteTilesH, blk.spritesPerRow, TileRenderer.SCALE, tileCount);

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
