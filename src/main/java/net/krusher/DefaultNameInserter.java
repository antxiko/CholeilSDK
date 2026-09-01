package net.krusher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Gives the hero a real name when the player confirms the name-entry screen
 * without typing anything.
 *
 * The game already has an empty-name path -- it just fills the buffer with
 * leftovers from an earlier font. The name lives at $FFFE4A, one byte per
 * glyph, 0xFF-terminated (that is what the text engine's {F1} handler at
 * 0x332f2 reads, and what the save block carries into SRAM). The name-entry
 * screen keeps the write cursor in $FFD990 and the length in $FFD99A, and
 * caps input at MAX_NAME_LENGTH characters (cmpi.w #$a at 0x524e). Its
 * "END" command lands at 0x53c2:
 *
 *   0053ca  tst.w   $d99a.w        ; anything typed?
 *   0053ce  bne.w   $53f2          ; yes -> terminate the name, done
 *   0053d2  lea.l   $fe4a.w, a0    ; no  -> write the built-in default
 *   0053d6  move.w  #$00b3, (a0)+
 *   0053da  move.w  #$00ec, (a0)+
 *   0053de  move.w  #$00c9, (a0)+
 *   0053e2  move.w  #$ffff, (a0)+
 *   0053e6  move.w  #$0000, (a0)+
 *   0053ea  move.w  #$0000, (a0)+
 *   0053ee  bra.w   $53fa          ; joins the normal confirm tail
 *
 * Those twelve bytes are 00 B3 00 EC 00 C9 FF FF 00 00 00 00: 0x00 is
 * <SPACE> and 0xB3/0xEC/0xC9 are past the end of soleil.tbl, so on this ROM
 * they land on blank tiles and the hero ends up nameless.
 *
 * So there is nothing to relocate and no code to add -- only the six
 * immediates change, and only the first four of them carry the name. The
 * instruction stream, its length, and the twelve bytes it touches all stay
 * exactly as they are. Nothing outside the name-entry routine (0x51e2-0x55b6)
 * reads $d990 or $d99a, so leaving the length at zero is as safe as it is
 * today, and re-entering the screen resets both through the initialiser at
 * 0x555c.
 */
public final class DefaultNameInserter {

    /** The name the hero gets when the player confirms an empty entry. */
    static final String DEFAULT_NAME = "Jaimito";

    /** lea.l $fe4a.w, a0 -- the head of the empty-name branch. */
    static final int LEA_ADDR = 0x53D2;
    static final int[] LEA_BYTES = {0x41, 0xF8, 0xFE, 0x4A};

    /** The six `move.w #imm, (a0)+` that follow it, 4 bytes each. */
    static final int FIRST_MOVE_ADDR = 0x53D6;
    static final int MOVE_COUNT = 6;
    static final int[] MOVE_OPCODE = {0x30, 0xFC};

    /** What the branch writes: MOVE_COUNT words, terminator included. */
    static final int BUFFER_SIZE = MOVE_COUNT * 2;

    /** The entry screen's own cap (cmpi.w #$a at 0x524e). */
    static final int MAX_NAME_LENGTH = 10;

    /** usage: DefaultNameInserter [romPath] [tblPath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String tblPath = args.length > 1 ? args[1] : DefaultPaths.TBL;
        String outPath = args.length > 2 ? args[2] : romPath;
        run(romPath, tblPath, outPath);
    }

    public static void run(String romPath, String tblPath, String outPath) throws IOException {
        byte[] rom = Files.readAllBytes(Paths.get(romPath));
        patch(rom, TblTable.load(tblPath));
        TextInserter.fixChecksum(rom);
        Files.write(Paths.get(outPath), rom);
        System.out.println("Wrote " + outPath);
    }

    static void patch(byte[] rom, TblTable table) {
        byte[] payload = encodeName(table, DEFAULT_NAME);

        // Checked by shape rather than by exact bytes, unlike
        // TextInserter.applyCodePatches: the immediates are whatever name was
        // inserted last, so only the instructions around them can be verified
        // -- which also makes re-running with a different name a no-op.
        if (!TextInserter.patchMatches(rom, LEA_ADDR, LEA_BYTES)) {
            throw new IllegalStateException(String.format(
                    "empty-name branch at 0x%x does not start with `lea $fe4a.w, a0` -- wrong ROM?", LEA_ADDR));
        }
        for (int i = 0; i < MOVE_COUNT; i++) {
            int addr = FIRST_MOVE_ADDR + i * 4;
            if (!TextInserter.patchMatches(rom, addr, MOVE_OPCODE)) {
                throw new IllegalStateException(String.format(
                        "empty-name branch: expected `move.w #imm, (a0)+` at 0x%x -- wrong ROM?", addr));
            }
        }

        for (int i = 0; i < MOVE_COUNT; i++) {
            rom[FIRST_MOVE_ADDR + i * 4 + 2] = payload[i * 2];
            rom[FIRST_MOVE_ADDR + i * 4 + 3] = payload[i * 2 + 1];
        }
        System.out.println("Default name set to \"" + DEFAULT_NAME + "\" ("
                + TextInserter.bytesToHex(payload) + " at $fffe4a).");
    }

    /**
     * The name as the {F1} handler wants it: one byte per glyph, a 0xFF
     * terminator, zero-padded out to the twelve bytes the branch writes.
     */
    static byte[] encodeName(TblTable table, String name) {
        byte[] encoded = table.encode(name);
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalStateException("default name \"" + name + "\" is " + name.length()
                    + " characters; the entry screen caps the player at " + MAX_NAME_LENGTH);
        }
        if (encoded.length + 1 > BUFFER_SIZE) {
            throw new IllegalStateException("default name \"" + name + "\" encodes to " + encoded.length
                    + " bytes; the empty-name branch writes only " + BUFFER_SIZE + " (terminator included)");
        }
        byte[] payload = new byte[BUFFER_SIZE];
        System.arraycopy(encoded, 0, payload, 0, encoded.length);
        payload[encoded.length] = (byte) 0xFF;
        return payload;
    }
}
