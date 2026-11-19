"""Prints where every icon, folder and widget is in layout.json, to compare two layouts."""
import json
import sys

d = json.load(open(sys.argv[1]))
rows = []
for i in d.get('items', []):
    if i['container'] < 0:
        rows.append((i['id'], i['container'], i['screen'], i['x'], i['y']))
    else:
        rows.append((i['id'], 'in-folder', i['container'], i['x']))
for r in sorted(rows, key=str):
    print(*r)
