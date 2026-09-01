package net.krusher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;

/**
 * The ROM the tests read, and the handful of ROM-walking helpers they share.
 *
 * The pipeline is run once per JVM, not once per test class: it takes well
 * under a second, but every test should be looking at the same artifact, and
 * rebuilding per class would say nothing extra.
 *
 * The base ROM is not part of the repo (it is gitignored on purpose), so
 * require() SKIPS rather than fails when it is missing -- a fresh clone can
 * still run `mvn test`. Drop "Soleil (Spain).md" in the project root, or point
 * -Dsmoke.rom at another dump of it, to make these suites runnable.
 */
final class SmokeRom {

    private SmokeRom() {}

    /** Overridable so the suites can be pointed at another dump of the base ROM. */
    static final String BASE = System.getProperty("smoke.rom", DefaultPaths.ROM);

    private static byte[] built;
    private static byte[] base;

    /**
     * Call from a @BeforeAll: skips the class when the ROM is absent, and
     * otherwise makes sure the pipeline has been run.
     */
    static void require() throws Exception {
        Assumptions.assumeTrue(Files.exists(Paths.get(BASE)),
                "base ROM '" + BASE + "' not present -- it is gitignored on purpose; "
                        + "drop it in the project root to run this suite");
        build();
    }

    /** The ROM as the pipeline's text + balloon stages leave it. */
    static byte[] built() {
        return built;
    }

    /** The untouched base ROM, for the counts the output no longer carries. */
    static byte[] base() {
        return base;
    }

    private static synchronized void build() throws Exception {
        if (built != null) return;
        Path out = Paths.get("target", "smoke-rom.md");
        Files.createDirectories(out.getParent());
        TextInserter.run(BASE, DefaultPaths.SCRIPT, DefaultPaths.TBL, DefaultPaths.FREE_SPACE, out.toString());
        MapBalloonInserter.run(out.toString(), DefaultPaths.TBL, out.toString());
        built = Files.readAllBytes(out);
        base = Files.readAllBytes(Paths.get(BASE));
    }

    /** Where room `room`'s NPC table lives in `rom`. */
    static int npcTableAddr(byte[] rom, int room) {
        return TextInserter.SCRIPT_BASE + TextExtractor.readU32(rom, TextInserter.SCRIPT_BASE + room * 4);
    }

    /** Room 0's table offset doubles as the room count. */
    static int roomCount(byte[] rom) {
        return TextExtractor.readU32(rom, TextInserter.SCRIPT_BASE) / 4 - 1;
    }

    /**
     * A string table's size. Slot 0 doubles as the size hint while it holds a
     * relative offset (str=0 always sits right behind its table), which covers
     * forked tables the base ROM knows nothing about; only a str=0 that had to
     * go indirect needs the base ROM's copy.
     */
    static int strCountOf(byte[] rom, int strTableAddr) {
        int slot0 = TextExtractor.readU16(rom, strTableAddr);
        return (slot0 < 0x8000 ? slot0 : TextExtractor.readU16(base, strTableAddr)) / 2;
    }

    /** Whether `addr` is inside the ROM and reaches an 0xFF within a text's worth of bytes. */
    static boolean terminatedStringAt(byte[] rom, int addr) {
        if (addr <= 0 || addr >= rom.length) return false;
        int len = 0;
        while (addr + len < rom.length && (rom[addr + len] & 0xFF) != 0xFF && len < 4096) len++;
        return addr + len < rom.length && (rom[addr + len] & 0xFF) == 0xFF;
    }

    static boolean startsWith(byte[] rom, int addr, byte[] want) {
        if (addr < 0 || addr + want.length > rom.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (rom[addr + i] != want[i]) return false;
        }
        return true;
    }

    /** Replaces one entry's body in a script.txt, keeping its header line. */
    static String replaceEntryBody(String script, int room, int npc, int str, String body) {
        String header = String.format("==== room=%d npc=%d str=%d ", room, npc, str);
        int at = script.indexOf(header);
        if (at < 0) {
            throw new IllegalStateException("no room=" + room + " npc=" + npc + " str=" + str
                    + " in " + DefaultPaths.SCRIPT);
        }
        int bodyStart = script.indexOf('\n', at) + 1;
        int bodyEnd = script.indexOf("\n====", bodyStart);
        if (bodyEnd < 0) bodyEnd = script.length();
        return script.substring(0, bodyStart) + body + "\n" + script.substring(bodyEnd + 1);
    }

    /** Writes a script.txt with one entry's body replaced, and returns its path. */
    static Path scriptWith(String name, int room, int npc, int str, String body) throws Exception {
        Path path = Paths.get("target", name);
        String script = new String(Files.readAllBytes(Paths.get(DefaultPaths.SCRIPT)), StandardCharsets.UTF_8);
        Files.write(path, replaceEntryBody(script, room, npc, str, body).getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
