#!/bin/bash
# Installs the launcher on the emulator, walks through the main screens and saves screenshots + logs to ./shots
set -x
mkdir -p shots
LOG=shots/steps.txt
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "shots/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "shots/$1.xml" >/dev/null 2>&1; }
ui() { python3 .github/scripts/ui.py "$@"; }
step() { echo "== $*" >> $LOG; }

adb install -r "$APK" | tee -a $LOG
# the stock launcher tends to ANR on CI emulators and covers the screen; switch it off
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher 2>&1 | tee -a $LOG
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.nyang.launcher 2>&1 | tee -a $LOG
adb shell cmd role get-role-holders --user 0 android.app.role.HOME 2>&1 | tee -a $LOG
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | tee -a $LOG
sleep 10
shot 01-home
dump home
step "home icons"; ui shots/home.xml list >> $LOG

step "drawer"
adb shell input swipe 540 1500 540 400 200
sleep 3
shot 02-drawer
dump drawer
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
A0=$(ui shots/drawer2.xml find "냥런처 설정")
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

step "settings"
adb shell am start -W -n com.nyang.launcher/.SettingsActivity | tee -a $LOG
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

adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|ANR in|Nyang" | tail -300 > shots/logcat.txt
adb shell pidof com.nyang.launcher > shots/pid.txt || echo "not running" > shots/pid.txt
echo "done" >> $LOG
exit 0
