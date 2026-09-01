package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.krusher.DefaultPaths;
import net.krusher.TextInserter;

/**
 * Puts an edited font sheet back into the ROM, in place: the table is a fixed
 * 16 bytes per glyph at a fixed address, so nothing moves and nothing has to
 * be relocated. Delete the PNG to leave the ROM's own font alone, the way a
 * missing tile sheet leaves its graphics block alone.
 *
 * See Font for the format, and for why this stops at code 0x60.
 */
public final class FontInserter {

    private FontInserter() {}

    /** usage: FontInserter [romPath] [fontPngPath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String fontPath = args.length > 1 ? args[1] : DefaultPaths.FONT;
        String outPath = args.length > 2 ? args[2] : romPath;
        run(romPath, fontPath, outPath);
    }

    public static void run(String romPath, String fontPath, String outPath) throws IOException {
        if (!Files.exists(Paths.get(fontPath))) {
            System.out.println("No " + fontPath + " -- leaving the ROM's own font untouched.");
            return;
        }
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        int changed = Font.fromSheet(rom, Png.read(fontPath));
        TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println(changed == 0
                ? "Font unchanged (" + Font.GLYPH_COUNT + " glyphs match the ROM)."
                : changed + " of " + (Font.GLYPH_COUNT * Font.GLYPH_BYTES) + " font byte(s) rewritten.");
        System.out.println("Wrote " + outPath);
    }
}
