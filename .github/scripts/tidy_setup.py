"""홈 화면 정리 check: adds a page of known apps (several per kind) to layout.json, so the tidy-up has folders to make.

usage: tidy_setup.py layout.json components.txt out.json
"""
import json
import sys

layout = json.load(open(sys.argv[1]))
comps = [l.strip() for l in open(sys.argv[2], errors='replace') if '/' in l and ' ' not in l.strip()]
items = layout.get('items', [])
serial = next((i['key'].split('#', 1)[1] for i in items if '#' in i.get('key', '')), '0')
# packages that land in the same 종류별 kind (도구, 소셜·메신저, 사진, 생산성 …)
wanted = ['com.android.settings', 'com.google.android.deskclock', 'com.android.vending',
          'com.google.android.dialer', 'com.google.android.apps.messaging', 'com.google.android.gm',
          'com.google.android.calendar', 'com.google.android.apps.docs', 'com.google.android.documentsui',
          'com.android.camera2', 'com.google.android.GoogleCamera', 'com.google.android.apps.photos',
          'com.google.android.youtube', 'com.google.android.apps.maps', 'com.android.chrome']
picked = []
for pkg in wanted:
    c = next((c for c in comps if c.split('/')[0] == pkg), None)
    if c:
        picked.append(c)
page = layout.get('pages', 1)
next_id = max([i['id'] for i in items] + [0]) + 1
for n, c in enumerate(picked[:12]):
    items.append({'id': next_id + n, 'type': 0, 'container': -100, 'screen': page, 'x': n % 4, 'y': n // 4,
                  'spanX': 1, 'spanY': 1, 'key': c + '#' + serial, 'title': c.split('/')[0], 'widgetId': -1,
                  'provider': '', 'color': 'teal', 'swipeUp': '', 'cover': False})
layout['items'] = items
layout['pages'] = page + 1
json.dump(layout, open(sys.argv[3], 'w'), ensure_ascii=False)
print(f'added {len(picked[:12])} apps on page {page}: ' + ' '.join(c.split('/')[0].split('.')[-1] for c in picked[:12]))
