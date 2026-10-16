#!/bin/bash
# Проверка на эмуляторе: вставить план и нажать «Сохранить».
set -x
OUT=smoke-out
mkdir -p $OUT
APK=app/build/outputs/apk/release/app-release.apk
PKG=app.weekplan

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r $APK
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

shot() { adb exec-out screencap -p > $OUT/$1.png; }

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml $OUT/ui-$2.xml >/dev/null
  b=$(python3 - "$1" "$OUT/ui-$2.xml" <<'PY'
import re,sys
t,f=sys.argv[1],sys.argv[2]
x=open(f,encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    tx=re.search(r' text="([^"]*)"',n); cd=re.search(r'content-desc="([^"]*)"',n)
    if (tx and tx.group(1)==t) or (cd and cd.group(1)==t):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  echo "tap '$1' at $b"
  [ -n "$b" ] && adb shell input tap $b
}

# 1. Чистый запуск
adb shell am start -W -n $PKG/.MainActivity
sleep 3; shot 1-empty

# 2. План через «Поделиться»
python3 - <<'PY'
t=open('.github/scripts/plan.txt',encoding='utf-8').read()
open('smoke-out/share.sh','w',encoding='utf-8').write(
  "am start -a android.intent.action.SEND -t text/plain -n app.weekplan/.MainActivity --es android.intent.extra.TEXT '"+t+"'\n")
PY
adb push $OUT/share.sh /data/local/tmp/share.sh
adb shell sh /data/local/tmp/share.sh
sleep 3; shot 2-dialog
tap_text "Сохранить" a
sleep 4; shot 3-after-save
tap_text "Сохранить" b   # если появился вопрос про непонятные строки
sleep 4; shot 4-week

# 3. Открыть день
tap_text "Открыть: Понедельник" c
sleep 3; shot 5-day
tap_text "Назад к неделе" d
sleep 2

# 4. Поворот экрана
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3; shot 6-rotated
adb shell settings put system user_rotation 0
sleep 2

# 5. Перезапуск после сохранения
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 3; shot 7-relaunch

adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt
adb shell dumpsys alarm | grep -c "app.weekplan" > $OUT/alarms-count.txt || true
grep -n -A40 "FATAL EXCEPTION" $OUT/logcat.txt | head -120 || true
cat $OUT/crash.txt | head -80
if grep -q "FATAL EXCEPTION" $OUT/logcat.txt; then echo "CRASH FOUND"; fi
exit 0
