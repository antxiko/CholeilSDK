package net.krusher.graphics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialogue font: sheet out, sheet in, and nothing beyond the table.
 *
 * The bound that matters is GLYPH_COUNT. The glyph table ends at 0xf5610 and
 * a strip of pre-rendered words begins there in the very same 1bpp format, so
 * an off-by-one here would quietly corrupt artwork rather than fail.
 */
@DisplayName("Font")
final class FontTest {

    private static byte[] base;

    @BeforeAll
    static void loadRom() throws Exception {
        String path = System.getProperty("smoke.rom", net.krusher.DefaultPaths.ROM);
        Assumptions.assumeTrue(Files.exists(Paths.get(path)),
                "base ROM '" + path + "' not present -- it is gitignored on purpose");
        base = Files.readAllBytes(Paths.get(path));
    }

    @Test
    @DisplayName("the sheet is the table, glyph for glyph")
    void theSheetIsTheTable() {
        Bitmap sheet = Font.toSheet(base);
        assertEquals(Font.SHEET_WIDTH, sheet.getWidth());
        assertEquals(Font.SHEET_HEIGHT, sheet.getHeight());

        for (int code = 0; code < Font.GLYPH_COUNT; code++) {
            for (int row = 0; row < Font.GLYPH_HEIGHT; row++) {
                int bits = base[Font.FONT_ADDR + code * Font.GLYPH_BYTES + row] & 0xFF;
                for (int col = 0; col < Font.GLYPH_WIDTH; col++) {
                    boolean lit = ((bits >> (7 - col)) & 1) != 0;
                    int pixel = sheet.getRgb(Font.cellX(code) + col, Font.cellY(code) + row);
                    assertEquals(lit ? Font.PALETTE[1] : Font.PALETTE[0], pixel,
                            String.format("code 0x%02x row %d col %d", code, row, col));
                }
            }
        }
    }

    @Test
    @DisplayName("an untouched sheet goes back in byte for byte")
    void anUntouchedSheetGoesBackInUnchanged() {
        byte[] rom = base.clone();
        int changed = Font.fromSheet(rom, Font.toSheet(base));
        assertEquals(0, changed, "re-inserting the ROM's own font should change nothing");
        assertArrayEquals(base, rom, "the whole ROM should be untouched");
    }

    @Test
    @DisplayName("it writes nothing outside the glyph table")
    void itWritesNothingOutsideTheGlyphTable() {
        // Every pixel set: the most destructive sheet there is. Only the table
        // may move -- in particular the pre-rendered words at FONT_END must
        // survive, since they are the same format and directly adjacent.
        Bitmap allOn = Bitmap.indexed(Font.SHEET_WIDTH, Font.SHEET_HEIGHT, Font.PALETTE);
        for (int y = 0; y < Font.SHEET_HEIGHT; y++) {
            for (int x = 0; x < Font.SHEET_WIDTH; x++) allOn.setIndex(x, y, 1);
        }
        byte[] rom = base.clone();
        Font.fromSheet(rom, allOn);

        assertArrayEquals(Arrays.copyOfRange(base, 0, Font.FONT_ADDR),
                Arrays.copyOfRange(rom, 0, Font.FONT_ADDR), "bytes before the table");
        assertArrayEquals(Arrays.copyOfRange(base, Font.FONT_END, base.length),
                Arrays.copyOfRange(rom, Font.FONT_END, rom.length), "bytes after the table");
        for (int i = Font.FONT_ADDR; i < Font.FONT_END; i++) {
            assertEquals((byte) 0xFF, rom[i], String.format("byte 0x%x of the table", i));
        }
    }

    @Test
    @DisplayName("the table stops where the pre-rendered words start")
    void theTableStopsWhereTheWordsStart() {
        // 0xf5610 is not a round number by accident: it is 0x61 glyphs of 16
        // bytes, and the data there has different vertical metrics.
        assertEquals(0xF5610, Font.FONT_END);
        assertEquals(Font.GLYPH_COUNT * Font.GLYPH_BYTES, Font.FONT_END - Font.FONT_ADDR);
    }

    @Test
    @DisplayName("one edited pixel moves exactly one bit")
    void oneEditedPixelMovesExactlyOneBit() {
        Bitmap sheet = Font.toSheet(base);
        int code = 0x0B; // 'A' in soleil.tbl
        int row = 0;     // blank on every glyph, so this cannot already be set
        sheet.setIndex(Font.cellX(code), Font.cellY(code) + row, 1);

        byte[] rom = base.clone();
        assertEquals(1, Font.fromSheet(rom, sheet), "exactly one byte should differ");
        int at = Font.FONT_ADDR + code * Font.GLYPH_BYTES + row;
        assertEquals((byte) 0x80, rom[at], "the leftmost pixel is the high bit");
        assertEquals(base[at] | 0x80, rom[at] & 0xFF, "no other bit in the row moved");
    }

    @Test
    @DisplayName("a sheet of the wrong size is rejected")
    void aSheetOfTheWrongSizeIsRejected() {
        Bitmap wrong = Bitmap.indexed(Font.SHEET_WIDTH, Font.SHEET_HEIGHT + Font.GLYPH_HEIGHT, Font.PALETTE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Font.fromSheet(base.clone(), wrong));
        assertTrue(e.getMessage().contains(Font.SHEET_WIDTH + "x" + Font.SHEET_HEIGHT),
                "the message says the size it wants: " + e.getMessage());
    }

    @Test
    @DisplayName("a sheet re-saved in another colour depth still resolves")
    void aSheetInAnotherColourDepthStillResolves() {
        // An editor may hand back greys or an inverted-looking palette; a pixel
        // counts as set when it is nearer white than black.
        Bitmap sheet = Font.toSheet(base);
        int[] pixels = new int[Font.SHEET_WIDTH * Font.SHEET_HEIGHT];
        for (int y = 0; y < Font.SHEET_HEIGHT; y++) {
            for (int x = 0; x < Font.SHEET_WIDTH; x++) {
                boolean lit = sheet.getRgb(x, y) == Font.PALETTE[1];
                pixels[y * Font.SHEET_WIDTH + x] = lit ? 0xFFC8C8C8 : 0xFF303030;
            }
        }
        byte[] rom = base.clone();
        assertEquals(0, Font.fromSheet(rom, Bitmap.trueColor(Font.SHEET_WIDTH, Font.SHEET_HEIGHT, pixels)));
        assertArrayEquals(base, rom);
    }

    @Test
    @DisplayName("extract to a file and insert it back, unchanged")
    void extractToAFileAndInsertItBack(@TempDir Path dir) throws Exception {
        Path rom = dir.resolve("rom.md");
        Path png = dir.resolve("font.png");
        Files.write(rom, base);

        FontExtractor.run(rom.toString(), png.toString());
        assertTrue(Files.size(png) > 0, "the sheet was written");
        FontInserter.run(rom.toString(), png.toString(), rom.toString());

        byte[] after = Files.readAllBytes(rom);
        assertArrayEquals(Arrays.copyOfRange(base, Font.FONT_ADDR, Font.FONT_END),
                Arrays.copyOfRange(after, Font.FONT_ADDR, Font.FONT_END), "the font survived the round trip");
    }

    @Test
    @DisplayName("a missing sheet leaves the ROM alone")
    void aMissingSheetLeavesTheRomAlone(@TempDir Path dir) throws Exception {
        Path rom = dir.resolve("rom.md");
        Files.write(rom, base);
        FontInserter.run(rom.toString(), dir.resolve("absent.png").toString(), rom.toString());
        assertArrayEquals(base, Files.readAllBytes(rom), "no sheet, no change -- not even a checksum rewrite");
    }
}
