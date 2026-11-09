#!/bin/bash
# Runs inside the emulator job: installs the debug APK and captures screenshots + UI dumps.
# Activities are not exported, so navigation is done by tapping like a user.
set -x
PKG=com.abdelhadi.gymcoach
mkdir -p shots
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; dump; cp /tmp/ui.xml "shots/$1.xml"; }
tap() { for k in 1 2 3; do dump; xy=$(python3 tools/tap.py /tmp/ui.xml "$1") && { adb shell input tap $xy; return 0; }; adb shell input swipe 540 1600 540 900 300; sleep 1; done; echo "NOT FOUND: $1"; }
top() { for k in 1 2 3; do adb shell input swipe 540 600 540 1900 200; done; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }
start() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; }

start; shot 01-main 5
adb shell input swipe 540 1700 540 500 400; shot 02-main-bottom
top; tap "خطة اليوم"; shot 03-plan; back
top; tap "الإعدادات"; shot 04-settings
tap "داكن"; shot 05-settings-dark
back; top; shot 06-main-dark
tap "السجل والإحصائيات"; shot 07-history-dark; back
top; tap "الأوزان والتقدّم"; shot 08-weights-dark; back
top; tap "الموسيقى والصوت"; sleep 2; tap "استوديو التسجيل والمشاركة"; shot 50-studio 4
adb shell input swipe 540 1700 540 500 400; shot 51-studio-2
adb shell input swipe 540 1700 540 500 400; shot 52-studio-3
tap "الجودة والمشاركة"; shot 53-studio-settings 3; back
back; back
top; tap "الأوزان والتقدّم"; sleep 2; tap "جهاز ضغط الصدر"; shot 54-weight-detail 3; back; back
adb shell settings put system font_scale 1.5
adb shell am force-stop $PKG; start; shot 09-main-bigfont 5
adb shell settings put system font_scale 1.0
adb shell am force-stop $PKG; start; sleep 4
adb shell am force-stop $PKG; start; sleep 3
top; tap "ابدأ الحصة"; shot 10-workout-intro 6
for i in 1 2 3 4 5 6; do top; tap "تخطي الخطوة الحالية"; shot "1$i-workout-skip$i" 4; done
for i in 1 2 3; do top; tap "تخطي الخطوة الحالية"; shot "2$i-workout-more$i" 7; done
adb shell input swipe 540 1700 540 500 400; shot 30-workout-bottom 2
tap "تسجيل وزن"; shot 40-weight-dialog 3
tap "حفظ"; sleep 2
adb shell am force-stop $PKG; start; sleep 3
top; tap "الأوزان والتقدّم"; shot 60-weights-list 3; tap "جهاز ضغط الصدر"; shot 61-weight-detail-data 3; back; back
adb logcat -d | grep -A40 "FATAL EXCEPTION" > shots/logcat.txt; adb logcat -d | grep -iE "ActivityTaskManager.*gymcoach" | tail -40 >> shots/logcat.txt
