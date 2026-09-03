#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ScorpioN-MsX — Inyector de intro para ROMs de Mega Drive / Genesis
=======================================================================

Pega una intro ya compilada (ROM .bin independiente) delante de un juego,
de forma que al encender la consola se vea primero la intro y luego arranque
el juego original.

Cómo funciona
-------------
El juego se queda en 0x000000 (su código usa direcciones absolutas y NO se
puede mover). La intro se copia a una dirección alta libre del mapa de
cartucho (por defecto 0x300000) y se le "reubican" (rebase) las pocas
direcciones absolutas que contiene su código 68000. Después:

  * el vector de RESET del juego (0x000004) apunta a un stub nuestro,
    alojado en la tabla de vectores muerta de la intro,
  * el stub desbloquea el TMSS, inicializa el contador y salta a la intro,
  * el bucle final de la intro pasa por nuestro comprobador: sale al pulsar
    START (o A/B/C) o al agotarse la espera,
  * al salir se silencia PSG/YM2612, se resetea el Z80, se apaga la pantalla,
    se limpian los registros de control de mandos (para que el juego haga su
    arranque en frío completo) y se salta al punto de entrada original.

Uso
---
  python3 insertar_intro.py juego.md intro.bin -o juego_intro.md
  python3 insertar_intro.py juego.md intro.bin --espera 300 --base 0x300000
  python3 insertar_intro.py juego.md intro.bin --espera 0        # solo START
"""

import argparse, struct, sys, os, hashlib

# ---------------------------------------------------------------------------
# Perfiles de intros conocidas
# ---------------------------------------------------------------------------
# Para cada intro: las direcciones absolutas de su código 68000 que hay que
# reubicar al moverla de 0x000000 a BASE (offset -> valor original), dónde está
# su bucle final y cuánto conviene esperar en él antes de entrar al juego.
PERFILES = [
    {
        'nombre':  'Charnego Translations INTRO (XGM)',
        'tam':     622118,
        'entrada': 0x00200,          # punto de entrada de la intro
        'bucle':   0x002DA,          # inicio del bucle final (vsync + XGM)
        'bra':     0x002E4,          # el "bra.w bucle" que lo cierra
        'vsync':   0x0038A,          # rutina de espera de vblank de la intro
        'subir':   0x002E8,          # rutina que vuelca un fotograma a la VRAM
        'espera':  300,              # frames de cortesía por defecto (~5 s)
        # troceado para el modo comprimido: (offset, tamaño)
        'codigo':  (0x00200, 0x001DE),   # código + paletas
        'frames':  (0x003DE, 0x08C00, 16),
        'pcm':     (0x8C400, 0x09E92),   # muestra PCM (alineada a 256)
        'vacio':   (0x96300, 0x00100),   # muestra vacía (alineada a 256)
        'drvlib':  (0x96400, 0x01A26),   # driver Z80 + librería XGM (van juntos)
        'relocs': [
            (0x00226, 0x097BAE),     # jsr  XGM_init
            (0x0023E, 0x097D20),     # jsr  XGM_setPCM
            (0x00232, 0x08C400),     # puntero a los datos XGM (música + samples)
            (0x002A8, 0x097DB8),     # jsr  XGM_vblankProcess
            (0x002D0, 0x097D6C),     # jsr  XGM_playPCM
            (0x002E0, 0x097DB8),     # jsr  XGM_vblankProcess
            (0x97BC8, 0x096400),     # lea  driver Z80 XGM
            (0x97BE4, 0x096300),     # sample vacío por defecto
            (0x97C86, 0x096300),     # sample vacío por defecto
        ],
    },
    {
        'nombre':  'Charnego Translations INTRO FINAL (XGM, con fundido)',
        'tam':     622630,
        'entrada': 0x00200,
        'bucle':   0x002EA,
        'bra':     0x002EE,
        'vsync':   0x003FA,
        'subir':   0x00358,
        'espera':  60,               # ya trae su propia espera + fundido a negro
        'codigo':  (0x00200, 0x003CE),
        'frames':  (0x005CE, 0x08C00, 16),
        'pcm':     (0x8C600, 0x09E92),
        'vacio':   (0x96500, 0x00100),
        'drvlib':  (0x96600, 0x01A26),
        'relocs': [
            (0x00226, 0x097DAE),     # jsr  XGM_init
            (0x0023E, 0x097F20),     # jsr  XGM_setPCM
            (0x00232, 0x08C600),     # puntero a los datos XGM (música + samples)
            (0x002CA, 0x097F6C),     # jsr  XGM_playPCM
            (0x002DE, 0x097FB8),     # jsr  XGM_vblankProcess
            (0x97DC8, 0x096600),     # lea  driver Z80 XGM
            (0x97DE4, 0x096500),     # sample vacío por defecto
            (0x97E86, 0x096500),     # sample vacío por defecto
        ],
    },
]
BRA_W = 0x6000

# ---------------------------------------------------------------------------
# Juegos con parches propios (quitar la pantalla del logo del arranque)
# ---------------------------------------------------------------------------
# Cada juego se identifica por su código de producto y por los bytes que se van
# a tocar (si no coinciden, la ROM no es la esperada y no se toca nada).
#   'parches' : bytes a escribir para saltarse el logo
#   'libres'  : trozos que quedan sin usar y se pueden reaprovechar con --huecos
JUEGOS = [
    {
        'nombre': 'Soleil / Choleil',
        'serie':  b'GM MK-01182-00',
        # El arranque es una máquina de estados: el estado 0 (rutina en 0x0CC4)
        # enseña el logo dos segundos y pasa al estado 6. Apuntando la entrada 0
        # de la tabla de saltos al estado 6, el logo no sale nunca -ni al
        # arrancar ni al volver desde el juego- y sus gráficos quedan libres.
        'comprobar': [(0x000460, bytes.fromhex('00000CC4'))],
        'parches':   [(0x000460, bytes.fromhex('00036D98'))],
        'libres':    [(0x000E36, 1632)],     # gráficos del logo
        'nota': 'estado 0 de la tabla de arranque -> estado 6',
        # Huecos comprobados: rachas largas de relleno donde, además, el juego
        # no lee NI UNA palabra en dos partidas largas grabadas con el emulador
        # instrumentado (arranque, menús, mapa y diálogos). Total ~246 KB.
        'huecos': [
            (0x0ACF08, 143608), (0x0E4FE0, 45088), (0x11BF6A, 16534),
            (0x1BE040,   8128), (0x1E6785,  6267), (0x113AC2,  5212),
            (0x1ED0FB,   3845), (0x04F260,  3488), (0x0532E8,  3352),
            (0x1F63AA,   3158), (0x1D7419,  3047), (0x0FE7A4,  2140),
            (0x043C00,   2048), (0x15DA3C,  1476), (0x1EBACC,  1332),
            (0x1F7AFD,   1283), (0x0F5B00,  1280), (0x0F2B24,  1244),
        ],
        # (0x03CE78 parece un hueco de 12 KB pero sus 8 primeros bytes se leen:
        #  es una tabla que empieza justo ahí. Queda fuera.)
    },
    {
        'nombre': 'Tecmo Atleti 2.0',
        'serie':  b'T-61013-00',
        # El logo es un bloque lineal (paleta, tiles, mapa, fundido y esperas)
        # que acaba saltando a 0x964E, y que el propio juego se salta con un
        # "bcs 0x964E" si pulsas un botón durante las esperas. Se pone ese mismo
        # salto al principio del bloque: bra.w 0x964E.
        'comprobar': [(0x008ECA, bytes.fromhex('43F900008F88'))],
        'parches':   [(0x008ECA, bytes.fromhex('60000782'))],
        'libres':    [],   # sobra sitio en esta ROM: no hace falta reaprovecharlo
        'nota': 'salta el bloque del logo, como hace el juego al pulsar un botón',
        # Media ROM libre al final. Los otros dos "huecos" que parecían libres
        # (0x02DAB4 y 0x04C696) SÍ los lee el juego: son ceros dentro de bloques
        # de gráficos, y usarlos rompía los gráficos de los partidos.
        'huecos': [(0x081CC8, 516920)],
    },
]


def detectar_juego(juego):
    for j in JUEGOS:
        if juego[0x180:0x180 + len(j['serie'])] != j['serie']:
            continue
        if all(juego[o:o + len(b)] == b for o, b in j['comprobar']):
            return j
    return None


def quitar_logo(salida, juego_info):
    for off, datos in juego_info['parches']:
        salida[off:off + len(datos)] = datos


# El stub se aloja en la tabla de vectores de la intro, que queda muerta
# cuando la intro no arranca desde 0x000000.
STUB_OFF = 0x0008


def detectar_perfil(intro):
    """Identifica la intro comparando sus direcciones absolutas y su bucle final."""
    for pf in PERFILES:
        if len(intro) < pf['relocs'][-1][0] + 4:
            continue
        if all(struct.unpack_from('>I', intro, off)[0] == val for off, val in pf['relocs']) \
           and struct.unpack_from('>H', intro, pf['bra'])[0] == BRA_W \
           and struct.unpack_from('>h', intro, pf['bra'] + 2)[0] == pf['bucle'] - pf['bra'] - 2:
            return pf
    return None


# ---------------------------------------------------------------------------
# Mini-ensamblador 68000 (solo lo que necesita el stub)
# ---------------------------------------------------------------------------
class Asm:
    def __init__(self, base):
        self.base = base          # direccion absoluta del primer byte
        self.buf  = bytearray()
        self.fix  = []            # parches de saltos pendientes
        self.lbl  = {}

    # -- utilidades -------------------------------------------------------
    def pc(self):            return self.base + len(self.buf)
    def w(self, v):          self.buf += struct.pack('>H', v & 0xFFFF)
    def l(self, v):          self.buf += struct.pack('>I', v & 0xFFFFFFFF)
    def label(self, n):      self.lbl[n] = self.pc()

    # -- instrucciones ----------------------------------------------------
    def move_w_sr(self, v):          self.w(0x46FC); self.w(v)          # move.w #v,sr
    def move_b_imm_abs(self, v, a):  self.w(0x13FC); self.w(v & 0xFF); self.l(a)
    def move_w_imm_abs(self, v, a):  self.w(0x33FC); self.w(v); self.l(a)
    def move_l_imm_abs(self, v, a):  self.w(0x23FC); self.l(v); self.l(a)
    def move_b_abs_d0(self, a):      self.w(0x1039); self.l(a)          # move.b (a).l,d0
    def move_w_abs_d0(self, a):      self.w(0x3039); self.l(a)          # move.w (a).l,d0
    def andi_b_d0(self, v):          self.w(0x0200); self.w(v & 0xFF)
    def clr_l_abs(self, a):          self.w(0x42B9); self.l(a)
    def clr_w_abs(self, a):          self.w(0x4279); self.l(a)
    def addq_w_abs(self, n, a):      self.w(0x5079 | ((n & 7) << 9)); self.l(a)
    def cmpi_w_abs(self, v, a):      self.w(0x0C79); self.w(v); self.l(a)
    def btst_imm_d0(self, b):        self.w(0x0800); self.w(b)          # btst #b,d0
    def btst_imm_abs(self, b, a):    self.w(0x0839); self.w(b); self.l(a)
    def movea_l_imm_a7(self, v):     self.w(0x2E7C); self.l(v)          # movea.l #v,a7
    def jmp_abs(self, a):            self.w(0x4EF9); self.l(a)
    def nop(self):                   self.w(0x4E71)
    def moveq(self, v, reg):         self.w(0x7000 | (reg << 9) | (v & 0xFF))
    def move_w_imm_dn(self, v, r):   self.w(0x303C | (r << 9)); self.w(v)
    def lea_abs(self, a, reg):       self.w(0x41F9 | (reg << 9)); self.l(a)
    def move_w_inc_inc(self):        self.w(0x32D8)              # move.w (a0)+,(a1)+
    def move_l_imm_dn(self, v, r):   self.w(0x203C | (r << 9)); self.l(v)
    def move_w_dn_abs(self, r, a):   self.w(0x33C0 | r); self.l(a)
    def subq_l_dn(self, n, r):       self.w(0x5180 | ((n & 7) << 9) | r)
    def move_b_inc_abs(self, a):     self.w(0x13D8); self.l(a)   # move.b (a0)+,(a).l
    def move_w_abs_dn(self, a, r):   self.w(0x3039 | (r << 9)); self.l(a)
    def move_w_inc_dn(self, r):      self.w(0x301B | (r << 9))   # move.w (a3)+,dn
    def move_w_inc_ind_a1(self):     self.w(0x329B)              # move.w (a3)+,(a1)
    def move_w_dn_ind_a1(self, r):   self.w(0x3280 | r)          # move.w dn,(a1)
    def lsl_w_imm(self, n, r):       self.w(0xE148 | ((n & 7) << 9) | r)
    def neg_w(self, r):              self.w(0x4440 | r)
    def subq_w_dn(self, n, r):       self.w(0x5140 | ((n & 7) << 9) | r)
    def movea_l_idx_a0(self, r, dst):    # movea.l (0,a0,dn.w),An
        self.w(0x2070 | (dst << 9)); self.w((r << 12))
    def bmi(self, t):  self._br(0x6B00, t)
    def db(self, datos):             self.buf += bytes(datos)

    def lea_pc_a0(self, target):     # lea etiqueta(pc),a0
        self.fix.append((len(self.buf), target)); self.w(0x41FA); self.w(0)
    def dbra(self, reg, target):
        self.fix.append((len(self.buf), target)); self.w(0x51C8 | reg); self.w(0)
    def dbra_d1(self, target):       self.dbra(1, target)
    def rts(self):                   self.w(0x4E75)

    def retardo(self, vueltas, reg=2):
        """Espera fija. En hardware NO se puede sondear el flag de ocupado del
        YM2612 (se queda a 1 en cuanto el Z80 entra en reset), así que se espera
        a ciegas."""
        self.move_w_imm_dn(vueltas, reg)
        et = 'ret%d' % len(self.buf); self.label(et); self.dbra(reg, et)

    # saltos con etiqueta (se resuelven en link())
    def _br(self, opw, target):
        self.fix.append((len(self.buf), target)); self.w(opw); self.w(0)
    def bra(self, t):  self._br(0x6000, t)
    def bsr(self, t):  self._br(0x6100, t)
    def beq(self, t):  self._br(0x6700, t)
    def bne(self, t):  self._br(0x6600, t)
    def bcc(self, t):  self._br(0x6400, t)

    def link(self, extra=None):
        tabla = dict(self.lbl); tabla.update(extra or {})
        for pos, t in self.fix:
            dst = tabla[t] if isinstance(t, str) else t
            disp = dst - (self.base + pos + 2)
            if not (-32768 <= disp <= 32767):
                raise ValueError("salto fuera de rango: %s (%d)" % (t, disp))
            struct.pack_into('>h', self.buf, pos + 2, disp)
        return bytes(self.buf)


# Colores del modo diagnóstico, en el orden en que deberían verse.
DIAG = [
    (0x000E, 'rojo',     'ha empezado la salida de la intro'),
    (0x00EE, 'amarillo', 'pantalla apagada (VDP)'),
    (0x00E0, 'verde',    'PSG silenciado'),
    (0x0EE0, 'cian',     'bus del Z80 concedido'),
    (0x0E00, 'azul',     'YM2612 silenciado'),
    (0x0E0E, 'magenta',  'Z80 en reset y bus liberado'),
    (0x0EEE, 'blanco',   'epílogo ejecutándose ya desde la RAM'),
    (0x0666, 'gris',     'SRAM remapeada; saltando al juego'),
]


def _diag_inline(a, color, vdp_ctrl, vdp_data):
    """Pinta el fondo de un color y espera, sin llamar a nada (vale en RAM)."""
    a.move_l_imm_abs(0xC0000000, vdp_ctrl)   # escribir en CRAM, entrada 0
    a.move_w_imm_abs(color, vdp_data)
    a.move_l_imm_dn(0x60000, 3)
    et = 'dw%d' % len(a.buf); a.label(et)
    a.subq_l_dn(1, 3); a.bne(et)


def construir_stub(dir_cerca, dir_lejos, dir_entrada, dir_bucle, juego_sp, juego_pc,
                   espera, cont_ram, salir_con_botones=True,
                   remapear_sram=False, diagnostico=False, tabla_frames=None):
    """Genera el stub en dos trozos:

      - "cerca": entrada, lectura de mando y contador. Va en la tabla de
        vectores de la intro (muerta al no arrancar desde 0), porque el bucle
        final de la intro solo puede saltar ahí con un bra.w (±32 KB).
      - "lejos": la salida al juego, con su tabla del YM y el epílogo. Se pega
        al final de la ROM, donde no hay límite de espacio.
    """
    VDP_CTRL, VDP_DATA     = 0xC00004, 0xC00000
    PSG                    = 0xC00011
    Z80_BUS, Z80_RST       = 0xA11100, 0xA11200
    YM_A0, YM_D0           = 0xA04000, 0xA04001
    IO_CTRL1, IO_DATA1     = 0xA10009, 0xA10003
    VER_REG                = 0xA10001
    EPI_RAM                = 0xFF0200        # el epílogo se copia y corre aquí

    def diag(asm, n):                        # marca visual de un paso
        if diagnostico:
            asm.move_w_imm_dn(DIAG[n][0], 3); asm.bsr('diag_rutina')

    # --- epílogo, que se ejecutará desde la RAM ---------------------------
    # Solo direccionamiento absoluto, así que funciona esté donde esté.
    e = Asm(0)
    if diagnostico:
        _diag_inline(e, DIAG[6][0], VDP_CTRL, VDP_DATA)
    if remapear_sram:
        # Al pasar de 2 MB la SRAM del juego (0x200000) deja de estar mapeada:
        # este registro del mapper oficial de Sega la devuelve a su sitio para
        # que las partidas guardadas sigan funcionando.
        e.move_b_imm_abs(0x01, 0xA130F1)
    if diagnostico:
        _diag_inline(e, DIAG[7][0], VDP_CTRL, VDP_DATA)
    e.clr_l_abs(0xA10008)                    # limpiar control de mandos: el juego
    e.clr_w_abs(0xA1000C)                    # tiene que ver un arranque en frío
    e.move_w_sr(0x2700)
    e.movea_l_imm_a7(juego_sp)
    e.jmp_abs(juego_pc)                      # -> juego original
    epilogo = e.link()

    # ---------------- salida al juego (trozo "lejos") ---------------------
    f = Asm(dir_lejos)
    f.label('salir')
    f.move_w_sr(0x2700)
    if diagnostico:
        f.move_w_imm_abs(0x8700, VDP_CTRL)   # fondo = entrada 0 de la paleta 0
    diag(f, 0)
    f.move_w_abs_d0(VDP_CTRL)                # leer estado: limpia el latch del VDP
    f.move_w_imm_abs(0x8004, VDP_CTRL)       # VDP reg0: sin interrupciones
    f.move_w_imm_abs(0x8104, VDP_CTRL)       # VDP reg1: pantalla apagada
    diag(f, 1)
    for v in (0x9F, 0xBF, 0xDF, 0xFF):       # silenciar PSG
        f.move_b_imm_abs(v, PSG)
    diag(f, 2)

    # El YM2612 se silencia ANTES de tocar el reset del Z80: esa línea de reset
    # apaga también el YM y corta el acceso del 68000 a su bus, así que después
    # ni se le puede escribir ni se puede leer su flag de ocupado (en hardware
    # ese bit se queda a 1 y la salida se colgaba ahí para siempre).
    f.move_w_imm_abs(0x0100, Z80_BUS)        # pedir el bus del Z80
    f.label('esperar_bus')
    f.btst_imm_abs(0, Z80_BUS)
    f.bne('esperar_bus')
    diag(f, 3)
    ym = (0x2B, 0x00, 0x27, 0x00,            # DAC off, timers off
          0x28, 0x00, 0x28, 0x01, 0x28, 0x02, # key-off de los 6 canales FM
          0x28, 0x04, 0x28, 0x05, 0x28, 0x06)
    f.lea_pc_a0('ym_tabla')
    f.moveq(len(ym) // 2 - 1, 1)
    f.label('ym_bucle')
    f.move_b_inc_abs(YM_A0)                  # registro
    f.retardo(24)                            # espera fija, sin leer el YM
    f.move_b_inc_abs(YM_D0)                  # valor
    f.retardo(24)
    f.dbra_d1('ym_bucle')
    diag(f, 4)

    f.move_w_imm_abs(0x0000, Z80_RST)        # Z80 (y con él el YM) a reset
    f.retardo(64)
    f.move_w_imm_abs(0x0000, Z80_BUS)        # soltar el bus
    diag(f, 5)

    # El epílogo se copia a la RAM y se ejecuta desde allí: al activar la SRAM
    # el cartucho puede tapar la ROM alta (en hardware real la SRAM se espeja
    # hasta 0x3FFFFF), y con ella este mismo código.
    f.lea_pc_a0('epilogo')
    f.lea_abs(EPI_RAM, 1)
    f.moveq(len(epilogo) // 2 - 1, 0)
    f.label('copiar')
    f.move_w_inc_inc()
    f.dbra(0, 'copiar')
    f.jmp_abs(EPI_RAM)                       # -> epílogo en RAM -> juego

    if diagnostico:                          # pinta el fondo del color de d3
        f.label('diag_rutina')
        f.move_l_imm_abs(0xC0000000, VDP_CTRL)
        f.move_w_dn_abs(3, VDP_DATA)
        f.move_l_imm_dn(0x60000, 3)
        f.label('diag_espera'); f.subq_l_dn(1, 3); f.bne('diag_espera')
        f.rts()

    if tabla_frames:
        # Sustituye a la rutina que volcaba un fotograma crudo a la VRAM: coge
        # el puntero del fotograma que toca de la tabla, lo descomprime y lo
        # escribe directamente en el puerto de datos del VDP (sin buffer).
        f.label('descomp')
        f.move_l_imm_abs(0x40000000, VDP_CTRL)   # escritura en VRAM, dirección 0
        f.lea_abs(VDP_DATA, 1)
        f.move_w_abs_dn(cont_ram + 4, 2)         # índice de fotograma
        f.addq_w_abs(1, cont_ram + 4)
        f.lsl_w_imm(2, 2)
        f.lea_pc_a0('frames_tabla')
        f.movea_l_idx_a0(2, 3)                   # movea.l (a0,d2.w),a3
        f.label('d_bucle')
        f.move_w_inc_dn(0)                       # n = move.w (a3)+,d0
        f.beq('d_fin')
        f.bmi('d_repe')
        f.subq_w_dn(1, 0)                        # n literales
        f.label('d_lit')
        f.move_w_inc_ind_a1()
        f.dbra(0, 'd_lit')
        f.bra('d_bucle')
        f.label('d_repe')
        f.neg_w(0)
        f.subq_w_dn(1, 0)
        f.move_w_inc_dn(1)                       # palabra a repetir
        f.label('d_rep')
        f.move_w_dn_ind_a1(1)
        f.dbra(0, 'd_rep')
        f.bra('d_bucle')
        f.label('d_fin')
        f.rts()

    f.label('ym_tabla')
    f.db(ym)
    f.label('epilogo')
    f.db(epilogo)
    if tabla_frames:
        f.label('frames_tabla')
        for dir_f in tabla_frames:
            f.l(dir_f)
    datos_lejos = f.link()

    # ---------------- trozo "cerca" (tabla de vectores) -------------------
    a = Asm(dir_cerca)

    # entrada (vector de RESET)
    a.label('entrada')
    a.move_w_sr(0x2700)
    a.move_b_abs_d0(VER_REG)          # ¿consola con TMSS?
    a.andi_b_d0(0x0F)
    a.beq('sin_tmss')
    a.move_l_imm_abs(0x53454741, 0xA14000)   # 'SEGA'
    a.label('sin_tmss')
    a.move_b_imm_abs(0x40, IO_CTRL1)  # mando 1: TH como salida
    a.move_b_imm_abs(0x40, IO_DATA1)
    a.clr_w_abs(cont_ram)             # contador de frames a 0
    if tabla_frames:
        a.clr_w_abs(cont_ram + 4)     # índice de fotograma a 0
    a.jmp_abs(dir_entrada)            # -> intro original

    # sustituto de la espera de vblank de la intro: se engancha aquí para poder
    # mirar el mando en CADA frame, no solo en el bucle final, y así START salta
    # la intro desde el principio.
    if salir_con_botones:
        a.label('vsync')
        a.move_b_imm_abs(0x00, IO_DATA1)     # TH=0 -> START y A
        a.nop(); a.nop(); a.nop(); a.nop()   # margen para que se asiente TH
        a.move_b_abs_d0(IO_DATA1)
        a.move_b_imm_abs(0x40, IO_DATA1)
        a.btst_imm_d0(5); a.beq('trampolin') # START (activo a 0)
        a.btst_imm_d0(4); a.beq('trampolin') # A
        a.label('vs1')                       # esperar a que acabe el vblank
        a.move_w_abs_d0(VDP_CTRL); a.btst_imm_d0(3); a.bne('vs1')
        a.label('vs2')                       # esperar al siguiente vblank
        a.move_w_abs_d0(VDP_CTRL); a.btst_imm_d0(3); a.beq('vs2')
        a.rts()

    # comprobación una vez por frame, en el bucle final de la intro
    a.label('comprobar')
    if espera > 0:
        a.addq_w_abs(1, cont_ram)
        a.cmpi_w_abs(espera, cont_ram)
        a.bcc('trampolin')                   # contador >= espera
    a.bra(dir_bucle)                         # seguir con la intro

    a.label('trampolin')                     # los bra.w no alcanzan el final de
    a.jmp_abs(f.lbl['salir'])                # la ROM: se salta con un jmp

    datos_cerca = a.link()
    etiquetas = dict(a.lbl); etiquetas.update(f.lbl)
    return datos_cerca, datos_lejos, etiquetas


def comprimir_rle(datos):
    """RLE por palabras. Formato, todo en palabras big-endian:
         n > 0  -> siguen n palabras literales
         n < 0  -> la siguiente palabra se repite -n veces
         n == 0 -> fin
    Se descomprime directamente al puerto de datos del VDP, sin buffer."""
    pal = list(struct.unpack('>%dH' % (len(datos) // 2), datos))
    out = bytearray(); i = 0; n = len(pal)
    while i < n:
        j = i
        while j + 1 < n and pal[j + 1] == pal[i] and j - i < 0x7FFE:
            j += 1
        if j - i >= 2:                        # racha de 3 o más
            out += struct.pack('>hH', -(j - i + 1), pal[i]); i = j + 1
        else:                                 # literales hasta la próxima racha
            k = i
            while k < n and k - i < 0x7FFE:
                if k + 2 < n and pal[k] == pal[k + 1] == pal[k + 2]:
                    break
                k += 1
            out += struct.pack('>h', k - i)
            out += struct.pack('>%dH' % (k - i), *pal[i:k])
            i = k
    out += struct.pack('>h', 0)
    return bytes(out)


def descomprimir_rle(datos):
    """Solo para comprobar el compresor desde Python."""
    out = bytearray(); i = 0
    while True:
        n, = struct.unpack_from('>h', datos, i); i += 2
        if n == 0: return bytes(out)
        if n > 0:
            out += datos[i:i + n * 2]; i += n * 2
        else:
            out += datos[i:i + 2] * (-n); i += 2


def huecos_libres(rom, minimo=1024):
    """Rachas largas del mismo byte: candidatas a espacio libre."""
    fuera = []; i = 0; n = len(rom)
    while i < n:
        j = i + 1
        while j < n and rom[j] == rom[i]: j += 1
        if j - i >= minimo: fuera.append((i, j - i, rom[i]))
        i = j
    return fuera


class Colocador:
    """Reparte bloques por los huecos libres y, si no caben, al final."""
    def __init__(self, tam_juego, huecos, inicio_extra):
        self.libres = sorted([[o, t] for o, t, _ in huecos])
        self.extra = inicio_extra
        self.fin = tam_juego          # solo crece si algo no cabe en los huecos
        self.en_hueco = 0

    def colocar(self, tam, alin=2):
        for r in self.libres:
            ini = (r[0] + alin - 1) // alin * alin
            if ini + tam <= r[0] + r[1]:
                sobra_fin = r[0] + r[1] - (ini + tam)
                r[0], r[1] = ini + tam, sobra_fin
                self.en_hueco += tam
                return ini
        ini = (self.extra + alin - 1) // alin * alin
        self.extra = ini + tam
        self.fin = max(self.fin, self.extra)
        return ini


# ---------------------------------------------------------------------------
# Utilidades de ROM
# ---------------------------------------------------------------------------
def cabecera(rom, off, largo):
    return rom[off:off + largo].decode('ascii', 'replace')

def checksum_sega(rom):
    s = 0
    for i in range(0x200, len(rom) - 1, 2):
        s = (s + struct.unpack_from('>H', rom, i)[0]) & 0xFFFF
    return s

def inyectar(juego, intro, perfil, base, espera, botones, arreglar_checksum,
             tocar_cabecera=False, relleno=0xFF, diagnostico=False, sin_logo=False):
    if len(juego) > base:
        raise SystemExit("ERROR: el juego ocupa hasta 0x%06X y la base de la intro "
                         "es 0x%06X (se solaparían)." % (len(juego) - 1, base))
    if base + len(intro) > 0x400000:
        raise SystemExit("ERROR: la intro no cabe: terminaría en 0x%06X y el mapa de "
                         "cartucho llega a 0x3FFFFF." % (base + len(intro)))

    juego_sp = struct.unpack_from('>I', juego, 0x00)[0]
    juego_pc = struct.unpack_from('>I', juego, 0x04)[0]
    if juego_pc >= len(juego):
        raise SystemExit("ERROR: el vector de RESET del juego (0x%06X) apunta fuera "
                         "de la ROM. ¿Ya tiene una intro insertada?" % juego_pc)

    # --- copia de la intro con las direcciones absolutas reubicadas -------
    ni = bytearray(intro)
    for off, val in perfil['relocs']:
        struct.pack_into('>I', ni, off, val + base)

    # --- stub -------------------------------------------------------------
    cont_ram = 0xFF0100
    # ¿el juego declara SRAM en una zona que ahora ocupa la ROM ampliada?
    sram = juego[0x1B0:0x1B2] == b'RA'
    sram_ini = struct.unpack_from('>I', juego, 0x1B4)[0] if sram else 0
    remapear = bool(sram and sram_ini < base + len(intro))

    lejos_off = (len(intro) + 1) & ~1        # el trozo grande va tras la intro
    cerca, lejos, etiquetas = construir_stub(
        base + STUB_OFF, base + lejos_off,
        base + perfil['entrada'], base + perfil['bucle'], juego_sp, juego_pc,
        espera, cont_ram, botones, remapear, diagnostico)
    if STUB_OFF + len(cerca) > perfil['entrada']:
        raise SystemExit("ERROR: el stub (%d bytes) no cabe en la tabla de vectores."
                         % len(cerca))
    ni[STUB_OFF:STUB_OFF + len(cerca)] = cerca
    ni += b'\x00' * (lejos_off - len(ni))
    ni += lejos

    # --- desviar el bucle final de la intro hacia el comprobador ----------
    disp = etiquetas['comprobar'] - (base + perfil['bra'] + 2)
    struct.pack_into('>Hh', ni, perfil['bra'], BRA_W, disp)

    # --- y su espera de vblank hacia nuestra versión con lectura de mando --
    if botones:
        struct.pack_into('>HI', ni, perfil['vsync'], 0x4EF9, etiquetas['vsync'])

    # --- montar la ROM final ---------------------------------------------
    salida = bytearray(juego)
    salida += bytes([relleno]) * (base - len(juego))
    salida += ni

    # vector de RESET -> stub de entrada
    struct.pack_into('>I', salida, 0x04, etiquetas['entrada'])
    juego_conocido = detectar_juego(juego)
    juego_info = juego_conocido if sin_logo else None
    if sin_logo and not juego_info:
        raise SystemExit("ERROR: --sin-logo no reconoce este juego.")
    if juego_info:
        quitar_logo(salida, juego_info)
        arreglar_checksum = True
    # OJO: la cabecera (fin de ROM en 0x1A4 y checksum en 0x18E) se deja INTACTA.
    # Soleil comprueba su propio checksum usando el "fin de ROM" de la cabecera:
    # si se actualiza, el juego se cuelga con la pantalla en rojo.
    if tocar_cabecera:
        struct.pack_into('>I', salida, 0x1A4, len(salida) - 1)
    if arreglar_checksum:
        struct.pack_into('>H', salida, 0x18E, checksum_sega(salida[:0x200000]))

    info = dict(juego_sp=juego_sp, juego_pc=juego_pc, base=base, sram=remapear,
                perfil=perfil['nombre'], diag=diagnostico,
                tam_lejos=len(lejos),
                sram_ini=sram_ini, stub=etiquetas, tam_stub=len(cerca),
                total=len(salida))
    return bytes(salida), info


def inyectar_comprimido(juego, intro, perfil, espera, botones, arreglar_checksum,
                        usar_huecos=False, inicio_extra=0x210000,
                        diagnostico=False, relleno=0xFF, sin_logo=False):
    """Trocea la intro, comprime los fotogramas y reparte los trozos por los
    huecos libres del juego (y, lo que no quepa, detrás de la ROM)."""
    juego_sp = struct.unpack_from('>I', juego, 0x00)[0]
    juego_pc = struct.unpack_from('>I', juego, 0x04)[0]
    if juego_pc >= len(juego):
        raise SystemExit("ERROR: el vector de RESET del juego (0x%06X) apunta fuera "
                         "de la ROM. ¿Ya tiene una intro insertada?" % juego_pc)

    juego_conocido = detectar_juego(juego)
    juego_info = juego_conocido if sin_logo else None
    if sin_logo and not juego_info:
        raise SystemExit("ERROR: --sin-logo no reconoce este juego (o ya está "
                         "parcheado). Juegos que sé hacer: " +
                         ", ".join(j['nombre'] for j in JUEGOS))

    cod_off, cod_tam = perfil['codigo']
    fr_off, fr_tam, fr_n = perfil['frames']
    pcm_off, pcm_tam = perfil['pcm']
    vac_off, vac_tam = perfil['vacio']
    dl_off,  dl_tam  = perfil['drvlib']

    codigo = bytearray(intro[cod_off:cod_off + cod_tam])
    drvlib = bytearray(intro[dl_off:dl_off + dl_tam])
    pcm    = intro[pcm_off:pcm_off + pcm_tam]
    vacio  = intro[vac_off:vac_off + vac_tam]

    frames, crudos = [], []
    for i in range(fr_n):
        crudo = intro[fr_off + i * fr_tam: fr_off + (i + 1) * fr_tam]
        comp = comprimir_rle(crudo)
        if descomprimir_rle(comp) != crudo:          # red de seguridad
            raise SystemExit("ERROR: el compresor no es reversible en el fotograma %d" % i)
        frames.append(comp); crudos.append(len(crudo))

    # --- ¿dónde va cada trozo? -------------------------------------------
    cont_ram = 0xFF0100
    sram = juego[0x1B0:0x1B2] == b'RA'
    sram_ini = struct.unpack_from('>I', juego, 0x1B4)[0] if sram else 0

    # El stub cambia de tamaño según haga falta remapear la SRAM o no, y eso
    # depende de si la ROM acaba creciendo: se reparte hasta que ambas cosas
    # cuadren (dos vueltas como mucho).
    remapear = bool(sram)
    for _ in range(3):
        huecos = []
        if usar_huecos:
            if juego_conocido and juego_conocido.get('huecos'):
                huecos = [(o, t, None) for o, t in juego_conocido['huecos']]
            else:
                # Juego desconocido: solo rachas MUY grandes, que es lo único que
                # se puede suponer relleno sin haberlo comprobado. Una racha de
                # ceros de unos pocos KB suele ser parte de un bloque de gráficos.
                huecos = [(o, t, v) for o, t, v in huecos_libres(juego, 0x8000)
                          if o >= 0x200]
                print("AVISO: juego no reconocido; uso solo rachas de relleno de "
                      ">=32 KB (%d encontradas). Compruébalo a fondo." % len(huecos))
            if sin_logo and juego_info:
                for o, t in juego_info['libres']:   # lo que deja libre el logo
                    huecos.append((o, t, None))
        col = Colocador(len(juego), huecos, inicio_extra)

        c0, l0, _ = construir_stub(0, 0, 0, 0, juego_sp, juego_pc, espera, cont_ram,
                                   botones, remapear, diagnostico, [0] * fr_n)
        tam_cerca, tam_lejos = len(c0), len(l0)

        # Se colocan de mayor a menor: si no, los trozos pequeños fragmentan los
        # huecos grandes y luego la muestra PCM o los fotogramas gordos no caben.
        # El trozo "cerca" tiene que quedar a tiro de bra.w del código, así que
        # van pegados en el mismo bloque.
        piezas = [('codigo', cod_tam + tam_cerca, 2), ('lejos', tam_lejos, 2),
                  ('pcm', len(pcm), 256), ('vacio', len(vacio), 256),
                  ('drvlib', len(drvlib), 2)]
        piezas += [('f%d' % i, len(f), 2) for i, f in enumerate(frames)]
        sitio = {}
        for nombre, tam, alin in sorted(piezas, key=lambda x: -x[1]):
            sitio[nombre] = col.colocar(tam, alin)

        if bool(sram and col.fin > sram_ini) == remapear:
            break
        remapear = bool(sram and col.fin > sram_ini)

    dir_cod   = sitio['codigo']
    dir_cerca = dir_cod + cod_tam
    dir_lejos = sitio['lejos']
    dir_pcm   = sitio['pcm']
    dir_vac   = sitio['vacio']
    dir_dl    = sitio['drvlib']
    dirs_fr   = [sitio['f%d' % i] for i in range(fr_n)]

    # segunda pasada, ya con las direcciones de verdad
    cerca, lejos, etiquetas = construir_stub(
        dir_cerca, dir_lejos, dir_cod + (perfil['entrada'] - cod_off),
        dir_cod + (perfil['bucle'] - cod_off), juego_sp, juego_pc, espera,
        cont_ram, botones, remapear, diagnostico, dirs_fr)
    if (len(cerca), len(lejos)) != (tam_cerca, tam_lejos):
        raise SystemExit("ERROR interno: el stub cambió de tamaño entre pasadas.")

    # --- reubicar las direcciones absolutas de la intro -------------------
    def mapear(val):
        if dl_off  <= val < dl_off + dl_tam:   return dir_dl  + (val - dl_off)
        if pcm_off <= val < pcm_off + pcm_tam: return dir_pcm + (val - pcm_off)
        if vac_off <= val < vac_off + vac_tam: return dir_vac + (val - vac_off)
        raise SystemExit("ERROR: no sé a qué trozo pertenece 0x%06X" % val)

    for off, val in perfil['relocs']:
        nuevo = mapear(val)
        if cod_off <= off < cod_off + cod_tam:
            struct.pack_into('>I', codigo, off - cod_off, nuevo)
        elif dl_off <= off < dl_off + dl_tam:
            struct.pack_into('>I', drvlib, off - dl_off, nuevo)
        else:
            raise SystemExit("ERROR: reubicación en 0x%05X fuera de los trozos" % off)

    # --- parches dentro del código de la intro ----------------------------
    # su rutina de volcado de fotograma -> nuestro descompresor
    struct.pack_into('>HI', codigo, perfil['subir'] - cod_off, 0x4EF9,
                     etiquetas['descomp'])
    # su espera de vblank -> la nuestra, que además lee el mando
    if botones:
        struct.pack_into('>HI', codigo, perfil['vsync'] - cod_off, 0x4EF9,
                         etiquetas['vsync'])
    # el bra.w del bucle final -> nuestro comprobador
    disp = etiquetas['comprobar'] - (dir_cod + (perfil['bra'] - cod_off) + 2)
    struct.pack_into('>Hh', codigo, perfil['bra'] - cod_off, BRA_W, disp)

    # --- montar la ROM ----------------------------------------------------
    if col.fin > 0x400000:
        raise SystemExit("ERROR: la ROM resultante (0x%06X) se sale del mapa de "
                         "cartucho (0x3FFFFF)." % col.fin)
    salida = bytearray(juego)
    if col.fin > len(salida):
        salida += bytes([relleno]) * (col.fin - len(salida))
    def poner(dirn, datos):
        salida[dirn:dirn + len(datos)] = datos
    poner(dir_cod, codigo); poner(dir_cerca, cerca); poner(dir_lejos, lejos)
    poner(dir_pcm, pcm);    poner(dir_vac, vacio);   poner(dir_dl, drvlib)
    for d, f in zip(dirs_fr, frames): poner(d, f)

    struct.pack_into('>I', salida, 0x04, etiquetas['entrada'])
    if juego_info:
        quitar_logo(salida, juego_info)      # fuera la pantalla del logo
    # Si se ha escrito dentro de la ROM del juego hay que rehacer el checksum:
    # Soleil se comprueba a sí mismo al arrancar y, si no cuadra, se queda con
    # la pantalla en rojo.
    if arreglar_checksum or col.en_hueco or juego_info:
        struct.pack_into('>H', salida, 0x18E, checksum_sega(salida[:len(juego)]))

    ocupado = (cod_tam + tam_cerca + tam_lejos + len(pcm) + len(vacio) +
               len(drvlib) + sum(len(f) for f in frames))
    info = dict(juego_sp=juego_sp, juego_pc=juego_pc, base=dir_cod, sram=remapear,
                perfil=perfil['nombre'], diag=diagnostico, sram_ini=sram_ini,
                stub=etiquetas, tam_stub=len(cerca), tam_lejos=len(lejos),
                total=len(salida), comprimido=True, ocupado=ocupado,
                sin_logo=juego_info['nota'] if juego_info else None,
                juego=juego_info['nombre'] if juego_info else None,
                en_huecos=col.en_hueco, crudo=sum(crudos),
                comp=sum(len(f) for f in frames), dirs=dict(
                    codigo=dir_cod, cerca=dir_cerca, lejos=dir_lejos, pcm=dir_pcm,
                    vacio=dir_vac, drvlib=dir_dl, frames=dirs_fr))
    return bytes(salida), info


def main():
    p = argparse.ArgumentParser(
        description="Inserta una intro de Mega Drive delante de un juego.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Ejemplo:\n  python3 insertar_intro.py Choleil.md charnego_introxgm.bin "
               "-o Soleil_intro.md --espera 300")
    p.add_argument('juego', help="ROM del juego (.md/.bin)")
    p.add_argument('intro', help="ROM de la intro (.bin)")
    p.add_argument('-o', '--salida', help="ROM resultante (por defecto <juego>_intro.md)")
    p.add_argument('--base', default='0x300000',
                   help="dirección donde se coloca la intro (por defecto 0x300000)")
    p.add_argument('--espera', type=int, default=None,
                   help="frames que se queda la intro en su pantalla final antes de "
                        "entrar al juego; 0 = esperar a START (por defecto, el propio "
                        "de cada intro)")
    p.add_argument('--sin-botones', action='store_true',
                   help="no salir al pulsar START/A (solo por tiempo)")
    p.add_argument('--comprimir', action='store_true',
                   help="comprime los fotogramas de la intro (RLE) y reparte los "
                        "trozos por la ROM: pasa de ~3,6 MB a ~2,2 MB")
    p.add_argument('--huecos', action='store_true',
                   help="con --comprimir, mete los trozos en los espacios libres "
                        "del propio juego (rachas largas del mismo byte) para que "
                        "la ROM no crezca nada. Comprueba que esas zonas están "
                        "realmente sin usar antes de distribuirla")
    p.add_argument('--extra', default='0x210000',
                   help="con --comprimir, dónde empieza lo que no quepa en huecos "
                        "(por defecto 0x210000, justo detrás de la ventana de SRAM)")
    p.add_argument('--sin-logo', action='store_true',
                   help="quita la pantalla del logo del arranque del juego (Soleil): "
                        "tras la intro entra directo, y sus gráficos quedan como "
                        "hueco libre para la intro")
    p.add_argument('--diagnostico', action='store_true',
                   help="ROM de diagnóstico: pinta la pantalla de un color distinto "
                        "en cada paso de la salida al juego, para ver en qué punto se "
                        "queda colgada en hardware real")
    p.add_argument('--checksum', action='store_true',
                   help="recalcular el checksum de la cabecera (normalmente NO hace falta)")
    p.add_argument('--fin-rom', action='store_true',
                   help="actualizar el campo 'fin de ROM' de la cabecera (0x1A4). "
                        "AVISO: Soleil comprueba su checksum con ese valor y se cuelga "
                        "en rojo si se cambia; dejar desactivado")
    p.add_argument('--relleno', default='0xFF',
                   help="byte de relleno entre juego e intro (por defecto 0xFF)")
    args = p.parse_args()

    juego = open(args.juego, 'rb').read()
    intro = open(args.intro, 'rb').read()
    base  = int(args.base, 0)
    if base & 1:
        raise SystemExit("ERROR: la base debe ser par.")
    perfil = detectar_perfil(intro)
    if perfil is None:
        raise SystemExit(
            "ERROR: no reconozco esta intro (no coinciden sus direcciones absolutas "
            "ni su bucle final con ningún perfil conocido).\n"
            "       Reubicar una intro desconocida a ciegas generaría una ROM rota; "
            "añade su perfil en PERFILES.")
    if args.espera is None:
        args.espera = perfil['espera']
    if args.espera == 0 and args.sin_botones:
        raise SystemExit("ERROR: con --espera 0 y --sin-botones la intro no saldría nunca.")

    if args.comprimir:
        salida, info = inyectar_comprimido(
            juego, intro, perfil, args.espera, not args.sin_botones, args.checksum,
            args.huecos, int(args.extra, 0), args.diagnostico, int(args.relleno, 0),
            args.sin_logo)
    else:
        salida, info = inyectar(juego, intro, perfil, base, args.espera,
                                not args.sin_botones, args.checksum,
                                args.fin_rom, int(args.relleno, 0), args.diagnostico,
                                args.sin_logo)

    destino = args.salida or (os.path.splitext(args.juego)[0] + "_intro.md")
    open(destino, 'wb').write(salida)

    print("Juego : %s  (%s, %d KB)" % (os.path.basename(args.juego),
                                       cabecera(juego, 0x120, 16).strip(), len(juego) // 1024))
    print("Intro : %s  (%s, %d KB)" % (os.path.basename(args.intro),
                                       cabecera(intro, 0x120, 32).strip(), len(intro) // 1024))
    print("Perfil           : %s" % info['perfil'])
    if info.get('sin_logo'):
        print("Logo de arranque : quitado (%s)" % info['sin_logo'])
    if info.get('comprimido'):
        print("Fotogramas       : %d KB -> %d KB comprimidos (RLE por palabras)"
              % (info['crudo'] // 1024, info['comp'] // 1024))
        print("Intro insertada  : %d KB en total, %d KB en huecos del juego"
              % (info['ocupado'] // 1024, info['en_huecos'] // 1024))
        d = info['dirs']
        print("Trozos           : código 0x%06X  stub 0x%06X/0x%06X  PCM 0x%06X  "
              "driver 0x%06X" % (d['codigo'], d['cerca'], d['lejos'], d['pcm'],
                                 d['drvlib']))
        print("                   fotogramas 0x%06X ... 0x%06X"
              % (min(d['frames']), max(d['frames'])))
    print("Base de la intro : 0x%06X   Espera: %s"
          % (info['base'], "solo START" if args.espera == 0 else "%d frames" % args.espera))
    print("Stub             : 0x%06X (%d bytes) + salida en 0x%06X (%d bytes)"
          % (info['stub']['entrada'], info['tam_stub'],
             info['stub']['salir'], info['tam_lejos']))
    print("Entrada original : SP=0x%08X  PC=0x%06X" % (info['juego_sp'], info['juego_pc']))
    if info['sram']:
        print("SRAM             : 0x%06X remapeada al salir de la intro (A130F1)"
              % info['sram_ini'])
    print("Salida           : %s (%d bytes, %.2f MB)"
          % (destino, info['total'], info['total'] / 1048576.0))
    print("MD5              : %s" % hashlib.md5(salida).hexdigest())
    if args.diagnostico:
        print("\nROM de diagnóstico. Colores de la salida, en orden:")
        for _, nombre, que in DIAG:
            print("   %-9s %s" % (nombre, que))
        print("   (si se queda en un color, ahí está el cuelgue)")


if __name__ == '__main__':
    main()
