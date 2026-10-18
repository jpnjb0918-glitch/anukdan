"""Reference render of the generated cat icons from the converted data (same rules as the launcher)."""
import io, re, sys, glob
import cairosvg
from PIL import Image

DATA_DIR, FRAMES = sys.argv[1], sys.argv[2]
names = open(f'{DATA_DIR}/names.txt').read().split()
rows = []
for f in sorted(glob.glob(f'{DATA_DIR}/icons/*.txt')):
    rows += open(f).read().rstrip('\n').split('\n')
DATA = dict(zip(names, rows))
FLAG = re.compile(r"(?:W(\d+))?(?:S([\d.]+))?(?:D([\d.,]+))?(?:O(-?[\d.]+))?(F)?(E)?$")
INK, PAPER = '#111111', ('#FFFFFF', '#8FEAE8')
WEIGHT = 2.9
CY, SIDE = 322.92, 285.54
COLOURS = ['cyan', 'cyan', 'cyan', 'cyan', 'cyan', 'gold', 'pink', 'paper']

def jhash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h

def glyph(name):
    fills, ink = [], []
    for el in DATA[name].split('|'):
        flags, d = el.split(' ', 1)
        m = FLAG.match(flags)
        if m.group(1) is not None:
            fills.append(f'<path d="{d}" fill="{PAPER[int(m.group(1)) % 2]}"/>')
            continue
        if m.group(5):
            ink.append(f'<path d="{d}" fill="{INK}" fill-rule="{"evenodd" if m.group(6) else "nonzero"}"/>')
        if m.group(2):
            da = f' stroke-dasharray="{m.group(3)}"' if m.group(3) else ''
            ink.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{float(m.group(2)) * WEIGHT}" stroke-linecap="round" stroke-linejoin="round"{da}/>')
    return ''.join(fills + ink)

def render(name, size, colour=None):
    colour = colour or COLOURS[jhash(name) % 8]
    k = SIDE / 48
    g = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">'
         f'<g transform="translate({256 - SIDE / 2:.2f} {CY - SIDE / 2:.2f}) scale({k:.5f})">{glyph(name)}</g></svg>')
    im = Image.open(f'{FRAMES}/back_{colour}.png').convert('RGBA')
    im.alpha_composite(Image.open(io.BytesIO(cairosvg.svg2png(bytestring=g.encode()))).convert('RGBA'))
    im.alpha_composite(Image.open(f'{FRAMES}/paws.png').convert('RGBA'))
    return im.resize((size, size), Image.LANCZOS)

if __name__ == '__main__':
    sample = sys.argv[4].split(',')
    S = 144
    cols = 12
    rows_n = (len(sample) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * S + 20, rows_n * S + 20 + S + 10), (52, 124, 124, 255))
    ours = ['web-browser', 'camera', 'settings', 'mail', 'calendar', 'clock', 'chat', 'store', 'notes', 'music', 'games', 'folder']
    for i, n in enumerate(ours):
        sheet.alpha_composite(Image.open(f'app/src/main/assets/icons/{n}.png').convert('RGBA').resize((S, S), Image.LANCZOS), (10 + i * S, 10))
    for i, n in enumerate(sample):
        sheet.alpha_composite(render(n, S), (10 + (i % cols) * S, 20 + S + (i // cols) * S))
    sheet.save(sys.argv[3])
