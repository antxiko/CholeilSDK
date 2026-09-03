# Iconos SAVE y TAKE OFF del menu de pausa

El archivo comodo para editar es:

`pause_gfx_out/iconos_pausa_EDITAME.png`

Mide 72x24 y contiene tres sprites de 24x24, de izquierda a derecha:

1. `SAVE`.
2. Primer estado/copia de `TAKE OFF`.
3. Segundo estado/copia de `TAKE OFF`.

Las dos copias de `TAKE OFF` no son iguales byte a byte, por lo que se exponen
por separado y hay que revisar las dos al hacer un rediseño. El magenta es el
indice transparente del sprite. Hay que conservar exactamente las dimensiones
y utilizar los colores que ya contiene el PNG.

`pause_gfx_out/iconos_pausa_x4_VISTA.png` es solo una ampliacion para mirar;
no se reinserta y no debe editarse.

## Donde estan

Los tres iconos estan comprimidos dentro del bloque LZ-Toshio de ROM
`0x0FD000`, que se descomprime en 6.048 bytes (189 tiles). El juego los carga
como sprites de hardware 3x3, con tiles en orden column-major:

| grafico | tiles dentro del bloque | tiles observados en VRAM |
|---------|--------------------------|--------------------------|
| SAVE | 0-8 | `0x180-0x188` |
| TAKE OFF A | 9-17 | `0x189-0x191` |
| TAKE OFF B | 18-26 | `0x192-0x19A` |

El marco azul que rodea la opcion seleccionada es otro sprite independiente de
4x4 tiles: aparece en VRAM `0x0E0-0x0EF`. Vive en otro bloque comprimido,
`0x0F3000`, empezando en su tile interno 224. No forma parte de
`iconos_pausa_EDITAME.png`.

La paleta real esta en ROM `0x000548` y ya estaba registrada para el bloque
`0x0FD000` en `known_palettes.txt`.

## Como reinsertarlos

El pipeline `x` genera el EDITAME y su vista ampliada. Antes de recomprimir los
graficos, el pipeline `i` copia la edicion a los tiles 0-26 de
`gfx_out/gfx_0fd000.png`; el resto de los 189 tiles queda intacto. Despues el
`GraphicsInserter` se encarga de comprimir y reinsertar el bloque, reubicandolo
si el dibujo editado hace que crezca.

Para comprobarlo rapidamente en BizHawk, se puede cargar el snapshot del slot 3
con el menu de pausa abierto.
