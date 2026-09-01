package net.krusher;

import java.io.IOException;

/**
 * Streamlined entry point: run the whole extraction or the whole insertion
 * pipeline with one letter and no further arguments, using the default
 * filenames every tool in this project already agrees on.
 *
 * Usage:
 *   x   extract everything: script.txt, pointers.txt, graphics_offsets.txt,
 *       gfx_out/, raw_gfx_out/, sprite_gfx_out/, stray_text.txt (prune
 *       stray_text.txt by hand before `i`)
 *   i   insert everything: free_space.txt (rescanned), Choleil.md (dialogue
 *       from script.txt, then whatever's left in stray_text.txt, then
 *       compressed graphics from gfx_out/, then raw graphics from
 *       raw_gfx_out/, then sprite-mosaic graphics from sprite_gfx_out/ --
 *       delete a PNG to leave that block untouched; edited PNGs must keep
 *       their original resolution), and the hero's default name
 *       (DefaultNameInserter) written over the empty-name branch
 */
public class CholeilSDK
{
    static final String ROM = "Soleil (Spain).md";
    static final String TBL = "soleil.tbl";
    static final String SCRIPT = "script.txt";
    static final String POINTERS = "pointers.txt";
    static final String GRAPHICS_OFFSETS = "graphics_offsets.txt";
    static final String GFX_OUT = "gfx_out";
    static final String RAW_GRAPHICS = "raw_graphics.txt";
    static final String RAW_GFX_OUT = "raw_gfx_out";
    static final String SPRITE_GRAPHICS = "sprite_graphics.txt";
    static final String SPRITE_GFX_OUT = "sprite_gfx_out";
    static final String FREE_SPACE = "free_space.txt";
    static final String STRAY_TEXT = "stray_text.txt";
    static final String CREDITS_POINTERS = "credits_pointers.txt";
    static final String OUT_ROM = "Choleil.md";

    public static void main( String[] args ) throws IOException
    {
        if ( args.length < 1 || (!args[0].equals("x") && !args[0].equals("i")) )
        {
            System.out.println("usage:");
            System.out.println("  x   extract everything (text + graphics + stray text)");
            System.out.println("  i   insert everything (rebuild " + OUT_ROM + " from " + SCRIPT + " + " + STRAY_TEXT + ")");
            return;
        }

        try
        {
            run( args[0] );
        }
        catch ( Exception ex )
        {
            // Every step in the pipeline below either completes or throws --
            // none of them are allowed to silently skip writing their output
            // and let the next step carry on as if nothing happened. Stop the
            // whole run here instead of a raw stack trace.
            System.out.println();
            System.out.println("ABORTED: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void run( String mode ) throws IOException
    {
        if ( mode.equals("x") )
        {
            System.out.println("=== extracting text ===");
            TextExtractor.run( ROM, TBL, SCRIPT, POINTERS );

            System.out.println();
            System.out.println("=== scanning for compressed graphics ===");
            net.krusher.graphics.GraphicsExtractor.scan( ROM, GRAPHICS_OFFSETS );

            System.out.println();
            System.out.println("=== extracting graphics ===");
            net.krusher.graphics.GraphicsExtractor.extract( ROM, GRAPHICS_OFFSETS, GFX_OUT );

            System.out.println();
            System.out.println("=== extracting raw (uncompressed) graphics ===");
            net.krusher.graphics.RawGraphicsExtractor.main( new String[] { ROM, RAW_GRAPHICS, RAW_GFX_OUT } );

            System.out.println();
            System.out.println("=== extracting sprite-mosaic graphics ===");
            net.krusher.graphics.SpriteGraphicsExtractor.main( new String[] { ROM, SPRITE_GRAPHICS, SPRITE_GFX_OUT } );

            System.out.println();
            System.out.println("=== scanning for stray text ===");
            StrayTextScanner.main( new String[] { ROM, SCRIPT, GRAPHICS_OFFSETS, STRAY_TEXT } );
        }
        else
        {
            System.out.println("=== scanning for free space ===");
            FreeSpaceScanner.main( new String[] { ROM, "1d8000", FREE_SPACE, GRAPHICS_OFFSETS } );

            System.out.println();
            System.out.println("=== inserting credits (pointer-relocatable) ===");
            CreditsInserter.run( ROM, STRAY_TEXT, TBL, FREE_SPACE, CREDITS_POINTERS, OUT_ROM );

            System.out.println();
            System.out.println("=== inserting text ===");
            TextInserter.run( OUT_ROM, SCRIPT, TBL, FREE_SPACE, OUT_ROM );

            System.out.println();
            System.out.println("=== fixing map balloon widths ===");
            MapBalloonInserter.run( OUT_ROM, TBL, OUT_ROM );

            System.out.println();
            System.out.println("=== inserting stray text ===");
            StrayTextInserter.main( new String[] { OUT_ROM, STRAY_TEXT, TBL, OUT_ROM, CREDITS_POINTERS } );

            System.out.println();
            System.out.println("=== recompressing and inserting graphics ===");
            net.krusher.graphics.GraphicsInserter.main( new String[] { OUT_ROM, GFX_OUT, GRAPHICS_OFFSETS, OUT_ROM } );

            System.out.println();
            System.out.println("=== inserting raw (uncompressed) graphics ===");
            net.krusher.graphics.RawGraphicsInserter.main( new String[] { OUT_ROM, RAW_GFX_OUT, RAW_GRAPHICS, OUT_ROM } );

            System.out.println();
            System.out.println("=== inserting sprite-mosaic graphics ===");
            net.krusher.graphics.SpriteGraphicsInserter.main( new String[] { OUT_ROM, SPRITE_GFX_OUT, SPRITE_GRAPHICS, OUT_ROM } );

            System.out.println();
            System.out.println("=== setting the default hero name ===");
            DefaultNameInserter.run( OUT_ROM, TBL, OUT_ROM );
        }
    }
}
