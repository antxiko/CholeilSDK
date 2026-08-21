# EL CARTEL DE CHOLEIL
### Los carteles de ciudad del mapa del mundo: cómo cambiarlos sin que explote nada
*Basada en hechos reales de 68000. Ninguna letra fue dañada permanentemente durante el rodaje.*

---

## LO QUE HAS VENIDO A BUSCAR: cambiar el nombre de un sitio

Los 21 nombres de ciudad viven en `script.txt`, bloques `room=0 npc=3 str=0..20`.
Se edita el nombre, se ejecuta el pipeline (`i`), y **la caja del cartel se recalcula sola**.
No hay que tocar tablas, ni código, ni saber dónde está nada.

**La regla de sastrería** (la caja la eliges con cómo escribes el nombre):

| Escribes en script.txt | Resultado en pantalla |
|---|---|
| `Nombre` a pelo (total IMPAR de letras) | caja mínima (len+1) con el texto centrado fino (4px de aire por lado) |
| ` Nombre ` con un espacio a cada lado (total PAR) | caja len+2, un tile entero (8px) de aire por lado |

**Reglas de oro:**
1. Después del nombre, **UNA sola línea vacía** antes del siguiente `====`. Con dos líneas
   vacías el nombre termina en `FE` (salto de línea) y el tile de redondeo de los impares
   pierde su relleno blanco: puede salir un carácter fantasma al final del cartel.
   (El inserter avisa con un WARNING si detecta esto.)
2. Los espacios laterales son sagrados: son el margen del cartel. No los recorte tu editor.
3. Cada letra ocupa 1 tile (8px) fijo — la `i` y la `l` ocupan lo mismo que la `m`. Un
   nombre de N letras necesita N tiles; las cajas son SIEMPRE pares (el motor dibuja por
   parejas de tiles), de ahí la regla par/impar.
4. Tras cada build, el paso `=== fixing map balloon widths ===` imprime **todos los nombres
   con sus medidas** (letras, caja vieja → nueva, tipo de centrado). Esa lista es tu verdad.
5. Nombres muy largos: a partir de ~18 tiles de caja el cartel roza el límite de sprites por
   línea de la consola. "Playa Benalmádena" (17 letras, caja 18) funciona; no os vengáis
   mucho más arriba.

**El reparto actual** (build de 2026-08-21):

| town | nombre | letras | caja | centrado |
|---|---|---|---|---|
| 1 | Choleil | 7 | 6 → 8 | fino (+4px) |
| 2 | ␣Rabesa␣ | 6+2 | 8 → 8 | margen en datos |
| 3 | Valle Baila | 11 | 12 → 12 | fino (+4px) |
| 4 | Playa Benalmádena | 17 | 14 → 18 | fino (+4px) |
| 7 | Córdoba | 7 | 10 → 8 | fino (+4px) |
| 8 | Ibi | 3 | 4 → 4 | fino (+4px) |
| 12 | Desierto Tabernas | 17 | 16 → 18 | fino (+4px) |
| 15 | ␣Torre de Papel␣ | 14+2 | 14 → 16 | margen en datos |
| 16 | Castillo Calipo | 15 | 14 → 16 | fino (+4px) |
| 17 | ␣Guadalpark␣ | 10+2 | 12 → 12 | margen en datos |
| 18 | Amapola | 7 | 8 → 8 | fino (+4px) |

Los towns 0, 5, 6, 9, 10, 11, 13, 14, 19 y 20 (Cabo Benalmádena, Nenúfar, Pueblo de Ibi,
Soria, Rosa Mágica, Palacio Tabernas, San Cielo, Barrio La Mina…) **no tienen cartel en el
mapa**: sus marcadores vienen apagados de fábrica (globo en 0,0) por el propio juego. Sus
nombres siguen vivos para la pantalla de ficheros de guardado.

---

## LA PELÍCULA: cómo llegamos hasta aquí

> *Llevaba décadas cortando nombres. Hasta que alguien miró la tabla.*

### PRÓLOGO — La ciudad que perdió su ele
El cartel del mapa escupía «Cholei». Dos agentes cayeron antes de empezar: el commit
`45d623a` (forzar el cursor de VRAM — desincronizaba glifos, pintaba basura) y el commit
`f8ba133` (ensanchar una caja… la de la pantalla de FICHEROS, a kilómetros de la escena del
crimen). Ambos revertidos. Y un dato clave en la autopsia: los datos estaban limpios — las
7 letras de «Choleil» estaban enteras en `0x1c0372`. El TextInserter nunca fue sospechoso.

### ACTO I — La autopsia 💥
El texto del mapa lo dibuja la rutina común `0x3314`, y dibuja TODOS los caracteres. El
corte era del **cartel**: la tabla de marcadores en ROM `0x35c6` (registros de 0x16 bytes:
casilla del cursor +0/+2, townId +0xa, **ancho del cartel en tiles +0xc**, posición del
globo +0x10/+0x12) lleva los anchos grabados a fuego con las longitudes de los nombres
**originales de 1994**. «Soleil» = 6. «Choleil» = 7. KA-BOOM: el motor enseña exactamente
W tiles, y W decía 6.

El cartel se monta con piezas de sprite de 16px — dos letras por pieza, tapa izquierda,
centrales, tapa derecha espejada — así que los anchos son **pares sí o sí** (`asr #1`:
un 9 pinta igual que un 8).

### ACTO II — Los fantasmas de la VRAM 👻
Al dar aire a las cajas aparecieron letras fantasma («Choleil a», «Valle Baila h»): el
cartel enseña la ventana de glifos tal cual, y todo tile sin glifo subido EN ESTA
regeneración es un solar okupado por restos del nombre anterior. Contramedida: los márgenes
van en los DATOS (espacios reales), y para el tile de redondeo de los impares, el as en la
manga del propio motor: una cadena terminada en `FF` sin `FE` delante hace que el handler
de `0x3380` suba **un glifo blanco de cortesía** en la misma línea. Relleno gratis firmado
por el propio juego. (De ahí la regla de oro nº 1.)

### ACTO III — El topo 🕵️
Para centrar los impares hacía falta pasarle la paridad al dibujador. Había un campo
"muerto": el `+4` de cada entrada de globo se lee a `d5`… y `d5` se pisa sin usarse.
KA-BOOM nº 2: **era un topo**. Ese campo lleva `$d806` = el id del nodo actual del mapa
(`0x209e`), y es lo que decide qué cartel sobrevive cuando el pájaro aterriza. Al
robárselo, el cartel del pueblo moría en cada aterrizaje (en vuelo perfecto, al posarse:
nada). Lección grabada a fuego: **un load muerto no autoriza a reescribir el store** —
busca TODOS los lectores del campo, no solo el visible. La paridad encontró un canal
limpio: el **bit 0 del ancho** en la propia tabla (par por naturaleza, el `asr #1` lo
descarta solo). Contrabando perfecto.

### ACTO IV — El villano final: la prioridad de sprites 🎬
Con el desplazamiento de medio tile, las letras anchas perdían su mitad derecha («Chcleil»,
«Valle Faila») y las flacas (i, l, e) sobrevivían. Patrón de bala inconfundible: en Mega
Drive, entre sprites solapados manda el orden de la tabla — lo emitido después queda
DELANTE — y el fondo de la pieza vecina, emitido después, tapaba los últimos 4px del texto
desplazado.

Y aquí el giro de guión, cortesía del director: *«te va a parecer una locura, pero ¿y si
imprimimos de derecha a izquierda?»*. No era una locura: era la solución canónica. Emitiendo
las piezas de derecha a izquierda, lo posterior queda siempre a la IZQUIERDA y el
desplazamiento ya no tiene a nadie delante que lo tape. Se reescribió el dibujador entero:
236 bytes de 68000 ensamblados a mano. Cero letras tapadas. Centrado exacto.

### CLÍMAX — La build final
«Choleil» 7 letras íntegras centradas en caja de 8. «Valle Baila» en 12 con la B con sus
dos barrigas. «Rabesa» en 8 con su margen 1+1 (*de puta madre*, según dirección). Verificado
en BizHawk con savestate: el cartel del pueblo sobrevive al aterrizaje, y el diálogo ni se
enteró (la rutina común `0x3314` no se tocó).

---

## FICHA TÉCNICA (por si alguien toca el código)

Todo lo aplica **`MapBalloonInserter`** (paso del pipeline `i`, corre tras TextInserter).
Lee los nombres ya colocados en la ROM de salida, recalcula cada ancho de la tabla `0x35c6`
y aplica tres parches de código **verificando byte a byte los originales** antes de
escribir (si la ROM no es la esperada, aborta el build). El detalle completo, con el porqué
de cada byte, está en el javadoc de la clase.

| Dónde | Qué |
|---|---|
| `0x35c6` +0xc | ancho por marcador = `(len+1) & ~1`, con el **bit 0** = flag de nombre impar |
| `0x315a` | consumidor reescrito en sus mismos 22 bytes: fuera el load muerto a `d5`, y `move.w d7,$d97e.w` guarda ancho\|paridad antes del `asr` (el campo +4 queda intacto: es del juego) |
| `0x1fffe0` | thunk de 16 bytes (cola FF de la ROM): las 3 `jsr $19528` del texto pasan por él — `btst #0,$d97f` → `addq #4,d2` si el nombre es impar |
| `0x0419b0` | el dibujador RTL de 236 bytes (hueco de CEROS de 1190 bytes en `0x041969`, invisible para FreeSpaceScanner, que solo lista huecos de FF ≥ 500) — `jmp` desde `0x317e`; piezas de derecha a izquierda; attr con wrap `[0x540,0x600)` avanzado a la última pieza y decrementado con unwrap; `d5=1` preservado para el texto de la tapa izquierda |

**RAM usada:** `$FFD97E` (word), verificada libre — el patrón `d97e` solo aparece en
offsets impares de la ROM, nunca como operando de instrucción.

---

*CholeilSDK Pictures presenta. Dirección y giro de guión del tercer acto: Antxiko.
Efectos especiales: MapBalloonInserter.java. Especialistas: BizHawk 2.11.1, capstone 5.0.7,
javac a pelo sin Maven. La maziza: SEGA Mega Drive, 64KB de VRAM de puro músculo.
In memoriam: Choleil_d4fix.md (2026–2026), un no-op que murió por nuestros pecados.*

**FIN**
