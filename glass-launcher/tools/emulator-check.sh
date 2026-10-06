#!/usr/bin/env bash
# Exercises Glass Launcher on a running emulator/device and fails on any crash.
# Usage: tools/emulator-check.sh <label>   (run from the repo root; writes glass-launcher/emulator-out/)
set -u
LABEL="${1:-device}"
PKG=com.adnan.glasslauncher
ACT=$PKG/.MainActivity
cd "$(dirname "$0")/.."
OUT=emulator-out
mkdir -p "$OUT"
adb wait-for-device
adb shell wm density 160 >/dev/null 2>&1 || true
adb logcat -c || true

echo "== Setup script (installs, grants, sets home, applies theme)"
bash tools/setup-headunit.sh --theme Violet --layout dashboard 2>&1 | tee "$OUT/setup-$LABEL.txt"

shot() {
  sleep "${2:-5}"
  adb exec-out screencap -p > "$OUT/$LABEL-$1.png"
  # Small inline copy (base64 JPEG) so the screens can be viewed straight from the job log.
  if command -v convert >/dev/null; then
    echo "SHOT-BEGIN $LABEL-$1"
    convert "$OUT/$LABEL-$1.png" -resize 640x -quality 55 jpg:- | base64 -w 0; echo
    echo "SHOT-END $LABEL-$1"
  fi
}

echo "== Screens"
adb shell input keyevent KEYCODE_HOME; shot home-dashboard 6
for s in music apps settings car; do
  adb shell am start -n $ACT --es screen $s >/dev/null; shot "$s" 4
done
for l in 1 2 3; do
  adb shell am start -n $ACT --ei home_layout $l --es screen home >/dev/null; shot "home-layout$l" 4
done
adb shell am start -n $ACT --es theme Emerald --ei home_layout 0 --es screen home >/dev/null; shot home-emerald 4

echo "== Hardware keys"
for k in KEYCODE_MEDIA_PLAY_PAUSE KEYCODE_MEDIA_NEXT KEYCODE_MUSIC KEYCODE_SETTINGS KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_BACK KEYCODE_HOME; do
  adb shell input keyevent $k; sleep 1
done

echo "== Monkey stress test (random taps/keys inside the launcher)"
adb shell monkey -p $PKG --pct-syskeys 0 --throttle 150 -s 42 -v 800 > "$OUT/monkey-$LABEL.txt" 2>&1 || true
tail -5 "$OUT/monkey-$LABEL.txt"

adb shell am start -n $ACT --es screen home >/dev/null; shot after-monkey 5
adb logcat -d > "$OUT/logcat-$LABEL.txt" || true

FAIL=0
if grep -q -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" "$OUT/logcat-$LABEL.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat-$LABEL.txt" | grep -q "$PKG"; then
  echo "CRASH found in logcat:"; grep -A25 "FATAL EXCEPTION" "$OUT/logcat-$LABEL.txt" | head -60; FAIL=1
fi
if grep -q -E "CRASH: $PKG|// CRASH" "$OUT/monkey-$LABEL.txt"; then echo "Monkey reported a crash"; FAIL=1; fi
if ! adb shell pidof $PKG >/dev/null 2>&1 && ! adb shell ps | grep -q $PKG; then echo "Launcher is not running at the end"; FAIL=1; fi
RECOVERED=$(adb shell run-as $PKG cat shared_prefs/glass_launcher.xml 2>/dev/null | grep -c last_error || true)
echo "Recovered (non-fatal) errors recorded: ${RECOVERED:-unknown}"
[ $FAIL = 0 ] && echo "RESULT $LABEL: PASS" || echo "RESULT $LABEL: FAIL"
exit $FAIL
