# CholeilSDK

A ROM-hacking toolchain for translating **Soleil** (Mega Drive / Genesis), known in
North America as *Crusader of Centy* and in Japan as *Shin Souseiki Ragnacënty*.

It extracts everything translatable from the ROM into editable files — dialogue,
menu and UI text, credits, graphics, the dialogue font — and rebuilds a patched ROM
plus a distributable IPS patch from them. It was written for, and is used by,
[Charnego Translations](https://charnego.krusher.net/)' Spanish translation
*Choleil*, but the toolchain is general: see
[Adapting it to another version](#adapting-it-to-another-version).

**The ROM is not included and never will be.** It is commercial game data. You must
supply your own dump.

---

## What it is built against

| | |
|---|---|
| ROM | `Soleil (Spain).md`, 2,097,152 bytes |
| CRC-32 | `9ED4C323` |
| MD5 | `6CBBB5D870CA386075A96BC727068EC2` |
| SHA-1 | `95AE529CECB7DBCA281DF25C3C4E7CB8F48D936C` |

Every address in this document and in the source refers to that dump.

## Requirements

- A JDK 24 or newer. GraalVM is needed only to build the native executable.
- Maven, or just the bundled `mvnw` wrapper, which bootstraps its own.

No other dependencies: the toolchain uses nothing outside the JDK, and deliberately
does not even use `java.desktop` (see [PNG codec](#png-codec)).

## Quick start

Put your ROM in the project root as `Soleil (Spain).md`, then:

```
choleil x     extract everything into editable files
choleil i     rebuild Choleil.md and Choleil.ips from those files
```

`choleil.cmd` compiles the toolchain first, then runs it, and can be run from any
directory. `x` overwrites `script.txt` and `stray_text.txt`, so it asks for
confirmation and warns about uncommitted edits; pass `-y` to skip the prompt in a
script.

Every tool is also runnable on its own — `java -cp target/classes
net.krusher.TextInserter …` — and takes its paths as arguments, falling back to the
names in `DefaultPaths` when run without any.

## The working files

| File | What it is |
|---|---|
| `script.txt` | The dialogue. The translation's main working file. |
| `stray_text.txt` | Text found outside the script proper: menus, prompts, credits, the region-lock message. |
| `soleil.tbl` | The byte ↔ glyph table for the game's text encoding. |
| `gfx_out/` | LZ-Toshio-compressed graphics, as editable PNGs. |
| `raw_gfx_out/` | Uncompressed graphics (SEGA logo, "PULSA START", …). |
| `sprite_gfx_out/` | Graphics stored as sprite mosaics (the title logo, the ground-money coin). |
| `font.png` | The 8×16 dialogue font, one editable sheet. |
| `charnego_introFinal.md` | The Charnego Translations intro, a standalone Mega Drive ROM. |
| `graphics_offsets.txt`, `raw_graphics.txt`, `sprite_graphics.txt` | Registries saying where each graphics block lives. |
| `known_palettes.txt` | Confirmed CRAM palettes, so blocks render in their real colours. |
| `free_space.txt` | Reclaimable regions, rescanned on every build. |
| `pointers.txt`, `credits_pointers.txt` | Where things were found; regenerated, not hand-edited. |
| `Choleil.md` | The patched ROM. Gitignored — it is >99% original game data. |
| `Choleil.ips` | The patch. **This is what you distribute.** |

Deleting an extracted PNG leaves that graphics block untouched on the next build.
The same applies to `font.png`.

---

## What it can do

### Dialogue script

The script lives at `0x1C0000` behind three levels of table: a room table of 4-byte
offsets, an NPC table of 2-byte offsets, and a string table of 2-byte offsets, then
the text itself terminated by `0xFF`. Each table's slot 0 doubles as its own size,
so the counts are self-describing.

`TextExtractor` walks all of it into `script.txt`, one entry per string:

```
==== room=23 npc=1 str=0 textAddr=0x1c6cc2 ptrFieldAddr=0x1c6cbe ====
CIRO: Los maromos no me
molan, siempre están en plan
```

`TextInserter` puts it back, and does considerably more than write bytes:

- **Relocation.** A translated string rarely fits where the original sat. Strings are
  grouped, placed in reclaimed space, and every pointer is rewritten.
- **Reach beyond the format's limit.** A string slot is a 16-bit *forward* offset from
  its own table, so the original format cannot address anything more than `0x7FFF`
  away. The inserter installs a small 68k helper at `0xF6000` and patches the ten
  inlined table-walk sites to call it, which makes a slot dual-mode: below `0x8000`
  it is still the stock relative offset, at or above it is an index into a table of
  absolute pointers. Strings that fit stay relative; only those that cannot go
  indirect.

  **Every string step must be patched, or indirect slots corrupt silently.** The
  game's original instruction is `adda.w`, which *sign-extends*: a slot with the high
  bit set is read as a large negative offset (`0x801c` becomes −32740), so an
  unpatched site lands mid-way inside some unrelated string and draws it from there.
  A tenth site at `0x32170` was missing for a long time and broke 84 of 1036 slots
  this way. The routines walk room → NPC → string with the same instruction shape at
  each step, so the NPC steps look identical to the string steps and only the latter
  may be patched; the test `noInlinedStringStepIsMissingFromThePatchTable` tells them
  apart and fails if a string step is left out.
- **Sharing.** Identical strings share one copy in the ROM, and `<SAME room=… npc=…
  str=…>` in `script.txt` marks an entry that resolves to another's text.
- **Forking rooms that share tables.** Fifteen room-table offsets are held by two to
  twelve rooms each, so those rooms share *every* string slot — writing different
  text for one used to be impossible. Replace a `<SAME …>` line with real text and
  the inserter gives that room its own copy of the table structure, laid out so the
  self-describing counts still hold and every index table stays word-aligned (the
  game reads slots with `move.w`, and an odd address is a 68000 address error).
  Rooms whose text has not diverged are untouched.
- **Control codes.** `<NAME>` (the hero's name), `<YESNO>`, line breaks, and the
  event-flag opcodes `{E0}`/`{E1}` with their operands.

### Stray text and credits

`StrayTextScanner` finds readable text outside the script and writes it to
`stray_text.txt` as `ascii` or `encoded` blocks. Prune it down to what you actually
want to change; `StrayTextInserter` writes back whatever remains.

These strings are referenced by fixed absolute address in 68k code, so there is
nowhere to relocate an oversized replacement to: a block that is too long is warned
about and trimmed rather than aborting the build.

The staff credits are the exception — they turned out to be genuinely pointer-based,
a table of absolute 4-byte pointers to "cards". `CreditsInserter` handles them
separately and can place a card anywhere in the ROM.

### Graphics

Three storage formats, each with its own extractor and inserter:

- **LZ-Toshio compressed** (`gfx_out/`). A decoder and re-encoder for the scheme used
  by several Mega Drive titles, ported from
  [lab313ru/lztoshio](https://github.com/lab313ru/lztoshio). Blocks are auto-detected
  by a scan; edited blocks are recompressed and, if they no longer fit, relocated
  with their pointers patched.
- **Raw** (`raw_gfx_out/`). Plain 4bpp tile bytes with no header — the boot SEGA logo,
  the "PULSA START" prompt. These cannot be auto-detected, so they are hand-verified
  and listed in `raw_graphics.txt`.
- **Sprite mosaics** (`sprite_gfx_out/`). Fixed-size sprites placed in a macro
  grid. Tile order within a sprite is column-major by default — the Mega Drive
  sprite convention — but a block that the game DMAs into VRAM as a plain tile
  run reads TL, TR, BL, BR instead, and needs `rowmajor` as a sixth field in
  `sprite_graphics.txt`. Getting it wrong still round-trips byte-exact, because
  extraction and reinsertion cancel out; what breaks is the PNG in between,
  whose quadrants come out shuffled and cannot be drawn on.

Everything renders as a PNG tile sheet, in the real CRAM palette where one is known
(`known_palettes.txt`) and a grayscale ramp otherwise. Edited PNGs must keep their
original resolution.

#### Finding an uncompressed sprite

Only the LZ-Toshio blocks can be auto-detected. Anything uncompressed has to be
located by hand, and the method that works is worth writing down (it is how the
ground-money coin was found — see [MONEDAS.md](MONEDAS.md), in Spanish):

1. Take an emulator savestate with the sprite on screen and dump VRAM.
2. Diff VRAM across several frames — only the animated tiles change, which
   isolates the sprite from the rest of the scene.
3. Search the ROM for those tile bytes literally; uncompressed blocks match
   exactly.
4. **Match every frame of a full animation cycle against *every* occurrence in
   the ROM, not just the first.** The coin turned out to cycle through eight
   consecutive 256-byte blocks at `0xd0f60 + n*0x400` holding only two distinct
   designs — four identical copies each. Editing one copy of a pose makes the
   sprite alternate between the new art and the old as it animates.
5. **Then widen the search and do it again.** An exact byte search finds exact
   copies; other zones often carry the same drawing with a handful of nibbles
   changed, and an exact search walks straight past them. Redrawing the coin
   fixed it in one zone and left it wrong in others until a fuzzy search — same
   pose, allowing a few differing nibbles, in *both* tile orders — turned up
   three more copies: `0xf4600` (both poses, column-major, byte-identical) and
   `0xd6d20 + n*0x200` (a second family whose art bakes in a drop shadow, 16
   transparent pixels replaced by palette index 11). Search on the most
   distinctive pose; a thin or mostly-transparent one matches half the noise in
   the ROM.

### The dialogue font

The letters the text engine draws as two stacked tiles. The renderer at `0x33732`
locates them:

```
033732  andi.w  #$ff, d0        ; character code from the script
033756  lea.l   $f5000.l, a3    ; the font
03375c  lsl.w   #$4, d0         ; 16 bytes per glyph
```

`0xF5000`, 16 bytes per glyph, indexed by the same codes as `soleil.tbl`: 1 bit per
pixel, one byte per row, MSB leftmost, 8 wide by 16 tall. The ROM holds only the
shape — the colours and the outline are the engine's doing at runtime.

`FontExtractor` writes `font.png` (128×112, 16 glyphs across, cell *(col, row)* =
code `row*16 + col`); `FontInserter` writes it back in place. A pixel counts as set
when it is nearer white than black, so a sheet re-saved greyscale or anti-aliased
still works.

The table is codes `0x00`–`0x60` and ends at `0xF5610`. What follows is **not** more
glyphs — it is a strip of pre-rendered words in the same 1bpp format, so the tools
never write past the last glyph.

### World-map town balloons

The map draws each town's name into a balloon whose width is baked into a table,
sized for the original names. `MapBalloonInserter` resizes each balloon to the
translated name and centres the text inside it, which needs three small 68k patches
plus a half-tile shift for odd-length names. `CARTELES.md` documents the data rules
translated names have to follow.

### Default hero name

Confirming the name-entry screen with nothing typed left the hero nameless: the
game's own empty-name branch at `0x53D2` filled the buffer with leftovers from an
earlier font, which land on blank tiles here. `DefaultNameInserter` rewrites that
branch's six immediates with a name of your choosing — no instruction moves, and it
is rejected at build time if it exceeds the 10 characters the entry screen allows.

### Boot intro

`IntroInserter` puts the Charnego Translations intro in front of the game, so a cold
boot plays the intro and START (or A) — or a 60-frame timeout after its own fade —
drops into Soleil.

The game cannot move: its code is full of absolute addresses. The intro is a
standalone 608 KB Mega Drive ROM that also wants to live at `0x000000`, so it is cut
into pieces — code, 16 animation frames, the PCM sample, the Z80/XGM driver — the
frames are RLE-compressed by words (560 KB → 155 KB), and the pieces are scattered
through filler the game itself never reads. The ROM does not grow: all 203 KB lands
inside the 2 MB. The handful of absolute addresses in the intro's own 68000 code are
rebased to wherever their piece ended up.

The boot chain is a hand-assembled 68000 stub, built by a mini-assembler in the same
class. The game's RESET vector points at its "near" half, which lives in the intro's
now-dead vector table because the intro's final loop can only reach it with a `bra.w`
(±32 KB); that half unlocks the TMSS and jumps into the intro. The intro's vblank
wait is redirected to ours, which reads pad 1 on *every* frame, so START skips the
intro from the first one and not just at the end. Its frame-blit routine is
redirected to our decompressor, which unpacks straight into the VDP data port with no
buffer. The "far" half handles the exit: PSG and YM2612 silenced (the YM *before* the
Z80 reset line is touched — that line cuts the 68000 off its bus, and on real hardware
the busy flag then sticks at 1 and the exit hangs forever), Z80 reset, screen blanked,
pad control registers cleared so the game still sees a cold boot. Its epilogue is
copied to RAM and run from there, because enabling the SRAM can mask the high ROM —
including the very code doing the enabling.

The boot logo screen is removed at the same time: entry 0 of the boot state machine's
jump table is pointed straight at state 6, so it never appears, on boot or on the way
back from the game, and its 1632 bytes of graphics become part of the intro's pool.
That is why `raw_gfx_out/raw_000e56.png` and `raw_001456.png` — the Charnego logo —
stop being drawn once the intro is in.

This runs **last**, over a ROM every other step has already rewritten, and the intro's
filler list overlaps `free_space.txt` in five places. So `free_space.txt` is subtracted
from its pool up front, and every piece is checked against the untouched ROM before
being written: a byte an earlier step changed stops the build instead of silently
eating a relocated string. The freed logo graphics are the one exemption, since
removing the logo is what makes them dead.

Ported from `insertar_intro.py` by ScorpioN-MsX; the Java step reproduces its output
byte for byte. Only the path the pipeline uses came across — the uncompressed mode,
the diagnostic-colour build, the second intro profile and the second game are in git
history.

The header's "ROM end" field at `0x1A4` is deliberately left alone: Soleil checksums
itself against it and hangs on a red screen if it changes.

### Free space

`FreeSpaceScanner` re-scans the ROM on every build for runs of filler, excluding
everything the other tools know about, and writes `free_space.txt`. Regions that
cannot be detected by scanning but have been verified by hand are listed as
`VERIFIED_GAPS` in the source.

### IPS patch

`IpsWriter` diffs the base ROM against the built one and writes `Choleil.ips`
following the standard format ([zerosoft](https://zerosoft.zophar.net/ips.php),
[ravener](https://gist.github.com/ravener/95aac30eb7d2fdc5e983bc143a7cfdf0)): runs
separated by fewer than five unchanged bytes are merged, repeated bytes go out as
RLE, blocks longer than the 16-bit size field are split, and a record that would
start at `0x454F46` — which encodes as the ASCII bytes `EOF` and stops every applier
dead — is started one byte earlier. Neither spec mentions that last one.

A build that changes nothing produces a patch of just header and terminator. That is
legal, but some appliers only look for the `EOF` marker *after* reading a record and
reject it as truncated — and it means your build did nothing anyway — so the writer
warns instead of shipping it silently.

The output is verified two ways: an applier written from the spec inside the test
suite, and, by hand, [ravener/ips-patcher](https://github.com/ravener/ips-patcher),
which reproduces the built ROM byte for byte from the base ROM.

### PNG codec

The toolchain reads and writes PNGs itself rather than using `javax.imageio`, which
cannot run in a native image without AWT. Reading accepts bit depths 1/2/4/8/16, all
five colour types, interlaced or not; writing emits 4-bit indexed colour, which keeps
an edited tile sheet restricted to the real Genesis palette when it is reopened.

This also fixed a real bug: ImageIO gives an 8- or 16-bit grayscale PNG a *linear*
gray colour space, so a stored sample of 51 read back as `0x7C` — enough to shift a
palette index from 3 to 7 and silently corrupt any tile sheet re-saved as plain
grayscale.

---

## The pipeline

`choleil x`:

1. `TextExtractor` → `script.txt`, `pointers.txt`
2. `GraphicsExtractor` scan → `graphics_offsets.txt`, then extract → `gfx_out/`
3. `RawGraphicsExtractor` → `raw_gfx_out/`
4. `SpriteGraphicsExtractor` → `sprite_gfx_out/`
5. `FontExtractor` → `font.png`
6. `StrayTextScanner` → `stray_text.txt`

`choleil i`:

1. `FreeSpaceScanner` → `free_space.txt`
2. `CreditsInserter` (needs free space before `TextInserter` claims it)
3. `TextInserter`
4. `MapBalloonInserter`
5. `StrayTextInserter`
6. `GraphicsInserter`, `RawGraphicsInserter`, `SpriteGraphicsInserter`
7. `DefaultNameInserter`
8. `FontInserter`
9. `IntroInserter` (last: it checks every piece against the untouched ROM)
10. `IpsWriter` → `Choleil.ips`

Each step reads the ROM the previous one wrote, fixes the Genesis header checksum,
and either completes or throws — none of them silently skip their output and let the
next step carry on.

## Tests

```
mvn test
```

73 tests across ten suites, run against a ROM built by the real pipeline: the 68k
code patches byte for byte, every balloon width against its placed name, every script
slot resolving to a terminated string, room forking, the font round trip and its
bounds, the IPS patch verified by an applier written from the format spec, sprite tile
order in both directions, every copy of the coin art being registered, the intro
landing entirely inside the filler it declared and refusing a ROM an earlier step has
already written to, and the header checksum.

The ROM is gitignored, so without it the suites **skip** rather than fail — a fresh
clone still builds green.

## Native executable

```
mvn -Pnative package              release build
mvn -Pnative,native-dev package   about twice as fast to build, barely optimised
```

Produces a single self-contained `target/CholeilSDK.exe` — no DLLs, no JDK needed on
the machine that runs it — which runs the whole insertion pipeline in about half a
second. It is built with `-march=compatibility` so it does not bake in the build
machine's instruction set.

---

## Adapting it to another version

The toolchain is not Spanish-specific in design, but every address in it was verified
against the Spanish dump. Porting to the US (*Crusader of Centy*), Japanese
(*Ragnacënty*) or another European release means re-verifying a fairly short list.
Nothing else should need to change.

| What | Where |
|---|---|
| Script base and bounds | `TextExtractor.SCRIPT_BASE` / `SCRIPT_START` / `SCRIPT_END`, `TextInserter.SCRIPT_BASE` / `DEFAULT_GAP_END` |
| The ten table-walk sites the fetch helper patches | `TextInserter.FETCH_SITE_PATCHES` |
| Where the fetch helper is installed | `TextInserter.FETCH_HELPER_ADDR` |
| Verified free-space gaps | `FreeSpaceScanner.VERIFIED_GAPS` |
| Map balloon marker table and the centring patches | `MapBalloonInserter.MARKER_TABLE`, `CODE_PATCHES` |
| Empty-name branch | `DefaultNameInserter.LEA_ADDR`, `FIRST_MOVE_ADDR`, `MAX_NAME_LENGTH` |
| Font table | `graphics.Font.FONT_ADDR`, `GLYPH_COUNT` |
| Boot logo patch and the filler the intro is scattered through | `IntroInserter.LOGO_SLOT` / `LOGO_OLD` / `LOGO_NEW` / `LOGO_FREED`, `HUECOS` |
| Character encoding | `soleil.tbl` |
| Graphics block registries | `raw_graphics.txt`, `sprite_graphics.txt`, `known_palettes.txt` |

Two things make this less daunting than the list looks. Every 68k patch is applied
through a helper that verifies the ROM still holds the *expected original bytes*
before touching anything and refuses loudly otherwise — so a wrong address fails the
build rather than producing a subtly broken ROM. And the compressed-graphics scan and
the free-space scan both find their own targets, so those need no per-version table
at all.

Crusader of Centy and Soleil share the same base text encoding, so `soleil.tbl` is a
reasonable starting point for the US release; the European versions carry extra
accented characters.

**Known issue in `soleil.tbl`:** `0x60` is a circumflex `^`, not the `-` the table
claims (`0x5F` is the real hyphen), and `0x61`/`0x62` have entries but are not glyphs
at all — they are the start of the pre-rendered word strip after the font. Encoding
is unaffected, because the first definition of a glyph wins, but a raw `0x60` in the
game's own text decodes as `-`.

## Credits

- **EvilJagaGenius** — [the pointer layout and text encoding](https://www.romhacking.net/forum/index.php?topic=34617.0)
- **Vladimir Kononovich** — [LZ-Toshio compression](https://github.com/lab313ru/lztoshio)
- **Krusher, ScorpionMSX, Antxiko** — Charnego Translations

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Soleil / Crusader of Centy / Shin Souseiki Ragnacënty are the property of their
respective rights holders. This project contains no game data and is not affiliated
with them.
