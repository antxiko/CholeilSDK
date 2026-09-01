package net.krusher.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.krusher.DefaultPaths;

/**
 * Extracts uncompressed graphics blocks that are stored as SPRITE MOSAICS
 * (tiles in column-major order within each fixed-size sprite, sprites placed
 * in a macro grid) rather than a flat row-major tile raster -- see
 * sprite_graphics.txt for why these need separate handling from
 * raw_graphics.txt/RawGraphicsExtractor.
 *
 * usage: SpriteGraphicsExtractor [romPath] [registryPath] [outDir]
 */
public final class SpriteGraphicsExtractor {

    private SpriteGraphicsExtractor() {}

    public static final class Block {
        public final int addr, length, spritesPerRow, spriteTilesW, spriteTilesH;
        Block(int addr, int length, int spritesPerRow, int spriteTilesW, int spriteTilesH) {
            this.addr = addr; this.length = length;
            this.spritesPerRow = spritesPerRow; this.spriteTilesW = spriteTilesW; this.spriteTilesH = spriteTilesH;
        }
    }

    /**
     * Fallback palette for blocks with no confirmed known_palettes.txt entry,
     * reverse-derived from a player-supplied reference screenshot of the
     * "Soleil" title logo (dominant quantized colors sorted by luminance) --
     * NOT a traced, ground-truth CRAM palette. Replace once a real one is
     * confirmed (e.g. via a CRAM write breakpoint in the emulator).
     */
    static final int[] GUESSED_PALETTE = {
        0x000000, 0x244949, 0x494924, 0x496D49, 0x6D6D49, 0x926D49, 0x92926D, 0xB69249,
        0xDB9249, 0xDBB649, 0xDBB66D, 0xDBB692, 0xFFB66D, 0xFFDB6D, 0xFFDB92, 0xFFFFB6,
    };

    static int[] guessedPaletteArgb() {
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) pal[i] = 0xFF000000 | GUESSED_PALETTE[i];
        return pal;
    }

    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String registryPath = args.length > 1 ? args[1] : DefaultPaths.SPRITE_GRAPHICS;
        String outDir = args.length > 2 ? args[2] : DefaultPaths.SPRITE_GFX_OUT;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<Block> blocks = loadBlocks(registryPath);
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);
        Path outPath = Paths.get(outDir);
        Files.createDirectories(outPath);

        int realPaletteCount = 0;
        for (Block blk : blocks) {
            byte[] data = new byte[blk.length];
            System.arraycopy(rom, blk.addr, data, 0, blk.length);

            int[] palette = guessedPaletteArgb();
            Integer paletteAddr = knownPalettes.get(blk.addr);
            if (paletteAddr != null) {
                palette = TileRenderer.readGenesisPalette(rom, paletteAddr);
                realPaletteCount++;
            }

            BufferedImage img = TileRenderer.renderSpriteSheet(data, palette, blk.spriteTilesW, blk.spriteTilesH, blk.spritesPerRow, TileRenderer.SCALE);
            String fileName = String.format("sprite_%06x.png", blk.addr);
            TileRenderer.writePng(img, outPath.resolve(fileName).toString());
        }
        System.out.println("Extracted " + blocks.size() + " sprite block(s) to " + outDir
                + " (" + realPaletteCount + " used a confirmed real palette, rest used the unconfirmed guessed palette)");
    }

    public static List<Block> loadBlocks(String path) throws IOException {
        List<Block> blocks = new ArrayList<Block>();
        if (!Files.exists(Paths.get(path))) return blocks;
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            if (parts.length < 5) continue;
            int addr = (int) Long.parseLong(strip0x(parts[0].trim()), 16);
            int length = Integer.parseInt(parts[1].trim());
            int spritesPerRow = Integer.parseInt(parts[2].trim());
            int spriteTilesW = Integer.parseInt(parts[3].trim());
            int spriteTilesH = Integer.parseInt(parts[4].trim());
            blocks.add(new Block(addr, length, spritesPerRow, spriteTilesW, spriteTilesH));
        }
        return blocks;
    }

    static String strip0x(String s) {
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }
}
