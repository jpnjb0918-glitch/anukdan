"""Counts icon drawings in a logcat dump (threadtime format) for one process.

Debug builds of both the old and the new launcher log every icon they draw ("cat icon …" / "pack icon …").
Lines from the main thread (tid == pid) are drawings that made the screen wait.
Prints: main=<count> other=<count> main_ms=<approx. main-thread time spent drawing icons>
"""
import re, sys
from datetime import datetime

path, pid = sys.argv[1], sys.argv[2].strip()
pat = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+Anuki\s*:\s+(cat icon|pack icon)')
main, other, times = 0, 0, []
for line in open(path, errors='ignore'):
    m = pat.match(line)
    if not m or m.group(2) != pid:
        continue
    if m.group(3) == pid:
        main += 1
        times.append(datetime.strptime('2000-' + m.group(1), '%Y-%m-%d %H:%M:%S.%f'))
    else:
        other += 1
# time between consecutive main-thread drawings that belong to one burst (each line is logged when a drawing ends)
ms = 0.0
for a, b in zip(times, times[1:]):
    d = (b - a).total_seconds() * 1000
    if d <= 250:
        ms += d
print(f"main={main} other={other} main_ms={ms:.0f}")
