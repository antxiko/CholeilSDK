package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.krusher.DefaultPaths;

/**
 * Writes the dialogue font out as one editable PNG sheet: 16 glyphs across,
 * each cell 8x16, cell (col, row) holding character code row * 16 + col. See
 * Font for where the table is and how it is stored.
 *
 * The sheet is black and white because the font is one bit per pixel -- the
 * colours and the outline are the engine's doing at run time, not the ROM's.
 */
public final class FontExtractor {

    private FontExtractor() {}

    /** usage: FontExtractor [romPath] [outPngPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String outPath = args.length > 1 ? args[1] : DefaultPaths.FONT;
        run(romPath, outPath);
    }

    public static void run(String romPath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        Png.write(Font.toSheet(rom), outPath);
        System.out.println("Wrote " + outPath + " (" + Font.GLYPH_COUNT + " glyphs, codes 0x00-0x"
                + Integer.toHexString(Font.GLYPH_COUNT - 1) + ", " + Font.SHEET_WIDTH + "x" + Font.SHEET_HEIGHT
                + ", from 0x" + Integer.toHexString(Font.FONT_ADDR) + ").");
    }
}
