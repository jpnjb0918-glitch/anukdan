#!/bin/bash
# Installs the launcher on the emulator, walks through the main screens and saves screenshots + logs to ./shots
set -x
mkdir -p shots
LOG=shots/steps.txt
APK=app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "shots/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "shots/$1.xml" >/dev/null 2>&1; }
ui() { python3 .github/scripts/ui.py "$@"; }

adb install -r "$APK" | tee -a $LOG
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.nyang.launcher 2>&1 | tee -a $LOG
adb shell cmd role get-role-holders --user 0 android.app.role.HOME 2>&1 | tee -a $LOG
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | tee -a $LOG
sleep 12
shot 01-home
dump home

echo "== home icons" >> $LOG; ui shots/home.xml list >> $LOG

# app drawer
adb shell input swipe 540 1500 540 400 200
sleep 3
shot 02-drawer
dump drawer
echo "== drawer icons" >> $LOG; ui shots/drawer.xml list | head -40 >> $LOG
adb shell input tap 540 215
sleep 1
adb shell input text "set"
sleep 2
shot 03-drawer-search
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_HOME
sleep 2

# long press on empty home space -> home menu
adb shell input swipe 540 700 540 700 1500
sleep 2
shot 04-home-menu
adb shell input keyevent KEYCODE_BACK
sleep 1

# long press first dock icon -> icon menu
dump home2
D0=$(ui shots/home2.xml dock 0)
D1=$(ui shots/home2.xml dock 1)
echo "dock0=$D0 dock1=$D1" >> $LOG
if [ -n "$D0" ]; then
  adb shell input swipe $D0 $D0 1500
  sleep 2
  shot 05-icon-menu
  adb shell input keyevent KEYCODE_BACK
  sleep 1
fi

# drag dock icon 0 onto dock icon 1 -> makes a folder
if [ -n "$D0" ] && [ -n "$D1" ]; then
  adb shell input draganddrop $D0 $D1 2500
  sleep 3
  shot 06-after-folder-drop
  dump home3
  echo "== after folder drop" >> $LOG; ui shots/home3.xml list >> $LOG
  F=$(ui shots/home3.xml find 폴더)
  echo "folder=$F" >> $LOG
  if [ -n "$F" ]; then
    adb shell input tap $F
    sleep 2
    shot 07-folder-open
    adb shell input keyevent KEYCODE_BACK
  fi
fi

# drag a drawer app onto the home screen
adb shell input swipe 540 1500 540 400 200
sleep 3
dump drawer2
A0=$(ui shots/drawer2.xml find "냥런처 설정")
echo "drawer app0=$A0" >> $LOG
if [ -n "$A0" ]; then
  adb shell input draganddrop $A0 540 900 3000
  sleep 3
  shot 08-after-drawer-drag
fi
adb shell input keyevent KEYCODE_HOME
sleep 1

# settings & wallpaper apply
adb shell am start -W -n com.nyang.launcher/.SettingsActivity | tee -a $LOG
sleep 3
shot 09-settings
adb shell input swipe 540 1800 540 600 300
sleep 1
shot 10-settings-2
adb shell input keyevent KEYCODE_HOME
sleep 2

# second page swipe + double tap (default: none)
adb shell input swipe 900 1200 150 1200 200
sleep 2
shot 11-page-swipe

# crash / ANR check
adb logcat -d -b crash > shots/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|ANR in|nyang" | tail -200 > shots/logcat.txt
adb shell pidof com.nyang.launcher > shots/pid.txt || echo "not running" > shots/pid.txt
echo "done" >> $LOG
exit 0
