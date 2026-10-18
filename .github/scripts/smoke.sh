#!/bin/bash
# Installs the launcher on the emulator, walks through the main screens and saves screenshots + logs to ./shots
set -x
mkdir -p shots
LOG=shots/steps.txt
# the badge build: it has everything the standard build has plus the badge service
APK=app/build/outputs/apk/badge/debug/app-badge-debug.apk
STD_APK=app/build/outputs/apk/standard/debug/app-standard-debug.apk
shot() { adb exec-out screencap -p > "shots/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "shots/$1.xml" >/dev/null 2>&1; }
ui() { python3 .github/scripts/ui.py "$@"; }
# long-press the app with this label (home or drawer) and pick "아이콘 바꾸기"
open_icon_picker() {
  dump lp
  local P=$(ui shots/lp.xml find "$1")
  [ -z "$P" ] && { echo "no icon $1" >> $LOG; return 1; }
  adb shell input swipe $P $P 1500
  sleep 1
  dump lpmenu
  local M=$(ui shots/lpmenu.xml text "아이콘 바꾸기")
  [ -z "$M" ] && { echo "no menu for $1" >> $LOG; return 1; }
  adb shell input tap $M
  sleep 2
  dump picker
  return 0
}
# scroll the current screen until a text shows up, print its centre
find_scroll() {
  for i in 1 2 3 4 5 6 7 8; do
    dump fs
    local P=$(ui shots/fs.xml text "$1")
    if [ -n "$P" ]; then echo "$P"; return 0; fi
    adb shell input swipe 540 1800 540 900 300
    sleep 1
  done
  return 1
}
step() { echo "== $*" >> $LOG; }

adb install -r "$APK" | tee -a $LOG
adb install -r testiconpack/build/outputs/apk/debug/testiconpack-debug.apk | tee -a $LOG
# a real, published icon pack (downloaded by the workflow), if the download worked
REALPACK=realpack/arcticons.apk
if [ -s "$REALPACK" ]; then adb install -r "$REALPACK" 2>&1 | tee -a $LOG; else echo "no real icon pack apk" >> $LOG; fi
adb shell cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > shots/components.txt 2>&1
echo "emulator date: $(adb shell date)" >> $LOG
# the stock launcher tends to ANR on CI emulators and covers the screen; switch it off
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher 2>&1 | tee -a $LOG
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.anuki.launcher 2>&1 | tee -a $LOG
adb shell cmd role get-role-holders --user 0 android.app.role.HOME 2>&1 | tee -a $LOG
adb logcat -G 16M
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | tee -a $LOG
sleep 15
adb shell input keyevent KEYCODE_HOME
sleep 4
shot 01-home
dump home
step "home icons"; ui shots/home.xml list >> $LOG

step "drawer"
for try in 1 2 3; do
  adb shell input swipe 540 1500 540 400 200
  sleep 3
  dump drawer
  if grep -q "앱 검색" shots/drawer.xml; then echo "drawer open (try $try)" >> $LOG; break; fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
done
shot 02-drawer
ui shots/drawer.xml list | head -40 >> $LOG
adb shell input tap 540 215
sleep 1
adb shell input text "set"
sleep 2
shot 03-drawer-search
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_HOME
sleep 2

step "home long press"
adb shell input swipe 540 700 540 700 1500
sleep 1
shot 04-home-menu
adb shell input keyevent KEYCODE_BACK
sleep 1

step "icon long press"
dump home2
D0=$(ui shots/home2.xml dock 0)
D1=$(ui shots/home2.xml dock 1)
echo "dock0=$D0 dock1=$D1" >> $LOG
if [ -n "$D0" ]; then
  adb shell input swipe $D0 $D0 1500
  sleep 1
  shot 05-icon-menu
  dump iconmenu
  ui shots/iconmenu.xml list >> $LOG
  adb shell input keyevent KEYCODE_BACK
  sleep 1
fi

step "folder by drag"
if [ -n "$D0" ] && [ -n "$D1" ]; then
  adb shell input draganddrop $D0 $D1 2500
  sleep 3
  shot 06-after-folder-drop
  dump home3
  ui shots/home3.xml list >> $LOG
  F=$(ui shots/home3.xml find 폴더)
  echo "folder=$F" >> $LOG
  if [ -n "$F" ]; then
    adb shell input tap $F
    sleep 2
    shot 07-folder-open
    adb shell input keyevent KEYCODE_BACK
  fi
fi

step "drawer -> home drag"
adb shell input swipe 540 1500 540 400 200
sleep 3
dump drawer2
A0=$(ui shots/drawer2.xml find "아늑이 런처 설정")
echo "drawer app=$A0" >> $LOG
if [ -n "$A0" ]; then
  adb shell input draganddrop $A0 540 900 3000
  sleep 3
  shot 08-after-drawer-drag
fi
adb shell input keyevent KEYCODE_HOME
sleep 1

step "swipe down (notifications)"
adb shell input swipe 540 600 540 1500 200
sleep 2
shot 09-swipe-down
adb shell input keyevent KEYCODE_BACK
adb shell cmd statusbar collapse
sleep 1

step "wallpaper"
adb shell input swipe 540 700 540 700 1500
sleep 1
dump menu2
W=$(ui shots/menu2.xml text 배경화면)
echo "wallpaper item=$W" >> $LOG
if [ -n "$W" ]; then
  adb shell input tap $W
  sleep 2
  shot 10-wallpaper-dialog
  dump wp
  T=$(ui shots/wp.xml text "고양이 패턴")
  echo "thumb label=$T" >> $LOG
  if [ -n "$T" ]; then
    set -- $T
    adb shell input tap $1 $(( $2 - 150 ))
    sleep 2
    dump wp2
    H=$(ui shots/wp2.xml text "홈 화면")
    echo "home option=$H" >> $LOG
    [ -n "$H" ] && adb shell input tap $H
    sleep 6
  fi
fi
adb shell input keyevent KEYCODE_HOME
sleep 2
shot 11-after-wallpaper

step "widget picker"
adb shell input swipe 540 700 540 700 1500
sleep 1
dump menu3
WG=$(ui shots/menu3.xml text "위젯 추가")
if [ -n "$WG" ]; then
  adb shell input tap $WG
  sleep 3
  shot 12-widget-picker
  adb shell input keyevent KEYCODE_BACK
fi
sleep 1

step "add analog clock widget"
adb shell input swipe 540 700 540 700 1500
sleep 1
dump menu4
WG=$(ui shots/menu4.xml text "위젯 추가")
if [ -n "$WG" ]; then
  adb shell input tap $WG
  sleep 3
  # other apps' widgets (e.g. the icon pack's) can push it below the fold
  AN=$(find_scroll "Analog")
  echo "analog=$AN" >> $LOG
  if [ -n "$AN" ]; then
    adb shell input tap $AN
    sleep 3
    shot 12b-bind-dialog
    dump bind
    for label in "Create" "만들기" "Allow" "허용"; do
      B=$(ui shots/bind.xml text "$label")
      if [ -n "$B" ]; then echo "bind button=$label $B" >> $LOG; adb shell input tap $B; break; fi
    done
    sleep 4
    dump cfg
    FACE=$(ui shots/cfg.xml text "Scallop")
    echo "clock face=$FACE" >> $LOG
    [ -n "$FACE" ] && adb shell input tap $FACE
    sleep 4
    shot 12c-widget-added
    dump afterwidget
    grep -o 'package="com.google.android.deskclock"' shots/afterwidget.xml | head -1 >> $LOG
  fi
fi
adb shell input keyevent KEYCODE_HOME
sleep 1

step "badges"
adb shell cmd notification allow_listener com.anuki.launcher/com.anuki.launcher.BadgeService 2>&1 | tee -a $LOG
sleep 2
adb shell cmd notification post -S bigtext -t "아늑이" smoke_test "배지 확인용 알림" 2>&1 | tee -a $LOG
sleep 3

step "change one icon (built-in cat icon)"
adb shell input keyevent KEYCODE_HOME
sleep 2
if open_icon_picker "Clock"; then
  shot 16-icon-picker
  G=$(ui shots/picker.xml find "고양이 아이콘: games")
  echo "games cell=$G" >> $LOG
  [ -n "$G" ] && adb shell input tap $G
  sleep 2
  shot 17-icon-changed
fi

step "photo icon"
adb push .github/scripts/test_photo.png /sdcard/Download/anuki_test.png | tee -a $LOG
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/anuki_test.png >/dev/null 2>&1
adb shell input keyevent KEYCODE_HOME
sleep 2
if open_icon_picker "Camera"; then
  PH=$(ui shots/picker.xml find "아이콘 선택: 사진")
  echo "photo cell=$PH" >> $LOG
  if [ -n "$PH" ]; then
    adb shell input tap $PH
    sleep 4
    shot 18-document-picker
    dump docs
    F=$(ui shots/docs.xml descprefix "anuki_test.png")
    [ -z "$F" ] && F=$(ui shots/docs.xml text anuki_test.png)
    if [ -z "$F" ]; then
      R=$(ui shots/docs.xml anydesc "Show roots")
      [ -n "$R" ] && adb shell input tap $R && sleep 2
      dump roots
      D=$(ui shots/roots.xml text "Downloads")
      [ -n "$D" ] && adb shell input tap $D && sleep 3
      dump docs2
      F=$(ui shots/docs2.xml descprefix "anuki_test.png")
      [ -z "$F" ] && F=$(ui shots/docs2.xml text anuki_test.png)
    fi
    echo "photo file=$F" >> $LOG
    if [ -n "$F" ]; then
      adb shell input tap $F
      sleep 3
      dump style
      ST=$(ui shots/style.xml text "고양이 얼굴 틀에 넣기")
      echo "style=$ST" >> $LOG
      [ -n "$ST" ] && adb shell input tap $ST
      sleep 5
    fi
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    shot 19-photo-icon
  fi
fi

step "launcher app icon -> pink"
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 3
AI=$(find_scroll "아늑이 런처 앱 아이콘")
echo "app icon row=$AI" >> $LOG
if [ -n "$AI" ]; then
  adb shell input tap $AI
  sleep 2
  dump appicon
  PK=$(ui shots/appicon.xml text "핑크")
  [ -n "$PK" ] && adb shell input tap $PK
  sleep 3
fi
adb shell cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null | grep -i anuki >> $LOG

step "external icon pack for every app"
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 2
IP=$(find_scroll "아이콘 팩")
echo "icon pack row=$IP" >> $LOG
if [ -n "$IP" ]; then
  adb shell input tap $IP
  sleep 2
  dump packs
  TP=$(ui shots/packs.xml text "시험용 아이콘 팩")
  echo "test pack item=$TP" >> $LOG
  [ -n "$TP" ] && adb shell input tap $TP
  sleep 2
fi
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 20-iconpack-home
adb shell input swipe 540 1500 540 400 200
sleep 3
shot 21-iconpack-drawer
adb shell input keyevent KEYCODE_HOME
sleep 2

step "one icon from the icon pack"
if open_icon_picker "Chrome"; then
  PC=$(ui shots/picker.xml find "아이콘 선택: 아이콘 팩")
  echo "pack cell=$PC" >> $LOG
  if [ -n "$PC" ]; then
    adb shell input tap $PC
    sleep 2
    # with more than one pack installed a list comes first
    dump packlist
    TL=$(ui shots/packlist.xml text "시험용 아이콘 팩")
    echo "pack list item=$TL" >> $LOG
    [ -n "$TL" ] && adb shell input tap $TL
    sleep 3
    dump packgrid
    STAR=$(ui shots/packgrid.xml anydesc pack_star)
    echo "pack_star=$STAR" >> $LOG
    [ -n "$STAR" ] && adb shell input tap $STAR
    sleep 2
  fi
  shot 22-pack-icon-one-app
fi
adb logcat -d | grep -E "icon override|app icon ->" >> $LOG
adb logcat -d | grep -E "icon pack |calendar icon" >> $LOG

step "next day: the calendar icon follows the date"
adb shell input keyevent KEYCODE_HOME
sleep 2
CAL_BEFORE=$(adb shell date +%d | tr -d '\r')
NOW=$(adb shell date +%s | tr -d '\r')
adb shell settings put global auto_time 0
adb shell cmd alarm set-time $(( (NOW + 86400) * 1000 )) 2>&1 | tee -a $LOG
sleep 3
CAL_AFTER=$(adb shell date +%d | tr -d '\r')
if [ "$CAL_AFTER" = "$CAL_BEFORE" ]; then
  echo "cmd alarm set-time did not move the clock, trying su date" >> $LOG
  adb shell su 0 date -u "$(date -u -d @$((NOW + 86400)) +%m%d%H%M%Y.%S)" 2>&1 | tee -a $LOG
  sleep 3
  CAL_AFTER=$(adb shell date +%d | tr -d '\r')
fi
echo "day before=$CAL_BEFORE after=$CAL_AFTER ($(adb shell date))" >> $LOG
sleep 3
shot 23-next-day-home
adb shell input swipe 540 1500 540 400 200
sleep 3
shot 24-next-day-drawer
adb shell input keyevent KEYCODE_HOME
adb logcat -d | grep -E "time broadcast|calendar icon" >> $LOG
# put the clock back
NOW2=$(adb shell date +%s | tr -d '\r')
adb shell cmd alarm set-time $(( (NOW2 - 86400) * 1000 )) 2>&1 | tee -a $LOG
[ "$(adb shell date +%d | tr -d '\r')" = "$CAL_AFTER" ] && adb shell su 0 date -u "$(date -u -d @$((NOW2 - 86400)) +%m%d%H%M%Y.%S)" >/dev/null 2>&1
adb shell settings put global auto_time 1
echo "clock restored: $(adb shell date)" >> $LOG
sleep 2

step "real icon pack (Arcticons, F-Droid)"
if adb shell pm list packages | grep -q com.donnnno.arcticons; then
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  IP=$(find_scroll "아이콘 팩")
  if [ -n "$IP" ]; then
    adb shell input tap $IP
    sleep 2
    dump packs2
    RP=$(ui shots/packs2.xml textprefix "Arcticons")
    echo "real pack item=$RP" >> $LOG
    if [ -n "$RP" ]; then
      T0=$(date +%s)
      adb shell input tap $RP
      for i in $(seq 1 30); do
        sleep 1
        dump wait
        grep -q "아이콘 팩 불러오는 중" shots/wait.xml || break
      done
      echo "real pack applied after ~$(( $(date +%s) - T0 ))s (loading dialog gone)" >> $LOG
    fi
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 4
  shot 25-realpack-home
  adb shell input swipe 540 1500 540 400 200
  sleep 4
  shot 26-realpack-drawer
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  if open_icon_picker "Photos"; then
    PC=$(ui shots/picker.xml find "아이콘 선택: 아이콘 팩")
    if [ -n "$PC" ]; then
      adb shell input tap $PC
      sleep 2
      dump packlist2
      RL=$(ui shots/packlist2.xml textprefix "Arcticons")
      echo "real pack list item=$RL" >> $LOG
      if [ -n "$RL" ]; then
        T0=$(date +%s)
        adb shell input tap $RL
        for i in $(seq 1 40); do
          sleep 1
          dump realgrid
          grep -q "아이콘 팩 불러오는 중" shots/realgrid.xml || break
        done
        echo "real pack icon list shown after ~$(( $(date +%s) - T0 ))s" >> $LOG
        sleep 2
        dump realgrid
        shot 27-realpack-grid
        grep -o 'text="Arcticons[^"]*"' shots/realgrid.xml | head -2 >> $LOG
        echo "grid cells on screen: $(ui shots/realgrid.xml count android.widget.ImageView)" >> $LOG
        ET=$(ui shots/realgrid.xml classfirst android.widget.EditText)
        if [ -n "$ET" ]; then
          adb shell input tap $ET
          sleep 1
          adb shell input text "photo"
          sleep 3
          # hide the keyboard (only if it is up, BACK would otherwise close the dialog)
          adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
          sleep 1
          dump realgrid2
          shot 28-realpack-search
          PI=$(ui shots/realgrid2.xml desccontains "photo")
          echo "first 'photo' icon=$PI" >> $LOG
          [ -n "$PI" ] && adb shell input tap $PI
          sleep 3
        fi
      fi
    fi
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    shot 29-realpack-one-icon
  fi
  adb logcat -d | grep -E "icon pack |icon override" | tail -20 >> $LOG

  # Arcticons draws white lines: on the light drawer they almost vanish, the dark drawer theme is the fix
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  TH=$(find_scroll "테마 (앱 서랍·폴더)")
  echo "theme row=$TH" >> $LOG
  if [ -n "$TH" ]; then
    adb shell input tap $TH
    sleep 2
    dump theme
    DK=$(ui shots/theme.xml text "어둡게")
    [ -n "$DK" ] && adb shell input tap $DK
    sleep 2
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell input swipe 540 1500 540 400 200
  sleep 4
  shot 30-realpack-dark-drawer
  adb shell input keyevent KEYCODE_HOME
  sleep 2
else
  echo "real icon pack not installed - skipped" >> $LOG
fi

step "cat icons for every app (54 hand-made + generated cat frames)"
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 2
IP=$(find_scroll "아이콘 팩")
if [ -n "$IP" ]; then
  adb shell input tap $IP
  sleep 2
  dump packs3
  CT=$(ui shots/packs3.xml text "고양이 아이콘 (내장)")
  echo "cat icons item=$CT" >> $LOG
  if [ -n "$CT" ]; then
    adb shell input tap $CT
    for i in $(seq 1 20); do sleep 1; dump wait; grep -q "아이콘 팩 불러오는 중" shots/wait.xml || break; done
  fi
fi
adb shell input keyevent KEYCODE_HOME
sleep 4
shot 31-cats-home
adb shell input swipe 540 1500 540 400 200
sleep 4
shot 32-cats-drawer
dump catdrawer
ui shots/catdrawer.xml list >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2
adb logcat -d | grep -E "line icons:|cat icon " | sort -u -k7 | tail -40 >> $LOG

step "one app: pick a generated cat icon by name"
# Maps is only in the app drawer
adb shell input swipe 540 1500 540 400 200
sleep 3
if open_icon_picker "Maps"; then
  LC=$(ui shots/picker.xml find "아이콘 선택: 더 많은 고양이")
  echo "more cats cell=$LC" >> $LOG
  if [ -n "$LC" ]; then
    adb shell input tap $LC
    for i in $(seq 1 20); do sleep 1; dump linegrid; grep -q "아이콘 팩 불러오는 중" shots/linegrid.xml || break; done
    sleep 1
    dump linegrid
    shot 33-cats-grid
    grep -o 'text="고양이 아이콘 ([^"]*"' shots/linegrid.xml | head -1 >> $LOG
    ET=$(ui shots/linegrid.xml classfirst android.widget.EditText)
    if [ -n "$ET" ]; then
      adb shell input tap $ET
      sleep 1
      adb shell input text "cat"
      sleep 3
      adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
      sleep 1
      dump linegrid2
      shot 34-cats-search
      CI=$(ui shots/linegrid2.xml desccontains "cat")
      echo "first 'cat' icon=$CI" >> $LOG
      [ -n "$CI" ] && adb shell input tap $CI
      sleep 3
    fi
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell input swipe 540 1500 540 400 200
  sleep 4
  shot 35-cats-one-icon
  adb shell input keyevent KEYCODE_HOME
  sleep 2
fi
adb logcat -d | grep -E "icon override" | tail -3 >> $LOG

step "icon drawing licence screen"
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 2
LI=$(find_scroll "아이콘 그림 라이선스")
echo "licence row=$LI" >> $LOG
if [ -n "$LI" ]; then
  adb shell input tap $LI
  sleep 2
  shot 36-cats-license
  dump licence
  grep -o "CC BY-SA 4.0" shots/licence.xml | head -1 >> $LOG
  adb shell input keyevent KEYCODE_BACK
fi
adb shell input keyevent KEYCODE_HOME
sleep 2

step "settings"
adb shell am start -W -n com.anuki.launcher/.SettingsActivity | tee -a $LOG
sleep 3
shot 13-settings
adb shell input swipe 540 1800 540 600 300
sleep 1
shot 14-settings-2
adb shell input keyevent KEYCODE_HOME
sleep 2

step "page swipe"
adb shell input swipe 900 1200 150 1200 200
sleep 2
shot 15-page-swipe

step "standard build (no badge service) installs over it and runs"
adb install -r "$STD_APK" 2>&1 | tee -a $LOG
adb shell dumpsys package com.anuki.launcher | grep -E "versionName|BadgeService" | head -5 >> $LOG
echo "badge service declared: $(adb shell dumpsys package com.anuki.launcher | grep -c BadgeService)" >> $LOG
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 2
NB=$(find_scroll "백업 파일 만들기")
adb shell input swipe 540 900 540 1300 300
sleep 1
shot 37-standard-badge-note
dump stdnote
grep -o "기본판[^\"]*" shots/stdnote.xml | head -1 >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 38-standard-home

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|ANR in|Anuki" | tail -3000 > shots/logcat.txt
adb logcat -d | grep -E "ANR in" > shots/anr.txt
PID=$(adb shell pidof com.anuki.launcher | tr -d '\r')
echo "${PID:-not running}" > shots/pid.txt
[ -n "$PID" ] && adb logcat -d --pid=$PID | grep -E "Skipped [0-9]+ frames|Davey" > shots/jank.txt
echo "done" >> $LOG
exit 0
