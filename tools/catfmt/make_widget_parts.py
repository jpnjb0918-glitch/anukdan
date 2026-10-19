"""Layers for the 아늑단 widgets, drawn with the cat icon pack's own parts (pack.py).

head_<expr>.png : the peeking cat (drawn BEHIND a widget's panel)       expr = happy / open
paws.png        : the two paws gripping the panel's top edge (drawn IN FRONT of the panel)
All three share one crop so they line up; the panel's top edge sits at EDGE_Y of the crop.
Printed: crop size in icon units, edge position and the scale, for CatWidgets.kt.
"""
import io, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cairosvg
from PIL import Image
from pack import Ctx, peek, DEFS

OUT = sys.argv[1]
SCALE = 2          # px per icon unit, so the parts stay sharp on big widgets
EDGE = 178
c = Ctx(False)

def render(inner):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="{512*SCALE}" height="{512*SCALE}">'
           f'<defs>{DEFS}</defs>{inner}</svg>')
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode()))).convert('RGBA')

heads = {}
for expr in ('happy', 'open'):
    kitty, paws = peek(c, 256, EDGE, 0.62, expr=expr)
    heads[expr] = render(kitty)
paws_img = render(paws)

# one crop for all layers: from the top of the ears to just under the paws, full width of head + paws
boxes = [im.getbbox() for im in list(heads.values()) + [paws_img]]
x1 = min(b[0] for b in boxes); x2 = max(b[2] for b in boxes)
y1 = min(b[1] for b in boxes)
y2 = paws_img.getbbox()[3]
pad = 2 * SCALE
box = (x1 - pad, y1 - pad, x2 + pad, y2 + pad)
for expr, im in heads.items():
    h = im.crop(box)
    # the head is hidden behind the panel below the edge: drop that part so nothing leaks past rounded corners
    edge_px = EDGE * SCALE - box[1]
    for y in range(int(edge_px) + 1, h.height):
        for x in range(h.width):
            h.putpixel((x, y), (0, 0, 0, 0))
    h.save(f'{OUT}/head_{expr}.png', optimize=True)
paws_img.crop(box).save(f'{OUT}/paws.png', optimize=True)
w = (box[2] - box[0]) / SCALE; hgt = (box[3] - box[1]) / SCALE
print(f'crop {w:.1f} x {hgt:.1f} units, edge at {EDGE - box[1]/SCALE:.1f} units from the top, '
      f'head centre x {256 - box[0]/SCALE:.1f}, scale {SCALE} px/unit')
