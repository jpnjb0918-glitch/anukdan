"""Renders every original SVG and its converted data with cairosvg and compares the ink."""
import io, os, re, sys, glob
from multiprocessing import Pool
import cairosvg
from PIL import Image, ImageChops

SRC, OUT = sys.argv[1], sys.argv[2]
S = 96

def load():
    names = open(f"{OUT}/names.txt").read().split()
    rows = []
    for f in sorted(glob.glob(f"{OUT}/icons/*.txt")):
        rows += open(f).read().rstrip("\n").split("\n")
    assert len(rows) == len(names), (len(rows), len(names))
    return dict(zip(names, rows))

def to_svg(d):
    parts = []
    for el in d.split("|"):
        flags, path = el.split(" ", 1)
        sw = None; fill = "none"; rule = "nonzero"; dash = ""
        m = re.match(r"(?:S([\d.]+))?(?:D([\d.,]+))?(?:O(-?[\d.]+))?(F)?(E)?$", flags)
        if m.group(1):
            sw = m.group(1)
        if m.group(2):
            dash = f' stroke-dasharray="{m.group(2)}"' + (f' stroke-dashoffset="{m.group(3)}"' if m.group(3) else "")
        if "F" in flags:
            fill = "#fff"
        if "E" in flags:
            rule = "evenodd"
        stroke = f'stroke="#fff" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"{dash}' if sw else 'stroke="none"'
        parts.append(f'<path d="{path}" fill="{fill}" fill-rule="{rule}" {stroke}/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="{S}" height="{S}">' + "".join(parts) + "</svg>"

def raster(svgtext):
    png = cairosvg.svg2png(bytestring=svgtext.encode(), output_width=S, output_height=S, background_color="black")
    return Image.open(io.BytesIO(png)).convert("L")

def check(item):
    n, d = item
    try:
        orig = open(f"{SRC}/{n}.svg", encoding="utf-8").read()
        a = raster(orig); b = raster(to_svg(d))
        diff = ImageChops.difference(a, b)
        ink = max(1, sum(1 for p in ImageChops.lighter(a, b).getdata() if p > 64))
        bad = sum(1 for p in diff.getdata() if p > 96)
        return n, bad / ink
    except Exception as e:
        return n, -1.0

if __name__ == "__main__":
    data = load()
    with Pool(os.cpu_count()) as pool:
        res = pool.map(check, sorted(data.items()), chunksize=50)
    res.sort(key=lambda r: -r[1])
    errs = [r for r in res if r[1] < 0]
    vals = [r[1] for r in res if r[1] >= 0]
    import statistics
    print(f"checked {len(res)}  render errors {len(errs)}")
    for t in (0.02, 0.05, 0.10, 0.20):
        print(f"  > {t:.0%} differing pixels: {sum(1 for v in vals if v > t)}")
    print("  median", round(statistics.median(vals), 4))
    print("worst:", [(n, round(v, 3)) for n, v in res[:15]])
    open(f"{OUT}/verify.txt", "w").write("\n".join(f"{n}\t{v:.4f}" for n, v in res))
