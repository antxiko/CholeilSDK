package net.krusher.graphics;

/**
 * A plain pixel buffer, replacing java.awt's BufferedImage.
 *
 * Not a general image class: it does exactly what the tile renderer and the
 * PNG codec need, and nothing else. Keeping java.desktop out of the toolchain
 * is what lets the native executable be a single file -- AWT there needs JNI
 * metadata and drags awt.dll, javajpeg.dll and lcms.dll along with it.
 *
 * Every image this project renders is indexed: 16 Genesis colours, one index
 * per pixel. An image read back from a PNG may be anything an editor decided
 * to save, so it carries plain ARGB as well and the decoders read it through
 * getRgb, which both shapes answer.
 */
public final class Bitmap {

    private final int width;
    private final int height;

    /** Every pixel as ARGB, which is all the tile decoders ever ask for. */
    private final int[] argb;

    /** Set only for an indexed image, which is what the PNG encoder wants. */
    private final int[] palette;
    private final byte[] indices;

    private Bitmap(int width, int height, int[] argb, int[] palette, byte[] indices) {
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.palette = palette;
        this.indices = indices;
    }

    /** A blank image whose pixels are indices into {@code palette}. */
    public static Bitmap indexed(int width, int height, int[] palette) {
        int[] copy = new int[palette.length];
        System.arraycopy(palette, 0, copy, 0, palette.length);
        return new Bitmap(width, height, new int[width * height], copy, new byte[width * height]);
    }

    /** An image of arbitrary colours, as decoded from a PNG. */
    public static Bitmap trueColor(int width, int height, int[] argb) {
        return new Bitmap(width, height, argb, null, null);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getRgb(int x, int y) {
        return argb[y * width + x];
    }

    /** Paints one pixel; only valid on an indexed image. */
    public void setIndex(int x, int y, int index) {
        if (indices == null) throw new IllegalStateException("not an indexed image");
        indices[y * width + x] = (byte) index;
        argb[y * width + x] = palette[index];
    }

    /** The palette, or null when the image did not come with one. */
    int[] palette() {
        return palette;
    }

    /** One palette index per pixel, or null when the image is not indexed. */
    byte[] indices() {
        return indices;
    }
}
