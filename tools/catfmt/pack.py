"""Cat icon pack: shared parts + 24 icon drawings (512-unit canvas)."""
import math
import gen

INK = "#111111"
WHITE = "#FFFFFF"
CYAN = "#4BD5D6"
TEAL = "#36A9AB"
TEAL_D = "#2E9497"
TEAL_DD = "#1E6467"
NAVY = "#2C4864"      # sheet colour
PINK = "#EC7474"      # sheet tongue colour
GOLD = "#E4B01C"      # sheet colour
BROWN = "#B08450"     # sheet colour
GRAY = "#9A9A9A"
PALE = "#E8FBFB"


class Ctx:
    def __init__(self, small):
        self.small = small
        self.sw = 30 if small else 16


DEFS = f'''
    <linearGradient id="gFront" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#6FE0E0"/><stop offset="1" stop-color="{TEAL}"/>
    </linearGradient>
    <linearGradient id="gBack" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="{TEAL_D}"/><stop offset="1" stop-color="{TEAL_DD}"/>
    </linearGradient>
    <linearGradient id="gBody" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#7FE6E4"/><stop offset="0.5" stop-color="{CYAN}"/><stop offset="1" stop-color="{TEAL_D}"/>
    </linearGradient>
    <linearGradient id="gScreen" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#8FEAE8"/><stop offset="0.55" stop-color="{CYAN}"/><stop offset="1" stop-color="{TEAL_D}"/>
    </linearGradient>
    <linearGradient id="gDark" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#21393B"/><stop offset="1" stop-color="#132324"/>
    </linearGradient>
    <linearGradient id="gNavy" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#3B5F82"/><stop offset="1" stop-color="{NAVY}"/>
    </linearGradient>
    <linearGradient id="gPaper" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E9F4F4"/>
    </linearGradient>
    <linearGradient id="gSky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#C9F3F2"/><stop offset="1" stop-color="#8FE3E2"/>
    </linearGradient>'''


# ---------------------------------------------------------------- parts
EAR_L_CLOSED = "M 64 250 L 92.6 111.7 Q 96 92 113.1 102.3 L 206 166 Z"
EAR_R_CLOSED = "M 448 250 L 419.4 111.7 Q 416 92 398.9 102.3 L 306 166 Z"
EAR_FILL_SMALL = ("M 106 128 L 96 186 Q 126 176 146 160 Z", "M 406 128 L 416 186 Q 386 176 366 160 Z")
EYES_SMALL = ("M 160 232 Q 188 200 220 224", "M 292 224 Q 324 200 352 232")
OPEN_MOUTH = "M 228 240 Q 256 236 284 240 Q 284 286 256 288 Q 228 286 228 240 Z"


def _tf(cx, cy, s):
    return f'translate({cx:.1f} {cy:.1f}) scale({s:.4f}) translate(-256 -220)'


def face(c, s, expr="happy", mouth=True):
    """Eyes + mouth in master coordinates (to be wrapped by a transform)."""
    k = 1 / s
    eyes = EYES_SMALL if c.small else (gen.EYE_L, gen.EYE_R)
    fw = (c.sw if c.small else 14.5) * k
    out = [f'<g fill="none" stroke="{INK}" stroke-width="{fw:.1f}" stroke-linecap="round" stroke-linejoin="round">',
           f'<path d="{eyes[0]}"/><path d="{eyes[1]}"/>']
    if mouth and not c.small:
        if expr == "open":
            out.append('</g>')
            out.append(f'<path d="{OPEN_MOUTH}" fill="{PINK}" stroke="{INK}" stroke-width="{12*k:.1f}" stroke-linejoin="round"/>')
            return "".join(out)
        out.append(f'<path d="{gen.MOUTH}"/>')
    out.append('</g>')
    return "".join(out)


def cat(c, cx, cy, s, expr="happy", collar=True, mouth=True):
    """Full cat head; master anchor (256,220) = between the eyes."""
    k = 1 / s
    sw = c.sw * k
    ears = EAR_FILL_SMALL if c.small else (gen.EAR_L, gen.EAR_R)
    parts = [f'<g transform="{_tf(cx, cy, s)}">',
             f'<path d="{gen.HEAD}" fill="{WHITE}"/>',
             f'<path d="{ears[0]}" fill="{CYAN}" stroke="{CYAN}" stroke-width="{4*k:.1f}" stroke-linejoin="round"/>',
             f'<path d="{ears[1]}" fill="{CYAN}" stroke="{CYAN}" stroke-width="{4*k:.1f}" stroke-linejoin="round"/>',
             f'<path d="{gen.HEAD}" fill="none" stroke="{INK}" stroke-width="{sw:.1f}" stroke-linejoin="round"/>']
    if collar:
        parts.append(f'<path d="{gen.COLLAR}" fill="none" stroke="{INK}" stroke-width="{sw*1.7:.1f}" stroke-linecap="round"/>')
        if not c.small:
            parts.append(f'<circle cx="256" cy="353" r="19" fill="{GRAY}" stroke="{INK}" stroke-width="{10*k:.1f}"/>'
                         f'<path d="M 256 358 L 256 367" stroke="{INK}" stroke-width="{7*k:.1f}" stroke-linecap="round"/>')
    parts.append(face(c, s, expr, mouth))
    parts.append('</g>')
    return "".join(parts)


def ears(c, cx, cy, s):
    """Just the two ears (closed shapes, bases meant to hide behind an object)."""
    k = 1 / s
    sw = c.sw * k
    fill = EAR_FILL_SMALL if c.small else (gen.EAR_L, gen.EAR_R)
    return (f'<g transform="{_tf(cx, cy, s)}">'
            f'<path d="{EAR_L_CLOSED}" fill="{WHITE}" stroke="{INK}" stroke-width="{sw:.1f}" stroke-linejoin="round"/>'
            f'<path d="{EAR_R_CLOSED}" fill="{WHITE}" stroke="{INK}" stroke-width="{sw:.1f}" stroke-linejoin="round"/>'
            f'<path d="{fill[0]}" fill="{CYAN}"/><path d="{fill[1]}" fill="{CYAN}"/>'
            f'</g>')


def face_only(c, cx, cy, s, expr="happy", mouth=True):
    return f'<g transform="{_tf(cx, cy, s)}">{face(c, s, expr, mouth)}</g>'


def paw(c, x, y, r=1.0):
    rx, ry = 30 * r, 21 * r
    out = f'<ellipse cx="{x}" cy="{y}" rx="{rx:.1f}" ry="{ry:.1f}" fill="{WHITE}" stroke="{INK}" stroke-width="{c.sw}"/>'
    if not c.small:
        out += (f'<path d="M {x-9*r:.1f} {y+4*r:.1f} L {x-9*r:.1f} {y+14*r:.1f} M {x+9*r:.1f} {y+4*r:.1f} L {x+9*r:.1f} {y+14*r:.1f}" '
                f'stroke="{INK}" stroke-width="{7*r:.1f}" stroke-linecap="round"/>')
    return out


def peek(c, cx, edge, s, expr="happy"):
    """Cat behind an edge: returns (cat_svg, paws_svg). Paws sit at the cheeks, clear of the face."""
    r = s / 0.6
    cat_svg = cat(c, cx, edge - 42 * s - 10, s, expr=expr, collar=False)
    dx = 167 * s
    paws = paw(c, cx - dx, edge + 2, r * 0.95) + paw(c, cx + dx, edge + 2, r * 0.95)
    return cat_svg, paws


def stroke(c, w=1.0):
    return f'stroke="{INK}" stroke-width="{c.sw*w:.1f}" stroke-linejoin="round" stroke-linecap="round"'


# ---------------------------------------------------------------- badges (folder emblem)
def badge(c, kind, x=372, y=372, r=66):
    sw = c.sw
    out = [f'<circle cx="{x}" cy="{y}" r="{r}" fill="{WHITE}" {stroke(c)}/>']
    s = r / 66
    def P(d, fill="none", w=1.0, color=INK):
        return f'<path d="{d}" fill="{fill}" stroke="{color}" stroke-width="{sw*w:.1f}" stroke-linejoin="round" stroke-linecap="round" transform="translate({x} {y}) scale({s:.3f})"/>'
    if kind == "documents":
        out.append(P("M -26 -36 L 12 -36 L 28 -20 L 28 36 L -26 36 Z", fill=PALE, w=0.75))
        if not c.small:
            out.append(P("M -14 -8 L 16 -8 M -14 8 L 16 8 M -14 22 L 6 22", w=0.5, color=TEAL_D))
    elif kind == "download":
        out.append(P("M 0 -38 L 0 14 M -24 -8 L 0 16 L 24 -8", w=1.0, color=TEAL_D))
        out.append(P("M -30 34 L 30 34", w=1.0, color=INK))
    elif kind == "pictures":
        out.append(P("M -34 -26 L 34 -26 L 34 30 L -34 30 Z", fill="url(#gSky)", w=0.7))
        out.append(P("M -34 30 L -10 2 L 6 18 L 18 6 L 34 24 L 34 30 Z", fill=TEAL, w=0.55))
        if not c.small:
            out.append(f'<circle cx="{x+16*s:.1f}" cy="{y-10*s:.1f}" r="{7*s:.1f}" fill="{GOLD}"/>')
    elif kind == "music":
        out.append(P("M -6 22 L -6 -32 L 26 -38 L 26 14", w=0.85))
        out.append(f'<ellipse cx="{x-18*s:.1f}" cy="{y+24*s:.1f}" rx="{14*s:.1f}" ry="{11*s:.1f}" fill="{TEAL_D}" stroke="{INK}" stroke-width="{sw*0.6:.1f}"/>')
        out.append(f'<ellipse cx="{x+14*s:.1f}" cy="{y+16*s:.1f}" rx="{14*s:.1f}" ry="{11*s:.1f}" fill="{TEAL_D}" stroke="{INK}" stroke-width="{sw*0.6:.1f}"/>')
    elif kind == "videos":
        out.append(P("M -16 -28 L 30 0 L -16 28 Z", fill=CYAN, w=0.8))
    elif kind == "desktop":
        out.append(P("M -34 -28 L 34 -28 L 34 18 L -34 18 Z", fill="url(#gScreen)", w=0.7))
        out.append(P("M -12 36 L 12 36 M 0 18 L 0 34", w=0.7))
    elif kind == "search":
        out.append(f'<circle cx="{x-8*s:.1f}" cy="{y-8*s:.1f}" r="{22*s:.1f}" fill="{PALE}" stroke="{INK}" stroke-width="{sw*0.85:.1f}"/>')
        out.append(P("M 8 8 L 30 30", w=1.2))
    return "".join(out)


# ---------------------------------------------------------------- icons
def folder(c, kind=None):
    back = ("M 52 150 Q 52 124 78 124 L 184 124 Q 200 124 210 136 L 226 156 L 434 156 "
            "Q 460 156 460 182 L 460 420 Q 460 446 434 446 L 78 446 Q 52 446 52 420 Z")
    front = ("M 62 268 L 450 268 Q 474 268 472 292 L 462 424 Q 460 450 434 450 L 78 450 "
             "Q 52 450 50 424 L 40 292 Q 38 268 62 268 Z")
    kitty, paws = peek(c, 256, 268, 0.66)
    out = [f'<path d="{back}" fill="url(#gBack)" {stroke(c)}/>', kitty]
    out.append(f'<path d="{front}" fill="url(#gFront)" {stroke(c)}/>')
    if not c.small:
        out.append(f'<path d="M 70 292 L 442 292" stroke="#FFFFFF" stroke-opacity="0.45" stroke-width="8" stroke-linecap="round"/>')
    out.append(paws)
    if kind:
        out.append(badge(c, kind, 350, 362, 96) if c.small else badge(c, kind))
    return "".join(out)


def computer(c):
    kitty, paws = peek(c, 256, 160, 0.64)
    out = [kitty]
    out.append(f'<path d="M 222 418 L 290 418 L 300 458 L 212 458 Z" fill="{GRAY}" {stroke(c)}/>')
    out.append(f'<rect x="150" y="452" width="212" height="28" rx="14" fill="#C9CFCF" {stroke(c)}/>')
    out.append(f'<rect x="44" y="160" width="424" height="266" rx="30" fill="#F2F5F5" {stroke(c)}/>')
    out.append(f'<rect x="74" y="190" width="364" height="206" rx="12" fill="url(#gScreen)" {stroke(c, 0.6)}/>')
    if not c.small:
        out.append('<path d="M 96 380 L 250 206 L 330 206 L 176 380 Z" fill="#FFFFFF" fill-opacity="0.18"/>')
    out.append(paws)
    return "".join(out)


def crumple(c, x, y, r, rot=0):
    pts = []
    for i in range(9):
        a = math.radians(rot + i * 40)
        rr = r * (1.0 if i % 2 == 0 else 0.8)
        pts.append((x + rr * math.cos(a), y + rr * math.sin(a)))
    d = "M " + " L ".join(f"{px:.1f} {py:.1f}" for px, py in pts) + " Z"
    out = f'<path d="{d}" fill="url(#gPaper)" {stroke(c, 0.9)}/>'
    if not c.small:
        out += (f'<path d="M {x-r*0.5:.0f} {y-r*0.15:.0f} L {x-r*0.05:.0f} {y+r*0.1:.0f} L {x+r*0.45:.0f} {y-r*0.3:.0f} '
                f'M {x-r*0.1:.0f} {y+r*0.1:.0f} L {x+r*0.05:.0f} {y+r*0.55:.0f}" fill="none" stroke="#9FB5B5" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>')
    return out


def trash(c, full=False):
    body = "M 98 232 L 414 232 L 384 450 Q 380 474 356 474 L 156 474 Q 132 474 128 450 Z"
    out = []
    kitty, paws = peek(c, 256, 214, 0.62, expr="open" if full else "happy")
    if full:
        k = 1.25 if c.small else 1.0
        out.append(crumple(c, 112, 168, 60 * k, 10))
        out.append(crumple(c, 402, 162, 62 * k, 30))
        if not c.small:
            out.append(crumple(c, 312, 112, 42, 0))
            out.append(crumple(c, 196, 120, 36, 20))
    out.append(kitty)
    out.append(f'<path d="{body}" fill="url(#gBody)" {stroke(c)}/>')
    if not c.small:
        for x0, x1 in ((180, 190), (256, 256), (332, 322)):
            out.append(f'<path d="M {x0} 278 L {x1} 432" stroke="{TEAL_DD}" stroke-opacity="0.55" stroke-width="14" stroke-linecap="round"/>')
    out.append(f'<rect x="78" y="214" width="356" height="40" rx="20" fill="#7FE6E4" {stroke(c)}/>')
    out.append(paws)
    return "".join(out)


def network(c):
    out = []
    cx, cy = 256, 238
    radii = (70, 135, 200) if not c.small else (90, 175)
    for r in radii:
        a = math.radians(42)
        x0, y0 = cx - r * math.sin(a), cy - r * math.cos(a)
        x1, y1 = cx + r * math.sin(a), cy - r * math.cos(a)
        d = f"M {x0:.1f} {y0:.1f} A {r} {r} 0 0 1 {x1:.1f} {y1:.1f}"
        out.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{c.sw*2+26:.1f}" stroke-linecap="round"/>')
        out.append(f'<path d="{d}" fill="none" stroke="{CYAN}" stroke-width="26" stroke-linecap="round"/>')
    out.append(cat(c, 256, 352, 0.74, expr="open"))
    return "".join(out)


def home(c):
    out = [ears(c, 256, 160, 0.76)]
    out.append(f'<path d="M 92 268 L 92 452 Q 92 470 110 470 L 402 470 Q 420 470 420 452 L 420 268 Z" fill="url(#gPaper)" {stroke(c)}/>')
    out.append(f'<path d="M 40 280 L 160 140 L 352 140 L 472 280 Z" fill="url(#gBack)" {stroke(c)}/>')
    if not c.small:
        out.append(f'<path d="M 92 262 L 172 166 L 340 166 L 420 262" fill="none" stroke="#FFFFFF" stroke-opacity="0.3" stroke-width="10" stroke-linecap="round"/>')
    out.append(f'<path d="M 214 470 L 214 400 Q 214 368 256 368 Q 298 368 298 400 L 298 470 Z" fill="url(#gFront)" {stroke(c)}/>')
    out.append(face_only(c, 256, 316, 0.62))
    return "".join(out)


def gear_path(cx, cy, R, r, n=8, tip=9, root=15):
    pts = []
    for i in range(n):
        a = 2 * math.pi * i / n
        for ang, rad in ((a - math.radians(root), r), (a - math.radians(tip), R),
                         (a + math.radians(tip), R), (a + math.radians(root), r)):
            pts.append((cx + rad * math.sin(ang), cy - rad * math.cos(ang)))
    return "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in pts) + " Z"


def settings(c):
    out = [f'<path d="{gear_path(256, 262, 214, 172)}" fill="url(#gBody)" {stroke(c)}/>',
           f'<circle cx="256" cy="262" r="128" fill="{TEAL_DD}" {stroke(c, 0.8)}/>',
           cat(c, 256, 262, 0.56)]
    return "".join(out)


def drive(c):
    kitty, paws = peek(c, 256, 238, 0.6)
    out = [kitty]
    out.append(f'<path d="M 86 236 L 426 236 L 470 304 L 42 304 Z" fill="#7FE6E4" {stroke(c)}/>')
    out.append(f'<rect x="42" y="304" width="428" height="136" rx="22" fill="url(#gBack)" {stroke(c)}/>')
    if not c.small:
        for i in range(5):
            x = 96 + i * 34
            out.append(f'<path d="M {x} 342 L {x} 402" stroke="{TEAL_DD}" stroke-width="12" stroke-linecap="round"/>')
    out.append(f'<circle cx="408" cy="372" r="20" fill="#7DF9C8" {stroke(c, 0.6)}/>')
    out.append(paws)
    return "".join(out)


def browser(c):
    svg = gen.svg("small" if c.small else "master")
    inner = svg.split("<defs>", 1)[1]
    defs, body = inner.split("</defs>", 1)
    body = body.rsplit("</svg>", 1)[0]
    return ("<defs>" + defs + "</defs>" + body)


def terminal(c):
    kitty, paws = peek(c, 330, 150, 0.5)
    out = [kitty]
    out.append(f'<rect x="40" y="150" width="432" height="310" rx="34" fill="url(#gDark)" {stroke(c)}/>')
    out.append(f'<path d="M 40 208 L 40 184 Q 40 150 74 150 L 438 150 Q 472 150 472 184 L 472 208 Z" fill="{TEAL_D}" {stroke(c)}/>')
    if not c.small:
        for i, col in enumerate((WHITE, WHITE, WHITE)):
            out.append(f'<circle cx="{80 + i*34}" cy="179" r="10" fill="{col}" fill-opacity="0.9"/>')
    out.append(f'<path d="M 104 268 L 166 316 L 104 364" fill="none" stroke="{CYAN}" stroke-width="{30 if not c.small else 40}" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append(f'<path d="M 206 380 L 290 380" stroke="{CYAN}" stroke-width="{30 if not c.small else 40}" stroke-linecap="round"/>')
    out.append(paws)
    return "".join(out)


def text_editor(c):
    out = [ears(c, 256, 140, 0.7)]
    out.append(f'<rect x="92" y="120" width="328" height="360" rx="26" fill="url(#gPaper)" {stroke(c)}/>')
    out.append(face_only(c, 256, 210, 0.62))
    if not c.small:
        for y in (290, 340, 390):
            out.append(f'<path d="M 140 {y} L {372 if y < 390 else 280} {y}" stroke="{CYAN}" stroke-width="14" stroke-linecap="round"/>')
    else:
        out.append(f'<path d="M 140 330 L 372 330" stroke="{TEAL}" stroke-width="30" stroke-linecap="round"/>')
    # pencil
    out.append(f'<g transform="rotate(-45 392 392)">'
               f'<path d="M 318 362 L 470 362 L 470 422 L 318 422 Z" fill="{GOLD}" {stroke(c)}/>'
               f'<path d="M 470 362 L 500 362 Q 512 362 512 374 L 512 410 Q 512 422 500 422 L 470 422 Z" fill="{PINK}" {stroke(c)}/>'
               f'<path d="M 318 362 L 270 392 L 318 422 Z" fill="#F6D9B8" {stroke(c)}/>'
               f'<path d="M 286 382 L 270 392 L 286 402 Z" fill="{INK}"/>'
               f'</g>')
    return "".join(out)


def calculator(c):
    out = [ears(c, 256, 102, 0.7)]
    out.append(f'<rect x="96" y="80" width="320" height="400" rx="40" fill="url(#gBody)" {stroke(c)}/>')
    out.append(f'<rect x="134" y="118" width="244" height="104" rx="18" fill="{PALE}" {stroke(c, 0.8)}/>')
    out.append(face_only(c, 256, 166, 0.5))
    keys = []
    for row in range(3):
        for col in range(3):
            x = 134 + col * 90
            y = 252 + row * 76
            fill = CYAN if col == 2 else WHITE
            if c.small and (row, col) not in ((0, 0), (0, 2), (2, 0), (2, 2), (1, 1)):
                continue
            keys.append(f'<rect x="{x}" y="{y}" width="64" height="54" rx="14" fill="{fill}" {stroke(c, 0.7)}/>')
    out += keys
    return "".join(out)


def music(c):
    band = "M 96 268 C 92 120 180 92 256 92 C 332 92 420 120 416 268"
    out = [f'<path d="{band}" fill="none" stroke="{INK}" stroke-width="{c.sw*2+30:.1f}" stroke-linecap="round"/>',
           f'<path d="{band}" fill="none" stroke="{NAVY}" stroke-width="30" stroke-linecap="round"/>']
    out.append(cat(c, 256, 262, 0.86, expr="open"))
    for x in (56, 392):
        out.append(f'<rect x="{x}" y="214" width="64" height="116" rx="30" fill="{PINK}" {stroke(c)}/>')
    if not c.small:
        out.append(f'<path d="M 446 40 L 446 132" stroke="{INK}" stroke-width="12" stroke-linecap="round"/>'
                   f'<path d="M 446 40 Q 482 50 488 88" fill="none" stroke="{INK}" stroke-width="12" stroke-linecap="round"/>'
                   f'<ellipse cx="430" cy="134" rx="22" ry="17" fill="{GOLD}" stroke="{INK}" stroke-width="10"/>')
    return "".join(out)


def video(c):
    out = [ears(c, 256, 156, 0.8)]
    out.append(f'<rect x="44" y="132" width="424" height="320" rx="40" fill="url(#gNavy)" {stroke(c)}/>')
    if not c.small:
        for i in range(7):
            x = 84 + i * 54
            out.append(f'<rect x="{x}" y="152" width="28" height="22" rx="6" fill="#FFFFFF" fill-opacity="0.85"/>')
            out.append(f'<rect x="{x}" y="410" width="28" height="22" rx="6" fill="#FFFFFF" fill-opacity="0.85"/>')
    out.append(f'<path d="M 214 222 L 330 292 L 214 362 Z" fill="{CYAN}" {stroke(c)}/>')
    return "".join(out)


def photos(c):
    out = []
    out.append(f'<g transform="rotate(9 256 280)"><rect x="96" y="96" width="330" height="372" rx="18" fill="#D7EEEE" {stroke(c)}/></g>')
    out.append('<g transform="rotate(-6 256 280)">')
    out.append(f'<rect x="80" y="84" width="352" height="396" rx="18" fill="{WHITE}" {stroke(c)}/>')
    out.append('<clipPath id="photoClip"><rect x="112" y="116" width="288" height="270" rx="6"/></clipPath>')
    out.append(f'<rect x="112" y="116" width="288" height="270" rx="6" fill="url(#gSky)"/>')
    out.append('<g clip-path="url(#photoClip)">' + cat(c, 256, 268, 0.6) + '</g>')
    out.append(f'<rect x="112" y="116" width="288" height="270" rx="6" fill="none" {stroke(c, 0.7)}/>')
    out.append('</g>')
    return "".join(out)


def mail(c):
    out = []
    out.append(f'<path d="M 48 236 L 256 104 L 464 236 Z" fill="#BFF1F0" {stroke(c)}/>')
    out.append(f'<rect x="48" y="236" width="416" height="226" rx="14" fill="#9FE6E5" {stroke(c)}/>')
    out.append(cat(c, 256, 236, 0.62, collar=False))
    out.append(f'<path d="M 48 248 L 256 372 L 464 248 L 464 448 Q 464 462 450 462 L 62 462 Q 48 462 48 448 Z" fill="url(#gPaper)" {stroke(c)}/>')
    if not c.small:
        out.append(f'<path d="M 64 446 L 200 344 M 448 446 L 312 344" stroke="#B8D6D6" stroke-width="10" stroke-linecap="round"/>')
    out.append(paw(c, 176, 304, 0.95))
    out.append(paw(c, 336, 304, 0.95))
    return "".join(out)


def games(c):
    body = ("M 132 196 Q 256 176 380 196 Q 466 208 482 330 Q 494 432 430 440 Q 384 446 352 384 "
            "L 160 384 Q 128 446 82 440 Q 18 432 30 330 Q 46 208 132 196 Z")
    out = [ears(c, 256, 210, 0.78)]
    out.append(f'<path d="{body}" fill="url(#gBody)" {stroke(c)}/>')
    # d-pad
    out.append(f'<path d="M 108 270 L 138 270 L 138 300 L 168 300 L 168 330 L 138 330 L 138 360 L 108 360 L 108 330 L 78 330 L 78 300 L 108 300 Z" fill="{WHITE}" {stroke(c, 0.75)}/>')
    # buttons
    for (x, y, col) in ((402, 280, PINK), (440, 318, GOLD), (364, 318, WHITE), (402, 356, CYAN)):
        if c.small and col in (WHITE, CYAN):
            continue
        out.append(f'<circle cx="{x}" cy="{y}" r="{19 if not c.small else 26}" fill="{col}" {stroke(c, 0.7)}/>')
    out.append(face_only(c, 256, 270, 0.5))
    return "".join(out)


# name, Korean label, Windows use, function
ICONS = [
    ("computer",         "내 PC",              lambda c: computer(c)),
    ("user-trash",       "휴지통(비어 있음)",    lambda c: trash(c, False)),
    ("user-trash-full",  "휴지통(가득 참)",      lambda c: trash(c, True)),
    ("network",          "네트워크",            lambda c: network(c)),
    ("user-home",        "사용자 파일",          lambda c: home(c)),
    ("settings",         "설정·제어판",          lambda c: settings(c)),
    ("drive-harddisk",   "로컬 디스크",          lambda c: drive(c)),
    ("folder",           "폴더",               lambda c: folder(c)),
    ("folder-documents", "문서 폴더",           lambda c: folder(c, "documents")),
    ("folder-download",  "다운로드 폴더",        lambda c: folder(c, "download")),
    ("folder-pictures",  "사진 폴더",           lambda c: folder(c, "pictures")),
    ("folder-music",     "음악 폴더",           lambda c: folder(c, "music")),
    ("folder-videos",    "동영상 폴더",          lambda c: folder(c, "videos")),
    ("user-desktop",     "바탕화면 폴더",        lambda c: folder(c, "desktop")),
    ("web-browser",      "인터넷 브라우저",       lambda c: browser(c)),
    ("file-manager",     "파일 탐색기",          lambda c: folder(c, "search")),
    ("terminal",         "터미널·명령 프롬프트",   lambda c: terminal(c)),
    ("text-editor",      "메모장·텍스트 편집기",   lambda c: text_editor(c)),
    ("calculator",       "계산기",              lambda c: calculator(c)),
    ("music",            "음악 플레이어",        lambda c: music(c)),
    ("video",            "동영상 플레이어",       lambda c: video(c)),
    ("photos",           "사진 보기",           lambda c: photos(c)),
    ("mail",             "메일",               lambda c: mail(c)),
    ("games",            "게임",               lambda c: games(c)),
]


def raw_svg(fn, small, fit=None):
    c = Ctx(small)
    body = fn(c)
    g_open = f'<g transform="{fit}">' if fit else "<g>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">\n'
            f'  <defs>{DEFS}\n  </defs>\n  {g_open}\n{body}\n  </g>\n</svg>\n')
