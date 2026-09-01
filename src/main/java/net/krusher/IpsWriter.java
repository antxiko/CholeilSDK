package net.krusher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Writes the translation as an IPS patch: what actually gets distributed,
 * since the built ROM is >99% copyrighted game data and the patch is only the
 * bytes this project put there.
 *
 * Format (zerosoft.zophar.net/ips.php), all values big-endian:
 *
 *   "PATCH"                     5-byte magic, not null-terminated
 *   record...                   offset (3 bytes), size (2 bytes), size bytes of data
 *                               size == 0 marks an RLE record instead:
 *                               offset (3), 0x0000, run length (2), value (1)
 *   "EOF"                       3-byte end marker
 *
 * The format's limits -- a 24-bit offset (16 MiB) and a 16-bit record size
 * (64 KiB) -- are checked rather than assumed; a 2 MiB Mega Drive ROM is well
 * inside both, but a run of changed bytes longer than 0xFFFF has to be split
 * across records, so that is handled too.
 *
 * The one trap the spec does not mention: a record whose offset happens to be
 * 0x454F46 encodes as the bytes "EOF", and every applier stops there. Such a
 * record is started one byte earlier instead (re-writing one unchanged byte
 * with its own value, which is harmless).
 *
 * A patch with no records at all is header + terminator, which the format
 * allows and this writer emits -- but some appliers only look for the EOF
 * marker after reading a record, and reject it as truncated. It also means the
 * build changed nothing, which is a problem in its own right, so run() says so
 * loudly rather than quietly shipping a patch that may not load.
 */
public final class IpsWriter {

    private IpsWriter() {}

    static final byte[] MAGIC = {'P', 'A', 'T', 'C', 'H'};
    static final byte[] EOF_MARKER = {'E', 'O', 'F'};

    /** The offset that would encode as the EOF marker. */
    static final int EOF_OFFSET = 0x454F46;

    static final int MAX_OFFSET = 0xFFFFFF;
    static final int MAX_RECORD = 0xFFFF;

    /**
     * Unchanged bytes worth swallowing to keep one record going. A record
     * costs 5 bytes of header, so bridging a gap shorter than that is always
     * smaller than starting again.
     */
    static final int MAX_GAP = 5;

    /**
     * A run this long is cheaper as RLE (8 bytes) than as literal data
     * (5 + length).
     */
    static final int MIN_RLE_RUN = 9;

    /** usage: IpsWriter [baseRomPath] [patchedRomPath] [outPath] */
    public static void main(String[] args) throws IOException {
        String basePath = args.length > 0 ? args[0] : DefaultPaths.ROM;
        String patchedPath = args.length > 1 ? args[1] : DefaultPaths.OUT_ROM;
        String outPath = args.length > 2 ? args[2] : DefaultPaths.PATCH;
        run(basePath, patchedPath, outPath);
    }

    public static void run(String basePath, String patchedPath, String outPath) throws IOException {
        byte[] base = Files.readAllBytes(Paths.get(basePath));
        byte[] patched = Files.readAllBytes(Paths.get(patchedPath));
        byte[] ips = build(base, patched);
        Files.write(Paths.get(outPath), ips);
        System.out.println("Wrote " + outPath + " (" + ips.length + " bytes, "
                + changedBytes(base, patched) + " changed of " + patched.length + ").");
        if (isEmpty(ips)) {
            System.out.println("WARNING: the patch has no records -- the built ROM is identical to the base ROM. "
                    + "Some appliers reject an empty patch as truncated.");
        }
    }

    /** The whole patch file, header and EOF marker included. */
    static byte[] build(byte[] base, byte[] patched) {
        if (patched.length < base.length) {
            throw new IllegalStateException("the patched ROM is smaller than the base ROM ("
                    + patched.length + " < " + base.length + "); IPS can add bytes but not remove them");
        }
        if (patched.length > MAX_OFFSET + 1) {
            throw new IllegalStateException("the patched ROM is " + patched.length
                    + " bytes; IPS offsets are 24-bit, so it cannot address past 0x" + Integer.toHexString(MAX_OFFSET));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);

        int at = 0;
        while (at < patched.length) {
            if (!differs(base, patched, at)) {
                at++;
                continue;
            }
            // Extend through the changed bytes, swallowing gaps of unchanged
            // ones too short to be worth a record of their own.
            int end = at;
            int gap = 0;
            for (int i = at; i < patched.length; i++) {
                if (differs(base, patched, i)) {
                    end = i + 1;
                    gap = 0;
                } else if (++gap > MAX_GAP) {
                    break;
                }
            }
            at = writeBlock(out, patched, at, end);
        }

        out.write(EOF_MARKER, 0, EOF_MARKER.length);
        return out.toByteArray();
    }

    /**
     * Emits [start, end) as one or more records, and returns where it got to.
     * Long runs of one byte go out as RLE; everything else as literal data,
     * split to fit the 16-bit size field.
     */
    private static int writeBlock(ByteArrayOutputStream out, byte[] patched, int start, int end) {
        int at = start;
        while (at < end) {
            int offset = at;
            // A record must never start at the offset that encodes as "EOF".
            // Backing up one byte re-writes an unchanged byte with its own
            // value, which no applier can tell from not having written it.
            if (offset == EOF_OFFSET) offset--;

            int run = runLengthAt(patched, offset, end);
            if (run >= MIN_RLE_RUN) {
                writeRle(out, offset, run, patched[offset]);
                at = offset + run;
                continue;
            }

            // Literal data, up to the next long run (which is cheaper as RLE)
            // and never longer than the size field allows. It has to reach at
            // least one byte past `at`, or a record backed off the EOF offset
            // would leave the walk where it started.
            int limit = Math.min(offset + MAX_RECORD, end);
            int stop = Math.min(Math.max(offset, at) + 1, limit);
            while (stop < limit && runLengthAt(patched, stop, end) < MIN_RLE_RUN) stop++;
            writeData(out, patched, offset, stop - offset);
            at = stop;
        }
        return at;
    }

    /** How many times the byte at `offset` repeats, without running past `end`. */
    private static int runLengthAt(byte[] data, int offset, int end) {
        int run = 1;
        while (offset + run < end && data[offset + run] == data[offset] && run < MAX_RECORD) run++;
        return run;
    }

    private static void writeData(ByteArrayOutputStream out, byte[] data, int offset, int length) {
        writeOffset(out, offset);
        writeU16(out, length);
        out.write(data, offset, length);
    }

    private static void writeRle(ByteArrayOutputStream out, int offset, int length, byte value) {
        writeOffset(out, offset);
        writeU16(out, 0); // marks the record as RLE
        writeU16(out, length);
        out.write(value);
    }

    private static void writeOffset(ByteArrayOutputStream out, int offset) {
        if (offset == EOF_OFFSET) {
            throw new IllegalStateException("record offset 0x454f46 encodes as the EOF marker");
        }
        out.write((offset >> 16) & 0xFF);
        out.write((offset >> 8) & 0xFF);
        out.write(offset & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** A byte past the end of the base ROM counts as changed: the patch appends it. */
    private static boolean differs(byte[] base, byte[] patched, int at) {
        return at >= base.length || base[at] != patched[at];
    }

    /** A patch that changes nothing: header and terminator, no records. */
    static boolean isEmpty(byte[] ips) {
        return ips.length == MAGIC.length + EOF_MARKER.length;
    }

    static int changedBytes(byte[] base, byte[] patched) {
        int changed = 0;
        for (int i = 0; i < patched.length; i++) {
            if (differs(base, patched, i)) changed++;
        }
        return changed;
    }
}
