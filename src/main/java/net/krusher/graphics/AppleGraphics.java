package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Friendly editors for the confirmed 16x16 normal and 24x24 golden apples. */
public final class AppleGraphics {
    private static final class Copy {
        final int blockOffset;
        final int firstTile;
        final int blockTileCount;

        Copy(int blockOffset, int firstTile, int blockTileCount) {
            this.blockOffset = blockOffset;
            this.firstTile = firstTile;
            this.blockTileCount = blockTileCount;
        }

        String gfxPath() {
            return String.format("gfx_out/gfx_%06x.png", blockOffset);
        }
    }

    /** The same red map-apple art is duplicated in these two zone tilesets. */
    private static final Copy[] MAP_COPIES = {
            new Copy(0x135322, 459, 496),
            new Copy(0x14D3AE, 390, 480)
    };

    private static final int TILES_W = 2;
    private static final int TILES_H = 2;
    private static final int TILE_COUNT = TILES_W * TILES_H;
    private static final int WIDTH = TILES_W * 8;
    private static final int HEIGHT = TILES_H * 8;
    private static final int TOWN_PICKUP_BLOCK = 0x0F4600;
    private static final int TOWN_PICKUP_LENGTH = 512;
    private static final int TOWN_PICKUP_SPRITES = 4;
    private static final int TOWN_PALETTE_OFFSET = 0x000548;
    private static final int[] RAW_APPLE_OFFSETS = {0x100, 0x180};
    private static final int GOLDEN_BLOCK = 0x0A5644;
    private static final int GOLDEN_BLOCK_TILE_COUNT = 36;
    private static final int[] GOLDEN_FRAME_TILES = {9, 27};
    private static final int GOLDEN_TILES_W = 3;
    private static final int GOLDEN_TILES_H = 3;
    private static final int GOLDEN_FRAME_TILE_COUNT = GOLDEN_TILES_W * GOLDEN_TILES_H;
    private static final int GOLDEN_WIDTH = GOLDEN_TILES_W * 8 * GOLDEN_FRAME_TILES.length;
    private static final int GOLDEN_HEIGHT = GOLDEN_TILES_H * 8;

    public static final String DEFAULT_RED_EDIT = "apple_gfx_out/manzana_roja_EDITAME.png";
    public static final String DEFAULT_RED_VIEW = "apple_gfx_out/manzana_roja_x8_VISTA.png";
    public static final String DEFAULT_GREEN_EDIT = "apple_gfx_out/manzana_verde_EDITAME.png";
    public static final String DEFAULT_GREEN_VIEW = "apple_gfx_out/manzana_verde_x8_VISTA.png";
    public static final String DEFAULT_GOLDEN_EDIT = "apple_gfx_out/manzana_dorada_EDITAME.png";
    public static final String DEFAULT_GOLDEN_VIEW = "apple_gfx_out/manzana_dorada_x8_VISTA.png";
    public static final String TOWN_PICKUP_SHEET = "sprite_gfx_out/sprite_0f4600.png";

    /* Captured CRAM line used by the map apple in QuickSave3. */
    private static final int[] CAPTURED_PALETTE = {
            0xFF000000, 0xFF442244, 0xFF6666EE, 0xFFCC4444,
            0xFFEEEE66, 0xFFEE8866, 0xFFEECC88, 0xFFEEAA00,
            0xFFAAAAEE, 0xFF88CC00, 0xFF44AA00, 0xFF446600,
            0xFF882222, 0xFF0044CC, 0xFF222222, 0xFFEEEEEE
    };

    private AppleGraphics() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage:");
            System.out.println("  AppleGraphics extract-red [rom] [editPng] [viewPng]");
            System.out.println("  AppleGraphics extract-green [rom] [editPng] [viewPng]");
            System.out.println("  AppleGraphics extract-golden [rom] [editPng] [viewPng]");
            System.out.println("  AppleGraphics sync-red [rom] [editPng]");
            System.out.println("  AppleGraphics sync-green [rom] [editPng]");
            System.out.println("  AppleGraphics sync-golden [rom] [editPng]");
            System.out.println("  AppleGraphics verify [rom] [redPng] [greenPng]");
            System.out.println("  AppleGraphics verify-golden [rom] [editPng]");
            return;
        }

        String mode = args[0];
        String rom = args.length > 1 ? args[1] : "Soleil (Spain).md";
        boolean goldenMode = mode.contains("golden");
        boolean greenMode = mode.contains("green");
        String edit = args.length > 2 ? args[2]
                : goldenMode ? DEFAULT_GOLDEN_EDIT
                : greenMode ? DEFAULT_GREEN_EDIT : DEFAULT_RED_EDIT;
        String view = args.length > 3 ? args[3]
                : goldenMode ? DEFAULT_GOLDEN_VIEW
                : greenMode ? DEFAULT_GREEN_VIEW : DEFAULT_RED_VIEW;
        if (mode.equals("extract-red")) {
            writeFriendly(rom, MAP_COPIES[0], edit, view, "red map apple", false);
        } else if (mode.equals("extract-green")) {
            writeFriendly(rom, MAP_COPIES[0], edit, view, "green pickup apple", true);
        } else if (mode.equals("extract-golden")) {
            extractGolden(rom, edit, view);
        } else if (mode.equals("sync-red")) {
            syncRed(rom, edit);
        } else if (mode.equals("sync-green")) {
            syncGreen(rom, edit);
        } else if (mode.equals("sync-golden")) {
            syncGolden(rom, edit);
        } else if (mode.equals("verify")) {
            String green = args.length > 3 ? args[3] : DEFAULT_GREEN_EDIT;
            verify(rom, edit, green);
        } else if (mode.equals("verify-golden")) {
            verifyGolden(rom, edit);
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    public static void extractRed(String romPath, String editPath, String viewPath) throws IOException {
        writeFriendly(romPath, MAP_COPIES[0], editPath, viewPath, "red map apple", false);
    }

    public static void extractGreen(String romPath, String editPath, String viewPath) throws IOException {
        writeFriendly(romPath, MAP_COPIES[0], editPath, viewPath, "green pickup apple", true);
    }

    /** Extracts the two real 3x3 golden-apple animation frames. */
    public static void extractGolden(String romPath, String editPath, String viewPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        byte[] block = LzToshio.decompress(rom, GOLDEN_BLOCK);
        byte[] golden = readGoldenFrames(block);
        int[] palette = goldenEditPalette(rom);

        Path edit = Paths.get(editPath);
        Path view = Paths.get(viewPath);
        if (edit.getParent() != null) Files.createDirectories(edit.getParent());
        if (view.getParent() != null) Files.createDirectories(view.getParent());
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(golden, palette,
                GOLDEN_TILES_W, GOLDEN_TILES_H, GOLDEN_FRAME_TILES.length, 1, false), edit.toString());
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(golden, palette,
                GOLDEN_TILES_W, GOLDEN_TILES_H, GOLDEN_FRAME_TILES.length, 8, false), view.toString());
        System.out.println("Extracted golden apple: " + edit
                + " (48x24, two column-major 24x24 frames)");
    }

    private static void writeFriendly(String romPath, Copy source, String editPath,
                                      String viewPath, String label, boolean green) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        byte[] apple = readApple(rom, source);
        if (green) apple = recolorGreen(apple);

        Path edit = Paths.get(editPath);
        Path view = Paths.get(viewPath);
        if (edit.getParent() != null) Files.createDirectories(edit.getParent());
        if (view.getParent() != null) Files.createDirectories(view.getParent());

        TileRenderer.writePng(TileRenderer.renderSpriteSheet(apple, editPalette(),
                TILES_W, TILES_H, 1, 1, true), edit.toString());
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(apple, viewPalette(),
                TILES_W, TILES_H, 1, 8, true), view.toString());
        System.out.println("Extracted " + label + ": " + edit + " (16x16, row-major)");
    }

    /** Copies the red edit into both confirmed fixed-map copies. */
    public static void syncRed(String romPath, String editPath) throws IOException {
        sync(romPath, editPath, "map apple", MAP_COPIES);
    }

    /** Copies the green edit into the two raw pickup-object slots. */
    public static void syncGreen(String romPath, String editPath) throws IOException {
        Path edit = Paths.get(editPath);
        if (!Files.exists(edit)) {
            System.out.println("Green-apple edit PNG not found; leaving raw pickup copies untouched.");
            return;
        }
        Bitmap image = TileRenderer.readPng(edit.toString());
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new IllegalStateException(edit + " must stay " + WIDTH + "x" + HEIGHT);
        }
        byte[] green = TileRenderer.decodeSpriteSheet(image, editPalette(),
                TILES_W, TILES_H, 1, 1, TILE_COUNT, true);
        syncRawPickupCopies(Files.readAllBytes(Paths.get(romPath)), green);
    }

    /** Places the independent two-frame golden edit in its compressed tileset. */
    public static void syncGolden(String romPath, String editPath) throws IOException {
        Path edit = Paths.get(editPath);
        if (!Files.exists(edit)) {
            System.out.println("Golden-apple edit PNG not found; leaving its figure untouched.");
            return;
        }

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        Bitmap image = TileRenderer.readPng(edit.toString());
        if (image.getWidth() != GOLDEN_WIDTH || image.getHeight() != GOLDEN_HEIGHT) {
            throw new IllegalStateException(edit + " must stay "
                    + GOLDEN_WIDTH + "x" + GOLDEN_HEIGHT);
        }
        byte[] golden = TileRenderer.decodeSpriteSheet(image, goldenEditPalette(rom),
                GOLDEN_TILES_W, GOLDEN_TILES_H, GOLDEN_FRAME_TILES.length, 1,
                GOLDEN_FRAME_TILE_COUNT * GOLDEN_FRAME_TILES.length, false);

        Path sheetPath = Paths.get(String.format("gfx_out/gfx_%06x.png", GOLDEN_BLOCK));
        byte[] current = LzToshio.decompress(rom, GOLDEN_BLOCK);
        int[] romPalette = TileRenderer.readGenesisPalette(rom, TOWN_PALETTE_OFFSET);
        if (Files.exists(sheetPath)) {
            Bitmap sheet = TileRenderer.readPng(sheetPath.toString());
            if (sheet.getWidth() != 128 || sheet.getHeight() != 24) {
                throw new IllegalStateException(sheetPath + " must stay 128x24");
            }
            current = TileRenderer.decodeTileSheet(sheet, romPalette, 16, 1,
                    GOLDEN_BLOCK_TILE_COUNT);
        }

        byte[] updated = Arrays.copyOf(current, current.length);
        for (int frame = 0; frame < GOLDEN_FRAME_TILES.length; frame++) {
            System.arraycopy(golden, frame * GOLDEN_FRAME_TILE_COUNT * TileRenderer.TILE_BYTES,
                    updated, GOLDEN_FRAME_TILES[frame] * TileRenderer.TILE_BYTES,
                    GOLDEN_FRAME_TILE_COUNT * TileRenderer.TILE_BYTES);
        }
        if (Arrays.equals(updated, current)) {
            System.out.println("Golden-apple edit is unchanged in " + sheetPath);
            return;
        }
        if (sheetPath.getParent() != null) Files.createDirectories(sheetPath.getParent());
        TileRenderer.writePng(TileRenderer.renderTileSheet(updated, romPalette, 16, 1),
                sheetPath.toString());
        System.out.println("Synced independent golden apple into block 0xA5644"
                + " (tiles 9-17 and 27-35)");
    }

    private static void sync(String romPath, String editPath, String label,
                             Copy[] copies) throws IOException {
        Path edit = Paths.get(editPath);
        if (!Files.exists(edit)) {
            System.out.println(label + " edit PNG not found; leaving its tilesets untouched.");
            return;
        }

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        Bitmap image = TileRenderer.readPng(edit.toString());
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new IllegalStateException(edit + " must stay " + WIDTH + "x" + HEIGHT);
        }
        byte[] apple = TileRenderer.decodeSpriteSheet(image, editPalette(),
                TILES_W, TILES_H, 1, 1, TILE_COUNT, true);

        for (Copy copy : copies) {
            Path gfx = Paths.get(copy.gfxPath());
            byte[] current = LzToshio.decompress(rom, copy.blockOffset);
            if (current.length != copy.blockTileCount * 32) {
                throw new IllegalStateException(String.format(
                        "%s block 0x%X has %d bytes, expected %d",
                        label,
                        copy.blockOffset, current.length, copy.blockTileCount * 32));
            }
            if (Files.exists(gfx)) {
                Bitmap sheet = TileRenderer.readPng(gfx.toString());
                int expectedHeight = ((copy.blockTileCount + 15) / 16) * 8;
                if (sheet.getWidth() != 128 || sheet.getHeight() != expectedHeight) {
                    throw new IllegalStateException(gfx + " must stay 128x" + expectedHeight);
                }
                current = TileRenderer.decodeTileSheet(sheet,
                        TileRenderer.defaultGrayscalePalette(), 16, 1, copy.blockTileCount);
            }

            byte[] updated = Arrays.copyOf(current, current.length);
            System.arraycopy(apple, 0, updated, copy.firstTile * 32, apple.length);
            if (Arrays.equals(updated, current)) {
                System.out.println(label + " edit is unchanged in " + gfx);
                continue;
            }
            if (gfx.getParent() != null) Files.createDirectories(gfx.getParent());
            TileRenderer.writePng(TileRenderer.renderTileSheet(updated,
                    TileRenderer.defaultGrayscalePalette(), 16, 1), gfx.toString());
            System.out.println(String.format("Synced %s into %s (block 0x%X tile %d)",
                    label, gfx, copy.blockOffset, copy.firstTile));
        }
    }

    /**
     * Choleil's pickup strip is copied as one 512-byte run to VRAM
     * 0x7F0-0x7FF. Its last two 16x16 sprites are column-major copies of the
     * same red apple used by the compressed map tilesets.
     */
    private static void syncRawPickupCopies(byte[] rom, byte[] rowMajorApple) throws IOException {
        Path sheetPath = Paths.get(TOWN_PICKUP_SHEET);
        byte[] current = Arrays.copyOfRange(rom, TOWN_PICKUP_BLOCK,
                TOWN_PICKUP_BLOCK + TOWN_PICKUP_LENGTH);
        int[] palette = TileRenderer.readGenesisPalette(rom, TOWN_PALETTE_OFFSET);

        if (Files.exists(sheetPath)) {
            Bitmap sheet = TileRenderer.readPng(sheetPath.toString());
            if (sheet.getWidth() != 64 || sheet.getHeight() != 16) {
                throw new IllegalStateException(TOWN_PICKUP_SHEET
                        + " must stay 64x16 (four 16x16 sprites)");
            }
            current = TileRenderer.decodeSpriteSheet(sheet, palette,
                    TILES_W, TILES_H, TOWN_PICKUP_SPRITES, 1,
                    TOWN_PICKUP_LENGTH / TileRenderer.TILE_BYTES, false);
        }

        byte[] columnMajorApple = rowToColumnMajor(rowMajorApple);
        byte[] updated = Arrays.copyOf(current, current.length);
        for (int offset : RAW_APPLE_OFFSETS) {
            System.arraycopy(columnMajorApple, 0, updated, offset, columnMajorApple.length);
        }
        if (Arrays.equals(updated, current)) {
            System.out.println("Raw pickup-apple copies are unchanged in " + TOWN_PICKUP_SHEET);
            return;
        }

        if (sheetPath.getParent() != null) Files.createDirectories(sheetPath.getParent());
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(updated, palette,
                TILES_W, TILES_H, TOWN_PICKUP_SPRITES, 1, false), sheetPath.toString());
        System.out.println("Synced map apple into raw pickup copies at 0xF4700 and 0xF4780");
    }

    public static void verify(String romPath, String redEditPath, String greenEditPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        byte[] expectedRed = decodeNormalEdit(redEditPath);
        for (Copy copy : MAP_COPIES) {
            byte[] actual = readApple(rom, copy);
            if (!Arrays.equals(expectedRed, actual)) {
                throw new IllegalStateException(String.format(
                        "red map apple differs at block 0x%X", copy.blockOffset));
            }
        }
        byte[] expectedGreen = decodeNormalEdit(greenEditPath);
        for (int offset : RAW_APPLE_OFFSETS) {
            byte[] columnMajor = Arrays.copyOfRange(rom,
                    TOWN_PICKUP_BLOCK + offset,
                    TOWN_PICKUP_BLOCK + offset + TILE_COUNT * TileRenderer.TILE_BYTES);
            if (!Arrays.equals(expectedGreen, columnToRowMajor(columnMajor))) {
                throw new IllegalStateException(String.format(
                        "green raw pickup apple differs at 0x%X",
                        TOWN_PICKUP_BLOCK + offset));
            }
        }
        System.out.println("Normal apples: red map copies and green raw copies are byte-identical to their editors");
    }

    private static byte[] decodeNormalEdit(String editPath) throws IOException {
        Bitmap image = TileRenderer.readPng(editPath);
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new IllegalStateException(editPath + " must stay " + WIDTH + "x" + HEIGHT);
        }
        return TileRenderer.decodeSpriteSheet(image, editPalette(),
                TILES_W, TILES_H, 1, 1, TILE_COUNT, true);
    }

    /** Verifies that both friendly golden frames are exactly present in a built ROM. */
    public static void verifyGolden(String romPath, String editPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        Bitmap image = TileRenderer.readPng(editPath);
        if (image.getWidth() != GOLDEN_WIDTH || image.getHeight() != GOLDEN_HEIGHT) {
            throw new IllegalStateException(editPath + " must stay "
                    + GOLDEN_WIDTH + "x" + GOLDEN_HEIGHT);
        }
        byte[] expected = TileRenderer.decodeSpriteSheet(image, goldenEditPalette(rom),
                GOLDEN_TILES_W, GOLDEN_TILES_H, GOLDEN_FRAME_TILES.length, 1,
                GOLDEN_FRAME_TILE_COUNT * GOLDEN_FRAME_TILES.length, false);
        byte[] actual = readGoldenFrames(LzToshio.decompress(rom, GOLDEN_BLOCK));
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("built golden-apple frames differ from " + editPath);
        }
        System.out.println("Golden-apple mapping: both animation frames are byte-identical");
    }

    /** TL,TR,BL,BR -> TL,BL,TR,BR. The permutation is its own inverse. */
    private static byte[] rowToColumnMajor(byte[] source) {
        byte[] reordered = new byte[source.length];
        int[] order = {0, 2, 1, 3};
        for (int tile = 0; tile < order.length; tile++) {
            System.arraycopy(source, order[tile] * TileRenderer.TILE_BYTES,
                    reordered, tile * TileRenderer.TILE_BYTES, TileRenderer.TILE_BYTES);
        }
        return reordered;
    }

    private static byte[] columnToRowMajor(byte[] source) {
        return rowToColumnMajor(source);
    }

    private static byte[] readApple(byte[] rom, Copy copy) {
        byte[] block = LzToshio.decompress(rom, copy.blockOffset);
        return Arrays.copyOfRange(block, copy.firstTile * 32,
                (copy.firstTile + TILE_COUNT) * 32);
    }

    private static byte[] recolorGreen(byte[] source) {
        byte[] green = Arrays.copyOf(source, source.length);
        for (int i = 0; i < green.length; i++) {
            int value = green[i] & 0xFF;
            green[i] = (byte) (remapGreen(value >>> 4) << 4 | remapGreen(value & 15));
        }
        return green;
    }

    private static int remapGreen(int index) {
        switch (index) {
            case 3: return 10; // mid red -> mid green
            case 5: return 9;  // light red -> light green
            case 7: return 4;  // orange highlight -> yellow highlight
            case 12: return 11; // dark red -> dark green
            default: return index;
        }
    }

    private static int[] editPalette() {
        int[] palette = Arrays.copyOf(CAPTURED_PALETTE, CAPTURED_PALETTE.length);
        palette[0] = 0xFFFF00FF;
        return palette;
    }

    private static int[] viewPalette() {
        int[] palette = Arrays.copyOf(CAPTURED_PALETTE, CAPTURED_PALETTE.length);
        palette[0] = 0xFF010101;
        return palette;
    }

    private static int[] goldenEditPalette(byte[] rom) {
        int[] palette = TileRenderer.readGenesisPalette(rom, TOWN_PALETTE_OFFSET);
        palette[0] = 0xFFFF00FF;
        return palette;
    }

    private static byte[] readGoldenFrames(byte[] block) {
        byte[] frames = new byte[GOLDEN_FRAME_TILE_COUNT
                * GOLDEN_FRAME_TILES.length * TileRenderer.TILE_BYTES];
        for (int frame = 0; frame < GOLDEN_FRAME_TILES.length; frame++) {
            System.arraycopy(block, GOLDEN_FRAME_TILES[frame] * TileRenderer.TILE_BYTES,
                    frames, frame * GOLDEN_FRAME_TILE_COUNT * TileRenderer.TILE_BYTES,
                    GOLDEN_FRAME_TILE_COUNT * TileRenderer.TILE_BYTES);
        }
        return frames;
    }
}
