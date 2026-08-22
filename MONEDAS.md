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

ACTUALIZACION: el SDK ya entiende este orden de tiles. `sprite_graphics.txt`
acepta un sexto campo, `rowmajor`, y los ocho bloques de la moneda lo llevan,
asi que los PNG de `sprite_gfx_out/sprite_0d*.png` salen ya con el dibujo
bien puesto (32x16: la pose en version sprite y la misma con la hierba
horneada) y se pueden editar directamente. Ya no hace falta el reorden
column-major del exportador.

Lo que SI sigue haciendo falta: editar las dos mitades de cada bloque de
forma coherente, y editar las cuatro copias de cada pose. El exportador de
Antxiko (`moneda_export.py`, fuera de este repo) sigue siendo comodo para
eso, partiendo de UN PNG de 16x16 por pose.

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

## Y no eran ocho bloques: eran once

Despues de meter el dibujo nuevo, la moneda salia bien en una zona y con el
dibujo viejo en otras. La busqueda literal encuentra copias EXACTAS, pero hay
copias con unos pocos nibbles cambiados que se cuelan. Repitiendo la busqueda
en difuso (misma pose, tolerando nibbles distintos, y en los DOS ordenes de
tiles) aparecen tres sitios mas. La pose de cara existe en la ROM en siete
sitios exactamente:

| donde                      | orden    | arte                                  |
|----------------------------|----------|---------------------------------------|
| d1760, d1b60, d2760, d2b60 | rowmajor | las de siempre                        |
| f4680                      | colmajor | identica; va con f4600 (canto) en un   |
|                            |          | unico bloque de 256 bytes             |
| d6f20, d7320               | rowmajor | igual + sombra: 16 nibbles que arriba  |
|                            |          | son transparentes (0) aqui son el 11  |

Y del canto hay copias en d6d20, d7120, e4660 y e4760 (estas dos con 6 nibbles
de diferencia, otro sombreado). El canto no se rediseño, asi que esas ya
estaban bien.

Todas estan ya en `sprite_graphics.txt` y las tres caras que faltaban llevan el
dibujo nuevo: f4680 y d7320 con el diseño 1, d6f20 con el diseño 2 (mismo orden
por direccion que la familia original). La sombra se vuelve a aplicar encima del
dibujo nuevo -- los 16 pixeles siguen siendo transparentes en el diseño nuevo,
asi que entra limpia. Si el ciclo real de esas zonas pide otro reparto de
diseños, se cambia editando los PNG.

Hay un test (`SpriteGraphicsTest`) que busca la pose de cara por toda la ROM y
falla si aparece en algun sitio que no este registrado.
