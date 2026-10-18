"""Generate the cat browser-style app icon as SVG.

Variants
  master : full detail (globe grid, gloss, bell, mouth) for 48px and up
  small  : thicker lines, fewer details for 16-32px
"""
import math, sys

INK = "#111111"
WHITE = "#FFFFFF"
CYAN = "#4BD5D6"      # sampled from sheet ears (#48d4d4~#4cd4d4)
BELL = "#9A9A9A"      # sampled bell gray (#989898)

CX, CY, R = 256, 298, 202  # globe

HEAD = (
    "M 78 196 "
    "L 92.6 111.7 Q 96 92 113.1 102.3 "   # left ear outer edge + rounded apex
    "L 189 148 "                            # left ear inner edge
    "C 220 136 292 136 323 148 "            # top of head
    "L 398.9 102.3 Q 416 92 419.4 111.7 "   # right ear inner edge + apex
    "L 434 196 "                            # right ear outer edge
    "C 446 220 450 240 446 262 "            # right cheek
    "C 442 300 420 318 380 324 "
    "C 330 332 300 334 256 334 "
    "C 212 334 182 332 132 324 "
    "C 92 318 70 300 66 262 "
    "C 62 240 66 220 78 196 Z"
)
EAR_L = "M 101 108 L 85 192 Q 124 182 156 156 Z"
EAR_R = "M 411 108 L 427 192 Q 388 182 356 156 Z"
COLLAR = "M 118 322 C 160 334 206 338 256 338 C 306 338 352 334 394 322"
EYE_L = "M 162 228 Q 186 205 214 221"
EYE_R = "M 298 221 Q 326 205 350 228"
MOUTH = "M 224 238 C 224 260 252 262 256 242 C 260 262 288 260 288 238"


def globe_grid():
    parts = []
    for rx in (92, 170):
        parts.append(f'<ellipse cx="{CX}" cy="{CY}" rx="{rx}" ry="{R}"/>')
    parts.append(f'<line x1="{CX}" y1="{CY-R}" x2="{CX}" y2="{CY+R}"/>')
    for dy in (-120, 0, 120):
        y0 = CY + dy
        w = math.sqrt(R * R - dy * dy)
        parts.append(f'<path d="M {CX-w:.1f} {y0} Q {CX} {y0+28} {CX+w:.1f} {y0}"/>')
    return "\n      ".join(parts)


def svg(variant="master", size=512):
    small = variant == "small"
    sw = 30 if small else 16             # outline width in 512-unit space
    fsw = 30 if small else 14.5          # face line width
    dy = 8                               # nudge cat down a little inside the globe

    grid = "" if small else f'''
    <g fill="none" stroke="#FFFFFF" stroke-opacity="0.32" stroke-width="8" stroke-linecap="round">
      {globe_grid()}
    </g>'''
    gloss = "" if small else '''
    <ellipse cx="170" cy="150" rx="110" ry="54" transform="rotate(-35 170 150)" fill="#FFFFFF" fill-opacity="0.20"/>'''
    shadow = f'''
    <path d="{HEAD}" transform="translate(0 {dy + (22 if small else 18)})" fill="#0B3B3D" fill-opacity="0.28"/>'''
    bell = "" if small else f'''
    <circle cx="256" cy="353" r="19" fill="{BELL}" stroke="{INK}" stroke-width="10"/>
    <path d="M 256 358 L 256 367" stroke="{INK}" stroke-width="7" stroke-linecap="round"/>'''
    mouth = "" if small else f'''
      <path d="{MOUTH}"/>'''
    eye_l, eye_r = EYE_L, EYE_R
    ear_l, ear_r = EAR_L, EAR_R
    g0, g1 = ("#8FEAE8", CYAN)
    if small:  # fatter arcs, inset ear colour, darker globe so ears read at 16-32px
        eye_l = "M 160 232 Q 188 200 220 224"
        eye_r = "M 292 224 Q 324 200 352 232"
        ear_l = "M 106 128 L 96 186 Q 126 176 146 160 Z"
        ear_r = "M 406 128 L 416 186 Q 386 176 366 160 Z"
        g0, g1 = ("#5CCFD0", "#36A9AB")

    # content spans y 84..508 / x 46..466 -> centre it with ~28px padding
    fit = "translate(256 256) scale(1.08) translate(-256 -296)"

    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{size}" height="{size}">
  <defs>
    <radialGradient id="globeGrad" cx="0.36" cy="0.28" r="0.82">
      <stop offset="0" stop-color="{g0}"/>
      <stop offset="0.38" stop-color="{g1}"/>
      <stop offset="0.80" stop-color="#2E9497"/>
      <stop offset="1" stop-color="#1E6467"/>
    </radialGradient>
    <clipPath id="globeClip"><circle cx="{CX}" cy="{CY}" r="{R}"/></clipPath>
  </defs>
<g transform="{fit}">

  <!-- globe -->
  <circle cx="{CX}" cy="{CY}" r="{R}" fill="url(#globeGrad)"/>
  <g clip-path="url(#globeClip)">{grid}{gloss}{shadow}
  </g>
  <circle cx="{CX}" cy="{CY}" r="{R}" fill="none" stroke="{INK}" stroke-width="{sw}"/>

  <g transform="translate(0 {dy})">
    <!-- head -->
    <path d="{HEAD}" fill="{WHITE}"/>
    <path d="{ear_l}" fill="{CYAN}" stroke="{CYAN}" stroke-width="4" stroke-linejoin="round"/>
    <path d="{ear_r}" fill="{CYAN}" stroke="{CYAN}" stroke-width="4" stroke-linejoin="round"/>
    <path d="{HEAD}" fill="none" stroke="{INK}" stroke-width="{sw}" stroke-linejoin="round"/>

    <!-- collar + bell -->
    <path d="{COLLAR}" fill="none" stroke="{INK}" stroke-width="{sw*1.7:.1f}" stroke-linecap="round"/>{bell}

    <!-- face -->
    <g fill="none" stroke="{INK}" stroke-width="{fsw}" stroke-linecap="round" stroke-linejoin="round">
      <path d="{eye_l}"/>
      <path d="{eye_r}"/>{mouth}
    </g>
  </g>
</g>
</svg>
'''


if __name__ == "__main__":
    open(sys.argv[1], "w").write(svg("master"))
    open(sys.argv[2], "w").write(svg("small"))
