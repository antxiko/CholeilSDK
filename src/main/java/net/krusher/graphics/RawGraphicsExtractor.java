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
 * Extracts uncompressed (raw, unencoded) graphics blocks to PNGs -- for
 * assets like the boot SEGA logo and "PULSA START" prompt that are stored
 * directly as 4bpp tile bytes with no LZ-Toshio header, so they can't be
 * auto-detected the way gfx_out/'s compressed blocks are. Each block is
 * hand-verified and listed in raw_graphics.txt (address, length, columns).
 *
 * usage: RawGraphicsExtractor [romPath] [registryPath] [outDir]
 */
public final class RawGraphicsExtractor {

    private RawGraphicsExtractor() {}

    public static final class Block {
        public final int addr, length, columns;
        Block(int addr, int length, int columns) { this.addr = addr; this.length = length; this.columns = columns; }
    }

    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String registryPath = args.length > 1 ? args[1] : DefaultPaths.RAW_GRAPHICS;
        String outDir = args.length > 2 ? args[2] : DefaultPaths.RAW_GFX_OUT;

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<Block> blocks = loadBlocks(registryPath);
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);
        Path outPath = Paths.get(outDir);
        Files.createDirectories(outPath);

        int[] defaultPalette = TileRenderer.defaultGrayscalePalette();
        int realPaletteCount = 0;
        for (Block blk : blocks) {
            byte[] data = new byte[blk.length];
            System.arraycopy(rom, blk.addr, data, 0, blk.length);

            int[] palette = defaultPalette;
            Integer paletteAddr = knownPalettes.get(blk.addr);
            if (paletteAddr != null) {
                palette = TileRenderer.readGenesisPalette(rom, paletteAddr);
                realPaletteCount++;
            }

            BufferedImage img = TileRenderer.renderTileSheet(data, palette, blk.columns, TileRenderer.SCALE);
            String fileName = String.format("raw_%06x.png", blk.addr);
            TileRenderer.writePng(img, outPath.resolve(fileName).toString());
        }
        System.out.println("Extracted " + blocks.size() + " raw block(s) to " + outDir
                + " (" + realPaletteCount + " used a confirmed real palette)");
    }

    public static List<Block> loadBlocks(String path) throws IOException {
        List<Block> blocks = new ArrayList<Block>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            if (parts.length < 3) continue;
            int addr = (int) Long.parseLong(strip0x(parts[0].trim()), 16);
            int length = Integer.parseInt(parts[1].trim());
            int columns = Integer.parseInt(parts[2].trim());
            blocks.add(new Block(addr, length, columns));
        }
        return blocks;
    }

    static String strip0x(String s) {
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }
}
