#!/usr/bin/env bash
# Speed comparison on the same emulator: the previous release (perf/base.apk, built from its tag by the workflow)
# against this build, each a fresh install with the default home screen, same steps, twice each:
#   1. first start + first drawer opening: icons drawn on the main thread (screen waits) vs in the background
#   2. cold start x5 with another app in front (am start -W TotalTime)
#   3. five app updates in a row: icons drawn again, and on which thread
#   4. home page swipes and the drawer when everything is ready: frames whose UI-thread part was slow
#   5. memory (total PSS)
# Frame "jank %" is not used: the CI emulator draws in software, so nearly every frame misses its deadline anyway.
NEW=${1:-app/build/outputs/apk/standard/debug/app-standard-debug.apk}
BASE=perf/base.apk
OUT=shots/perf.txt
PKG=com.anuki.launcher
PACK=testiconpack/build/outputs/apk/debug/testiconpack-debug.apk

pidof_l() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
icons() { adb logcat -d > shots/perf-log.txt; python3 .github/scripts/perflog.py shots/perf-log.txt "$(pidof_l)"; }
slow_ui() { adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Number Slow UI thread" | awk -F': ' '{printf "%s ", $2}'; }
drawer_scroll() {
  adb shell input swipe 540 1500 540 400 200; sleep 1.5
  for i in 1 2 3; do adb shell input swipe 540 1800 540 700 250; sleep 0.5; done
  for i in 1 2 3; do adb shell input swipe 540 700 540 1800 250; sleep 0.5; done
  adb shell input keyevent KEYCODE_BACK; sleep 1
}

run() { # $1 = label, $2 = apk
  local L=$1
  adb uninstall $PKG >/dev/null 2>&1
  if ! adb install "$2" >/dev/null 2>&1; then echo "| $L | 설치 실패 | | | | |" >> $OUT; return; fi
  adb logcat -c
  adb shell cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME; sleep 1.5
  drawer_scroll
  sleep 4
  local first=$(icons)

  # cold start with Settings in front, so the system doesn't restart the home screen by itself first
  local ts=""
  for i in 1 2 3 4 5; do
    adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 1
    adb shell am force-stop $PKG; sleep 2
    t=$(adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.HomeActivity | grep -E "^TotalTime" | awk '{print $2}' | tr -d '\r')
    ts="$ts ${t:-?}"; sleep 3
  done
  local med=$(echo $ts | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | awk '{a[NR]=$1} END {print (NR ? a[int((NR+1)/2)] : "?")}')

  # app updates in a row
  adb shell input keyevent KEYCODE_HOME; sleep 4
  adb logcat -c
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4 5; do adb install -r "$PACK" >/dev/null 2>&1; done
  sleep 6
  local upd=$(icons) upd_ui=$(slow_ui)

  # warm: page swipes + drawer
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3; do adb shell input swipe 900 1200 150 1200 250; sleep 0.6; adb shell input swipe 150 1200 900 1200 250; sleep 0.6; done
  drawer_scroll
  local warm_ui=$(slow_ui)

  local pss=$(adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS:" | grep -oE "[0-9]+" | head -1)
  [ -z "$pss" ] && pss=$(adb shell dumpsys meminfo $PKG | grep -E "^ +TOTAL " | head -1 | awk '{print $2}')

  echo "| $L | $first | $med ms ($ts ) | $upd · 느린 프레임 $upd_ui | 느린 프레임 $warm_ui | $(( ${pss:-0} / 1024 )) MB |" >> $OUT
}

{
  echo "## 속도 비교 (같은 에뮬레이터, 새로 설치한 기본 홈 화면)"
  echo
  echo "아이콘 칸: main = 화면(메인 스레드)이 기다리며 그린 아이콘 수, other = 뒤에서 그린 수, main_ms = 메인 스레드가 아이콘 그리는 데 쓴 대략의 시간"
  echo "느린 프레임 칸: 그린 프레임 수, 그중 UI 스레드 쪽이 느렸던 프레임 수"
  echo
  echo "| 빌드 | 처음 켜고 서랍 열기: 아이콘 | 켜지는 시간 중앙값 (5번) | 앱 업데이트 5번: 아이콘 | 페이지·서랍 (준비된 뒤) | 메모리 |"
  echo "|---|---|---|---|---|---|"
} > $OUT
if [ -s "$BASE" ]; then run "이전 ($(cat perf/base-tag.txt 2>/dev/null))" "$BASE"; else echo "| 이전 | 기준 APK 없음 | | | | |" >> $OUT; fi
run "이번 빌드" "$NEW"
if [ -s "$BASE" ]; then run "이전 (다시)" "$BASE"; run "이번 빌드 (다시)" "$NEW"; fi
rm -f shots/perf-log.txt
cat $OUT
