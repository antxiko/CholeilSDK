package net.krusher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Genesis header every insertion step has to leave consistent. Real
 * hardware ignores the checksum, but some emulators and flashcarts do not,
 * so each step fixes it before writing -- and the last one to run decides
 * whether the ROM boots on a picky setup.
 */
@DisplayName("ROM header")
final class RomHeaderTest {

    private static byte[] rom;

    @BeforeAll
    static void buildRom() throws Exception {
        SmokeRom.require();
        rom = SmokeRom.built();
    }

    @Test
    @DisplayName("the stored checksum matches the ROM that was written")
    void storedChecksumMatchesTheRom() {
        int sum = 0;
        for (int i = 0x200; i + 1 < rom.length; i += 2) {
            sum = (sum + (((rom[i] & 0xFF) << 8) | (rom[i + 1] & 0xFF))) & 0xFFFF;
        }
        assertEquals(MapBalloonInserter.readU16(rom, 0x18e), sum,
                String.format("header checksum (stored %04x, computed %04x)",
                        MapBalloonInserter.readU16(rom, 0x18e), sum));
    }
}
