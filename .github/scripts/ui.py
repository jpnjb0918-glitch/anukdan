"""Tiny helper over `uiautomator dump`: list launcher icons or print an icon's centre."""
import re
import sys
import xml.etree.ElementTree as ET

path, mode = sys.argv[1], sys.argv[2]
try:
    nodes = [n for n in ET.parse(path).iter('node')
             if n.get('package') == 'com.anuki.launcher' and n.get('content-desc')]
except Exception:
    nodes = []


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2


if mode == 'list':
    for n in nodes:
        print(n.get('content-desc'), *center(n))
elif mode == 'dock' and nodes:
    bottom = max(center(n)[1] for n in nodes)
    row = sorted([n for n in nodes if abs(center(n)[1] - bottom) < 60], key=lambda n: center(n)[0])
    i = int(sys.argv[3])
    if i < len(row):
        print(*center(row[i]))
elif mode == 'find':
    for n in nodes:
        if n.get('content-desc') == sys.argv[3]:
            print(*center(n))
            break
elif mode == 'text':
    for n in ET.parse(path).iter('node'):
        if n.get('text') == sys.argv[3]:
            print(*center(n))
            break
elif mode == 'anydesc':
    for n in ET.parse(path).iter('node'):
        if n.get('content-desc') == sys.argv[3]:
            print(*center(n))
            break
elif mode == 'descprefix':
    for n in ET.parse(path).iter('node'):
        if (n.get('content-desc') or '').startswith(sys.argv[3]):
            print(*center(n))
            break
elif mode == 'textprefix':
    for n in ET.parse(path).iter('node'):
        if (n.get('text') or '').startswith(sys.argv[3]):
            print(*center(n))
            break
elif mode == 'desccontains':
    for n in ET.parse(path).iter('node'):
        if sys.argv[3] in (n.get('content-desc') or ''):
            print(*center(n))
            break
elif mode == 'classfirst':
    for n in ET.parse(path).iter('node'):
        if n.get('class') == sys.argv[3]:
            print(*center(n))
            break
elif mode == 'count':
    # number of nodes of a class, e.g. icon cells in a grid
    print(sum(1 for n in ET.parse(path).iter('node') if n.get('class') == sys.argv[3]))
elif mode == 'index':
    i = int(sys.argv[3])
    if i < len(nodes):
        print(*center(nodes[i]))
