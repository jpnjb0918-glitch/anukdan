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

step "folder on the home screen (not the dock)"
# open the drawer and drop the app with label $1 at $2 $3 on the home screen
drag_from_drawer() {
  adb shell input swipe 540 1500 540 400 200
  sleep 3
  dump drw
  local P=$(ui shots/drw.xml find "$1")
  echo "drawer $1=$P -> $2 $3" >> $LOG
  [ -n "$P" ] && adb shell input draganddrop $P $2 $3 3000
  sleep 3
}
# the dock already has a folder called 폴더 (row y=2203): only count one above the dock
home_folder() { ui "$1" list | awk '$1=="폴더" && $3<2000 {print $2" "$3; exit}'; }
dump homeF0
T=$(ui shots/homeF0.xml find "아늑이 런처 설정")
echo "home icon to drop on=$T" >> $LOG
if [ -n "$T" ]; then
  drag_from_drawer "YouTube" $T
  dump homeF1
  shot 08b-home-folder
  ui shots/homeF1.xml list >> $LOG
  F=$(home_folder shots/homeF1.xml)
  echo "home folder after drawer drop=$F" >> $LOG
  if [ -n "$F" ]; then
    drag_from_drawer "Gmail" $F
    # a home icon dropped on the folder
    dump homeF2
    C=$(ui shots/homeF2.xml find "Calendar")
    F=$(home_folder shots/homeF2.xml)
    echo "calendar=$C folder=$F" >> $LOG
    [ -n "$C" ] && [ -n "$F" ] && adb shell input draganddrop $C $F 2500
    sleep 3
    dump homeF3
    shot 08c-home-folder-4
    ui shots/homeF3.xml list >> $LOG
    F=$(home_folder shots/homeF3.xml)
    if [ -n "$F" ]; then
      adb shell input tap $F
      sleep 2
      shot 08d-home-folder-open
      dump homeF4
      echo "inside the folder:" >> $LOG
      ui shots/homeF4.xml list >> $LOG
      adb shell input keyevent KEYCODE_BACK
      sleep 1
    fi
  fi
fi
cp /dev/null shots/layout-after-folder.json
adb shell run-as com.anuki.launcher cat files/layout.json > shots/layout-after-folder.json 2>&1
echo "== launcher restarted" >> $LOG
adb shell am force-stop com.anuki.launcher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 8
adb shell input keyevent KEYCODE_HOME
sleep 2
dump homeF5
shot 08e-home-folder-after-restart
ui shots/homeF5.xml list >> $LOG
echo "home folder after restart=$(home_folder shots/homeF5.xml)" >> $LOG

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
  T=$(ui shots/wp.xml text "아늑단 패턴")
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
  G=$(ui shots/picker.xml find "아늑단 아이콘: games")
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
      ST=$(ui shots/style.xml text "아늑단 얼굴 틀에 넣기")
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
  CT=$(ui shots/packs3.xml text "아늑단 아이콘 (내장)")
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
  LC=$(ui shots/picker.xml find "아이콘 선택: 더 많은 아늑단")
  echo "more cats cell=$LC" >> $LOG
  if [ -n "$LC" ]; then
    adb shell input tap $LC
    for i in $(seq 1 20); do sleep 1; dump linegrid; grep -q "아이콘 팩 불러오는 중" shots/linegrid.xml || break; done
    sleep 1
    dump linegrid
    shot 33-cats-grid
    grep -o 'text="아늑단 아이콘 ([^"]*"' shots/linegrid.xml | head -1 >> $LOG
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

step "app drawer layouts (설정 → 앱 서랍)"
scroll_top() { for i in 1 2 3 4 5; do adb shell input swipe 540 700 540 2100 150; done; sleep 1; }
# open settings, pick option $2 in the choice row $1, go home
set_choice() {
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  scroll_top
  local R=$(find_scroll "$1")
  echo "row $1=$R" >> $LOG
  [ -z "$R" ] && return 1
  adb shell input tap $R
  sleep 2
  dump choice
  local O=$(ui shots/choice.xml text "$2")
  echo "option $2=$O" >> $LOG
  [ -n "$O" ] && adb shell input tap $O
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 2
}
open_drawer() { adb shell input swipe 540 1500 540 400 200; sleep 3; }
drawer_labels() { ui "$1" list | awk '$NF > 300 && $NF < 2250' | head -${2:-8} | awk '{$NF=""; $(NF-1)=""; print}' | tr '\n' ',' ; }

# move the settings slider under the row titled $1 to fraction $2 of its length (0 = minimum, 1 = maximum)
set_slider() {
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  scroll_top
  local T=$(find_scroll "$1")
  echo "slider row $1=$T" >> $LOG
  [ -z "$T" ] && return 1
  local f="$2"
  set -- $T
  local B=$(ui shots/fs.xml below android.widget.SeekBar "$2")
  echo "slider=$B" >> $LOG
  [ -z "$B" ] && return 1
  set -- $B
  local x=$(python3 -c "print(int($1 + 30 + ($3 - $1 - 60) * $f))")
  adb shell input tap $x $(( ($2 + $4) / 2 ))
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 2
}

set_choice "서랍 형식" "가로 페이지 (좌우로 넘기기)"
# fewer, bigger cells so the 21 test apps need more than one page
set_slider "열 수" 0
set_slider "아이콘 크기" 1
open_drawer
shot 40-drawer-pages
dump dpages
echo "pages, first page: $(drawer_labels shots/dpages.xml 40)" >> $LOG
adb shell input swipe 900 1300 150 1300 300
sleep 2
dump dpages2
# a slow emulator sometimes drops the swipe: try once more, slower
if [ "$(drawer_labels shots/dpages2.xml 3)" = "$(drawer_labels shots/dpages.xml 3)" ]; then
  echo "page swipe did not move, trying again" >> $LOG
  adb shell input swipe 950 1100 100 1100 500
  sleep 2
  dump dpages2
fi
shot 41-drawer-pages-2
echo "pages, second page: $(drawer_labels shots/dpages2.xml 40)" >> $LOG
adb logcat -d | grep "drawer pages:" | tail -2 >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2
set_slider "열 수" 0.5
set_slider "아이콘 크기" 0.5

set_choice "서랍 형식" "목록 (아이콘 + 이름 한 줄씩)"
open_drawer
shot 42-drawer-list
dump dlist
echo "list: $(drawer_labels shots/dlist.xml 12)" >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2

set_choice "서랍 형식" "가나다 묶음 (첫 글자별, 오른쪽 막대로 이동)"
open_drawer
shot 43-drawer-sections
dump dsec
echo "section headings: $(grep -o 'content-desc="구역 [^"]*"' shots/dsec.xml | tr '\n' ' ')" >> $LOG
# tap the bottom of the jump strip on the right edge
adb shell input tap 1050 2050
sleep 2
shot 44-drawer-sections-jump
dump dsec2
echo "after jump: $(grep -o 'content-desc="구역 [^"]*"' shots/dsec2.xml | tr '\n' ' ')" >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2

set_choice "서랍 형식" "세로 격자 (위아래로 넘기기)"
set_choice "정렬" "최근 설치순"
open_drawer
shot 45-drawer-recent
dump drecent
echo "recent first: $(drawer_labels shots/drecent.xml 6)" >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2
set_choice "정렬" "이름순"

set_choice "서랍 배경" "배경화면 보이게"
open_drawer
shot 46-drawer-wallpaper-bg
adb shell input keyevent KEYCODE_HOME
sleep 2
set_choice "서랍 배경" "테마 색"

step "icon colours"
open_settings_row() {
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  scroll_top
  local R=$(find_scroll "$1")
  echo "row $1=$R" >> $LOG
  [ -z "$R" ] && return 1
  adb shell input tap $R
  sleep 2
  return 0
}
if open_settings_row "아이콘 색"; then
  dump colord
  P=$(ui shots/colord.xml anydesc "아이콘 색: 핑크")
  echo "pink=$P" >> $LOG
  [ -n "$P" ] && adb shell input tap $P
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 60-color-pink-home
  open_drawer
  shot 61-color-pink-drawer
  adb shell input keyevent KEYCODE_HOME
  sleep 2
fi
if open_icon_picker "Settings"; then
  C=$(ui shots/picker.xml find "아이콘 선택: 색 바꾸기")
  echo "colour cell=$C" >> $LOG
  if [ -n "$C" ]; then
    adb shell input tap $C
    sleep 2
    dump appcolor
    G=$(ui shots/appcolor.xml anydesc "아이콘 색: 초록")
    echo "green=$G" >> $LOG
    [ -n "$G" ] && adb shell input tap $G
    sleep 2
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  shot 62-color-app-green
fi
if open_settings_row "아이콘 색"; then
  dump colord2
  D=$(ui shots/colord2.xml text "직접 고르기")
  echo "custom button=$D" >> $LOG
  if [ -n "$D" ]; then
    adb shell input tap $D
    sleep 2
    dump custom
    H=$(ui shots/custom.xml below android.widget.SeekBar 0)
    echo "hue slider=$H" >> $LOG
    if [ -n "$H" ]; then
      set -- $H
      adb shell input tap $(( $1 + ($3 - $1) * 78 / 100 )) $(( ($2 + $4) / 2 ))
      sleep 1
    fi
    shot 63-color-custom-dialog
    OK=$(ui shots/custom.xml text "확인")
    [ -n "$OK" ] && adb shell input tap $OK
    sleep 1
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 64-color-custom-home
fi
if open_settings_row "아이콘 색"; then
  dump colord3
  grep -o 'text="아이콘 색[^"]*"' shots/fs.xml | head -1 >> $LOG
  B=$(ui shots/colord3.xml anydesc "아이콘 색: 기본 (청록)")
  [ -n "$B" ] && adb shell input tap $B
  sleep 1
fi
if open_icon_picker "Settings"; then
  C=$(ui shots/picker.xml find "아이콘 선택: 색 바꾸기")
  [ -n "$C" ] && adb shell input tap $C && sleep 2 && dump appcolor2
  F=$(ui shots/appcolor2.xml anydesc "아이콘 색: 전체 색 따르기")
  [ -n "$F" ] && adb shell input tap $F
  sleep 1
fi
adb shell input keyevent KEYCODE_HOME
sleep 2

step "app name size in pixels (설정 → 앱 이름 글자 크기)"
if open_settings_row "앱 이름 글자 크기"; then
  dump lsz
  echo "size now: $(grep -o 'text="[0-9][0-9]*"' shots/lsz.xml | head -1)" >> $LOG
  PLUS=$(ui shots/lsz.xml anydesc "글자 1픽셀 크게")
  echo "plus button=$PLUS" >> $LOG
  if [ -n "$PLUS" ]; then
    for i in $(seq 1 10); do adb shell input tap $PLUS; done
    sleep 1
    dump lsz2
    echo "after +10: $(grep -o 'text="[0-9][0-9]*"' shots/lsz2.xml | head -1)" >> $LOG
    shot 65-label-size-dialog
    OK=$(ui shots/lsz2.xml text "확인")
    [ -n "$OK" ] && adb shell input tap $OK
    sleep 1
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 66-label-size-big-home
  open_drawer
  shot 67-label-size-big-drawer
  adb shell input keyevent KEYCODE_HOME
  sleep 2
fi
# type the number directly
if open_settings_row "앱 이름 글자 크기"; then
  dump lsz3
  NUM=$(ui shots/lsz3.xml anydesc "글자 크기 숫자")
  echo "number field=$NUM" >> $LOG
  if [ -n "$NUM" ]; then
    adb shell input tap $NUM
    sleep 1
    adb shell input keyevent KEYCODE_MOVE_END
    for i in 1 2 3; do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text 20
    sleep 1
    dump lsz4
    echo "typed: $(grep -o 'text="[0-9][0-9]*"' shots/lsz4.xml | head -1)" >> $LOG
    shot 68-label-size-typed
    OK=$(ui shots/lsz4.xml text "확인")
    echo "ok button=$OK" >> $LOG
    [ -n "$OK" ] && adb shell input tap $OK
    sleep 1
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 69-label-size-small-home
fi
adb logcat -d | grep -E "label size" | tail -4 >> $LOG
# back to 기본
if open_settings_row "앱 이름 글자 크기"; then
  dump lsz5
  D=$(ui shots/lsz5.xml text "기본으로")
  [ -n "$D" ] && adb shell input tap $D
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 2
fi
adb logcat -d | grep -E "label size" | tail -1 >> $LOG

step "new version check (설정 → 업데이트 확인)"
# the test build's version is the test run number, older than the newest release, so a new version is offered
adb shell appops set com.anuki.launcher REQUEST_INSTALL_PACKAGES allow 2>&1 | tee -a $LOG
for edition in 배지판 기본판 아니요; do
  if open_settings_row "업데이트 확인"; then
    sleep 6
    dump upd
    grep -o 'text="[^"]*"' shots/upd.xml | grep -E "새 버전|지금 버전|최신|바뀐 점" | head -3 | cut -c1-400 | tr '\n' ' ' >> $LOG; echo >> $LOG
    if [ "$edition" = "아니요" ]; then
      N=$(ui shots/upd.xml text "아니요")
      echo "no button=$N" >> $LOG
      [ -n "$N" ] && adb shell input tap $N
      sleep 2
      shot 75-update-declined
      adb shell input keyevent KEYCODE_HOME
      sleep 2
      continue
    fi
    Y=$(ui shots/upd.xml text "예")
    if [ -n "$Y" ]; then
      [ "$edition" = "배지판" ] && shot 70-update-ask
      adb shell input tap $Y
      sleep 2
      dump updc
      [ "$edition" = "배지판" ] && shot 71-update-choose
      E=$(ui shots/updc.xml textprefix "$edition")
      echo "edition $edition=$E" >> $LOG
      [ -n "$E" ] && adb shell input tap $E
      sleep 3
      dump updd
      if [ "$edition" = "배지판" ]; then shot 72-update-badge-howto; else shot 73-update-standard; fi
      grep -o 'text="[^"]*"' shots/updd.xml | head -2 | cut -c1-80 | tr '\n' ' ' >> $LOG; echo >> $LOG
      OK=$(ui shots/updd.xml text "확인")
      [ -n "$OK" ] && adb shell input tap $OK
      sleep 1
      if [ "$edition" = "기본판" ]; then
        # the installer should open by itself once the download finishes (settings screen still in front)
        for i in $(seq 1 12); do
          sleep 5
          adb shell dumpsys activity activities 2>/dev/null | grep -qiE "packageinstaller|InstallStart|PackageInstallerActivity" && break
        done
        sleep 2
        shot 74-update-installer
        dump inst
        echo "installer: $(adb shell dumpsys activity activities | grep -iE 'topResumedActivity|mResumedActivity' | head -2 | tr -s ' ')" >> $LOG
        grep -o 'text="[^"]*"' shots/inst.xml | head -6 | tr '\n' ' ' >> $LOG; echo >> $LOG
        adb shell input keyevent KEYCODE_BACK
        sleep 1
      fi
    fi
  fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
done
echo "downloaded: $(adb shell ls -la /sdcard/Download/ 2>/dev/null | grep anuki-launcher | tr '\n' ' ')" >> $LOG
adb logcat -d | grep Anuki | grep -E "update" | tail -10 >> $LOG
# turn the automatic question back on is not needed in the test (debug builds never ask by themselves)

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

step "moving background (pattern, video, GIF)"
adb push .github/scripts/test_motion.mp4 /sdcard/Download/anuki_motion.mp4 | tee -a $LOG
adb push .github/scripts/test_motion.gif /sdcard/Download/anuki_motion.gif | tee -a $LOG
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/anuki_motion.mp4 >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/anuki_motion.gif >/dev/null 2>&1
open_wallpaper_dialog() {
  adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
  sleep 2
  scroll_top
  local W=$(find_scroll "배경화면")
  echo "wallpaper row=$W" >> $LOG
  [ -z "$W" ] && return 1
  adb shell input tap $W
  sleep 2
  dump wpd
  return 0
}
# in the system file picker, open the file whose name starts with $1
pick_doc() {
  dump docsm
  local F=$(ui shots/docsm.xml descprefix "$1")
  [ -z "$F" ] && F=$(ui shots/docsm.xml text "$1")
  if [ -z "$F" ]; then
    local R=$(ui shots/docsm.xml anydesc "Show roots")
    [ -n "$R" ] && adb shell input tap $R && sleep 2
    dump rootsm
    local D=$(ui shots/rootsm.xml text "Downloads")
    [ -n "$D" ] && adb shell input tap $D && sleep 3
    dump docsm2
    F=$(ui shots/docsm2.xml descprefix "$1")
    [ -z "$F" ] && F=$(ui shots/docsm2.xml text "$1")
  fi
  echo "picked $1 at=$F" >> $LOG
  [ -n "$F" ] && adb shell input tap $F
}
if open_wallpaper_dialog; then
  grep -o 'text="움직이는 배경[^"]*"' shots/wpd.xml | head -1 >> $LOG
  O=$(ui shots/wpd.xml text "아늑단 패턴 흐르기")
  [ -n "$O" ] && adb shell input tap $O
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 50-moving-pattern-a
  sleep 2
  shot 50-moving-pattern-b
fi
for kind in mp4 gif; do
  if open_wallpaper_dialog; then
    O=$(ui shots/wpd.xml text "내 동영상·GIF 고르기")
    [ -n "$O" ] && adb shell input tap $O
    sleep 4
    shot 51-moving-picker-$kind
    pick_doc "anuki_motion.$kind"
    sleep 5
    adb shell input keyevent KEYCODE_HOME
    sleep 4
    shot 52-moving-$kind-a
    sleep 1
    shot 52-moving-$kind-b
  fi
done
step "moving background as the phone wallpaper (home + lock screen)"
if open_wallpaper_dialog; then
  O=$(ui shots/wpd.xml text "휴대폰 배경화면으로 설정 (잠금 화면 포함)")
  echo "system wallpaper option=$O" >> $LOG
  if [ -n "$O" ]; then
    adb shell input tap $O
    sleep 5
    shot 54-live-preview
    dump livep
    grep -o 'text="[^"]*"' shots/livep.xml | head -20 | tr '\n' ' ' >> $LOG; echo >> $LOG
    for t in "Set wallpaper" "SET WALLPAPER" "Set Wallpaper" "배경화면 설정" "Apply"; do
      B=$(ui shots/livep.xml text "$t")
      if [ -n "$B" ]; then echo "set button=$t $B" >> $LOG; adb shell input tap $B; break; fi
    done
    sleep 3
    dump liveopt
    shot 55-live-options
    grep -o 'text="[^"]*"' shots/liveopt.xml | head -20 | tr '\n' ' ' >> $LOG; echo >> $LOG
    for t in "Home and lock screens" "Home screen and lock screen" "Home & lock screens" "Home and lock screen" "홈 화면 및 잠금 화면"; do
      B=$(ui shots/liveopt.xml text "$t")
      if [ -n "$B" ]; then echo "where=$t $B" >> $LOG; adb shell input tap $B; break; fi
    done
    sleep 5
  fi
  adb shell dumpsys wallpaper | grep -iE "AnukiWallpaperService|mWallpaperComponent|component=" | head -6 >> $LOG
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 56-live-home
  echo "wallpaper states:" >> $LOG
  adb shell dumpsys wallpaper | grep -iE "wallpaper state|mWallpaperComponent" | head -8 >> $LOG
  # lock screen: the test emulator has the lock screen turned off, so turn the plain swipe lock screen on first
  adb shell locksettings set-disabled false 2>&1 | tee -a $LOG
  adb shell input keyevent KEYCODE_SLEEP
  sleep 2
  adb shell input keyevent KEYCODE_WAKEUP
  sleep 3
  shot 57-lock-a
  dump lock
  echo "lock screen showing: $(adb shell dumpsys window | grep -iE 'mDreamingLockscreen|isKeyguardShowing|KeyguardShowing|mShowingLockscreen' | head -3 | tr -s ' ' | tr '\n' ' ')" >> $LOG
  grep -o 'resource-id="com.android.systemui:id/[^"]*"' shots/lock.xml | sort -u | head -8 | tr '\n' ' ' >> $LOG; echo >> $LOG
  sleep 1
  shot 57-lock-b
  adb shell wm dismiss-keyguard
  adb shell locksettings set-disabled true 2>&1 | tee -a $LOG
  adb shell input swipe 540 1900 540 600 200
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb logcat -d | grep -E "system wallpaper" | tail -5 >> $LOG
fi
if open_wallpaper_dialog; then
  O=$(ui shots/wpd.xml text "움직이는 배경 끄기")
  [ -n "$O" ] && adb shell input tap $O
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  shot 53-moving-off
fi
adb logcat -d | grep -E "moving bg" | tail -20 >> $LOG
adb shell run-as com.anuki.launcher ls -la files/moving >> $LOG 2>&1

step "아늑단 widgets (홈 길게 누르기 → 위젯 추가 → 아늑단 위젯)"
# long-press an empty spot of the current page and pick "위젯 추가" (tries a few spots; widgets may cover some)
open_widget_list() {
  for P in "540 700" "540 1500" "200 1500" "880 1500" "540 400" "200 400" "880 400" "540 1100"; do
    adb shell input swipe $P $P 1500
    sleep 1
    dump wmenu
    local W=$(ui shots/wmenu.xml text "위젯 추가")
    if [ -n "$W" ]; then adb shell input tap $W; sleep 3; return 0; fi
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  done
  return 1
}
# scroll a list until a node whose content-desc is $1 shows up; prints its centre
find_desc() {
  for i in 1 2 3 4 5 6; do
    dump fd
    local P=$(ui shots/fd.xml anydesc "$1")
    if [ -n "$P" ]; then echo "$P"; return 0; fi
    adb shell input swipe 540 1800 540 1000 300
    sleep 1
  done
  return 1
}
add_cat_widget() {
  open_widget_list || { echo "no widget list for $1" >> $LOG; return 1; }
  [ "$1" = "아늑단 시계" ] && shot 80-widget-list
  local R=$(find_desc "위젯: $1")
  echo "widget row $1=$R" >> $LOG
  [ -z "$R" ] && { adb shell input keyevent KEYCODE_BACK; return 1; }
  adb shell input tap $R
  sleep 3
  return 0
}
widget_at() { dump wh; ui shots/wh.xml descprefix "$1"; }
# go home (page 1) and page right until the widget $1 is in view; prints its centre
find_widget() {
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  local X=""
  for i in 1 2 3 4; do
    X=$(widget_at "$1")
    [ -n "$X" ] && { echo "$X"; return 0; }
    adb shell input swipe 900 1200 150 1200 300
    sleep 2
  done
  return 1
}
adb shell input keyevent KEYCODE_HOME
sleep 2
for W in "아늑단 시계" "아늑단 배터리" "아늑단 검색창" "아늑단 메모"; do add_cat_widget "$W"; done
adb shell input keyevent KEYCODE_HOME
sleep 1
adb logcat -d | grep -E "cat widget" | tail -4 >> $LOG
# the page with the widgets (the last one added is in view)
shot 81-widgets-home
dump wh
grep -o 'content-desc="아늑단 [^"]*"' shots/wh.xml | tr '\n' ' ' >> $LOG; echo >> $LOG

# memo: tap, type, save
M=$(find_widget "아늑단 메모")
echo "memo widget=$M" >> $LOG
if [ -n "$M" ]; then
  adb shell input tap $M
  sleep 2
  dump memo
  F=$(ui shots/memo.xml anydesc "메모 내용")
  [ -n "$F" ] && adb shell input tap $F
  adb shell input text "Buy%smilk"
  adb shell input keyevent KEYCODE_ENTER
  adb shell input text "Yoga%sat%s6"
  sleep 1
  dump memo2
  S=$(ui shots/memo2.xml text "저장")
  [ -n "$S" ] && adb shell input tap $S
  sleep 2
  shot 86-widget-memo
  dump wh; echo "memo after save: $(grep -o 'content-desc="아늑단 메모[^"]*"' shots/wh.xml)" >> $LOG
fi
# battery: low, then charging
find_widget "아늑단 배터리" >/dev/null
adb shell dumpsys battery unplug
adb shell dumpsys battery set status 3
adb shell dumpsys battery set level 12
sleep 2
shot 82-widget-battery-low
dump wh; echo "battery low: $(grep -o 'content-desc="아늑단 배터리[^"]*"' shots/wh.xml)" >> $LOG
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set status 2
adb shell dumpsys battery set level 64
sleep 2
shot 83-widget-battery-charging
dump wh; echo "battery charging: $(grep -o 'content-desc="아늑단 배터리[^"]*"' shots/wh.xml)" >> $LOG
adb shell dumpsys battery reset

# the clock: analog face from the long-press menu, on the clock's page
C=$(find_widget "아늑단 시계")
echo "clock widget=$C" >> $LOG
if [ -n "$C" ]; then
  shot 84-widget-clock-digital
  adb shell input swipe $C $C 1500
  sleep 1
  dump cmenu
  A=$(ui shots/cmenu.xml text "아날로그 시계로 바꾸기")
  echo "analog menu=$A" >> $LOG
  [ -n "$A" ] && adb shell input tap $A
  sleep 2
  shot 85-widget-clock-analog
  dump wh; echo "clock: $(grep -o 'content-desc="아늑단 시계[^"]*"' shots/wh.xml)" >> $LOG
fi

# search: opens the drawer with the search box
Q=$(find_widget "아늑단 검색창")
echo "search widget=$Q" >> $LOG
if [ -n "$Q" ]; then
  shot 86b-widget-search-page
  adb shell input tap $Q
  sleep 2
  shot 86c-widget-search-tapped
  echo "ime shown: $(adb shell dumpsys input_method | grep -c 'mInputShown=true')" >> $LOG
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell input keyevent KEYCODE_BACK
  sleep 1
fi

step "글꼴 (설정 → 글꼴)"
for FONT in "주아" "나눔손글씨 펜"; do
  if open_settings_row "글꼴"; then
    [ "$FONT" = "주아" ] && shot 87-font-picker
    dump fontp
    P=$(ui shots/fontp.xml anydesc "글꼴: $FONT")
    echo "font row $FONT=$P" >> $LOG
    [ -n "$P" ] && adb shell input tap $P
    sleep 1
  fi
  find_widget "아늑단 메모" >/dev/null
  if [ "$FONT" = "주아" ]; then shot 88-font-jua-widgets; else shot 89-font-nanumpen-widgets; fi
  open_drawer
  if [ "$FONT" = "주아" ]; then shot 90-font-jua-drawer; fi
  adb shell input keyevent KEYCODE_HOME
  sleep 2
done
adb logcat -d | grep -E "font ->|font .* failed" | tail -4 >> $LOG
if open_settings_row "글꼴"; then
  dump fontp
  P=$(ui shots/fontp.xml anydesc "글꼴: 기본 (휴대폰 글꼴)")
  [ -n "$P" ] && adb shell input tap $P
  sleep 1
fi
if open_settings_row "글꼴 라이선스"; then
  shot 91-font-license
  dump flic
  grep -o 'text="[^"]*Open Font[^"]*"' shots/flic.xml | head -1 | cut -c1-120 >> $LOG
  adb shell input keyevent KEYCODE_BACK
fi
adb shell input keyevent KEYCODE_HOME
sleep 2

# remove the widgets again (long-press → 위젯 삭제)
for W in "아늑단 시계" "아늑단 배터리" "아늑단 검색창" "아늑단 메모"; do
  X=$(find_widget "$W")
  if [ -n "$X" ]; then
    adb shell input swipe $X $X 1500
    sleep 1
    dump dmenu
    D=$(ui shots/dmenu.xml text "위젯 삭제")
    [ -n "$D" ] && adb shell input tap $D
    sleep 2
  fi
done
LEFT=0
for W in "아늑단 시계" "아늑단 배터리" "아늑단 검색창" "아늑단 메모"; do find_widget "$W" >/dev/null && LEFT=$((LEFT + 1)); done
echo "widgets left: $LEFT" >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 2

step "standard build (no badge service) installs over it and runs"
adb install -r "$STD_APK" 2>&1 | tee -a $LOG
adb shell dumpsys package com.anuki.launcher | grep -E "versionName|BadgeService" | head -5 >> $LOG
echo "badge service declared: $(adb shell dumpsys package com.anuki.launcher | grep -c BadgeService)" >> $LOG
adb shell am start -W -n com.anuki.launcher/.SettingsActivity >/dev/null
sleep 2
RN=$(find_scroll "모든 앱을 아늑단 아이콘으로")
echo "renamed row=$RN" >> $LOG
NB=$(find_scroll "알림 배지")
echo "badge section=$NB" >> $LOG
sleep 1
shot 37-standard-badge-note
dump stdnote
grep -o "기본판[^\"]*" shots/stdnote.xml | head -1 >> $LOG
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 38-standard-home

# the character is called 아늑단 everywhere on screen now; list any screen dump that still says 고양이
echo "screens still saying 고양이: $(grep -l "text=\"[^\"]*고양이\|content-desc=\"[^\"]*고양이" shots/*.xml 2>/dev/null | tr '\n' ' ')" >> $LOG
echo "screens mentioning 노바런처: $(grep -l "text=\"[^\"]*노바" shots/*.xml 2>/dev/null | tr '\n' ' ')" >> $LOG

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|ANR in|Anuki" | tail -3000 > shots/logcat.txt
adb logcat -d | grep -E "ANR in" > shots/anr.txt
PID=$(adb shell pidof com.anuki.launcher | tr -d '\r')
echo "${PID:-not running}" > shots/pid.txt
[ -n "$PID" ] && adb logcat -d --pid=$PID | grep -E "Skipped [0-9]+ frames|Davey" > shots/jank.txt
echo "done" >> $LOG
exit 0
