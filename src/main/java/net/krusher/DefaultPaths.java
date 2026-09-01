package net.krusher;

/**
 * The filenames every tool in this project agrees on.
 *
 * Each tool takes its paths as arguments and only falls back to these when
 * run without any, so nothing here is load-bearing at run time -- but the
 * fallbacks have to agree across a dozen classes for the pipeline to hand
 * one step's output to the next, and they used to be repeated as literals in
 * every main(). They live here so a rename is one edit.
 *
 * All of them are relative, and every tool resolves them against the working
 * directory: choleil.cmd switches to the project root first for exactly that
 * reason.
 */
public final class DefaultPaths {

    private DefaultPaths() {}

    /** The untouched European ROM everything is extracted from (gitignored). */
    public static final String ROM = "Soleil (Spain).md";

    /** The translated ROM the insertion pipeline builds (gitignored). */
    public static final String OUT_ROM = "Choleil.md";

    /** Byte <-> glyph table for the game's text encoding. */
    public static final String TBL = "soleil.tbl";

    /** The dialogue script: the translation's main working file. */
    public static final String SCRIPT = "script.txt";

    /** Where each script string was found, for reference. */
    public static final String POINTERS = "pointers.txt";

    /** Text found outside the script proper, script.txt's companion. */
    public static final String STRAY_TEXT = "stray_text.txt";

    /** Reclaimable regions found by FreeSpaceScanner. */
    public static final String FREE_SPACE = "free_space.txt";

    /** Where CreditsInserter parked each credits string, for StrayTextInserter. */
    public static final String CREDITS_POINTERS = "credits_pointers.txt";

    /** Compressed graphics: registry and extracted PNGs. */
    public static final String GRAPHICS_OFFSETS = "graphics_offsets.txt";
    public static final String GFX_OUT = "gfx_out";

    /** Uncompressed graphics: registry and extracted PNGs. */
    public static final String RAW_GRAPHICS = "raw_graphics.txt";
    public static final String RAW_GFX_OUT = "raw_gfx_out";

    /** Sprite-mosaic graphics: registry and extracted PNGs. */
    public static final String SPRITE_GRAPHICS = "sprite_graphics.txt";
    public static final String SPRITE_GFX_OUT = "sprite_gfx_out";

    /** Palettes the graphics tools reuse when re-encoding a PNG. */
    public static final String KNOWN_PALETTES = "known_palettes.txt";
}
