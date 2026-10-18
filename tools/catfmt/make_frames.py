"""Frame layers for the generated cat icons, drawn with the cat icon pack's own parts (pack.py).

back_<colour>.png : peeking cat behind + coloured tile + shine   (512 x 512), colour = cyan / gold / pink / paper
paws.png          : the two paws gripping the tile's top edge      (512 x 512)
Everything is scaled about the centre so the drawing fills the canvas like the hand-made icons (~90%).
The glyph box (in 512 units) is printed at the end.
"""
import io, sys
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
import cairosvg
from pack import Ctx, peek, stroke, DEFS

OUT = sys.argv[1]
EXTRA = '''
<linearGradient id="gGold" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F7D774"/><stop offset="0.5" stop-color="#EDBF35"/><stop offset="1" stop-color="#D49E12"/></linearGradient>
<linearGradient id="gPink" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F7B0B0"/><stop offset="0.5" stop-color="#EF8A8A"/><stop offset="1" stop-color="#DC6262"/></linearGradient>
<linearGradient id="gNavy2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4E76A0"/><stop offset="0.5" stop-color="#3B5F82"/><stop offset="1" stop-color="#2C4864"/></linearGradient>'''
# no dark tile: the glyph lines are black. Paper = the white body of the mail/calendar icons
TILES = {'cyan': 'url(#gBody)', 'gold': 'url(#gGold)', 'pink': 'url(#gPink)', 'paper': 'url(#gPaper)'}
EDGE = 178

# drawing bounds before scaling: x 50..462, y 58..474 -> fit into 24..488
K = 464 / 416
CY = 266
def svg(inner):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">'
            f'<defs>{DEFS}{EXTRA}</defs><g transform="translate(256 256) scale({K:.5f}) translate(-256 -{CY})">{inner}</g></svg>')

c = Ctx(False)
kitty, paws = peek(c, 256, EDGE, 0.62)
for name, fill in TILES.items():
    body = f'<rect x="58" y="{EDGE}" width="396" height="{466 - EDGE}" rx="64" fill="{fill}" {stroke(c)}/>'
    shine = f'<path d="M 104 {EDGE + 28} L 408 {EDGE + 28}" stroke="#FFFFFF" stroke-opacity="0.4" stroke-width="10" stroke-linecap="round"/>'
    cairosvg.svg2png(bytestring=svg(kitty + body + shine).encode(), write_to=f'{OUT}/back_{name}.png')
cairosvg.svg2png(bytestring=svg(paws).encode(), write_to=f'{OUT}/paws.png')
print('glyph box centre y = %.2f, side = %.2f (512 units)' % (256 + (326 - CY) * K, 256 * K))
