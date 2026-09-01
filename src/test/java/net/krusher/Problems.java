package net.krusher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collects every failed check in a test and reports them together.
 *
 * A ROM regression rarely trips one thing: a pointer that moved wrongly can
 * break hundreds of strings, and WHICH ones broke is the diagnosis. Stopping
 * at the first failed assertion throws that away, so checks are recorded and
 * the walk carries on; assertNone() at the end fails with the whole list.
 */
final class Problems {

    private final List<String> found = new ArrayList<String>();

    /** Records `what` as a problem when `condition` does not hold. */
    void check(boolean condition, String what) {
        if (!condition) found.add(what);
    }

    /** Records a mismatch, naming the first byte that differs. */
    void bytesAt(byte[] rom, int addr, int[] want, String what) {
        for (int i = 0; i < want.length; i++) {
            if ((rom[addr + i] & 0xFF) != want[i]) {
                found.add(what + String.format(" -- mismatch at 0x%x (got %02x, want %02x)",
                        addr + i, rom[addr + i] & 0xFF, want[i]));
                return;
            }
        }
    }

    void assertNone() {
        assertTrue(found.isEmpty(), () -> found.size() + " check(s) failed:\n  - " + String.join("\n  - ", found));
    }
}
