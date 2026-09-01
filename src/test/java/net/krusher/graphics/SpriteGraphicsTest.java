package net.krusher.graphics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprite-mosaic blocks, and the tile order inside one.
 *
 * Column-major is the Mega Drive sprite convention and the default. The
 * ground-coin blocks are the counter-example: they are DMAd into VRAM as a
 * plain tile run and read TL, TR, BL, BR, so they need "rowmajor" in the
 * registry. Either way the round trip is byte-exact -- extract and insert
 * cancel out -- so the only thing the flag changes, and the only thing worth
 * testing, is whether the PNG in between is the drawing or its quadrants
 * shuffled.
 */
@DisplayName("sprite-mosaic graphics")
final class SpriteGraphicsTest {

    /** The eight blocks the coin spin cycles through: 0xd0f60 + n * 0x400. */
    static final int COIN_BASE = 0xD0F60;
    static final int COIN_STRIDE = 0x400;
    static final int COIN_COUNT = 8;
    static final int COIN_LENGTH = 256;

    private static byte[] rom;

    @BeforeAll
    static void loadRom() throws Exception {
        String path = System.getProperty("smoke.rom", net.krusher.DefaultPaths.ROM);
        Assumptions.assumeTrue(Files.exists(Paths.get(path)),
                "base ROM '" + path + "' not present -- it is gitignored on purpose");
        rom = Files.readAllBytes(Paths.get(path));
    }

    @Test
    @DisplayName("a registry entry without a tile order means column-major")
    void aRegistryEntryWithoutATileOrderMeansColumnMajor() throws Exception {
        List<SpriteGraphicsExtractor.Block> blocks = parse("0xa8d36,7168,7,4,4");
        assertEquals(1, blocks.size());
        assertFalse(blocks.get(0).rowMajor, "the field is optional and defaults to the sprite convention");
    }

    @Test
    @DisplayName("the tile order field is read")
    void theTileOrderFieldIsRead() throws Exception {
        assertTrue(parse("0xd0f60,256,2,2,2,rowmajor").get(0).rowMajor);
        assertFalse(parse("0xd0f60,256,2,2,2,colmajor").get(0).rowMajor);
        assertTrue(parse("0xd0f60,256,2,2,2, RowMajor ").get(0).rowMajor, "spacing and case do not matter");
    }

    @Test
    @DisplayName("an unknown tile order is rejected")
    void anUnknownTileOrderIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parse("0xd0f60,256,2,2,2,diagonal"));
        assertTrue(e.getMessage().contains("rowmajor"), "the message says the options: " + e.getMessage());
    }

    @Test
    @DisplayName("both orders round-trip byte-exact")
    void bothOrdersRoundTripByteExact() {
        byte[] data = new byte[COIN_LENGTH];
        new Random(3).nextBytes(data);
        int[] palette = SpriteGraphicsExtractor.guessedPaletteArgb();
        int tiles = data.length / TileRenderer.TILE_BYTES;

        for (boolean rowMajor : new boolean[]{false, true}) {
            Bitmap sheet = TileRenderer.renderSpriteSheet(data, palette, 2, 2, 2, 1, rowMajor);
            byte[] back = TileRenderer.decodeSpriteSheet(sheet, palette, 2, 2, 2, 1, tiles, rowMajor);
            assertArrayEquals(data, back, "rowMajor=" + rowMajor);
        }
    }

    @Test
    @DisplayName("the two orders swap a 2x2 sprite's off-diagonal quadrants")
    void theTwoOrdersSwapTheOffDiagonalQuadrants() {
        // The whole point of the flag: same bytes, different picture. For a
        // 2x2 sprite, column-major and row-major differ by exactly TR <-> BL.
        byte[] data = new byte[COIN_LENGTH];
        new Random(5).nextBytes(data);
        int[] palette = SpriteGraphicsExtractor.guessedPaletteArgb();
        Bitmap col = TileRenderer.renderSpriteSheet(data, palette, 2, 2, 2, 1, false);
        Bitmap row = TileRenderer.renderSpriteSheet(data, palette, 2, 2, 2, 1, true);

        boolean anyDifference = false;
        for (int y = 0; y < col.getHeight(); y++) {
            for (int x = 0; x < col.getWidth(); x++) {
                int quadrantX = (x % 16) / 8;
                int quadrantY = y / 8;
                int mirroredX = quadrantX == quadrantY ? x : x + (quadrantX == 1 ? -8 : 8);
                int mirroredY = quadrantX == quadrantY ? y : (quadrantY == 1 ? y - 8 : y + 8);
                assertEquals(col.getRgb(x, y), row.getRgb(mirroredX, mirroredY),
                        "pixel " + x + "," + y);
                if (col.getRgb(x, y) != row.getRgb(x, y)) anyDifference = true;
            }
        }
        assertTrue(anyDifference, "the flag has to actually change the picture");
    }

    @Test
    @DisplayName("the ROM still holds two coin designs in four copies each")
    void theRomStillHoldsTwoCoinDesignsInFourCopiesEach() {
        // Guards the block map: if a future edit touches only one copy of a
        // pose, the coin alternates between the new and old art as it spins.
        byte[] edge = block(0);
        byte[] face = block(2);
        assertFalse(Arrays.equals(edge, face), "the two poses are different drawings");
        for (int n = 0; n < COIN_COUNT; n++) {
            boolean isEdge = n == 0 || n == 1 || n == 4 || n == 5;
            assertArrayEquals(isEdge ? edge : face, block(n),
                    String.format("block %d (0x%x) should be the %s pose",
                            n, COIN_BASE + n * COIN_STRIDE, isEdge ? "edge-on" : "face"));
        }
    }

    @Test
    @DisplayName("a coin block survives extraction and reinsertion")
    void aCoinBlockSurvivesExtractionAndReinsertion(@TempDir Path dir) throws Exception {
        int[] palette = SpriteGraphicsExtractor.guessedPaletteArgb();
        byte[] original = block(0);

        Path png = dir.resolve("coin.png");
        TileRenderer.writePng(TileRenderer.renderSpriteSheet(original, palette, 2, 2, 2, 1, true), png.toString());
        Bitmap back = TileRenderer.readPng(png.toString());
        assertEquals(32, back.getWidth(), "two 16x16 poses side by side");
        assertEquals(16, back.getHeight());

        byte[] reinserted = TileRenderer.decodeSpriteSheet(back, palette, 2, 2, 2, 1,
                original.length / TileRenderer.TILE_BYTES, true);
        assertArrayEquals(original, reinserted);
    }

    @Test
    @DisplayName("every coin block is registered as row-major")
    void everyCoinBlockIsRegisteredAsRowMajor() throws Exception {
        Path registry = Paths.get(net.krusher.DefaultPaths.SPRITE_GRAPHICS);
        Assumptions.assumeTrue(Files.exists(registry), "no sprite registry in the working directory");
        List<SpriteGraphicsExtractor.Block> blocks = SpriteGraphicsExtractor.loadBlocks(registry.toString());

        for (int n = 0; n < COIN_COUNT; n++) {
            int addr = COIN_BASE + n * COIN_STRIDE;
            SpriteGraphicsExtractor.Block blk = blocks.stream().filter(b -> b.addr == addr).findFirst()
                    .orElseThrow(() -> new AssertionError(String.format("0x%x is not registered", addr)));
            assertTrue(blk.rowMajor, String.format("0x%x must be rowmajor or its quadrants come out swapped", addr));
            assertEquals(COIN_LENGTH, blk.length, String.format("0x%x length", addr));
        }
    }

    @Test
    @DisplayName("every place the coin art lives is registered")
    void everyPlaceTheCoinArtLivesIsRegistered() throws Exception {
        // The bug this guards against: the coin was redrawn in the eight
        // blocks of the first family and still looked wrong in other zones,
        // because the same art lives in three more places. The face pose is
        // the one to search on -- the edge pose is a thin sliver that matches
        // half the noise in the ROM.
        Path registry = Paths.get(net.krusher.DefaultPaths.SPRITE_GRAPHICS);
        Assumptions.assumeTrue(Files.exists(registry), "no sprite registry in the working directory");
        List<SpriteGraphicsExtractor.Block> blocks = SpriteGraphicsExtractor.loadBlocks(registry.toString());

        for (int at : facePoseAddresses()) {
            assertTrue(blocks.stream().anyMatch(b -> at >= b.addr && at < b.addr + b.length),
                    String.format("the coin face at 0x%x is in no registered block -- redrawing the coin "
                            + "would leave this copy showing the old art", at));
        }
    }

    /**
     * Every 128-byte window holding the coin's face pose, in either tile
     * order, allowing the few nibbles that differ between zone variants (one
     * family bakes a drop shadow into otherwise transparent pixels).
     */
    private static List<Integer> facePoseAddresses() {
        byte[] face = Arrays.copyOfRange(rom, 0xD1760, 0xD1760 + 128);
        int[] rowMajor = nibbles(face);
        int[] colMajor = nibbles(reorder(face));
        List<Integer> found = new java.util.ArrayList<Integer>();
        for (int at = 0; at + 128 <= rom.length; at += 32) {
            int[] here = nibbles(Arrays.copyOfRange(rom, at, at + 128));
            if (differences(here, rowMajor) <= 60 || differences(here, colMajor) <= 60) found.add(at);
        }
        assertEquals(7, found.size(), "the ROM holds seven copies of the coin face; found " + found);
        return found;
    }

    private static int differences(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }

    private static int[] nibbles(byte[] data) {
        int[] out = new int[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = (data[i] >> 4) & 0xF;
            out[i * 2 + 1] = data[i] & 0xF;
        }
        return out;
    }

    /** TL TR BL BR -> TL BL TR BR. */
    private static byte[] reorder(byte[] pose) {
        byte[] out = new byte[pose.length];
        int[] order = {0, 2, 1, 3};
        for (int t = 0; t < 4; t++) System.arraycopy(pose, order[t] * 32, out, t * 32, 32);
        return out;
    }

    private static byte[] block(int n) {
        int at = COIN_BASE + n * COIN_STRIDE;
        return Arrays.copyOfRange(rom, at, at + COIN_LENGTH);
    }

    private static List<SpriteGraphicsExtractor.Block> parse(String line) throws Exception {
        Path tmp = Files.createTempFile("sprite-registry", ".txt");
        try {
            Files.write(tmp, ("; a comment\n" + line + "\n").getBytes(StandardCharsets.UTF_8));
            return SpriteGraphicsExtractor.loadBlocks(tmp.toString());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
