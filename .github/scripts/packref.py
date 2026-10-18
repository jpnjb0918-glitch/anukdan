"""Counts what an icon pack's appfilter/drawable xml contain (from `aapt2 dump xmltree` output or plain assets xml).

Writes the summary first, then every component -> drawable line, so the launcher's log can be checked against it.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

d = sys.argv[1]


def from_tree(path):
    """Parse aapt2 xmltree text into (tag, attrs) tuples."""
    out, cur = [], None
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.match(r'\s*E: (\S+)', line)
        if m:
            cur = (m.group(1), {})
            out.append(cur)
            continue
        m = re.match(r'\s*A: (?:[^=]*:)?(\w+)(?:\([^)]*\))?="([^"]*)"', line)
        if m and cur:
            cur[1][m.group(1)] = m.group(2)
    return out


def from_xml(path):
    try:
        return [(e.tag, dict(e.attrib)) for e in ET.parse(path).iter()]
    except Exception:
        return []


def load(name):
    for f, fn in ((f'{d}/{name}-tree.txt', from_tree), (f'{d}/{name}-assets.xml', from_xml)):
        if os.path.exists(f) and os.path.getsize(f) > 0:
            return f, fn(f)
    return None, []


src, af = load('appfilter')
items = [a for t, a in af if t == 'item' and a.get('component', '').startswith('ComponentInfo{')]
cals = [a for t, a in af if t == 'calendar']
backs = [a for t, a in af if t == 'iconback']
masks = [a for t, a in af if t == 'iconmask']
upons = [a for t, a in af if t == 'iconupon']
scales = [a for t, a in af if t == 'scale']
src2, dr = load('drawable')
dnames = {a.get('drawable') for t, a in dr if t == 'item' and a.get('drawable')}
print(f'appfilter source: {src}')
print(f'item entries: {len(items)}  unique components: {len({a["component"] for a in items})}')
print(f'calendar entries: {len(cals)}  {[a.get("component") for a in cals][:5]}')
print(f'iconback: {backs}  iconmask: {masks}  iconupon: {upons}  scale: {scales}')
print(f'drawable.xml source: {src2}  icon names: {len(dnames)}')
print('---- components')
for a in items:
    print(a['component'][14:-1], a.get('drawable', ''))
for a in cals:
    print(a.get('component', '')[14:-1], 'CALENDAR', a.get('prefix', ''))
