# Manzanas normales y marcadores de vida

La manzana roja fija del mapa se localizo usando el snapshot 3. Ahora hay dos
editores normales separados:

- `apple_gfx_out/manzana_roja_EDITAME.png`
- `apple_gfx_out/manzana_verde_EDITAME.png`

Los dos miden 16x16. El magenta es transparente y hay que conservar exactamente
las dimensiones. Los archivos `*_x8_VISTA.png` son solo ampliaciones para verlos
mejor y no se reinsertan. El antiguo `manzana_mapa_EDITAME.png` queda como copia
de seguridad de la primera prueba, pero el pipeline ya no lo lee.

## Copias confirmadas

El dibujo capturado ocupa cuatro tiles consecutivos, en orden de lectura
normal (arriba izquierda, arriba derecha, abajo izquierda, abajo derecha).
Esta duplicado en dos tilesets LZ-Toshio:

| bloque ROM | tiles internos | tamano descomprimido |
|---|---:|---:|
| `0x135322` | 459-462 | 15.872 bytes (496 tiles) |
| `0x14D3AE` | 390-393 | 15.360 bytes (480 tiles) |

El pipeline `i` copia automaticamente `manzana_roja_EDITAME.png` a las dos
ubicaciones comprimidas antes de recomprimir los graficos. De ese modo las dos
zonas confirmadas del objeto colocado en mapa quedan sincronizadas.

Ademas, el cargador de esta familia copia de una vez `0x0F4600-0x0F47FF` a VRAM
`0x7F0-0x7FF`. Sus dos ultimas figuras son otras dos copias exactas de la
manzana roja, en `0x0F4700` y `0x0F4780`, almacenadas en orden column-major.
El pipeline copia `manzana_verde_EDITAME.png` solamente en estas dos posiciones
raw. Asi roja y verde dejan de sobrescribirse entre ellas. Son las posiciones
preparadas para objetos sprite, como las manzanas soltadas por enemigos.

La edicion inicial verde reutiliza los verdes ya presentes en la misma paleta.
No se modifica la logica: las manzanas siguen dando la misma vida y solo cambia
su aspecto.

## Que son las manzanas verdes

Las manzanas verdes no son objetos que haya que recoger. Son los iconos con los
que el juego representa la vida de los enemigos. Las rojas del marcador son la
vida del protagonista, y las doradas aumentan su vida maxima.

Los dibujos rojos y verdes del marcador tambien estan localizados en el bloque
`0x0F2000`: el grupo rojo ocupa los tiles 144-159 y el verde los tiles 160-175.
Son graficos de 8x8 repetidos para formar el indicador, no el objeto 16x16 que
aparece colocado en el mapa.

## Alcance confirmado

Quedan localizados el objeto rojo del mapa, las dos posiciones raw separadas
para la verde y los iconos rojos/verdes del marcador. Una manzana soltada por un
enemigo aun no se ha capturado en BizHawk; falta confirmar visualmente que esas
posiciones raw son las que emplea ese caso concreto.

## Manzana dorada: editor independiente

El snapshot 5, grabado en Choleil con la dorada junto al agua, permitio seguir
la entrada real de la tabla de sprites. Es un sprite de 24x24 (3x3 tiles) que
alterna cada ocho frames entre:

| fotograma | tiles VRAM | fuente descomprimida |
|---|---:|---:|
| brillo arriba | `0x4D4-0x4DC` | bloque `0x0A5644`, tiles 9-17 |
| brillo izquierda | `0x4E6-0x4EE` | bloque `0x0A5644`, tiles 27-35 |

La busqueda byte a byte de los 288 bytes de cada fotograma no encuentra una
copia raw en la ROM. Al descomprimir todos los bloques catalogados, ambos
aparecen exactamente una vez y en esas posiciones.

El archivo comodo para editar los DOS fotogramas es:

`apple_gfx_out/manzana_dorada_EDITAME.png`

Mide 48x24: dos figuras de 24x24, una al lado de la otra. El magenta es
transparente y no se deben cambiar las dimensiones. La ampliacion
`manzana_dorada_x8_VISTA.png` es solo de consulta y no se reinserta. El pipeline
`i` coloca automaticamente ambas figuras en `gfx_out/gfx_0a5644.png` antes de
recomprimir el bloque.

Esto separa la dorada confirmada de los PNG de la moneda. El grafico de
`0x0F4680` fue una falsa identificacion: se carga con el paquete de objetos y
se parece a lo que hay en pantalla, pero la tabla de sprites demuestra que la
dorada visible no lo referencia.

La prueba final se hizo recargando la sala del snapshot 5 para obligar a
BizHawk a leer los graficos de la ROM reconstruida; la dorada volvio a aparecer
correctamente. El editor queda verificado byte a byte para los dos fotogramas.

Este resultado cubre la variante observada en Choleil. No se afirma aun que
una variante de otra subzona use el mismo bloque: si aparece distinta, se
seguira su entrada de sprite y se añadira como otra copia al mismo editor.
