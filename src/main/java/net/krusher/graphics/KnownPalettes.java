package net.krusher.graphics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of confirmed real CRAM palettes for specific compressed graphics
 * blocks, found by tracing the disassembly (not guessed) -- e.g. the boot-time
 * font/HUD/sprite bundle (0xF2000/0xF3000/0xF4800/0xFD000) all share the
 * 32-byte palette embedded uncompressed in code at ROM 0x000548, confirmed by
 * finding the exact `movel (a0)+,(a1)+ x8` copy into CRAM that loads it.
 *
 * Blocks not listed here fall back to the default grayscale palette. Add an
 * entry whenever a real palette gets confirmed for a block, so extraction
 * and reinsertion both render/decode it correctly instead of guessing.
 */
public final class KnownPalettes {

    private KnownPalettes() {}

    public static Map<Integer, Integer> load(String path) throws IOException {
        Map<Integer, Integer> map = new LinkedHashMap<Integer, Integer>();
        if (!Files.exists(Paths.get(path))) return map;
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String[] parts = t.split(",");
            if (parts.length < 2) continue;
            int blockOffset = (int) Long.parseLong(strip0x(parts[0].trim()), 16);
            int paletteOffset = (int) Long.parseLong(strip0x(parts[1].trim()), 16);
            map.put(blockOffset, paletteOffset);
        }
        return map;
    }

    private static String strip0x(String s) {
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }
}
