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
}

if ! adb shell pm list packages | grep -q "$PKG"; then
  echo "== Install failed; Android package manager log:"
  adb logcat -d | grep -i -E -A25 "PackageParser|PackageManager.*(xception|ail)|vmdl|AndroidManifest" | head -120
fi

echo "== Screens"
adb shell input keyevent KEYCODE_HOME; shot home-dashboard 6
for s in music apps settings car; do
  adb shell am start -n $ACT --es screen $s >/dev/null; shot "$s" 4
done
for l in 1 2 3; do
  adb shell am start -n $ACT --ei home_layout $l --es screen home >/dev/null; shot "home-layout$l" 4
done
adb shell am start -n $ACT --es theme Emerald --ei home_layout 0 --es screen home >/dev/null; shot home-emerald 4

echo "== New in 1.4: songs on the head unit, swipe to apps, Wi-Fi page"
python3 - "$OUT/Glass Test Tone.wav" <<'PY'
import math, struct, sys, wave
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
w.writeframes(b"".join(struct.pack("<h", int(9000 * math.sin(2 * math.pi * 440 * i / 22050))) for i in range(22050 * 6)))
w.close()
PY
adb shell mkdir -p /sdcard/Music >/dev/null 2>&1
adb push "$OUT/Glass Test Tone.wav" "/sdcard/Music/Glass Test Tone.wav" 2>&1 | tail -1
adb shell ls -l /sdcard/Music/ 2>&1 | head -3
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/Glass%20Test%20Tone.wav" >/dev/null 2>&1 || true
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 3
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $ACT --es screen music >/dev/null; shot music-songs 7
adb shell input tap 350 208; shot music-song-playing 4
adb shell am start -n $ACT --es screen home >/dev/null; shot home-song-playing 4
adb shell dumpsys media_session 2>/dev/null | grep -i -A3 "GlassLauncherPlayer" | head -8
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; sleep 1
adb shell input swipe 1000 360 450 370 250; shot swipe-to-apps 3
adb shell am start -n $ACT --es screen settings --ei settings_section 5 >/dev/null; shot settings-wifi 4
adb shell am start -n $ACT --es screen settings --ei settings_section 3 >/dev/null; shot settings-weather 4

echo "== Pull-down panel"
adb shell settings get secure theme_customization_overlay_packages
adb shell cmd statusbar expand-settings >/dev/null 2>&1 || adb shell service call statusbar 2 >/dev/null 2>&1
shot shade-emerald 3
adb shell cmd statusbar collapse >/dev/null 2>&1 || adb shell service call statusbar 2 >/dev/null 2>&1
adb shell am start -n $ACT --es theme Violet --es screen home >/dev/null; sleep 3
adb shell cmd statusbar expand-settings >/dev/null 2>&1 || true
shot shade-violet 3
adb shell cmd statusbar collapse >/dev/null 2>&1 || true

echo "== Hardware keys"
for k in KEYCODE_MEDIA_PLAY_PAUSE KEYCODE_MEDIA_NEXT KEYCODE_MUSIC KEYCODE_SETTINGS KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_BACK KEYCODE_HOME; do
  adb shell input keyevent $k; sleep 1
done

echo "== Monkey stress test (random taps/keys inside the launcher)"
adb shell monkey -p $PKG --pct-syskeys 0 --throttle 150 -s 42 -v 800 > "$OUT/monkey-$LABEL.txt" 2>&1 || true
tail -5 "$OUT/monkey-$LABEL.txt"

adb shell am start -n $ACT --es screen home >/dev/null; shot after-monkey 5

echo "== Compatibility mode and Help screen"
adb shell am start -n $ACT --ez compat_mode true >/dev/null; sleep 4
adb shell am start -n $ACT --es screen home >/dev/null; shot home-compat-mode 6
adb shell am start -n $ACT --ez compat_mode false >/dev/null; sleep 4
adb shell am start -n $PKG/.HelpActivity >/dev/null; shot help-screen 4
adb shell am start -n $ACT --es screen home >/dev/null; sleep 3

echo "== Glass Link phone app (Android 6+)"
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ -f dist/GlassLink.apk ] && [ "${API:-0}" -ge 23 ]; then
  adb install -r -g dist/GlassLink.apk 2>&1 | tail -1
  adb shell am start -n com.adnan.glasslink/.MainActivity >/dev/null; shot glass-link 4
  # Press Start (scroll down, find the button with uiautomator) and let the link service run.
  for i in 1 2 3; do adb shell input swipe 640 600 640 150 200; done; sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  XY=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import re, sys
m = re.search(r"text=\"Start\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", sys.stdin.read())
print("%d %d" % ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else "")')
  if [ -n "$XY" ]; then adb shell input tap $XY; sleep 6; shot glass-link-started 1; else echo "Start button not found"; fi
  adb shell dumpsys activity services com.adnan.glasslink 2>/dev/null | grep -E "ServiceRecord|isForeground" | head -4
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
adb shell am start -n $ACT --es screen home >/dev/null; sleep 3
adb logcat -d > "$OUT/logcat-$LABEL.txt" || true

FAIL=0
if grep -q -E "FATAL EXCEPTION" "$OUT/logcat-$LABEL.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat-$LABEL.txt" | grep -q -E "$PKG|com.adnan.glasslink"; then
  echo "CRASH found in logcat:"; grep -A25 "FATAL EXCEPTION" "$OUT/logcat-$LABEL.txt" | head -60; FAIL=1
fi
if grep -q -E "CRASH: $PKG|// CRASH" "$OUT/monkey-$LABEL.txt"; then echo "Monkey reported a crash"; FAIL=1; fi
if ! adb shell pidof $PKG >/dev/null 2>&1 && ! adb shell ps | grep -q $PKG; then echo "Launcher is not running at the end"; FAIL=1; fi
RECOVERED=$(adb shell run-as $PKG cat shared_prefs/glass_launcher.xml 2>/dev/null | grep -c last_error || true)
echo "Recovered (non-fatal) errors recorded: ${RECOVERED:-unknown}"
[ $FAIL = 0 ] && echo "RESULT $LABEL: PASS" || echo "RESULT $LABEL: FAIL"
exit $FAIL
