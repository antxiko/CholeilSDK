package net.krusher.graphics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * PNG reading and writing, replacing javax.imageio.
 *
 * ImageIO cannot be used from a native image without AWT, which needs JNI
 * metadata collected by an agent and ships three DLLs beside the executable;
 * java.util.zip, which is all a PNG really needs, works there as-is. Doing it
 * here is what makes the toolchain a single self-contained .exe.
 *
 * Reading accepts what an editor might hand back: bit depths 1/2/4/8/16, all
 * five colour types, interlaced or not, every filter type. Writing always
 * emits 4-bit indexed colour (type 3), since every image this project renders
 * has 16 colours -- which also keeps an edited PNG restricted to the real
 * Genesis palette when it is opened again.
 *
 * Ancillary chunks (gAMA, pHYs, cHRM, text, ...) are skipped on read and not
 * written; nothing in this toolchain looks at them.
 */
public final class Png {

    private Png() {}

    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Bit depth used when writing: 16 colours fit in a nibble. */
    static final int WRITE_BIT_DEPTH = 4;

    /** PNG colour type 3, palette-indexed. */
    static final int COLOR_TYPE_INDEXED = 3;

    public static Bitmap read(String path) throws IOException {
        return decode(Files.readAllBytes(Paths.get(path)), path);
    }

    public static void write(Bitmap image, String path) throws IOException {
        Files.write(Paths.get(path), encode(image));
    }

    // ---------------------------------------------------------------- reading

    static Bitmap decode(byte[] png, String what) throws IOException {
        if (png.length < SIGNATURE.length) throw new IOException(what + ": too short to be a PNG");
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) throw new IOException(what + ": not a PNG (bad signature)");
        }

        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        boolean interlaced = false;
        byte[] plte = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean sawHeader = false;

        int at = SIGNATURE.length;
        while (at + 8 <= png.length) {
            int length = readU32(png, at);
            String type = new String(png, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int data = at + 8;
            if (length < 0 || data + length + 4 > png.length) {
                throw new IOException(what + ": truncated " + type + " chunk");
            }
            switch (type) {
                case "IHDR":
                    width = readU32(png, data);
                    height = readU32(png, data + 4);
                    bitDepth = png[data + 8] & 0xFF;
                    colorType = png[data + 9] & 0xFF;
                    if ((png[data + 10] & 0xFF) != 0) throw new IOException(what + ": unknown compression method");
                    if ((png[data + 11] & 0xFF) != 0) throw new IOException(what + ": unknown filter method");
                    interlaced = (png[data + 12] & 0xFF) != 0;
                    sawHeader = true;
                    break;
                case "PLTE":
                    plte = new byte[length];
                    System.arraycopy(png, data, plte, 0, length);
                    break;
                case "IDAT":
                    idat.write(png, data, length);
                    break;
                default:
                    break; // ancillary, or IEND
            }
            if (type.equals("IEND")) break;
            at = data + length + 4; // skip the chunk's CRC too
        }

        if (!sawHeader) throw new IOException(what + ": no IHDR chunk");
        if (width <= 0 || height <= 0) throw new IOException(what + ": zero-sized image");
        if (colorType == COLOR_TYPE_INDEXED && plte == null) throw new IOException(what + ": indexed PNG with no PLTE");

        byte[] raw = inflate(idat.toByteArray(), what);
        int channels = channelsFor(colorType, what);
        int[] argb = new int[width * height];
        if (interlaced) {
            decodeInterlaced(raw, argb, width, height, bitDepth, channels, colorType, plte, what);
        } else {
            decodePass(raw, 0, argb, 0, 0, 1, 1, width, height, width, bitDepth, channels, colorType, plte, what);
        }
        return Bitmap.trueColor(width, height, argb);
    }

    /** Adam7: seven passes, each a smaller image scattered over the full one. */
    private static void decodeInterlaced(byte[] raw, int[] argb, int width, int height, int bitDepth,
                                         int channels, int colorType, byte[] plte, String what) throws IOException {
        int[] xStart = {0, 4, 0, 2, 0, 1, 0};
        int[] yStart = {0, 0, 4, 0, 2, 0, 1};
        int[] xStep = {8, 8, 4, 4, 2, 2, 1};
        int[] yStep = {8, 8, 8, 4, 4, 2, 2};

        int offset = 0;
        for (int pass = 0; pass < 7; pass++) {
            int passWidth = (width - xStart[pass] + xStep[pass] - 1) / xStep[pass];
            int passHeight = (height - yStart[pass] + yStep[pass] - 1) / yStep[pass];
            if (passWidth <= 0 || passHeight <= 0) continue;
            offset += decodePass(raw, offset, argb, xStart[pass], yStart[pass], xStep[pass], yStep[pass],
                    passWidth, passHeight, width, bitDepth, channels, colorType, plte, what);
        }
    }

    /**
     * Un-filters one image (or one Adam7 pass) and expands it into {@code argb},
     * returning how many bytes of {@code raw} it consumed.
     */
    private static int decodePass(byte[] raw, int offset, int[] argb,
                                  int xStart, int yStart, int xStep, int yStep,
                                  int passWidth, int passHeight, int imageWidth,
                                  int bitDepth, int channels, int colorType, byte[] plte, String what)
            throws IOException {
        int bitsPerPixel = bitDepth * channels;
        int stride = (passWidth * bitsPerPixel + 7) / 8;
        int step = Math.max(1, bitsPerPixel / 8); // filters work on whole bytes
        int needed = (stride + 1) * passHeight;
        if (offset + needed > raw.length) throw new IOException(what + ": image data ends early");

        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        int at = offset;
        for (int row = 0; row < passHeight; row++) {
            int filter = raw[at++] & 0xFF;
            System.arraycopy(raw, at, current, 0, stride);
            at += stride;
            unfilter(filter, current, previous, step, what);

            for (int col = 0; col < passWidth; col++) {
                int x = xStart + col * xStep;
                int y = yStart + row * yStep;
                argb[y * imageWidth + x] = pixelAt(current, col, bitDepth, channels, colorType, plte);
            }
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return needed;
    }

    private static void unfilter(int filter, byte[] line, byte[] prior, int step, String what) throws IOException {
        switch (filter) {
            case 0:
                break;
            case 1:
                for (int i = step; i < line.length; i++) line[i] += line[i - step];
                break;
            case 2:
                for (int i = 0; i < line.length; i++) line[i] += prior[i];
                break;
            case 3:
                for (int i = 0; i < line.length; i++) {
                    int left = i >= step ? (line[i - step] & 0xFF) : 0;
                    line[i] += (byte) ((left + (prior[i] & 0xFF)) / 2);
                }
                break;
            case 4:
                for (int i = 0; i < line.length; i++) {
                    int a = i >= step ? (line[i - step] & 0xFF) : 0;
                    int b = prior[i] & 0xFF;
                    int c = i >= step ? (prior[i - step] & 0xFF) : 0;
                    line[i] += (byte) paeth(a, b, c);
                }
                break;
            default:
                throw new IOException(what + ": unknown row filter " + filter);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    /** One pixel of an un-filtered scanline, as ARGB. */
    private static int pixelAt(byte[] line, int col, int bitDepth, int channels, int colorType, byte[] plte) {
        int base = col * channels;
        switch (colorType) {
            case 0: { // grayscale
                int gray = scaleTo8(sample(line, base, bitDepth), bitDepth);
                return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
            case 2: { // truecolour
                int r = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int g = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                int b = scaleTo8(sample(line, base + 2, bitDepth), bitDepth);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case COLOR_TYPE_INDEXED: {
                int index = sample(line, base, bitDepth);
                int at = index * 3;
                if (at + 2 >= plte.length) return 0xFF000000;
                return 0xFF000000 | ((plte[at] & 0xFF) << 16) | ((plte[at + 1] & 0xFF) << 8) | (plte[at + 2] & 0xFF);
            }
            case 4: { // grayscale + alpha
                int gray = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int alpha = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                return (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            }
            default: { // 6, truecolour + alpha
                int r = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int g = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                int b = scaleTo8(sample(line, base + 2, bitDepth), bitDepth);
                int alpha = scaleTo8(sample(line, base + 3, bitDepth), bitDepth);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Sample number {@code index} of a scanline, whatever the bit depth. */
    private static int sample(byte[] line, int index, int bitDepth) {
        switch (bitDepth) {
            case 8:
                return line[index] & 0xFF;
            case 16:
                return ((line[index * 2] & 0xFF) << 8) | (line[index * 2 + 1] & 0xFF);
            default: {
                int perByte = 8 / bitDepth;
                int b = line[index / perByte] & 0xFF;
                int shift = 8 - bitDepth * (index % perByte + 1);
                return (b >> shift) & ((1 << bitDepth) - 1);
            }
        }
    }

    /** Spreads a sample of any depth over the full 0..255 range. */
    private static int scaleTo8(int value, int bitDepth) {
        switch (bitDepth) {
            case 8:  return value;
            case 16: return value >> 8;
            case 4:  return value * 17;
            case 2:  return value * 85;
            default: return value * 255;
        }
    }

    private static int channelsFor(int colorType, String what) throws IOException {
        switch (colorType) {
            case 0: case COLOR_TYPE_INDEXED: return 1;
            case 4: return 2;
            case 2: return 3;
            case 6: return 4;
            default: throw new IOException(what + ": unknown colour type " + colorType);
        }
    }

    private static byte[] inflate(byte[] data, String what) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 4));
        byte[] buffer = new byte[16384];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException(what + ": corrupt image data -- " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- writing

    static byte[] encode(Bitmap image) throws IOException {
        int[] palette = image.palette();
        byte[] indices = image.indices();
        if (palette == null || indices == null) {
            throw new IllegalStateException("only indexed images are written; this one carries no palette");
        }
        if (palette.length > (1 << WRITE_BIT_DEPTH)) {
            throw new IllegalStateException("palette has " + palette.length + " colours, more than the "
                    + (1 << WRITE_BIT_DEPTH) + " a " + WRITE_BIT_DEPTH + "-bit PNG can index");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeU32(ihdr, width);
        writeU32(ihdr, height);
        ihdr.write(WRITE_BIT_DEPTH);
        ihdr.write(COLOR_TYPE_INDEXED);
        ihdr.write(0); // deflate
        ihdr.write(0); // adaptive filtering
        ihdr.write(0); // no interlace
        writeChunk(out, "IHDR", ihdr.toByteArray());

        ByteArrayOutputStream plte = new ByteArrayOutputStream();
        for (int color : palette) {
            plte.write((color >> 16) & 0xFF);
            plte.write((color >> 8) & 0xFF);
            plte.write(color & 0xFF);
        }
        writeChunk(out, "PLTE", plte.toByteArray());

        writeChunk(out, "IDAT", deflate(scanlines(indices, width, height)));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Rows of packed nibbles, each behind a filter byte (0 = no filtering). */
    private static byte[] scanlines(byte[] indices, int width, int height) {
        int stride = (width * WRITE_BIT_DEPTH + 7) / 8;
        byte[] raw = new byte[(stride + 1) * height];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0;
            for (int x = 0; x < width; x++) {
                int index = indices[y * width + x] & 0xF;
                int shift = (x & 1) == 0 ? 4 : 0;
                raw[at + x / 2] |= (byte) (index << shift);
            }
            at += stride;
        }
        return raw;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buffer = new byte[16384];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeU32(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeU32(out, (int) crc.getValue());
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readU32(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
