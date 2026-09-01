package net.krusher.graphics;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Png replaced ImageIO so the native executable could drop java.desktop, so
 * ImageIO is exactly the right thing to check it against -- it is still on the
 * test classpath even though main no longer touches it.
 *
 * Every case here is "does Png agree with ImageIO", in both directions: files
 * ImageIO wrote must decode the same, and files Png writes must be readable by
 * ImageIO (which stands in for whatever editor opens a tile sheet).
 */
@DisplayName("Png")
final class PngTest {

    @Test
    @DisplayName("an image survives a write/read round trip")
    void roundTripsThroughItsOwnCodec() throws Exception {
        Bitmap original = noise(37, 21);
        Bitmap back = Png.decode(Png.encode(original), "round trip");
        assertPixelsMatch(original, back, "round trip");
    }

    @Test
    @DisplayName("what it writes, ImageIO reads identically")
    void whatItWritesImageIoReads() throws Exception {
        Bitmap original = noise(64, 24);
        BufferedImage viaImageIo = ImageIO.read(new java.io.ByteArrayInputStream(Png.encode(original)));
        assertTrue(viaImageIo != null, "ImageIO could not read the PNG at all");
        assertEquals(original.getWidth(), viaImageIo.getWidth());
        assertEquals(original.getHeight(), viaImageIo.getHeight());
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                assertEquals(original.getRgb(x, y), viaImageIo.getRGB(x, y),
                        "pixel " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("it decodes every colour type and bit depth ImageIO can write")
    void decodesEveryColourTypeImageIoWrites() throws Exception {
        List<BufferedImage> images = new ArrayList<BufferedImage>();
        images.add(fill(new BufferedImage(19, 7, BufferedImage.TYPE_INT_RGB)));      // type 2
        images.add(fill(new BufferedImage(19, 7, BufferedImage.TYPE_INT_ARGB)));     // type 6
        images.add(fill(new BufferedImage(19, 7, BufferedImage.TYPE_BYTE_BINARY)));  // type 0, 1-bit
        images.add(fill(indexedImage(19, 7, 16)));                                   // type 3, 4-bit
        images.add(fill(indexedImage(19, 7, 256)));                                  // type 3, 8-bit

        for (BufferedImage image : images) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            String what = "ImageIO image " + image.getType() + "/" + image.getColorModel().getPixelSize() + "bpp";
            assertMatchesImageIo(out.toByteArray(), what);
        }
    }

    @Test
    @DisplayName("a grayscale PNG decodes to the samples it stores, unlike ImageIO")
    void grayscaleDecodesToTheStoredSamples() throws Exception {
        // The one place Png deliberately disagrees with ImageIO, and it is a
        // fix rather than a difference. ImageIO gives an 8- or 16-bit
        // grayscale PNG a LINEAR gray colour space, so getRGB gamma-converts
        // on the way out: a stored 51 comes back as 0x7c. Feed that to
        // TileRenderer's nearest-palette-index search against the 16-shade
        // ramp (0, 17, 34, ...) and index 3 comes back as 7 -- so a tile sheet
        // re-saved as 8-bit grayscale used to come back corrupted. PNG
        // grayscale samples are display values, so Png reads them as they are
        // and the round trip holds.
        for (int bits : new int[]{BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY}) {
            BufferedImage gray = new BufferedImage(16, 1, bits);
            int max = bits == BufferedImage.TYPE_BYTE_GRAY ? 255 : 65535;
            for (int x = 0; x < 16; x++) {
                gray.getRaster().setSample(x, 0, 0, x * max / 15);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(gray, "png", out);

            Bitmap decoded = Png.decode(out.toByteArray(), "grayscale");
            for (int x = 0; x < 16; x++) {
                int want = x * 17; // the same ramp TileRenderer renders with
                assertEquals(want, decoded.getRgb(x, 0) & 0xFF, "shade " + x);
            }
        }
    }

    @Test
    @DisplayName("a grayscale tile sheet round-trips to the same tile bytes")
    void aGrayscaleTileSheetRoundTrips() throws Exception {
        // The end-to-end consequence of the above: an editor that saves a
        // tile sheet as plain 8-bit grayscale must not change the tiles.
        byte[] tiles = new byte[TileRenderer.TILE_BYTES * 4];
        new Random(11).nextBytes(tiles);
        int[] palette = TileRenderer.defaultGrayscalePalette();

        Bitmap sheet = TileRenderer.renderTileSheet(tiles, palette, 2, 1);
        BufferedImage grayscale = new BufferedImage(sheet.getWidth(), sheet.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < sheet.getHeight(); y++) {
            for (int x = 0; x < sheet.getWidth(); x++) {
                grayscale.getRaster().setSample(x, y, 0, sheet.getRgb(x, y) & 0xFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(grayscale, "png", out);

        byte[] back = TileRenderer.decodeTileSheet(
                Png.decode(out.toByteArray(), "grayscale sheet"), palette, 2, 1, 4);
        for (int i = 0; i < tiles.length; i++) {
            assertEquals(tiles[i], back[i], "tile byte " + i);
        }
    }

    @Test
    @DisplayName("it decodes an interlaced PNG")
    void decodesAnInterlacedPng() throws Exception {
        byte[] interlaced = writeInterlaced(fill(new BufferedImage(23, 17, BufferedImage.TYPE_INT_RGB)));
        Assumptions.assumeTrue(interlaced != null, "this ImageIO cannot write interlaced PNGs");
        assertTrue(isInterlaced(interlaced), "the fixture is not actually interlaced");
        assertMatchesImageIo(interlaced, "interlaced");
    }

    @Test
    @DisplayName("it decodes every PNG this project has committed")
    void decodesEveryCommittedPng() throws Exception {
        List<Path> pngs = new ArrayList<Path>();
        for (String dir : new String[]{"gfx_out", "raw_gfx_out", "sprite_gfx_out"}) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) continue;
            try (java.util.stream.Stream<Path> files = Files.list(root)) {
                files.filter(p -> p.toString().endsWith(".png")).forEach(pngs::add);
            }
        }
        Assumptions.assumeFalse(pngs.isEmpty(), "no extracted PNGs in the working directory");

        // These were all written by ImageIO before Png existed, and they are
        // the real input to graphics reinsertion: 4-bit grayscale for the
        // blocks with no known palette, 8-bit indexed for the rest.
        for (Path png : pngs) {
            assertMatchesImageIo(Files.readAllBytes(png), png.toString());
        }
    }

    @Test
    @DisplayName("a file that is not a PNG is rejected")
    void aFileThatIsNotAPngIsRejected() {
        byte[] notAPng = "this is not a PNG at all, not even close".getBytes();
        IOException e = assertThrows(IOException.class, () -> Png.decode(notAPng, "junk"));
        assertTrue(e.getMessage().contains("signature"), "the message says what went wrong: " + e.getMessage());
    }

    @Test
    @DisplayName("a truncated PNG is rejected")
    void aTruncatedPngIsRejected() throws Exception {
        byte[] full = Png.encode(noise(16, 16));
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 20);
        assertThrows(IOException.class, () -> Png.decode(cut, "truncated"));
    }

    @Test
    @DisplayName("an image with no palette cannot be written")
    void anImageWithNoPaletteCannotBeWritten() {
        Bitmap trueColor = Bitmap.trueColor(2, 2, new int[]{0, 0, 0, 0});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Png.encode(trueColor));
        assertTrue(e.getMessage().contains("palette"), "the message says what went wrong: " + e.getMessage());
    }

    @Test
    @DisplayName("a tile sheet survives the render/save/load/decode cycle")
    void aTileSheetSurvivesTheWholeCycle(@TempDir Path dir) throws Exception {
        // The cycle the pipeline actually performs: render tiles to a PNG on
        // extraction, read it back on insertion, and get the same bytes.
        byte[] tiles = new byte[TileRenderer.TILE_BYTES * 12];
        new Random(7).nextBytes(tiles);
        int[] palette = TileRenderer.defaultGrayscalePalette();

        Path png = dir.resolve("sheet.png");
        TileRenderer.writePng(TileRenderer.renderTileSheet(tiles, palette, 4, 1), png.toString());
        byte[] back = TileRenderer.decodeTileSheet(TileRenderer.readPng(png.toString()), palette, 4, 1, 12);

        assertEquals(tiles.length, back.length);
        for (int i = 0; i < tiles.length; i++) {
            assertEquals(tiles[i], back[i], "tile byte " + i);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Decodes with both codecs and insists every pixel agrees. */
    private static void assertMatchesImageIo(byte[] png, String what) throws IOException {
        BufferedImage expected = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertTrue(expected != null, what + ": ImageIO could not read the fixture");
        Bitmap actual = Png.decode(png, what);

        assertEquals(expected.getWidth(), actual.getWidth(), what + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRgb(x, y),
                        () -> String.format("%s: pixel differs", what));
            }
        }
    }

    private static void assertPixelsMatch(Bitmap expected, Bitmap actual, String what) {
        assertEquals(expected.getWidth(), actual.getWidth(), what + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRgb(x, y), actual.getRgb(x, y), what + ": pixel " + x + "," + y);
            }
        }
    }

    /** An indexed Bitmap of pseudo-random palette indices. */
    private static Bitmap noise(int width, int height) {
        Bitmap image = Bitmap.indexed(width, height, TileRenderer.defaultGrayscalePalette());
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setIndex(x, y, random.nextInt(16));
            }
        }
        return image;
    }

    /** Paints a gradient, so no two rows or columns are alike. */
    private static BufferedImage fill(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xFF000000 | (x * 11 % 256) << 16 | (y * 7 % 256) << 8 | ((x + y) * 5 % 256));
            }
        }
        return image;
    }

    private static BufferedImage indexedImage(int width, int height, int colors) {
        byte[] r = new byte[colors];
        byte[] g = new byte[colors];
        byte[] b = new byte[colors];
        for (int i = 0; i < colors; i++) {
            r[i] = (byte) (i * 255 / (colors - 1));
            g[i] = (byte) (255 - i * 255 / (colors - 1));
            b[i] = (byte) (i * 37 % 256);
        }
        int bits = colors <= 16 ? 4 : 8;
        IndexColorModel model = new IndexColorModel(bits, colors, r, g, b);
        return new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, model);
    }

    /** An Adam7-interlaced PNG, or null if this ImageIO will not write one. */
    private static byte[] writeInterlaced(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (!param.canWriteProgressive()) return null;
        param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out);
        writer.setOutput(stream);
        writer.write(null, new IIOImage(image, null, null), param);
        stream.flush();
        writer.dispose();
        return out.toByteArray();
    }

    /** The interlace flag is the last byte of IHDR. */
    private static boolean isInterlaced(byte[] png) {
        return png[8 + 8 + 12] != 0;
    }
}
