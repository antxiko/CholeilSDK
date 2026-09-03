package net.krusher.graphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Friendly editor for the SAVE and TAKE OFF icons on the pause screen.
 *
 * The three 24x24 hardware sprites live inside the compressed block at
 * 0x0FD000. In VRAM they occupy tiles 0x180-0x19A: SAVE followed by
 * two distinct TAKE OFF states. This adapter extracts only those 27 tiles,
 * arranges the three column-major sprites side by side, and maps an edited
 * PNG back into the ordinary gfx_out/gfx_0fd000.png sheet.
 */
public final class PauseMenuGraphics {
    public static final int BLOCK_OFFSET = 0x0FD000;
    public static final int PALETTE_OFFSET = 0x000548;
    public static final int BLOCK_TILE_COUNT = 189;
    public static final int FIRST_ICON_TILE = 0;
    public static final int ICON_TILES_W = 3;
    public static final int ICON_TILES_H = 3;
    public static final int ICON_COUNT = 3;
    public static final int ICON_TILE_COUNT = ICON_TILES_W * ICON_TILES_H * ICON_COUNT;
    public static final int EDIT_WIDTH = ICON_TILES_W * 8 * ICON_COUNT;
    public static final int EDIT_HEIGHT = ICON_TILES_H * 8;

    public static final String DEFAULT_EDIT = "pause_gfx_out/iconos_pausa_EDITAME.png";
    public static final String DEFAULT_VIEW = "pause_gfx_out/iconos_pausa_x4_VISTA.png";
    public static final String DEFAULT_GFX = "gfx_out/gfx_0fd000.png";

    private PauseMenuGraphics() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage:");
            System.out.println("  PauseMenuGraphics extract [rom] [editPng] [viewPng]");
            System.out.println("  PauseMenuGraphics sync [rom] [editPng] [gfxPng]");
            System.out.println("  PauseMenuGraphics verify [rom]");
            return;
        }
        String mode = args[0];
        String rom = args.length > 1 ? args[1] : "Soleil (Spain).md";
        if (mode.equals("extract")) {
            extract(rom,
                    args.length > 2 ? args[2] : DEFAULT_EDIT,
                    args.length > 3 ? args[3] : DEFAULT_VIEW);
        } else if (mode.equals("sync")) {
            sync(rom,
                    args.length > 2 ? args[2] : DEFAULT_EDIT,
                    args.length > 3 ? args[3] : DEFAULT_GFX);
        } else if (mode.equals("verify")) {
            verify(rom);
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    public static void extract(String romPath, String editPath, String viewPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        byte[] block = readBlock(rom);
        byte[] icons = Arrays.copyOfRange(block, FIRST_ICON_TILE * 32,
                (FIRST_ICON_TILE + ICON_TILE_COUNT) * 32);

        Path edit = Paths.get(editPath);
        Path view = Paths.get(viewPath);
        if (edit.getParent() != null) Files.createDirectories(edit.getParent());
        if (view.getParent() != null) Files.createDirectories(view.getParent());

        TileRenderer.writePng(TileRenderer.renderSpriteSheet(icons, editPalette(rom),
                ICON_TILES_W, ICON_TILES_H, ICON_COUNT, 1, false), edit.toString());
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(icons, viewPalette(rom),
                ICON_TILES_W, ICON_TILES_H, ICON_COUNT, 4, false), view.toString());
        System.out.println("Extracted pause icons: " + edit
                + " (SAVE, TAKE OFF A, TAKE OFF B; 24x24 each)");
    }

    /** Copies the friendly three-icon edit back into the full compressed sheet. */
    public static void sync(String romPath, String editPath, String gfxPath) throws IOException {
        Path edit = Paths.get(editPath);
        if (!Files.exists(edit)) {
            System.out.println("Pause-menu edit PNG not found; leaving block 0x"
                    + Integer.toHexString(BLOCK_OFFSET) + " untouched.");
            return;
        }

        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        Bitmap image = TileRenderer.readPng(edit.toString());
        if (image.getWidth() != EDIT_WIDTH || image.getHeight() != EDIT_HEIGHT) {
            throw new IllegalStateException(edit + " must stay " + EDIT_WIDTH + "x" + EDIT_HEIGHT
                    + " (three 24x24 icons side by side)");
        }
        byte[] icons = TileRenderer.decodeSpriteSheet(image, editPalette(rom),
                ICON_TILES_W, ICON_TILES_H, ICON_COUNT, 1, ICON_TILE_COUNT, false);

        Path gfx = Paths.get(gfxPath);
        int[] palette = fullPalette(rom);
        byte[] current = readBlock(rom);
        if (Files.exists(gfx)) {
            Bitmap currentImage = TileRenderer.readPng(gfx.toString());
            int expectedHeight = ((BLOCK_TILE_COUNT + 15) / 16) * 8;
            if (currentImage.getWidth() != 128 || currentImage.getHeight() != expectedHeight) {
                throw new IllegalStateException(gfx + " must stay 128x" + expectedHeight);
            }
            current = TileRenderer.decodeTileSheet(currentImage, palette, 16, 1, BLOCK_TILE_COUNT);
        }

        byte[] updated = Arrays.copyOf(current, current.length);
        System.arraycopy(icons, 0, updated, FIRST_ICON_TILE * 32, icons.length);
        if (Arrays.equals(updated, current)) {
            System.out.println("Pause-menu edit is unchanged; keeping " + gfx + " byte-for-byte.");
            return;
        }

        if (gfx.getParent() != null) Files.createDirectories(gfx.getParent());
        TileRenderer.writePng(TileRenderer.renderTileSheet(updated, palette, 16, 1), gfx.toString());
        System.out.println("Synced pause-menu icons into " + gfx);
    }

    public static void verify(String romPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        byte[] block = readBlock(rom);
        byte[] icons = Arrays.copyOfRange(block, FIRST_ICON_TILE * 32,
                (FIRST_ICON_TILE + ICON_TILE_COUNT) * 32);
        Bitmap image = TileRenderer.renderSpriteSheet(icons, editPalette(rom),
                ICON_TILES_W, ICON_TILES_H, ICON_COUNT, 1, false);
        byte[] roundTrip = TileRenderer.decodeSpriteSheet(image, editPalette(rom),
                ICON_TILES_W, ICON_TILES_H, ICON_COUNT, 1, ICON_TILE_COUNT, false);
        if (!Arrays.equals(icons, roundTrip)) {
            throw new IllegalStateException("pause-menu icon mapping is not byte-exact");
        }
        System.out.println("Pause-menu icon mapping round-trip: byte-identical ("
                + ICON_TILE_COUNT + " tiles; SAVE + two TAKE OFF states)");
    }

    private static byte[] readBlock(byte[] rom) {
        byte[] block = LzToshio.decompress(rom, BLOCK_OFFSET);
        if (block.length != BLOCK_TILE_COUNT * 32) {
            throw new IllegalStateException(String.format(
                    "pause-menu block 0x%X has %d bytes, expected %d",
                    BLOCK_OFFSET, block.length, BLOCK_TILE_COUNT * 32));
        }
        return block;
    }

    private static int[] fullPalette(byte[] rom) {
        return TileRenderer.readGenesisPalette(rom, PALETTE_OFFSET);
    }

    private static int[] editPalette(byte[] rom) {
        int[] palette = fullPalette(rom);
        palette[0] = 0xFFFF00FF; // hardware-transparent sprite index
        return palette;
    }

    private static int[] viewPalette(byte[] rom) {
        int[] palette = fullPalette(rom);
        palette[0] = 0xFF010101; // visual-only dark background, distinct from black ink
        return palette;
    }
}
