package net.krusher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Sizes the world-map town-name balloons to the translated names and centers
 * the text inside them. Runs after TextInserter (it reads the placed names
 * back from the output ROM).
 *
 * How the map draws a town name (all verified by disassembly + BizHawk trace):
 * the marker table at 0x35c6 (0x16-byte records: cursor tile x/y at +0/+2,
 * town string id at +0xa, balloon TEXT WIDTH IN TILES at +0xc, balloon tile
 * x/y at +0x10/+0x12, records end at a negative word) drives 0x3086, which
 * prints the name with the shared text engine 0x3314 (uploads every glyph to
 * the $a800-$c000 VRAM window) and queues a balloon entry (0xc bytes at
 * $ffd80e: +0 px x, +2 px y, +4 scratch, +6 first-tile attr, +8 width,
 * +a town id). Each frame 0x313e/0x317e rebuild the balloon from 16px sprite
 * pieces, two glyph tiles per piece, so widths are always EVEN and the box
 * shows exactly `width` tiles of whatever sits in VRAM.
 *
 * The stock table bakes in the ORIGINAL names' lengths ("Soleil"=6), so a
 * longer translation gets its tail cut ("Choleil" -> "Cholei"). This step
 * rewrites every enabled marker's width from the actual placed string:
 * width = (len+1) &amp; ~1. Disabled markers (balloon x/y == 0, skipped by
 * the 0x3152 x==0 check) are left alone, and the save-file screen only ever
 * reads +0xa from this table, so it is unaffected.
 *
 * Centering, tile-quantized part: the box can only show tiles BACKED BY REAL
 * UPLOADED GLYPHS -- anything past the string shows stale VRAM. So the
 * margins live in the data: script.txt keeps even-length names padded as
 * " name " (one space each side), and odd-length names bare. For a bare odd
 * name the engine itself backs the rounding tile: a string terminated 0xFF
 * with NO 0xFE newline (a single blank line before the next ==== header)
 * makes the 0x3380 terminator handler upload one blank glyph right after the
 * name, on the same row. Odd names that still end in 0xFE get a warning here.
 *
 * Centering, half-tile part: an odd name in its even box sits 4px off. The
 * odd-name flag rides in bit 0 of the marker table's width field (widths
 * are naturally even; the consumer's asr #1 discards bit 0, and the
 * allocator advance just over-reserves one blank VRAM slot). The consumer
 * block at 0x315a is rewritten inside its own 22 bytes -- its dead
 * move.w $4(a0),d5 load is dropped (+4 is the game's CURRENT-NODE id from
 * $d806, used to keep the right balloon alive when the bird lands, so it
 * must stay untouched) and the freed bytes stash the raw width into spare
 * RAM $ffd97e before the asr -- and the three text-piece `jsr $19528`
 * calls go through a 16-byte thunk in the ROM's tail padding (0x1fffe0,
 * below FreeSpaceScanner's 500-byte minimum, so TextInserter can never
 * claim it) that adds 4px to the sprite X when $ffd97e is odd.
 */
public final class MapBalloonInserter {

    static final int MARKER_TABLE = 0x35C6;

    static final int REC_SIZE = 0x16;
    static final int REC_TOWN_ID = 0x0A;
    static final int REC_WIDTH = 0x0C;
    static final int REC_BALLOON_X = 0x10;
    static final int REC_BALLOON_Y = 0x12;

    /** {address}, {expected original bytes}, {replacement bytes} */
    static final int[][][] CODE_PATCHES = {
        // consumer 0x315a..0x316f, rewritten in its own 22 bytes: the dead
        // move.w $4(a0),d5 load is dropped (+4 is the game's node id -- the
        // balloon that survives landing is chosen by it, so it must NOT be
        // repurposed), and the freed 4 bytes stash d7 (width, whose bit 0
        // carries the odd-name flag from the marker table) into $ffd97e
        // before asr #1 discards it. The closing bsr.w stays byte-identical.
        { {0x315a},
          {0x3a,0x28,0x00,0x04, 0x31,0xe8,0x00,0x06,0xd8,0xd4, 0x3e,0x28,0x00,0x08, 0xe2,0x47, 0x53,0x47, 0x61,0x00,0x00,0x10},
          {0x31,0xe8,0x00,0x06,0xd8,0xd4, 0x3e,0x28,0x00,0x08, 0x31,0xc7,0xd9,0x7e, 0xe2,0x47, 0x53,0x47, 0x61,0x00,0x00,0x10} },
        // the three text-piece sprite calls detour through the thunk
        { {0x31ac}, {0x4e,0xb9,0x00,0x01,0x95,0x28}, {0x4e,0xb9,0x00,0x1f,0xff,0xe0} },
        { {0x31fc}, {0x4e,0xb9,0x00,0x01,0x95,0x28}, {0x4e,0xb9,0x00,0x1f,0xff,0xe0} },
        { {0x3256}, {0x4e,0xb9,0x00,0x01,0x95,0x28}, {0x4e,0xb9,0x00,0x1f,0xff,0xe0} },
        // thunk at 0x1fffe0 (ROM tail padding):
        //   btst #0,($d97f).w   ; odd glyph count?
        //   beq.b .go
        //   addq.w #4,d2        ; nudge sprite X half a tile right
        //   .go: jmp $19528.l
        { {0x1fffe0},
          {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff},
          {0x08,0x38,0x00,0x00,0xd9,0x7f, 0x67,0x02, 0x58,0x42, 0x4e,0xf9,0x00,0x01,0x95,0x28} },
        // balloon renderer redirected to the right-to-left rewrite below: with
        // pieces emitted right to left, later (= front) sprites are always to
        // the LEFT of earlier ones, so the +4px text shift can never be
        // clipped by a neighboring background piece (Mega Drive sprite
        // priority is table order). The 6 overwritten bytes (sub.w/addi.w)
        // are replicated at the routine's entry.
        { {0x317e},
          {0x94,0x78,0xa4,0xf2, 0x06,0x42},
          {0x4e,0xf9,0x00,0x04,0x19,0xb0} },
        // the RTL balloon renderer itself, assembled into the zero-padding
        // run at 0x041969+ (not FF, so FreeSpaceScanner never lists it and
        // no pipeline step can ever claim it; entry point 0x0419b0 leaves a
        // 0x40-byte cushion to the neighboring data). Same contract as
        // 0x317e: d2/d3 = map px, d7 = pieces-1, $d8d4 = first tile attr.
        { {0x0419b0},
          {
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
            0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
          },
          {
            0x94,0x78,0xa4,0xf2,0x06,0x42,0x00,0x80,0x96,0x78,0xa4,0xf6,0x06,0x43,
            0x00,0x80,0x30,0x07,0xe9,0x40,0xd4,0x40,0x48,0xa7,0x30,0x00,0x32,0x07,
            0x60,0x1c,0x30,0x38,0xd8,0xd4,0x02,0x40,0x07,0xff,0x58,0x40,0x0c,0x40,
            0x06,0x00,0x6d,0x04,0x04,0x40,0x00,0xc0,0x00,0x40,0xe0,0x00,0x31,0xc0,
            0xd8,0xd4,0x51,0xc9,0xff,0xe2,0x4b,0xf8,0x32,0x94,0x4c,0x97,0x00,0x0c,
            0x42,0x45,0x4e,0xb9,0x00,0x01,0x95,0x28,0x4c,0x97,0x00,0x0c,0x4b,0xf9,
            0x00,0xff,0xd8,0xd2,0x42,0x45,0x4e,0xb9,0x00,0x1f,0xff,0xe0,0x61,0x6c,
            0x4c,0x97,0x00,0x0c,0x04,0x42,0x00,0x10,0x48,0x97,0x00,0x0c,0x55,0x47,
            0x6b,0x34,0x4b,0xf8,0x32,0x90,0x4c,0x97,0x00,0x0c,0x42,0x45,0x4e,0xb9,
            0x00,0x01,0x95,0x28,0x4c,0x97,0x00,0x0c,0x4b,0xf9,0x00,0xff,0xd8,0xd2,
            0x42,0x45,0x4e,0xb9,0x00,0x1f,0xff,0xe0,0x61,0x38,0x4c,0x97,0x00,0x0c,
            0x04,0x42,0x00,0x10,0x48,0x97,0x00,0x0c,0x51,0xcf,0xff,0xce,0x4b,0xf8,
            0x32,0x8c,0x4c,0x97,0x00,0x0c,0x42,0x45,0x4e,0xb9,0x00,0x01,0x95,0x28,
            0x4c,0x97,0x00,0x0c,0x4b,0xf9,0x00,0xff,0xd8,0xd2,0x7a,0x01,0x4e,0xb9,
            0x00,0x1f,0xff,0xe0,0x4c,0x9f,0x00,0x0c,0x4e,0x75,0x30,0x38,0xd8,0xd4,
            0x02,0x40,0x07,0xff,0x59,0x40,0x0c,0x40,0x05,0x40,0x6c,0x04,0x06,0x40,
            0x00,0xc0,0x00,0x40,0xe0,0x00,0x31,0xc0,0xd8,0xd4,0x4e,0x75
          } },
    };

    /** usage: MapBalloonInserter [romPath] [tblPath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : "Choleil.md";
        String tblPath = args.length > 1 ? args[1] : "soleil.tbl";
        String outPath = args.length > 2 ? args[2] : romPath;
        run(romPath, tblPath, outPath);
    }

    public static void run(String romPath, String tblPath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        TblTable table = TblTable.load(tblPath);

        int scriptBase = TextInserter.SCRIPT_BASE + readS32(rom, TextInserter.SCRIPT_BASE);
        int tableBase = scriptBase + readU16(rom, scriptBase + 6);
        // slot 0 doubles as the table-size hint only while it's a relative
        // offset; if TextInserter had to move the first town name behind an
        // indirect slot, fall back to the marker table's highest town id
        // (the only ids this step -- and the save screen -- ever look up).
        int count;
        int slot0 = readU16(rom, tableBase);
        if (slot0 < 0x8000) {
            count = slot0 / 2;
        } else {
            count = 0;
            for (int off = MARKER_TABLE; readU16(rom, off) < 0x8000; off += REC_SIZE) {
                count = Math.max(count, readU16(rom, off + REC_TOWN_ID) + 1);
            }
        }

        int[] lengths = new int[count];
        String[] names = new String[count];
        boolean[] endsInNewline = new boolean[count];
        for (int k = 0; k < count; k++) {
            // resolveStringAddr: a translated name that outgrew its table's
            // relative reach may live behind an indirect slot.
            int addr = TextInserter.resolveStringAddr(rom, tableBase, k);
            StringBuilder sb = new StringBuilder();
            int len = 0;
            while ((rom[addr + len] & 0xFF) < 0xFE) {
                int b = rom[addr + len] & 0xFF;
                String g = table.hasByte(b) ? table.glyphFor(b) : "?";
                sb.append("<SPACE>".equals(g) ? " " : g);
                len++;
            }
            lengths[k] = len;
            names[k] = sb.toString();
            endsInNewline[k] = (rom[addr + len] & 0xFF) == 0xFE;
        }

        System.out.println("town  name                  chars  box  centering");
        int patched = 0;
        boolean[] listed = new boolean[count];
        for (int off = MARKER_TABLE; readU16(rom, off) < 0x8000; off += REC_SIZE) {
            boolean enabled = readU16(rom, off + REC_BALLOON_X) != 0 || readU16(rom, off + REC_BALLOON_Y) != 0;
            int town = readU16(rom, off + REC_TOWN_ID);
            if (!enabled || town >= count) continue;
            int oldWidth = readU16(rom, off + REC_WIDTH);
            // Even width the sprite builder needs, with the odd-name flag in
            // bit 0 (widths are naturally even, and the consumer's asr #1
            // throws bit 0 away, so only our thunk ever sees it; the
            // allocator advance at 0x311e over-reserves one blank slot for
            // odd names, which shields the courtesy blank glyph).
            int newWidth = ((lengths[town] + 1) & ~1) | (lengths[town] & 1); // bit 0 = odd-name flag for the RTL half-tile shift
            if (!listed[town]) {
                listed[town] = true;
                StringBuilder pad = new StringBuilder(names[town]);
                while (pad.length() < 18) pad.append(' ');
                System.out.println(String.format("%4d  \"%s\"  %3d  %2d->%-2d  %s",
                        town, pad, lengths[town], oldWidth, newWidth,
                        lengths[town] % 2 != 0 ? "half-tile shift" : "padded in data"));
                if (lengths[town] % 2 != 0 && endsInNewline[town]) {
                    System.out.println("      WARNING: odd-length name still ends in a newline (0xFE) -- its rounding"
                            + " tile is not backed by the courtesy blank and may show garbage. Remove the extra"
                            + " blank line after it in script.txt.");
                }
            }
            if (newWidth != oldWidth) {
                rom[off + REC_WIDTH] = (byte) (newWidth >> 8);
                rom[off + REC_WIDTH + 1] = (byte) newWidth;
                patched++;
            }
        }
        System.out.println(patched + " balloon widths patched.");

        applyCodePatches(rom);

        TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }

    static void applyCodePatches(byte[] rom) {
        TextInserter.applyCodePatches(rom, CODE_PATCHES);
        System.out.println("Centering code patch applied (0x315a stash, 3x jsr -> thunk 0x1fffe0, RTL renderer at 0x0419b0).");
    }

    static boolean matches(byte[] rom, int addr, int[] bytes) {
        return TextInserter.patchMatches(rom, addr, bytes);
    }

    static int readU16(byte[] rom, int off) {
        return ((rom[off] & 0xFF) << 8) | (rom[off + 1] & 0xFF);
    }

    static int readS32(byte[] rom, int off) {
        return (readU16(rom, off) << 16) | readU16(rom, off + 2);
    }
}
