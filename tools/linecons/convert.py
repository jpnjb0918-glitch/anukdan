"""Converts Arcticons (CC BY-SA 4.0) white SVG icons + appfilter into compact data for the launcher.

Usage: python3 convert.py <Arcticons>/icons/white <Arcticons>/app/src/main/assets/appfilter.xml <out dir>

Output (in OUT dir):
  names.txt      every icon name, sorted (Java String.compareTo order), one per line
  icons/NN.txt   icon data in the same order, CHUNK lines per file (file NN holds names[NN*CHUNK ...])
                 line = element|element|...  element = flags + ' ' + SVG path data in the 48x48 box
                 flags: S<w> stroke width, D<a,b,..> dash intervals, O<x> dash offset, F fill, E even-odd fill,
                        W<n> paper fill of one closed outline, n = how many other closed outlines enclose it
                        (W elements come first, outermost first; the launcher colours them white / pale cyan)
                 path data: relative m/l/h/v/c/q/a/z commands, 2 decimals
  map/NN.txt     package/class<TAB>icon   ('@prefix' = date icons prefix1..prefix31)
                 NN = Java String.hashCode() of the package name, floorMod 64
"""
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from svgelements import SVG, Shape, Path, Move, Line, Close, CubicBezier, QuadraticBezier, Arc, Color
from shapely.geometry import Polygon

SRC, APPFILTER, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
SHARDS = 64


def jhash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


def shard(s):
    return jhash(s) % SHARDS  # Python % is already floorMod


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def whitish(c):
    if c is None or c.value is None:
        return False
    try:
        if c.alpha == 0:
            return False
        return (c.red + c.green + c.blue) / 3 > 127
    except Exception:
        return False


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    if s.startswith("0."):
        s = s[1:]
    elif s.startswith("-0."):
        s = "-" + s[2:]
    return s


def join(vals):
    """SVG-style compact number list: no space before a minus sign."""
    out = ""
    for v in vals:
        t = fmt(v)
        if out and not t.startswith("-"):
            out += " "
        out += t
    return out


def r2(v):
    return round(v, 2)


def path_data(p):
    """Relative SVG path syntax (m l h v c q a z) with 2-decimal rounding and no drift."""
    d = []
    cx = cy = sx = sy = 0.0  # rounded current point and subpath start
    first = True
    for seg in p:
        if isinstance(seg, Move):
            x, y = r2(seg.end.x), r2(seg.end.y)
            d.append(("M" + join((x, y))) if first else ("m" + join((x - cx, y - cy))))
            first = False
            cx, cy, sx, sy = x, y, x, y
        elif isinstance(seg, Close):
            d.append("z")
            cx, cy = sx, sy
        elif isinstance(seg, Line):
            x, y = r2(seg.end.x), r2(seg.end.y)
            dx, dy = r2(x - cx), r2(y - cy)
            if dx == 0 and dy == 0:
                pass
            elif dy == 0:
                d.append("h" + join((dx,)))
            elif dx == 0:
                d.append("v" + join((dy,)))
            else:
                d.append("l" + join((dx, dy)))
            cx, cy = x, y
        elif isinstance(seg, CubicBezier):
            v = [r2(seg.control1.x), r2(seg.control1.y), r2(seg.control2.x), r2(seg.control2.y), r2(seg.end.x), r2(seg.end.y)]
            d.append("c" + join((v[0] - cx, v[1] - cy, v[2] - cx, v[3] - cy, v[4] - cx, v[5] - cy)))
            cx, cy = v[4], v[5]
        elif isinstance(seg, QuadraticBezier):
            v = [r2(seg.control.x), r2(seg.control.y), r2(seg.end.x), r2(seg.end.y)]
            d.append("q" + join((v[0] - cx, v[1] - cy, v[2] - cx, v[3] - cy)))
            cx, cy = v[2], v[3]
        elif isinstance(seg, Arc):
            x, y = r2(seg.end.x), r2(seg.end.y)
            rx, ry = abs(seg.rx), abs(seg.ry)
            rot = math.degrees(seg.get_rotation().as_radians) if hasattr(seg, "get_rotation") else 0.0
            large = 1 if abs(seg.sweep) > math.pi else 0
            sweep = 1 if seg.sweep > 0 else 0
            if rx < 1e-6 or ry < 1e-6:
                d.append("l" + join((x - cx, y - cy)))
            else:
                d.append("a" + " ".join((fmt(r2(rx)), fmt(r2(ry)), fmt(r2(rot % 360)), str(large), str(sweep))) + " " + join((x - cx, y - cy)))
            cx, cy = x, y
    return "".join(d)


def subpaths(p):
    out, cur = [], []
    for seg in p:
        if isinstance(seg, Move) and cur:
            out.append(cur)
            cur = []
        cur.append(seg)
    if cur:
        out.append(cur)
    return out


def closed_polygon(segs):
    """Polygon of a closed subpath (explicit Z, or ends where it starts), else None."""
    sp = Path(*segs)
    n = max(48, 12 * len(segs))
    try:
        pts = [sp.point(t / n) for t in range(n + 1)]
    except Exception:
        return None
    closed = any(isinstance(x, Close) for x in segs)
    if not closed and abs(pts[0].x - pts[-1].x) < 0.05 and abs(pts[0].y - pts[-1].y) < 0.05:
        closed = True
    if not closed:
        return None
    try:
        poly = Polygon([(q.x, q.y) for q in pts]).buffer(0)
    except Exception:
        return None
    return poly if poly.area > 1.0 else None


def convert(path):
    svg = SVG.parse(path, reify=True)
    out = []
    papers = []  # (segments, polygon) of closed outlines that get a paper fill
    for e in svg.elements():
        if not isinstance(e, Shape):
            continue
        stroke = whitish(e.stroke)
        fill = whitish(e.fill)
        if not stroke and not fill:
            continue
        p = Path(e)
        p.reify()
        data = path_data(p)
        if not data or len(data) < 3:
            continue
        flags = ""
        if stroke:
            w = e.implicit_stroke_width if hasattr(e, "implicit_stroke_width") else e.stroke_width
            flags += "S" + fmt(r2(w if w else 1))
            dash = str(e.values.get("stroke-dasharray", "") or "").strip()
            if dash and dash != "none":
                try:
                    k = (w / e.stroke_width) if (w and e.stroke_width) else 1.0
                    vals = [float(v) * k for v in re.split(r"[\s,]+", dash) if v]
                    if len(vals) % 2:
                        vals = vals * 2
                    if vals and sum(vals) > 0:
                        flags += "D" + ",".join(fmt(r2(v)) for v in vals)
                        off = float(str(e.values.get("stroke-dashoffset", "0") or "0").replace("px", "") or 0) * k
                        if off:
                            flags += "O" + fmt(r2(off))
                except ValueError:
                    pass
            for segs in subpaths(p):
                poly = closed_polygon(segs)
                if poly is not None:
                    papers.append((segs, poly))
        if fill:
            flags += "F"
            if str(e.values.get("fill-rule", "")).strip() == "evenodd":
                flags += "E"
        out.append(flags + " " + data)
    # paper fills: depth = number of bigger closed outlines around it; outermost drawn first
    depth = []
    for i, (_, poly) in enumerate(papers):
        rp = poly.representative_point()
        depth.append(sum(1 for j, (_, q) in enumerate(papers) if j != i and q.area > poly.area and q.contains(rp)))
    order = sorted(range(len(papers)), key=lambda i: (depth[i], -papers[i][1].area))
    fills = []
    for i in order:
        d = path_data(Path(*papers[i][0]))
        if d:
            fills.append(f"W{depth[i]} {d}")
    return "|".join(fills + out)


CHUNK = 256


def main():
    os.makedirs(f"{OUT}/icons", exist_ok=True)
    os.makedirs(f"{OUT}/map", exist_ok=True)
    # Java compares strings by UTF-16 code units; icon names are ASCII so Python's order matches
    names = sorted(f[:-4] for f in os.listdir(SRC) if f.endswith(".svg"))
    assert all(n.isascii() and "\t" not in n for n in names)
    data, failed = {}, []
    for i, n in enumerate(names):
        try:
            d = convert(f"{SRC}/{n}.svg")
        except Exception as ex:
            failed.append((n, str(ex)[:80]))
            continue
        if not d:
            failed.append((n, "empty"))
            continue
        data[n] = d
    names = [n for n in names if n in data]
    open(f"{OUT}/names.txt", "w").write("\n".join(names) + "\n")
    for k in range(0, len(names), CHUNK):
        open(f"{OUT}/icons/{k // CHUNK:02d}.txt", "w").write("".join(data[n] + "\n" for n in names[k:k + CHUNK]))
    have = set(names)

    # appfilter: same rules as the launcher's icon pack reader (first entry wins, spaces trimmed)
    maps = [dict() for _ in range(SHARDS)]
    items = cals = skipped = 0
    for ev, el in ET.iterparse(APPFILTER):
        if el.tag not in ("item", "calendar"):
            continue
        comp = (el.get("component") or "").strip()
        if not (comp.startswith("ComponentInfo{") and comp.endswith("}")):
            continue
        comp = comp[14:-1].strip()
        if "/" not in comp:
            continue
        pkg, cls = comp.split("/", 1)
        if cls.startswith("."):
            cls = pkg + cls
        if "\t" in pkg or "\t" in cls or "\n" in cls:
            continue
        key = f"{pkg}/{cls}"
        m = maps[shard(pkg)]
        if el.tag == "item":
            d = el.get("drawable") or ""
            if d not in have:
                skipped += 1
                continue
            if key not in m:
                m[key] = d
                items += 1
        else:
            pre = el.get("prefix") or ""
            if pre and f"{pre}1" in have:
                m[key] = "@" + pre  # date icons win over a plain icon for the same app
                cals += 1
    for k in range(SHARDS):
        open(f"{OUT}/map/{k:02d}.txt", "w").write("".join(f"{c}\t{d}\n" for c, d in sorted(maps[k].items())))
    print(f"icons: {len(names)} converted, {len(failed)} failed; map: {items} apps, {cals} date icons, {skipped} entries without an icon")
    for f in failed[:20]:
        print("  failed:", *f)


main()
