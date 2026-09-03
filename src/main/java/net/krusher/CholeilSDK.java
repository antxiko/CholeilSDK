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
 *       (DefaultNameInserter) written over the empty-name branch, the
 *       Charnego Translations intro (IntroInserter) in front of the game,
 *       and Choleil.ips, the distributable patch
 */
public class CholeilSDK
{

    public static void main( String[] args ) throws IOException
    {
        if ( args.length < 1 || (!args[0].equals("x") && !args[0].equals("i")) )
        {
            System.out.println("usage:");
            System.out.println("  x   extract everything (text + graphics + stray text)");
            System.out.println("  i   insert everything (rebuild " + DefaultPaths.OUT_ROM + " from " + DefaultPaths.SCRIPT + " + " + DefaultPaths.STRAY_TEXT + ")");
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
            TextExtractor.run( DefaultPaths.ROM, DefaultPaths.TBL, DefaultPaths.SCRIPT, DefaultPaths.POINTERS );

            System.out.println();
            System.out.println("=== scanning for compressed graphics ===");
            net.krusher.graphics.GraphicsExtractor.scan( DefaultPaths.ROM, DefaultPaths.GRAPHICS_OFFSETS );

            System.out.println();
            System.out.println("=== extracting graphics ===");
            net.krusher.graphics.GraphicsExtractor.extract( DefaultPaths.ROM, DefaultPaths.GRAPHICS_OFFSETS, DefaultPaths.GFX_OUT );

            System.out.println();
            System.out.println("=== extracting pause-menu icons ===");
            net.krusher.graphics.PauseMenuGraphics.extract(
                    DefaultPaths.ROM,
                    net.krusher.graphics.PauseMenuGraphics.DEFAULT_EDIT,
                    net.krusher.graphics.PauseMenuGraphics.DEFAULT_VIEW );

            System.out.println();
            System.out.println("=== extracting raw (uncompressed) graphics ===");
            net.krusher.graphics.RawGraphicsExtractor.main( new String[] { DefaultPaths.ROM, DefaultPaths.RAW_GRAPHICS, DefaultPaths.RAW_GFX_OUT } );

            System.out.println();
            System.out.println("=== extracting sprite-mosaic graphics ===");
            net.krusher.graphics.SpriteGraphicsExtractor.main( new String[] { DefaultPaths.ROM, DefaultPaths.SPRITE_GRAPHICS, DefaultPaths.SPRITE_GFX_OUT } );

            System.out.println();
            System.out.println("=== extracting the dialogue font ===");
            net.krusher.graphics.FontExtractor.run( DefaultPaths.ROM, DefaultPaths.FONT );

            System.out.println();
            System.out.println("=== scanning for stray text ===");
            StrayTextScanner.main( new String[] { DefaultPaths.ROM, DefaultPaths.SCRIPT, DefaultPaths.GRAPHICS_OFFSETS, DefaultPaths.STRAY_TEXT } );
        }
        else
        {
            System.out.println("=== scanning for free space ===");
            FreeSpaceScanner.main( new String[] { DefaultPaths.ROM, "1d8000", DefaultPaths.FREE_SPACE, DefaultPaths.GRAPHICS_OFFSETS } );

            System.out.println();
            System.out.println("=== inserting credits (pointer-relocatable) ===");
            CreditsInserter.run( DefaultPaths.ROM, DefaultPaths.STRAY_TEXT, DefaultPaths.TBL, DefaultPaths.FREE_SPACE, DefaultPaths.CREDITS_POINTERS, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== inserting text ===");
            TextInserter.run( DefaultPaths.OUT_ROM, DefaultPaths.SCRIPT, DefaultPaths.TBL, DefaultPaths.FREE_SPACE, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== fixing map balloon widths ===");
            MapBalloonInserter.run( DefaultPaths.OUT_ROM, DefaultPaths.TBL, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== inserting stray text ===");
            StrayTextInserter.main( new String[] { DefaultPaths.OUT_ROM, DefaultPaths.STRAY_TEXT, DefaultPaths.TBL, DefaultPaths.OUT_ROM, DefaultPaths.CREDITS_POINTERS } );

            System.out.println();
            System.out.println("=== arranging pause-menu icon edits for compression ===");
            net.krusher.graphics.PauseMenuGraphics.sync(
                    DefaultPaths.ROM,
                    net.krusher.graphics.PauseMenuGraphics.DEFAULT_EDIT,
                    net.krusher.graphics.PauseMenuGraphics.DEFAULT_GFX );

            System.out.println();
            System.out.println("=== recompressing and inserting graphics ===");
            net.krusher.graphics.GraphicsInserter.main( new String[] { DefaultPaths.OUT_ROM, DefaultPaths.GFX_OUT, DefaultPaths.GRAPHICS_OFFSETS, DefaultPaths.OUT_ROM } );

            System.out.println();
            System.out.println("=== inserting raw (uncompressed) graphics ===");
            net.krusher.graphics.RawGraphicsInserter.main( new String[] { DefaultPaths.OUT_ROM, DefaultPaths.RAW_GFX_OUT, DefaultPaths.RAW_GRAPHICS, DefaultPaths.OUT_ROM } );

            System.out.println();
            System.out.println("=== inserting sprite-mosaic graphics ===");
            net.krusher.graphics.SpriteGraphicsInserter.main( new String[] { DefaultPaths.OUT_ROM, DefaultPaths.SPRITE_GFX_OUT, DefaultPaths.SPRITE_GRAPHICS, DefaultPaths.OUT_ROM } );

            System.out.println();
            System.out.println("=== setting the default hero name ===");
            DefaultNameInserter.run( DefaultPaths.OUT_ROM, DefaultPaths.TBL, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== inserting the dialogue font ===");
            net.krusher.graphics.FontInserter.run( DefaultPaths.OUT_ROM, DefaultPaths.FONT, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== inserting the intro ===");
            IntroInserter.run( DefaultPaths.OUT_ROM, DefaultPaths.INTRO, DefaultPaths.ROM, DefaultPaths.FREE_SPACE, DefaultPaths.OUT_ROM );

            System.out.println();
            System.out.println("=== writing the IPS patch ===");
            IpsWriter.run( DefaultPaths.ROM, DefaultPaths.OUT_ROM, DefaultPaths.PATCH );
        }
    }
}
