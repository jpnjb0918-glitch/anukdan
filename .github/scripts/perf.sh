#!/usr/bin/env bash
# Speed comparison on the same emulator: the previous release (perf/base.apk, built from its tag by the workflow)
# against this build. Each gets a fresh install with the default home screen and runs the same steps:
#   1. cold start x5 (am start -W TotalTime)
#   2. open the drawer half a second after a cold start (icons not drawn yet) and scroll it: frames
#   3. swipe home pages and open/scroll/close the drawer with everything warm: frames
#   4. five app updates in a row (reinstalling the test icon pack): frames while the launcher reacts
#   5. memory (total PSS)
# Results: shots/perf.txt (table) and shots/perf-*.txt (raw gfxinfo).
NEW=${1:-app/build/outputs/apk/standard/debug/app-standard-debug.apk}
BASE=perf/base.apk
OUT=shots/perf.txt
PKG=com.anuki.launcher
PACK=testiconpack/build/outputs/apk/debug/testiconpack-debug.apk

gfx() { # prints: frames janky% p50 p90 p99
  adb shell dumpsys gfxinfo $PKG > "shots/perf-$1.txt"
  python3 - "shots/perf-$1.txt" <<'EOF'
import re, sys
t = open(sys.argv[1], errors='ignore').read()
def g(p, d='-'):
    m = re.search(p, t)
    return m.group(1) if m else d
print(g(r'Total frames rendered: (\d+)'), g(r'Janky frames: \d+ \(([\d.]+)%\)'),
      g(r'50th percentile: (\d+)ms'), g(r'90th percentile: (\d+)ms'), g(r'99th percentile: (\d+)ms'))
EOF
}
skipped() { adb logcat -d | grep -E "Choreographer.*Skipped [0-9]+ frames" | grep -oE "Skipped [0-9]+" | awk '{s+=$2} END {print s+0}'; }
drawer_scroll() {
  adb shell input swipe 540 1500 540 400 200; sleep 1
  for i in 1 2 3; do adb shell input swipe 540 1800 540 700 250; sleep 0.5; done
  for i in 1 2 3; do adb shell input swipe 540 700 540 1800 250; sleep 0.5; done
  adb shell input keyevent KEYCODE_BACK; sleep 1
}

run() { # $1 = label, $2 = apk
  local L=$1
  adb uninstall $PKG >/dev/null 2>&1
  if ! adb install "$2" >/dev/null 2>&1; then echo "| $L | 설치 실패 | | | | | |" >> $OUT; return; fi
  adb shell cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME; sleep 8

  # 1. cold start
  local ts=""
  for i in 1 2 3 4 5; do
    adb shell am force-stop $PKG; sleep 2
    t=$(adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.HomeActivity | grep -E "^TotalTime" | awk '{print $2}' | tr -d '\r')
    ts="$ts ${t:-0}"; sleep 3
  done
  local med=$(echo $ts | tr ' ' '\n' | sort -n | sed -n 3p)

  # 2. drawer right after a cold start
  adb shell am force-stop $PKG; sleep 2
  adb logcat -c
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.HomeActivity >/dev/null
  sleep 0.5
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  drawer_scroll
  local early=$(gfx "$L-early") early_skip=$(skipped)

  # 3. warm: page swipes + drawer
  sleep 3
  adb logcat -c
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3; do adb shell input swipe 900 1200 150 1200 250; sleep 0.6; adb shell input swipe 150 1200 900 1200 250; sleep 0.6; done
  drawer_scroll
  local warm=$(gfx "$L-warm") warm_skip=$(skipped)

  # 4. app updates in a row
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb logcat -c
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4 5; do adb install -r "$PACK" >/dev/null 2>&1; done
  sleep 6
  local upd=$(gfx "$L-updates") upd_skip=$(skipped)

  # 5. memory
  local pss=$(adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS:|^ +TOTAL " | head -1 | grep -oE "[0-9]+" | head -1)

  echo "| $L | $med ms ($ts ) | $early / 끊김 $early_skip | $warm / 끊김 $warm_skip | $upd / 끊김 $upd_skip | $((${pss:-0} / 1024)) MB |" >> $OUT
}

{
  echo "## 속도 비교 (같은 에뮬레이터, 새로 설치한 기본 홈 화면)"
  echo
  echo "프레임 칸: 그린 프레임 수, 버벅인 프레임 %, 50/90/99번째 프레임 시간(ms) / 끊김 = Choreographer가 건너뛴 프레임 합계"
  echo
  echo "| 빌드 | 켜지는 시간 중앙값 (5번) | 켜자마자 서랍 열기 | 페이지·서랍 (준비된 뒤) | 앱 업데이트 5번 | 메모리 |"
  echo "|---|---|---|---|---|---|"
} > $OUT
if [ -s "$BASE" ]; then run "이전 ($(cat perf/base-tag.txt 2>/dev/null))" "$BASE"; else echo "| 이전 | 기준 APK 없음 | | | | |" >> $OUT; fi
run "이번 빌드" "$NEW"
# run the old one once more so a slow first run of the emulator doesn't decide the result
if [ -s "$BASE" ]; then run "이전 (다시)" "$BASE"; run "이번 빌드 (다시)" "$NEW"; fi
cat $OUT
