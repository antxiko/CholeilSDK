package net.krusher.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import net.krusher.DefaultPaths;

/**
 * CLI for finding and dumping LZ-Toshio-compressed graphics blocks.
 *
 * Usage:
 *   scan    [romPath] [offsetsPath]                                       - auto-detect candidate blocks, write them to an editable offsets file
 *   extract [romPath] [offsetsPath] [outDir]                              - decompress each listed offset and render a PNG tile sheet (16 cols, grayscale)
 *   single  <romPath> <hexOffset> <outFile> [paletteHexOffset] [columns]  - decompress one block and render it, optionally with a real CRAM palette
 */
public class GraphicsExtractor {

    static final int DEFAULT_MAX_ENC_SIZE = 0x8000;  // 32KB compressed
    static final int DEFAULT_MAX_DEC_SIZE = 0x20000;  // 128KB decompressed
    static final int DEFAULT_SCAN_STEP = 1;           // blocks are packed back-to-back, not word-aligned

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String mode = args[0];
        if (mode.equals("scan")) {
            String romPath = args.length > 1 ? args[1] : DefaultPaths.ROM;
            String offsetsPath = args.length > 2 ? args[2] : DefaultPaths.GRAPHICS_OFFSETS;
            scan(romPath, offsetsPath);
        } else if (mode.equals("extract")) {
            String romPath = args.length > 1 ? args[1] : DefaultPaths.ROM;
            String offsetsPath = args.length > 2 ? args[2] : DefaultPaths.GRAPHICS_OFFSETS;
            String outDir = args.length > 3 ? args[3] : DefaultPaths.GFX_OUT;
            extract(romPath, offsetsPath, outDir);
        } else if (mode.equals("single")) {
            String romPath = args[1];
            int offset = (int) Long.parseLong(strip0x(args[2]), 16);
            String outFile = args[3];
            Integer paletteOffset = args.length > 4 ? (int) Long.parseLong(strip0x(args[4]), 16) : null;
            int columns = args.length > 5 ? Integer.parseInt(args[5]) : 16;
            single(romPath, offset, outFile, paletteOffset, columns);
        } else {
            printUsage();
        }
    }

    static String strip0x(String s) {
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }

    static void single(String romPath, int offset, String outFile, Integer paletteOffset, int columns) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        LzToshio.Result r = LzToshio.tryDecompress(rom, offset, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (r == null) {
            System.out.println("Not a valid LZ-Toshio stream at 0x" + Integer.toHexString(offset));
            return;
        }
        int[] palette = paletteOffset != null
                ? TileRenderer.readGenesisPalette(rom, paletteOffset)
                : TileRenderer.defaultGrayscalePalette();
        BufferedImage img = TileRenderer.renderTileSheet(r.data, palette, columns, TileRenderer.SCALE);
        TileRenderer.writePng(img, outFile);
        System.out.println("Wrote " + outFile + " (" + (r.decSize / TileRenderer.TILE_BYTES) + " tiles, "
                + (paletteOffset != null ? "palette @0x" + Integer.toHexString(paletteOffset) : "default grayscale palette") + ")");
    }

    public static void scan(String romPath, String offsetsPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<RomScanner.Candidate> candidates = RomScanner.scan(rom, DEFAULT_SCAN_STEP, DEFAULT_MAX_ENC_SIZE, DEFAULT_MAX_DEC_SIZE);
        RomScanner.writeOffsetsFile(candidates, offsetsPath);
        System.out.println("Scanned " + rom.length + " bytes, found " + candidates.size() + " candidate LZ-Toshio blocks.");
        System.out.println("Offsets written to: " + offsetsPath);
    }

    public static void extract(String romPath, String offsetsPath, String outDir) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        List<Integer> offsets = RomScanner.readOffsetsFile(offsetsPath);
        Path outPath = Paths.get(outDir);
        Files.createDirectories(outPath);

        int[] defaultPalette = TileRenderer.defaultGrayscalePalette();
        Map<Integer, Integer> knownPalettes = KnownPalettes.load(DefaultPaths.KNOWN_PALETTES);
        int ok = 0, failed = 0, realPalette = 0;

        for (int offset : offsets) {
            LzToshio.Result r = LzToshio.tryDecompress(rom, offset, Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (r == null) {
                System.out.println("SKIP 0x" + Integer.toHexString(offset) + ": no longer decodes as a valid LZ-Toshio stream");
                failed++;
                continue;
            }
            int[] palette = defaultPalette;
            Integer paletteAddr = knownPalettes.get(offset);
            if (paletteAddr != null) {
                palette = TileRenderer.readGenesisPalette(rom, paletteAddr);
                realPalette++;
            }
            BufferedImage img = TileRenderer.renderTileSheet(r.data, palette, 16, TileRenderer.SCALE);
            String fileName = String.format("gfx_%06x.png", offset);
            TileRenderer.writePng(img, outPath.resolve(fileName).toString());
            ok++;
        }
        System.out.println("Extracted " + ok + " tile sheets to " + outDir + " (" + failed + " skipped, "
                + realPalette + " used a confirmed real palette)");
    }

    static void printUsage() {
        System.out.println("usage:");
        System.out.println("  scan    [romPath] [offsetsPath]                                       - auto-detect LZ-Toshio blocks in the ROM");
        System.out.println("  extract [romPath] [offsetsPath] [outDir]                              - decompress each listed offset and render a PNG tile sheet");
        System.out.println("  single  <romPath> <hexOffset> <outFile> [paletteHexOffset] [columns]  - decompress one block, optionally with a real CRAM palette");
    }
}
