# La moneda del suelo (Malin): anatomia y como editarla

Documentacion del grafico de la moneda que sueltan la hierba, cajas y cofres
(el "Malin"), a raiz del PR "monedita". Todo lo de aqui esta verificado en
emulador (BizHawk, savestate con la moneda en pantalla) y con round-trip
byte a byte contra la ROM.

## Donde vive: 8 bloques, no 1

La animacion del giro cicla por OCHO bloques consecutivos de 256 bytes SIN
comprimir, en `0xd0f60 + n*0x400` (es decir: d0f60, d1360, d1760, d1b60,
d1f60, d2360, d2760, d2b60). El juego sube el bloque que toca por DMA a los
tiles VRAM 760-767 en cada frame de animacion.

En la ROM original solo habia DOS dibujos: el canto (4 copias identicas) y
la cara (4 copias identicas). Tras este PR:

| bloques                    | contenido               |
|----------------------------|-------------------------|
| d0f60, d1360, d1f60, d2360 | canto (arte original)   |
| d2760, d2b60               | cara nueva 1            |
| d1760, d1b60               | cara nueva 2            |

Ciclo por frames observado: canto x8, cara1 (d2760 4f -> d2b60 4f), canto x8,
cara2 (d1760 4f -> d1b60 4f). Resultado visual: canto -> cara 1 -> canto ->
cara 2.

OJO: las copias duplicadas importan. Si se edita solo un bloque de una
pareja, en juego se alternan el dibujo nuevo y el viejo (nos paso). Editar
siempre la pareja completa.

## Que hay dentro de cada bloque

Cada bloque de 256 bytes = 8 tiles = DOS codificaciones del MISMO dibujo
16x16, con los tiles en orden visual row-major (TL, TR, BL, BR):

- Tiles 0-3 ("mitad A"): version sprite, fondo transparente.
  Indices de paleta: 0=transparente, 4=amarillo, 7=naranja, 14=negro, 15=blanco.
- Tiles 4-7 ("mitad B"): el mismo dibujo con OTROS indices (1=blanco,
  5=negro, 9=naranja, 15=amarillo) y el fondo de hierba horneado en el
  propio tile (indice 10 + una sombra sutil en 14).

Colores reales en juego (medidos casando VRAM contra un pantallazo, match
100%): amarillo (238,238,102), naranja (238,170,0), negro (34,34,34),
blanco (238,238,238), verde del fondo (170,204,68). La CRAM es fija: no se
pueden inventar colores nuevos solo tocando estos bloques.

## Como editarla

Los PNG de `sprite_gfx_out/sprite_0d*.png` (32x16) son los que reinserta el
paso "inserting sprite-mosaic graphics" del pipeline `i`, pero llevan los
tiles en el orden interno del SDK (column-major por sprite) y las dos
mitades por separado: editarlos a mano es incomodo y facil de romper.

Flujo comodo (herramientas de Antxiko, fuera de este repo; se aportan si
interesan): se edita UN PNG de 16x16 por pose en orden visual normal con
los 4 colores + alpha, y un exportador (`moneda_export.py`) genera las dos
mitades, el reorden column-major y las copias duplicadas en
`sprite_gfx_out/`; despues, pipeline `i` normal.

Para verificarlo en emulador sin jugar hasta encontrar una moneda: hace
falta un savestate con una moneda EN PANTALLA (desaparecen a los segundos
de soltarse; guardar el state antes de recogerla). Al recargar el state la
animacion refresca los tiles desde la ROM en pocos frames.

## El icono del contador del HUD

El icono de la moneda que sale arriba (junto al contador) es aparte: vive en
la hoja comprimida LZ `gfx_out/gfx_0f2000.png` (bloque ROM `0xf2000`, con
paleta real conocida), que se carga 1:1 en VRAM. El icono son los tiles
236 (arriba-izq), 237 (abajo-izq), 238 (arriba-dcha) y 239 (abajo-dcha) de
esa hoja; en el PNG estan seguidos en la fila 14 (x=96..128, y=112..120).
Los tiles 240-243 de al lado son el icono de la fruta roja del HUD.

Se edita el PNG y el paso "recompressing and inserting graphics" del
pipeline `i` lo reinserta (recolocando el bloque si crece). Para verlo en
juego desde un savestate hay que forzar la recarga del HUD: abrir y cerrar
el menu (Start) basta.

## Como se encontro (metodo reutilizable para otros sprites)

1. Savestate con el sprite en pantalla y volcado de VRAM por Lua.
2. Diff de VRAM entre varios instantes: solo cambian los tiles animados
   (la banda DMA de sprites, aqui v736-767).
3. Busqueda literal de esos tiles en la ROM (los sprites de Soleil van en
   bloques raw sin comprimir, que gfx_out/ no cataloga).
4. IMPORTANTE: volcar la banda en CADA frame durante un ciclo entero y
   casar cada frame contra TODAS las apariciones en la ROM (`find` en
   bucle, no solo la primera): los bloques duplicados ocultan copias que
   el juego lee de otra direccion.
