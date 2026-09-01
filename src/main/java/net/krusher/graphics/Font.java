package net.krusher.graphics;

/**
 * The in-game dialogue font: where it lives and how it is stored.
 *
 * Found by disassembly. The text engine's per-glyph renderer at 0x33732 does:
 *
 *   033732  andi.w  #$ff, d0        ; d0 = the character code from the script
 *   03373a  lea.l   $ffe800.l, a0   ; scratch buffer, filled with 0xee
 *   033756  lea.l   $f5000.l, a3    ; <- the font
 *   03375c  lsl.w   #$4, d0         ; 16 bytes per glyph
 *   03375e  adda.w  d0, a3
 *
 * -- so a glyph is 16 bytes at FONT_ADDR + code * 16. It is 1 bit per pixel,
 * one byte per row, most significant bit leftmost: 8 wide by 16 tall, which is
 * the two stacked 8x8 tiles each character occupies on screen. The engine
 * expands that into 4bpp tiles at run time (the helper at 0x3381e, called
 * repeatedly at one-pixel offsets in different colours, is what gives the
 * letters their outline), so the ROM holds only the shape.
 *
 * Three call sites -- 0x33fc, 0x3375a and 0x337f6, plus 0x3b99a -- all read
 * the same table, so this is the only dialogue font in the ROM.
 *
 * The table runs 0xf5000 to 0xf5610: codes 0x00 to 0x60, which is exactly the
 * range soleil.tbl maps. What follows at 0xf5610 is NOT more glyphs -- it is a
 * strip of pre-rendered words in the same 1bpp format, with different vertical
 * metrics, so nothing here ever writes past GLYPH_COUNT.
 */
public final class Font {

    private Font() {}

    /** Base of the glyph table, from the lea at 0x33756. */
    public static final int FONT_ADDR = 0xF5000;

    /** From the lsl.w #$4 that indexes it. */
    public static final int GLYPH_BYTES = 16;

    public static final int GLYPH_WIDTH = 8;
    public static final int GLYPH_HEIGHT = 16;

    /**
     * Codes 0x00-0x60. Past this the table gives way to other data, so this
     * bound is a hard limit on what may be written, not a convention.
     */
    public static final int GLYPH_COUNT = 0x61;

    /** Where the table ends, and the pre-rendered word strip begins. */
    public static final int FONT_END = FONT_ADDR + GLYPH_COUNT * GLYPH_BYTES;

    /** Glyphs per row on the sheet; 16 keeps the code of a cell readable as row/col. */
    public static final int SHEET_COLUMNS = 16;

    public static final int SHEET_ROWS = (GLYPH_COUNT + SHEET_COLUMNS - 1) / SHEET_COLUMNS;

    public static final int SHEET_WIDTH = SHEET_COLUMNS * GLYPH_WIDTH;
    public static final int SHEET_HEIGHT = SHEET_ROWS * GLYPH_HEIGHT;

    /** Background and foreground; a 1bpp font needs no more. */
    public static final int[] PALETTE = {0xFF000000, 0xFFFFFFFF};

    /** Top-left corner of a code's cell on the sheet. */
    public static int cellX(int code) {
        return (code % SHEET_COLUMNS) * GLYPH_WIDTH;
    }

    public static int cellY(int code) {
        return (code / SHEET_COLUMNS) * GLYPH_HEIGHT;
    }

    /** Paints the whole table onto a fresh sheet. */
    public static Bitmap toSheet(byte[] rom) {
        Bitmap sheet = Bitmap.indexed(SHEET_WIDTH, SHEET_HEIGHT, PALETTE);
        for (int code = 0; code < GLYPH_COUNT; code++) {
            int at = FONT_ADDR + code * GLYPH_BYTES;
            for (int row = 0; row < GLYPH_HEIGHT; row++) {
                int bits = rom[at + row] & 0xFF;
                for (int col = 0; col < GLYPH_WIDTH; col++) {
                    int on = (bits >> (7 - col)) & 1;
                    sheet.setIndex(cellX(code) + col, cellY(code) + row, on);
                }
            }
        }
        return sheet;
    }

    /**
     * Reads the table back off a sheet and writes it into the ROM. A pixel
     * counts as set when it is nearer white than black, so a sheet that came
     * back from an editor in some other colour depth still works.
     */
    public static int fromSheet(byte[] rom, Bitmap sheet) {
        if (sheet.getWidth() != SHEET_WIDTH || sheet.getHeight() != SHEET_HEIGHT) {
            throw new IllegalStateException("the font sheet must be " + SHEET_WIDTH + "x" + SHEET_HEIGHT
                    + " (" + SHEET_COLUMNS + " glyphs across, " + GLYPH_WIDTH + "x" + GLYPH_HEIGHT
                    + " each); this one is " + sheet.getWidth() + "x" + sheet.getHeight());
        }
        int changed = 0;
        for (int code = 0; code < GLYPH_COUNT; code++) {
            int at = FONT_ADDR + code * GLYPH_BYTES;
            for (int row = 0; row < GLYPH_HEIGHT; row++) {
                int bits = 0;
                for (int col = 0; col < GLYPH_WIDTH; col++) {
                    if (isForeground(sheet.getRgb(cellX(code) + col, cellY(code) + row))) {
                        bits |= 1 << (7 - col);
                    }
                }
                if ((rom[at + row] & 0xFF) != bits) changed++;
                rom[at + row] = (byte) bits;
            }
        }
        return changed;
    }

    /** Nearer white than black, so any grey an editor introduces still resolves. */
    private static boolean isForeground(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r + g + b >= 3 * 128;
    }
}
