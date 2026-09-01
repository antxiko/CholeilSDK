package net.krusher.graphics;

import java.io.IOException;

/**
 * Renders decompressed graphics bytes as Mega Drive 8x8, 4bpp packed tiles
 * (two pixels per byte, 4 bytes per row, 32 bytes per tile) into a PNG tile
 * sheet. No CRAM palette is known yet, so a default 16-shade grayscale ramp
 * is used unless a real palette is supplied.
 *
 * Images are Bitmaps and PNGs go through Png, not java.awt/ImageIO: see Png
 * for why the toolchain stays clear of java.desktop.
 */
public final class TileRenderer {

    public static final int TILE_SIZE = 8;
    public static final int TILE_BYTES = 32;

    /** Pixel scale used by every extractor/inserter -- 1 = native resolution. */
    public static final int SCALE = 1;

    private TileRenderer() {}

    public static int[] defaultGrayscalePalette() {
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) {
            int v = i * 17;
            pal[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        return pal;
    }

    /**
     * Reads 16 colors in raw Genesis CRAM format (0000 bbb0 ggg0 rrr0, one
     * word per color) starting at {@code offset} and converts them to ARGB.
     */
    public static int[] readGenesisPalette(byte[] rom, int offset) {
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) {
            int hi = rom[offset + i * 2] & 0xFF;
            int lo = rom[offset + i * 2 + 1] & 0xFF;
            int b3 = (hi >> 1) & 7;
            int g3 = (lo >> 5) & 7;
            int r3 = (lo >> 1) & 7;
            int r = Math.round(r3 * 255f / 7f);
            int g = Math.round(g3 * 255f / 7f);
            int b = Math.round(b3 * 255f / 7f);
            pal[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        return pal;
    }

    public static Bitmap renderTileSheet(byte[] data, int[] palette, int columns, int scale) {
        int tileCount = Math.max(1, data.length / TILE_BYTES);
        int cols = Math.max(1, columns);
        int rows = (tileCount + cols - 1) / cols;

        Bitmap img = createIndexedImage(cols * TILE_SIZE * scale, rows * TILE_SIZE * scale, palette);

        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = (tile % cols) * TILE_SIZE;
            int tileY = (tile / cols) * TILE_SIZE;
            int base = tile * TILE_BYTES;

            for (int row = 0; row < TILE_SIZE; row++) {
                for (int col = 0; col < TILE_SIZE; col += 2) {
                    int byteIndex = base + row * 4 + col / 2;
                    int b = byteIndex < data.length ? (data[byteIndex] & 0xFF) : 0;
                    int leftIdx = (b >> 4) & 0xF;
                    int rightIdx = b & 0xF;

                    plotScaled(img, tileX + col, tileY + row, leftIdx, scale);
                    plotScaled(img, tileX + col + 1, tileY + row, rightIdx, scale);
                }
            }
        }
        return img;
    }

    /**
     * Builds a PNG-indexed-color (color type 3) image whose palette table is
     * exactly the given 16 Genesis colors -- so a saved PNG can only ever
     * contain those colors, and editors show/restrict to the real palette.
     */
    static Bitmap createIndexedImage(int width, int height, int[] palette) {
        return Bitmap.indexed(width, height, palette);
    }

    private static void plotScaled(Bitmap img, int x, int y, int paletteIndex, int scale) {
        for (int dy = 0; dy < scale; dy++) {
            for (int dx = 0; dx < scale; dx++) {
                img.setIndex(x * scale + dx, y * scale + dy, paletteIndex);
            }
        }
    }

    public static void writePng(Bitmap img, String path) throws IOException {
        Png.write(img, path);
    }

    public static Bitmap readPng(String path) throws IOException {
        return Png.read(path);
    }

    /**
     * Reverse of renderTileSheet for an EXACT tile count (the caller already
     * knows it, e.g. from the original block's decSize) -- use this whenever
     * that's available, since a rectangular PNG can't distinguish "the last
     * row has trailing blank padding tiles" from "there are genuinely that
     * many tiles" by pixel content alone.
     */
    public static byte[] decodeTileSheet(Bitmap img, int[] palette, int columns, int scale, int tileCount) {
        int cols = Math.max(1, columns);
        byte[] data = new byte[tileCount * TILE_BYTES];
        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = (tile % cols) * TILE_SIZE;
            int tileY = (tile / cols) * TILE_SIZE;
            int base = tile * TILE_BYTES;

            for (int row = 0; row < TILE_SIZE; row++) {
                for (int col = 0; col < TILE_SIZE; col += 2) {
                    int leftIdx = nearestPaletteIndex(img.getRgb((tileX + col) * scale, (tileY + row) * scale), palette);
                    int rightIdx = nearestPaletteIndex(img.getRgb((tileX + col + 1) * scale, (tileY + row) * scale), palette);
                    data[base + row * 4 + col / 2] = (byte) (((leftIdx & 0xF) << 4) | (rightIdx & 0xF));
                }
            }
        }
        return data;
    }

    /**
     * Renders tiles stored as a sequence of fixed-size sprites (spriteTilesW x
     * spriteTilesH tiles each), sprites placed left-to-right/top-to-bottom in a
     * macro grid of spritesPerRow columns. Used for assets that aren't a single
     * flat row-major tile raster (see sprite_graphics.txt).
     *
     * Tile order WITHIN a sprite defaults to the Mega Drive sprite convention --
     * column-major, tile0 = col0/row0, tile1 = col0/row1 -- which is what the
     * hardware sprite renderer expects. Some blocks are DMAd into VRAM as a plain
     * tile run instead and are laid out row-major (tile0 = TL, tile1 = TR); pass
     * rowMajor for those, or their quadrants come out swapped.
     */
    public static Bitmap renderSpriteSheet(byte[] data, int[] palette, int spriteTilesW, int spriteTilesH, int spritesPerRow, int scale, boolean rowMajor) {
        int tilesPerSprite = spriteTilesW * spriteTilesH;
        int spriteCount = Math.max(1, data.length / TILE_BYTES / tilesPerSprite);
        int spritesPerRow2 = Math.max(1, spritesPerRow);
        int macroRows = (spriteCount + spritesPerRow2 - 1) / spritesPerRow2;

        int imgW = spritesPerRow2 * spriteTilesW * TILE_SIZE * scale;
        int imgH = macroRows * spriteTilesH * TILE_SIZE * scale;
        Bitmap img = createIndexedImage(imgW, imgH, palette);

        for (int s = 0; s < spriteCount; s++) {
            int blockX = (s % spritesPerRow2) * spriteTilesW * TILE_SIZE;
            int blockY = (s / spritesPerRow2) * spriteTilesH * TILE_SIZE;
            int spriteBase = s * tilesPerSprite * TILE_BYTES;
            for (int t = 0; t < tilesPerSprite; t++) {
                int col = rowMajor ? t % spriteTilesW : t / spriteTilesH;
                int row = rowMajor ? t / spriteTilesW : t % spriteTilesH;
                int tileX = blockX + col * TILE_SIZE;
                int tileY = blockY + row * TILE_SIZE;
                int base = spriteBase + t * TILE_BYTES;
                for (int ry = 0; ry < TILE_SIZE; ry++) {
                    for (int rx = 0; rx < TILE_SIZE; rx += 2) {
                        int byteIndex = base + ry * 4 + rx / 2;
                        int b = byteIndex < data.length ? (data[byteIndex] & 0xFF) : 0;
                        int leftIdx = (b >> 4) & 0xF;
                        int rightIdx = b & 0xF;
                        plotScaled(img, tileX + rx, tileY + ry, leftIdx, scale);
                        plotScaled(img, tileX + rx + 1, tileY + ry, rightIdx, scale);
                    }
                }
            }
        }
        return img;
    }

    /**
     * Reverse of renderSpriteSheet for an EXACT tile count.
     */
    public static byte[] decodeSpriteSheet(Bitmap img, int[] palette, int spriteTilesW, int spriteTilesH, int spritesPerRow, int scale, int tileCount, boolean rowMajor) {
        int tilesPerSprite = spriteTilesW * spriteTilesH;
        int spriteCount = tileCount / tilesPerSprite;
        int spritesPerRow2 = Math.max(1, spritesPerRow);
        byte[] data = new byte[tileCount * TILE_BYTES];

        for (int s = 0; s < spriteCount; s++) {
            int blockX = (s % spritesPerRow2) * spriteTilesW * TILE_SIZE;
            int blockY = (s / spritesPerRow2) * spriteTilesH * TILE_SIZE;
            int spriteBase = s * tilesPerSprite * TILE_BYTES;
            for (int t = 0; t < tilesPerSprite; t++) {
                int col = rowMajor ? t % spriteTilesW : t / spriteTilesH;
                int row = rowMajor ? t / spriteTilesW : t % spriteTilesH;
                int tileX = blockX + col * TILE_SIZE;
                int tileY = blockY + row * TILE_SIZE;
                int base = spriteBase + t * TILE_BYTES;
                for (int ry = 0; ry < TILE_SIZE; ry++) {
                    for (int rx = 0; rx < TILE_SIZE; rx += 2) {
                        int leftIdx = nearestPaletteIndex(img.getRgb((tileX + rx) * scale, (tileY + ry) * scale), palette);
                        int rightIdx = nearestPaletteIndex(img.getRgb((tileX + rx + 1) * scale, (tileY + ry) * scale), palette);
                        data[base + ry * 4 + rx / 2] = (byte) (((leftIdx & 0xF) << 4) | (rightIdx & 0xF));
                    }
                }
            }
        }
        return data;
    }

    private static int nearestPaletteIndex(int argb, int[] palette) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int pr = (palette[i] >> 16) & 0xFF, pg = (palette[i] >> 8) & 0xFF, pb = palette[i] & 0xFF;
            int dr = r - pr, dg = g - pg, db = b - pb;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return best;
    }
}
