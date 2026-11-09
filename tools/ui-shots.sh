#!/bin/bash
# Runs inside the emulator job: installs the debug APK and captures screenshots + UI dumps of the main screens.
set -x
PKG=com.abdelhadi.gymcoach
mkdir -p shots
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure immersive_mode_confirmations confirmed
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; dump; cp /tmp/ui.xml "shots/$1.xml"; }
tap() { dump; xy=$(python3 tools/tap.py /tmp/ui.xml "$1") && adb shell input tap $xy || echo "NOT FOUND: $1"; }
scroll() { adb shell input swipe 540 1700 540 500 400; }
open() { adb shell am start -W -n "$PKG/.$1" >/dev/null; }

open MainActivity; shot 01-main-dark 5
scroll; shot 02-main-dark-bottom
open PlanActivity; shot 03-plan-dark
open SettingsActivity; shot 04-settings-dark
tap "فاتح"; shot 05-settings-light
open MainActivity; shot 06-main-light
open PlanActivity; shot 07-plan-light
open SettingsActivity; tap "داكن"; sleep 2
adb shell settings put system font_scale 1.5
open MainActivity; shot 08-main-bigfont 4
adb shell settings put system font_scale 1.0
adb shell am force-stop $PKG
open MainActivity; sleep 3
tap "ابدأ الحصة"; shot 10-workout-intro 6
for i in 1 2 3 4 5 6; do tap "تخطي الخطوة الحالية"; shot "1$i-workout-skip$i" 4; done
for i in 1 2 3; do tap "تخطي الخطوة الحالية"; shot "2$i-workout-more$i" 6; done
scroll; shot 30-workout-bottom 2
adb logcat -d | grep -iE "AndroidRuntime|FATAL|gymcoach" | tail -60 > shots/logcat.txt
