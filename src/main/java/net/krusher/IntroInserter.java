package net.krusher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the Charnego Translations intro in front of the game: on power-up the
 * intro plays, and START (or A) -- or a timeout -- drops into Soleil.
 *
 * The game stays at 0x000000, because its code is full of absolute addresses
 * and cannot be moved. The intro is a standalone Mega Drive ROM that also
 * expects to live at 0x000000, so it is cut into pieces (code, 16 animation
 * frames, the PCM sample, the Z80/XGM driver), the frames are RLE-compressed,
 * and the pieces are scattered through the filler the game itself never
 * reads. The few absolute addresses in the intro's 68000 code are rebased to
 * wherever their piece landed. Then:
 *
 *   - the game's RESET vector (0x000004) points at a stub of ours, which
 *     lives in the intro's own vector table (dead, since the intro no longer
 *     boots from 0),
 *   - the stub unlocks the TMSS, zeroes the frame counter and jumps into the
 *     intro,
 *   - the intro's vblank wait is redirected to ours, which reads pad 1 every
 *     frame, so START skips the intro from the first frame and not just at
 *     the end,
 *   - the intro's frame-blit routine is redirected to our RLE decompressor,
 *     which unpacks straight into the VDP data port with no buffer,
 *   - the intro's final loop runs through our check, which falls out after
 *     ESPERA frames,
 *   - on the way out the PSG and YM2612 are silenced, the Z80 is reset, the
 *     screen is blanked and the pad control registers are cleared so the game
 *     still sees a cold boot, and control jumps to the game's original entry.
 *
 * The exit epilogue is copied to RAM and run from there: enabling the SRAM
 * can mask the high ROM, and on real hardware that would include the very
 * code doing the enabling.
 *
 * This runs LAST in the insertion pipeline, over a ROM the rest of the
 * pipeline has already rewritten, so every piece it places is checked against
 * the untouched ROM first: if a byte in the target region has already been
 * changed by an earlier step, the build stops instead of quietly eating a
 * relocated string. For the same reason the free_space.txt regions -- the
 * pool the text steps draw from -- are taken out of the intro's own pool up
 * front.
 *
 * Ported from insertar_intro.py (ScorpioN-MsX). Only the path the pipeline
 * uses came across: this one intro, this one game, compressed frames placed
 * in the game's filler, boot logo removed. The Python tool's uncompressed
 * mode, its diagnostic-colour build, its second intro profile and its second
 * game are gone; git history has them.
 */
public final class IntroInserter {

    private IntroInserter() {}

    // ---- the intro: "Charnego Translations INTRO FINAL (XGM, con fundido)" --
    // Where each piece sits inside the intro ROM, and the absolute addresses
    // in its 68000 code that have to be rebased when the pieces move.

    private static final int INTRO_SIZE = 622630;
    private static final int ENTRADA = 0x00200;   // the intro's entry point
    private static final int BUCLE   = 0x002EA;   // start of its final loop
    private static final int BRA     = 0x002EE;   // the bra.w that closes it
    private static final int VSYNC   = 0x003FA;   // its vblank wait
    private static final int SUBIR   = 0x00358;   // its frame-blit routine
    private static final int ESPERA  = 60;        // frames to sit on the last
                                                  // screen (it fades out itself)

    private static final int COD_OFF = 0x00200, COD_TAM = 0x003CE;  // code + palettes
    private static final int FR_OFF  = 0x005CE, FR_TAM  = 0x08C00, FR_N = 16;
    private static final int PCM_OFF = 0x8C600, PCM_TAM = 0x09E92;  // PCM sample
    private static final int VAC_OFF = 0x96500, VAC_TAM = 0x00100;  // empty sample
    private static final int DL_OFF  = 0x96600, DL_TAM  = 0x01A26;  // Z80 driver + XGM lib

    /** offset in the intro -&gt; the absolute address stored there. */
    private static final int[][] RELOCS = {
            {0x00226, 0x097DAE},   // jsr  XGM_init
            {0x0023E, 0x097F20},   // jsr  XGM_setPCM
            {0x00232, 0x08C600},   // pointer to the XGM data (music + samples)
            {0x002CA, 0x097F6C},   // jsr  XGM_playPCM
            {0x002DE, 0x097FB8},   // jsr  XGM_vblankProcess
            {0x97DC8, 0x096600},   // lea  XGM Z80 driver
            {0x97DE4, 0x096500},   // default empty sample
            {0x97E86, 0x096500},   // default empty sample
    };

    private static final int BRA_W = 0x6000;

    // ---- the game ----------------------------------------------------------

    private static final byte[] SERIE = "GM MK-01182-00".getBytes(StandardCharsets.US_ASCII);

    // Booting is a state machine: state 0 (the routine at 0x0CC4) shows the
    // logo for two seconds and moves on to state 6. Pointing entry 0 of the
    // jump table straight at state 6 means the logo never appears -- neither
    // on boot nor on the way back from the game -- and its graphics are freed.
    static final int LOGO_SLOT = 0x000460;
    private static final int LOGO_OLD  = 0x00000CC4;
    static final int LOGO_NEW  = 0x00036D98;
    static final int[][] LOGO_FREED = {{0x000E36, 1632}};   // the logo's graphics

    // Long filler runs where, on top of that, the game reads not one word
    // across two long recorded sessions on an instrumented emulator (boot,
    // menus, overworld and dialogue). ~246 KB in total.
    //
    // 0x03CE78 looks like a 12 KB hole but its first 8 bytes ARE read: a table
    // starts exactly there. It is deliberately not in this list.
    static final int[][] HUECOS = {
            {0x0ACF08, 143608}, {0x0E4FE0, 45088}, {0x11BF6A, 16534},
            {0x1BE040,   8128}, {0x1E6785,  6267}, {0x113AC2,  5212},
            {0x1ED0FB,   3845}, {0x04F260,  3488}, {0x0532E8,  3352},
            {0x1F63AA,   3158}, {0x1D7419,  3047}, {0x0FE7A4,  2140},
            {0x043C00,   2048}, {0x15DA3C,  1476}, {0x1EBACC,  1332},
            {0x1F7AFD,   1283}, {0x0F5B00,  1280}, {0x0F2B24,  1244},
    };

    /** Scratch RAM: frame counter at +0, frame index at +4. */
    private static final int CONT_RAM = 0xFF0100;

    /** Where anything that does not fit in the game's filler would start. */
    private static final int INICIO_EXTRA = 0x210000;

    private static final int RELLENO = 0xFF;

    // =======================================================================

    /** usage: IntroInserter [romPath] [introPath] [baseRomPath] [freeSpacePath] [outPath] */
    public static void main(String[] args) throws IOException {
        String romPath   = args.length > 0 ? args[0] : DefaultPaths.OUT_ROM;
        String introPath = args.length > 1 ? args[1] : DefaultPaths.INTRO;
        String basePath  = args.length > 2 ? args[2] : DefaultPaths.ROM;
        String freeSpace = args.length > 3 ? args[3] : DefaultPaths.FREE_SPACE;
        String outPath   = args.length > 4 ? args[4] : DefaultPaths.OUT_ROM;
        run(romPath, introPath, basePath, freeSpace, outPath);
    }

    public static void run(String romPath, String introPath, String basePath,
                           String freeSpacePath, String outPath) throws IOException {
        byte[] juego = Files.readAllBytes(Paths.get(romPath));
        byte[] intro = Files.readAllBytes(Paths.get(introPath));
        byte[] base  = Files.exists(Paths.get(basePath))
                ? Files.readAllBytes(Paths.get(basePath)) : null;

        List<Region> banned = new ArrayList<>();
        if (freeSpacePath != null && Files.exists(Paths.get(freeSpacePath))) {
            for (FreeSpaceScanner.Region r : FreeSpaceScanner.readRegionsFile(freeSpacePath)) {
                banned.add(new Region(r.start, r.length));
            }
        }

        byte[] salida = inject(juego, intro, base, banned);
        Files.write(Paths.get(outPath), salida);
        System.out.println("Written: " + outPath + " (" + salida.length + " bytes)");
    }

    // ---- the injection -----------------------------------------------------

    static byte[] inject(byte[] juego, byte[] intro, byte[] base, List<Region> banned) {
        checkIntro(intro);
        checkGame(juego);

        int juegoSp = readU32(juego, 0x00);
        int juegoPc = readU32(juego, 0x04);
        if (juegoPc >= juego.length) {
            throw new IllegalStateException(String.format(
                    "the game's RESET vector (0x%06X) points outside the ROM -- does it "
                            + "already have an intro?", juegoPc));
        }

        byte[] codigo = Arrays.copyOfRange(intro, COD_OFF, COD_OFF + COD_TAM);
        byte[] drvlib = Arrays.copyOfRange(intro, DL_OFF, DL_OFF + DL_TAM);
        byte[] pcm    = Arrays.copyOfRange(intro, PCM_OFF, PCM_OFF + PCM_TAM);
        byte[] vacio  = Arrays.copyOfRange(intro, VAC_OFF, VAC_OFF + VAC_TAM);

        byte[][] frames = new byte[FR_N][];
        int crudo = 0, comp = 0;
        for (int i = 0; i < FR_N; i++) {
            byte[] raw = Arrays.copyOfRange(intro, FR_OFF + i * FR_TAM, FR_OFF + (i + 1) * FR_TAM);
            frames[i] = rleCompress(raw);
            if (!Arrays.equals(rleExpand(frames[i]), raw)) {   // safety net
                throw new IllegalStateException("the RLE compressor is not reversible on frame " + i);
            }
            crudo += raw.length;
            comp  += frames[i].length;
        }

        // The stub changes size depending on whether the SRAM needs remapping,
        // and that depends on whether the ROM ends up growing: lay the pieces
        // out until the two agree (two passes at most).
        boolean sram = juego[0x1B0] == 'R' && juego[0x1B1] == 'A';
        int sramIni = sram ? readU32(juego, 0x1B4) : 0;

        boolean remapear = sram;
        Colocador col = null;
        Map<String, Integer> sitio = null;
        int tamCerca = 0, tamLejos = 0;
        for (int pass = 0; pass < 3; pass++) {
            col = new Colocador(juego.length, pool(banned), INICIO_EXTRA);

            Stub probe = buildStub(0, 0, 0, 0, juegoSp, juegoPc, CONT_RAM, remapear, new int[FR_N]);
            tamCerca = probe.cerca.length;
            tamLejos = probe.lejos.length;

            // Biggest first: otherwise the small pieces fragment the big holes
            // and the PCM sample or a fat frame stops fitting. The "near" stub
            // has to stay within a bra.w of the code, so they share one block.
            List<Object[]> piezas = new ArrayList<>();
            piezas.add(new Object[]{"codigo", COD_TAM + tamCerca, 2});
            piezas.add(new Object[]{"lejos", tamLejos, 2});
            piezas.add(new Object[]{"pcm", pcm.length, 256});
            piezas.add(new Object[]{"vacio", vacio.length, 256});
            piezas.add(new Object[]{"drvlib", drvlib.length, 2});
            for (int i = 0; i < FR_N; i++) piezas.add(new Object[]{"f" + i, frames[i].length, 2});
            piezas.sort((x, y) -> (Integer) y[1] - (Integer) x[1]);   // stable, like Python's

            sitio = new LinkedHashMap<>();
            for (Object[] p : piezas) {
                sitio.put((String) p[0], col.colocar((Integer) p[1], (Integer) p[2]));
            }

            boolean nuevo = sram && col.fin > sramIni;
            if (nuevo == remapear) break;
            remapear = nuevo;
        }

        int dirCod   = sitio.get("codigo");
        int dirCerca = dirCod + COD_TAM;
        int dirLejos = sitio.get("lejos");
        int dirPcm   = sitio.get("pcm");
        int dirVac   = sitio.get("vacio");
        int dirDl    = sitio.get("drvlib");
        int[] dirsFr = new int[FR_N];
        for (int i = 0; i < FR_N; i++) dirsFr[i] = sitio.get("f" + i);

        // second pass, now with the real addresses
        Stub stub = buildStub(dirCerca, dirLejos, dirCod + (ENTRADA - COD_OFF),
                dirCod + (BUCLE - COD_OFF), juegoSp, juegoPc, CONT_RAM, remapear, dirsFr);
        if (stub.cerca.length != tamCerca || stub.lejos.length != tamLejos) {
            throw new IllegalStateException("internal error: the stub changed size between passes");
        }

        // ---- rebase the intro's absolute addresses -------------------------
        for (int[] r : RELOCS) {
            int off = r[0], val = r[1], nuevo;
            if (val >= DL_OFF && val < DL_OFF + DL_TAM)         nuevo = dirDl  + (val - DL_OFF);
            else if (val >= PCM_OFF && val < PCM_OFF + PCM_TAM) nuevo = dirPcm + (val - PCM_OFF);
            else if (val >= VAC_OFF && val < VAC_OFF + VAC_TAM) nuevo = dirVac + (val - VAC_OFF);
            else throw new IllegalStateException(String.format(
                        "no idea which piece 0x%06X belongs to", val));

            if (off >= COD_OFF && off < COD_OFF + COD_TAM)  writeU32(codigo, off - COD_OFF, nuevo);
            else if (off >= DL_OFF && off < DL_OFF + DL_TAM) writeU32(drvlib, off - DL_OFF, nuevo);
            else throw new IllegalStateException(String.format(
                        "relocation at 0x%05X falls outside the pieces", off));
        }

        // ---- patches inside the intro's own code ---------------------------
        writeU16(codigo, SUBIR - COD_OFF, 0x4EF9);                         // its frame blit ->
        writeU32(codigo, SUBIR - COD_OFF + 2, stub.labels.get("descomp")); // our decompressor
        writeU16(codigo, VSYNC - COD_OFF, 0x4EF9);                         // its vblank wait ->
        writeU32(codigo, VSYNC - COD_OFF + 2, stub.labels.get("vsync"));   // ours, which reads the pad
        int disp = stub.labels.get("comprobar") - (dirCod + (BRA - COD_OFF) + 2);
        writeU16(codigo, BRA - COD_OFF, BRA_W);                            // final loop's bra.w ->
        writeU16(codigo, BRA - COD_OFF + 2, disp);                         // our check

        // ---- assemble the ROM ----------------------------------------------
        if (col.fin > 0x400000) {
            throw new IllegalStateException(String.format(
                    "the resulting ROM (0x%06X) runs off the cartridge map (0x3FFFFF)", col.fin));
        }
        byte[] salida = Arrays.copyOf(juego, Math.max(juego.length, col.fin));
        Arrays.fill(salida, juego.length, salida.length, (byte) RELLENO);

        List<Object[]> writes = new ArrayList<>();
        writes.add(new Object[]{"code", dirCod, codigo});
        writes.add(new Object[]{"near stub", dirCerca, stub.cerca});
        writes.add(new Object[]{"far stub", dirLejos, stub.lejos});
        writes.add(new Object[]{"PCM sample", dirPcm, pcm});
        writes.add(new Object[]{"empty sample", dirVac, vacio});
        writes.add(new Object[]{"Z80 driver", dirDl, drvlib});
        for (int i = 0; i < FR_N; i++) writes.add(new Object[]{"frame " + i, dirsFr[i], frames[i]});

        if (base != null) assertUntouched(writes, juego, base);
        for (Object[] w : writes) {
            byte[] d = (byte[]) w[2];
            System.arraycopy(d, 0, salida, (Integer) w[1], d.length);
        }

        writeU32(salida, 0x04, stub.labels.get("entrada"));   // RESET vector -> our stub
        writeU32(salida, LOGO_SLOT, LOGO_NEW);                // and away with the boot logo

        // The header is left alone on purpose: Soleil checksums itself against
        // the "ROM end" field at 0x1A4, and touching that hangs it on a red
        // screen. The checksum at 0x18E does have to be redone, because we
        // wrote inside the game's own address space.
        writeU16(salida, 0x18E, checksumSega(salida, juego.length));

        int ocupado = COD_TAM + tamCerca + tamLejos + pcm.length + vacio.length + drvlib.length + comp;
        System.out.println("Intro            : Charnego Translations INTRO FINAL (XGM, with fade)");
        System.out.println("Boot logo        : removed (jump-table state 0 -> state 6)");
        System.out.printf ("Frames           : %d KB -> %d KB RLE-compressed by words%n",
                crudo / 1024, comp / 1024);
        System.out.printf ("Inserted         : %d KB in total, %d KB in the game's own filler%n",
                ocupado / 1024, col.enHueco / 1024);
        System.out.printf ("Pieces           : code 0x%06X  stub 0x%06X/0x%06X  PCM 0x%06X  driver 0x%06X%n",
                dirCod, dirCerca, dirLejos, dirPcm, dirDl);
        System.out.printf ("                   frames 0x%06X ... 0x%06X%n",
                Arrays.stream(dirsFr).min().getAsInt(), Arrays.stream(dirsFr).max().getAsInt());
        System.out.printf ("Stub             : 0x%06X (%d bytes) + exit at 0x%06X (%d bytes), %d frames' wait%n",
                stub.labels.get("entrada"), tamCerca, stub.labels.get("salir"), tamLejos, ESPERA);
        System.out.printf ("Original entry   : SP=0x%08X  PC=0x%06X%n", juegoSp, juegoPc);
        if (remapear) {
            System.out.printf("SRAM             : 0x%06X remapped on the way out (A130F1)%n", sramIni);
        }
        return salida;
    }

    private static void checkIntro(byte[] intro) {
        if (intro.length != INTRO_SIZE) {
            throw new IllegalStateException("unknown intro: expected " + INTRO_SIZE
                    + " bytes, got " + intro.length + ". Rebasing an unknown intro blind "
                    + "would produce a broken ROM.");
        }
        for (int[] r : RELOCS) {
            if (readU32(intro, r[0]) != r[1]) {
                throw new IllegalStateException(String.format(
                        "unknown intro: expected 0x%06X at 0x%05X, found 0x%06X",
                        r[1], r[0], readU32(intro, r[0])));
            }
        }
        if (readU16(intro, BRA) != BRA_W || (short) readU16(intro, BRA + 2) != BUCLE - BRA - 2) {
            throw new IllegalStateException("unknown intro: its final loop is not where expected");
        }
    }

    private static void checkGame(byte[] juego) {
        for (int i = 0; i < SERIE.length; i++) {
            if (juego[0x180 + i] != SERIE[i]) {
                throw new IllegalStateException("this is not Soleil/Choleil: the product code "
                        + "at 0x180 does not match");
            }
        }
        if (readU32(juego, LOGO_SLOT) != LOGO_OLD) {
            throw new IllegalStateException(String.format(
                    "the boot jump table at 0x%06X does not hold the expected 0x%08X -- the ROM "
                            + "is not the one this step knows how to patch (an intro already in?)",
                    LOGO_SLOT, LOGO_OLD));
        }
    }

    /**
     * Nothing the intro writes may land on a byte an earlier pipeline step
     * already changed. The intro's filler list was measured against the stock
     * ROM, and it overlaps free_space.txt in five places, so without this a
     * relocated string could be eaten silently.
     */
    private static void assertUntouched(List<Object[]> writes, byte[] juego, byte[] base) {
        for (Object[] w : writes) {
            int at = (Integer) w[1], len = ((byte[]) w[2]).length;
            if (at + len > base.length) continue;      // past the stock ROM: nothing to clash with
            for (int i = at; i < at + len; i++) {
                if (inLogoGraphics(i)) continue;       // dead art: see below
                if (juego[i] != base[i]) {
                    throw new IllegalStateException(String.format(
                            "the intro's %s would be written at 0x%06X..0x%06X, but 0x%06X was "
                                    + "already changed by an earlier step of the pipeline. Take that "
                                    + "region out of IntroInserter.HUECOS, or free space elsewhere.",
                            w[0], at, at + len - 1, i));
                }
            }
        }
    }

    /**
     * The boot logo's graphics are exempt from that check even though the
     * pipeline does rewrite them -- raw_gfx_out/raw_000e56.png and
     * raw_001456.png, the Charnego logo, live exactly here. Removing the logo
     * screen is what frees these bytes in the first place, so once the intro
     * is in, that art is never drawn again and the intro is entitled to it.
     */
    private static boolean inLogoGraphics(int at) {
        for (int[] h : LOGO_FREED) {
            if (at >= h[0] && at < h[0] + h[1]) return true;
        }
        return false;
    }

    /** The game's filler, plus what the logo frees, minus what the text steps use. */
    private static List<Region> pool(List<Region> banned) {
        List<Region> out = new ArrayList<>();
        for (int[] h : HUECOS)     out.add(new Region(h[0], h[1]));
        for (int[] h : LOGO_FREED) out.add(new Region(h[0], h[1]));

        for (Region b : banned) {
            List<Region> next = new ArrayList<>();
            for (Region r : out) {
                int s = Math.max(r.start, b.start), e = Math.min(r.end(), b.end());
                if (s >= e) { next.add(r); continue; }              // no overlap
                if (r.start < s) next.add(new Region(r.start, s - r.start));
                if (e < r.end()) next.add(new Region(e, r.end() - e));
            }
            out = next;
        }
        out.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start)
                : Integer.compare(a.length, b.length));
        return out;
    }

    static final class Region {
        final int start, length;
        Region(int start, int length) { this.start = start; this.length = length; }
        int end() { return start + length; }
    }

    /** Hands out the holes, and puts anything that does not fit past the end. */
    static final class Colocador {
        private final List<int[]> libres = new ArrayList<>();
        private int extra;
        int fin;
        int enHueco;

        Colocador(int tamJuego, List<Region> huecos, int inicioExtra) {
            for (Region r : huecos) libres.add(new int[]{r.start, r.length});
            this.extra = inicioExtra;
            this.fin = tamJuego;
        }

        int colocar(int tam, int alin) {
            for (int[] r : libres) {
                int ini = (r[0] + alin - 1) / alin * alin;
                if (ini + tam <= r[0] + r[1]) {
                    int sobraFin = r[0] + r[1] - (ini + tam);
                    r[0] = ini + tam;
                    r[1] = sobraFin;
                    enHueco += tam;
                    return ini;
                }
            }
            int ini = (extra + alin - 1) / alin * alin;
            extra = ini + tam;
            fin = Math.max(fin, extra);
            return ini;
        }
    }

    // ---- the stub ----------------------------------------------------------

    static final class Stub {
        byte[] cerca, lejos;
        Map<String, Integer> labels;
    }

    /**
     * Two pieces:
     *
     *   "cerca" -- entry, pad reading and the frame counter. It goes in the
     *     intro's vector table, because the intro's final loop can only reach
     *     it with a bra.w (+-32 KB).
     *   "lejos" -- the exit to the game, with its YM table and the epilogue.
     *     No size pressure there, so it goes wherever it fits.
     */
    static Stub buildStub(int dirCerca, int dirLejos, int dirEntrada, int dirBucle,
                          int juegoSp, int juegoPc, int contRam, boolean remapear,
                          int[] tablaFrames) {
        final int VDP_CTRL = 0xC00004, VDP_DATA = 0xC00000;
        final int PSG = 0xC00011;
        final int Z80_BUS = 0xA11100, Z80_RST = 0xA11200;
        final int YM_A0 = 0xA04000, YM_D0 = 0xA04001;
        final int IO_CTRL1 = 0xA10009, IO_DATA1 = 0xA10003;
        final int VER_REG = 0xA10001;
        final int EPI_RAM = 0xFF0200;        // the epilogue is copied here and run from RAM

        // ---- the epilogue, which will run from RAM -------------------------
        // Absolute addressing only, so it works wherever it ends up.
        Asm e = new Asm(0);
        if (remapear) {
            // Past 2 MB the game's SRAM (0x200000) stops being mapped; this
            // register of Sega's official mapper puts it back so saves keep
            // working.
            e.moveBImmAbs(0x01, 0xA130F1);
        }
        e.clrLAbs(0xA10008);                 // clear the pad control registers: the
        e.clrWAbs(0xA1000C);                 // game has to see a cold boot
        e.moveWSr(0x2700);
        e.moveaLImmA7(juegoSp);
        e.jmpAbs(juegoPc);                   // -> the original game
        byte[] epilogo = e.link();

        // ---- the exit to the game (the "lejos" piece) ----------------------
        Asm f = new Asm(dirLejos);
        f.label("salir");
        f.moveWSr(0x2700);
        f.moveWAbsD0(VDP_CTRL);              // read status: clears the VDP's write latch
        f.moveWImmAbs(0x8004, VDP_CTRL);     // VDP reg0: no interrupts
        f.moveWImmAbs(0x8104, VDP_CTRL);     // VDP reg1: screen off
        for (int v : new int[]{0x9F, 0xBF, 0xDF, 0xFF}) f.moveBImmAbs(v, PSG);   // silence the PSG

        // The YM2612 is silenced BEFORE the Z80 reset line is touched: that
        // line also powers down the YM and cuts the 68000 off its bus, after
        // which it can neither be written nor have its busy flag read (on real
        // hardware that bit sticks at 1 and the exit hung there forever).
        f.moveWImmAbs(0x0100, Z80_BUS);      // ask for the Z80 bus
        f.label("esperar_bus");
        f.btstImmAbs(0, Z80_BUS);
        f.bne("esperar_bus");
        int[] ym = {0x2B, 0x00, 0x27, 0x00,               // DAC off, timers off
                    0x28, 0x00, 0x28, 0x01, 0x28, 0x02,   // key-off on the 6 FM channels
                    0x28, 0x04, 0x28, 0x05, 0x28, 0x06};
        f.leaPcA0("ym_tabla");
        f.moveq(ym.length / 2 - 1, 1);
        f.label("ym_bucle");
        f.moveBIncAbs(YM_A0);                // register
        f.retardo(24);                       // fixed wait, without reading the YM
        f.moveBIncAbs(YM_D0);                // value
        f.retardo(24);
        f.dbra(1, "ym_bucle");
        f.moveWImmAbs(0x0000, Z80_RST);      // Z80 (and with it the YM) into reset
        f.retardo(64);
        f.moveWImmAbs(0x0000, Z80_BUS);      // let the bus go

        // The epilogue is copied to RAM and run from there: turning the SRAM on
        // can let the cartridge mask the high ROM (on real hardware the SRAM
        // mirrors up to 0x3FFFFF), and with it this very code.
        f.leaPcA0("epilogo");
        f.leaAbs(EPI_RAM, 1);
        f.moveq(epilogo.length / 2 - 1, 0);
        f.label("copiar");
        f.moveWIncInc();
        f.dbra(0, "copiar");
        f.jmpAbs(EPI_RAM);                   // -> epilogue in RAM -> the game

        // Replaces the routine that blitted a raw frame to VRAM: takes the
        // pointer of the frame that's due from the table, unpacks it and writes
        // it straight into the VDP data port, with no buffer.
        f.label("descomp");
        f.moveLImmAbs(0x40000000, VDP_CTRL); // VRAM write, address 0
        f.leaAbs(VDP_DATA, 1);
        f.moveWAbsDn(contRam + 4, 2);        // frame index
        f.addqWAbs(1, contRam + 4);
        f.lslWImm(2, 2);
        f.leaPcA0("frames_tabla");
        f.moveaLIdxA0(2, 3);                 // movea.l (a0,d2.w),a3
        f.label("d_bucle");
        f.moveWIncDn(0);                     // n = move.w (a3)+,d0
        f.beq("d_fin");
        f.bmi("d_repe");
        f.subqWDn(1, 0);                     // n literals
        f.label("d_lit");
        f.moveWIncIndA1();
        f.dbra(0, "d_lit");
        f.bra("d_bucle");
        f.label("d_repe");
        f.negW(0);
        f.subqWDn(1, 0);
        f.moveWIncDn(1);                     // the word to repeat
        f.label("d_rep");
        f.moveWDnIndA1(1);
        f.dbra(0, "d_rep");
        f.bra("d_bucle");
        f.label("d_fin");
        f.rts();

        f.label("ym_tabla");
        f.db(ym);
        f.label("epilogo");
        f.db(epilogo);
        f.label("frames_tabla");
        for (int dirF : tablaFrames) f.l(dirF);
        byte[] datosLejos = f.link();

        // ---- the "cerca" piece (in the vector table) -----------------------
        Asm a = new Asm(dirCerca);

        a.label("entrada");                  // the RESET vector
        a.moveWSr(0x2700);
        a.moveBAbsD0(VER_REG);               // a console with TMSS?
        a.andiBD0(0x0F);
        a.beq("sin_tmss");
        a.moveLImmAbs(0x53454741, 0xA14000); // 'SEGA'
        a.label("sin_tmss");
        a.moveBImmAbs(0x40, IO_CTRL1);       // pad 1: TH as output
        a.moveBImmAbs(0x40, IO_DATA1);
        a.clrWAbs(contRam);                  // frame counter to 0
        a.clrWAbs(contRam + 4);              // frame index to 0
        a.jmpAbs(dirEntrada);                // -> the original intro

        // Stands in for the intro's vblank wait: hooking in here means the pad
        // is read on EVERY frame, not just in the final loop, so START skips
        // the intro from the very beginning.
        a.label("vsync");
        a.moveBImmAbs(0x00, IO_DATA1);       // TH=0 -> START and A
        a.nop(); a.nop(); a.nop(); a.nop();  // let TH settle
        a.moveBAbsD0(IO_DATA1);
        a.moveBImmAbs(0x40, IO_DATA1);
        a.btstImmD0(5); a.beq("trampolin");  // START (active low)
        a.btstImmD0(4); a.beq("trampolin");  // A
        a.label("vs1");                      // wait for vblank to end
        a.moveWAbsD0(VDP_CTRL); a.btstImmD0(3); a.bne("vs1");
        a.label("vs2");                      // wait for the next one
        a.moveWAbsD0(VDP_CTRL); a.btstImmD0(3); a.beq("vs2");
        a.rts();

        a.label("comprobar");                // once per frame, in the final loop
        a.addqWAbs(1, contRam);
        a.cmpiWAbs(ESPERA, contRam);
        a.bcc("trampolin");                  // counter >= ESPERA
        a.bra(dirBucle);                     // carry on with the intro

        a.label("trampolin");                // a bra.w cannot reach the far
        a.jmpAbs(f.labels.get("salir"));     // stub, so jump to it

        Stub out = new Stub();
        out.cerca = a.link();
        out.lejos = datosLejos;
        out.labels = new HashMap<>(a.labels);
        out.labels.putAll(f.labels);
        return out;
    }

    // ---- a 68000 mini-assembler: only what the stub needs -------------------

    static final class Asm {
        private final int base;               // absolute address of the first byte
        private byte[] buf = new byte[256];
        private int len;
        private final List<Object[]> fix = new ArrayList<>();   // {pos, label or address}
        final Map<String, Integer> labels = new HashMap<>();

        Asm(int base) { this.base = base; }

        int pc() { return base + len; }
        void label(String n) { labels.put(n, pc()); }

        void w(int v) {
            if (len + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[len++] = (byte) (v >> 8);
            buf[len++] = (byte) v;
        }
        void l(int v) { w(v >>> 16); w(v); }
        void db(int[] datos) {
            for (int d : datos) {
                if (len + 1 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[len++] = (byte) d;
            }
        }
        void db(byte[] datos) {
            if (len + datos.length > buf.length) buf = Arrays.copyOf(buf, len + datos.length);
            System.arraycopy(datos, 0, buf, len, datos.length);
            len += datos.length;
        }

        void moveWSr(int v)             { w(0x46FC); w(v); }
        void moveBImmAbs(int v, int a)  { w(0x13FC); w(v & 0xFF); l(a); }
        void moveWImmAbs(int v, int a)  { w(0x33FC); w(v); l(a); }
        void moveLImmAbs(int v, int a)  { w(0x23FC); l(v); l(a); }
        void moveBAbsD0(int a)          { w(0x1039); l(a); }        // move.b (a).l,d0
        void moveWAbsD0(int a)          { w(0x3039); l(a); }        // move.w (a).l,d0
        void andiBD0(int v)             { w(0x0200); w(v & 0xFF); }
        void clrLAbs(int a)             { w(0x42B9); l(a); }
        void clrWAbs(int a)             { w(0x4279); l(a); }
        void addqWAbs(int n, int a)     { w(0x5079 | ((n & 7) << 9)); l(a); }
        void cmpiWAbs(int v, int a)     { w(0x0C79); w(v); l(a); }
        void btstImmD0(int b)           { w(0x0800); w(b); }        // btst #b,d0
        void btstImmAbs(int b, int a)   { w(0x0839); w(b); l(a); }
        void moveaLImmA7(int v)         { w(0x2E7C); l(v); }        // movea.l #v,a7
        void jmpAbs(int a)              { w(0x4EF9); l(a); }
        void nop()                      { w(0x4E71); }
        void moveq(int v, int reg)      { w(0x7000 | (reg << 9) | (v & 0xFF)); }
        void moveWImmDn(int v, int r)   { w(0x303C | (r << 9)); w(v); }
        void leaAbs(int a, int reg)     { w(0x41F9 | (reg << 9)); l(a); }
        void moveWIncInc()              { w(0x32D8); }              // move.w (a0)+,(a1)+
        void moveBIncAbs(int a)         { w(0x13D8); l(a); }        // move.b (a0)+,(a).l
        void moveWAbsDn(int a, int r)   { w(0x3039 | (r << 9)); l(a); }
        void moveWIncDn(int r)          { w(0x301B | (r << 9)); }   // move.w (a3)+,dn
        void moveWIncIndA1()            { w(0x329B); }              // move.w (a3)+,(a1)
        void moveWDnIndA1(int r)        { w(0x3280 | r); }          // move.w dn,(a1)
        void lslWImm(int n, int r)      { w(0xE148 | ((n & 7) << 9) | r); }
        void negW(int r)                { w(0x4440 | r); }
        void subqWDn(int n, int r)      { w(0x5140 | ((n & 7) << 9) | r); }
        void moveaLIdxA0(int r, int dst) { w(0x2070 | (dst << 9)); w(r << 12); }  // movea.l (0,a0,dn.w),An
        void rts()                      { w(0x4E75); }

        void leaPcA0(String target)     { fix.add(new Object[]{len, target}); w(0x41FA); w(0); }
        void dbra(int reg, String t)    { fix.add(new Object[]{len, t}); w(0x51C8 | reg); w(0); }

        private void br(int opw, Object t) { fix.add(new Object[]{len, t}); w(opw); w(0); }
        void bra(Object t) { br(0x6000, t); }
        void beq(Object t) { br(0x6700, t); }
        void bne(Object t) { br(0x6600, t); }
        void bcc(Object t) { br(0x6400, t); }
        void bmi(Object t) { br(0x6B00, t); }

        /**
         * A fixed wait. On real hardware the YM2612's busy flag cannot be
         * polled -- it sticks at 1 the moment the Z80 goes into reset -- so the
         * wait is blind.
         */
        void retardo(int vueltas) {
            moveWImmDn(vueltas, 2);
            String et = "ret" + len;
            label(et);
            dbra(2, et);
        }

        byte[] link() {
            for (Object[] f : fix) {
                int pos = (Integer) f[0];
                Object t = f[1];
                Integer dst = t instanceof String ? labels.get(t) : (Integer) t;
                if (dst == null) throw new IllegalStateException("undefined label: " + t);
                int disp = dst - (base + pos + 2);
                if (disp < -32768 || disp > 32767) {
                    throw new IllegalStateException("branch out of range: " + t + " (" + disp + ")");
                }
                buf[pos + 2] = (byte) (disp >> 8);
                buf[pos + 3] = (byte) disp;
            }
            return Arrays.copyOf(buf, len);
        }
    }

    // ---- RLE by words ------------------------------------------------------

    /**
     * Big-endian words throughout:
     *   n &gt; 0   -- n literal words follow
     *   n &lt; 0   -- the next word repeats -n times
     *   n == 0  -- end
     * It is unpacked straight into the VDP data port, with no buffer.
     */
    static byte[] rleCompress(byte[] datos) {
        int n = datos.length / 2;
        int[] pal = new int[n];
        for (int i = 0; i < n; i++) pal[i] = ((datos[2 * i] & 0xFF) << 8) | (datos[2 * i + 1] & 0xFF);

        byte[] out = new byte[64];
        int len = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && pal[j + 1] == pal[i] && j - i < 0x7FFE) j++;
            int need = (j - i >= 2) ? 4 : 2 + 2 * (n - i);
            if (len + need > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, len + need));
            if (j - i >= 2) {                       // a run of 3 or more
                int count = -(j - i + 1);
                out[len++] = (byte) (count >> 8);
                out[len++] = (byte) count;
                out[len++] = (byte) (pal[i] >> 8);
                out[len++] = (byte) pal[i];
                i = j + 1;
            } else {                                // literals up to the next run
                int k = i;
                while (k < n && k - i < 0x7FFE) {
                    if (k + 2 < n && pal[k] == pal[k + 1] && pal[k + 1] == pal[k + 2]) break;
                    k++;
                }
                out[len++] = (byte) ((k - i) >> 8);
                out[len++] = (byte) (k - i);
                for (int q = i; q < k; q++) {
                    out[len++] = (byte) (pal[q] >> 8);
                    out[len++] = (byte) pal[q];
                }
                i = k;
            }
        }
        return Arrays.copyOf(out, len + 2);         // the terminating 0
    }

    /** Only used to check the compressor is reversible before trusting it. */
    static byte[] rleExpand(byte[] datos) {
        byte[] out = new byte[datos.length * 4];
        int len = 0, i = 0;
        while (true) {
            short n = (short) (((datos[i] & 0xFF) << 8) | (datos[i + 1] & 0xFF));
            i += 2;
            if (n == 0) return Arrays.copyOf(out, len);
            int bytes = Math.abs((int) n) * 2;
            if (len + bytes > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, len + bytes));
            if (n > 0) {
                System.arraycopy(datos, i, out, len, n * 2);
                len += n * 2;
                i += n * 2;
            } else {
                for (int q = 0; q < -n; q++) {
                    out[len++] = datos[i];
                    out[len++] = datos[i + 1];
                }
                i += 2;
            }
        }
    }

    // ---- odds and ends -----------------------------------------------------

    /** Sega's header checksum: every word from 0x200 to the end of the game. */
    static int checksumSega(byte[] rom, int upTo) {
        int s = 0;
        for (int i = 0x200; i + 1 < upTo; i += 2) s = (s + readU16(rom, i)) & 0xFFFF;
        return s;
    }

    static int readU16(byte[] b, int at) { return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF); }
    static int readU32(byte[] b, int at) { return (readU16(b, at) << 16) | readU16(b, at + 2); }
    static void writeU16(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 8);
        b[at + 1] = (byte) v;
    }
    static void writeU32(byte[] b, int at, int v) {
        writeU16(b, at, v >>> 16);
        writeU16(b, at + 2, v);
    }
}
