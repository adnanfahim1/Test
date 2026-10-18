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
  timeout 30 adb exec-out screencap -p > "$OUT/$LABEL-$1.png"
  # No half-written or empty screenshots (e.g. when the device didn't answer).
  head -c 8 "$OUT/$LABEL-$1.png" 2>/dev/null | grep -q PNG || rm -f "$OUT/$LABEL-$1.png"
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

UI="python3 tools/ui.py"
RAIL_FAIL=0
# Android's "Remove animations": the launcher then stops its constant animations (spinning record,
# equaliser bars), so uiautomator can read the screen on every Android version.
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0
adb shell am force-stop $PKG; sleep 1
adb shell am start -n $ACT --es screen home >/dev/null; sleep 4
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

echo "-- Rail: Apps in the bottom slot"
adb shell am start -n $ACT --es screen home >/dev/null; sleep 3
if $UI tap '^Apps$'; then sleep 2; if $UI has '^[0-9]+ apps$'; then echo "RAIL: Apps opens the app drawer"; else echo "RAIL: Apps didn't open the drawer"; RAIL_FAIL=1; fi; else echo "RAIL: Apps button not found"; RAIL_FAIL=1; fi
adb shell am start -n $ACT --es screen home >/dev/null; sleep 2

echo "-- Always open on Home: car switched off with Settings open, then on again"
HOME_FAIL=0
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
# Keep the CPU awake while the screen is off (root-only, i.e. the Android 5 test image): there the
# emulator otherwise suspends fully and adb no longer reaches it after waking. The screen still goes
# off, so the launcher sees the same "screen back on after the car was off" as on a head unit.
adb shell 'echo glasscheck > /sys/power/wake_lock' >/dev/null 2>&1 || true
adb shell am start -n $ACT --es screen settings >/dev/null; sleep 2
adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity" | tr -d '\r'
timeout 20 adb shell input keyevent KEYCODE_SLEEP; sleep 100
# Some Android versions (5.x) really suspend once the screen is off, like a head unit at ACC-off,
# and adb can't reach them: then press the power key from the emulator side, like switching the car on.
if ! timeout 15 adb shell input keyevent KEYCODE_WAKEUP; then
  echo "(device asleep: power key pressed from the emulator)"
  adb emu event send EV_KEY:KEY_POWER:1 EV_KEY:KEY_POWER:0 >/dev/null 2>&1 || adb emu event send 1:116:1 1:116:0 >/dev/null 2>&1
  sleep 5
  timeout 60 adb wait-for-device
fi
sleep 3
timeout 15 adb shell input keyevent 82; sleep 5
TOP=$(timeout 20 adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity" | tr -d '\r')
echo "$TOP"
timeout 20 adb logcat -d -s GlassHome | tail -2
if echo "$TOP" | grep -q "$PKG/"; then
  if $UI has '^Good (morning|afternoon|evening)$'; then echo "HOME ON POWER-ON: Home shown"; else echo "HOME ON POWER-ON: launcher in front but not on Home"; $UI text | head -10; HOME_FAIL=1; fi
else
  echo "HOME ON POWER-ON: FAILED (another app stayed in front)"; HOME_FAIL=1
fi
shot home-after-power-on 1
timeout 15 adb shell 'echo glasscheck > /sys/power/wake_unlock' >/dev/null 2>&1 || true
adb shell am start -n $ACT --es screen home >/dev/null; sleep 2

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

echo "== Glass Link phone app + phone link end to end (Android 6+)"
# Car app and phone app run on the same emulator: Glass Link must find the car's Wi-Fi
# announcement, connect, deliver weather, reconnect after the car app restarts and send
# an update that the car installs after Install is pressed.
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
LINK_FAIL=0
if [ -f dist/GlassLink.apk ] && [ "${API:-0}" -ge 23 ]; then
  adb install -r -g dist/GlassLink.apk 2>&1 | tail -1
  adb emu geo fix 90.4125 23.8103 >/dev/null 2>&1 || true
  adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
  adb shell am start -n com.adnan.glasslink/.MainActivity >/dev/null; shot glass-link 4
  $UI tap '^Start$' --scroll
  sleep 2
  adb shell am start -n com.adnan.glasslink/.MainActivity >/dev/null; sleep 1
  adb shell input swipe 640 200 640 700 200; adb shell input swipe 640 200 640 700 200
  if $UI wait '^Connected to' 45; then
    echo "LINK: connected automatically (Wi-Fi announcement)"
  else
    echo "LINK: not found automatically; entering the address by hand"
    $UI tap "Enter the car's Wi-Fi address" --scroll && sleep 1 && $UI edit 127.0.0.1 && $UI tap '^(Save|SAVE)$'
    adb shell input swipe 640 200 640 700 200; adb shell input swipe 640 200 640 700 200
    if $UI wait '^Connected to' 40; then echo "LINK: connected with the typed address"; else echo "LINK: FAILED"; LINK_FAIL=1; fi
  fi
  shot glass-link-connected 2
  $UI tap '^Show$' --scroll >/dev/null; shot glass-link-log 2
  adb shell input swipe 640 200 640 700 200; adb shell input swipe 640 200 640 700 200
  $UI text | head -12

  echo "-- Phone music and navigation through the link (test app pretends to be Waze + a music app)"
  if [ -f "$OUT/e2e-app.apk" ]; then
    adb install -r -g "$OUT/e2e-app.apk" 2>&1 | tail -1
    # Only Glass Link may read notifications/media during this step, so whatever the car shows
    # can only have come through the phone link (the car app could otherwise see the test app itself).
    LIS=$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')
    adb shell settings put secure enabled_notification_listeners "com.adnan.glasslink/com.adnan.glasslink.NavListener"
    adb shell cmd notification allow_listener com.adnan.glasslink/com.adnan.glasslink.NavListener >/dev/null 2>&1 || true
    sleep 3
    adb shell am start -n com.waze/.TestActivity >/dev/null; sleep 5
    adb shell am start -n $ACT --es screen home >/dev/null; sleep 4
    if $UI wait 'E2E Test Song 1' 30 && $UI has 'ON YOUR PHONE'; then echo "PHONE MUSIC: shown on the car (from the phone)"; else echo "PHONE MUSIC: FAILED"; $UI text | head -30; LINK_FAIL=1; fi
    if $UI wait '^300 m$' 20 && $UI has '^Waze on (?!this head unit)'; then echo "PHONE NAV: shown on the car (from the phone)"; $UI text | grep -E "300 m|Test Street|ETA|Waze on"; else echo "PHONE NAV: FAILED"; LINK_FAIL=1; fi
    shot home-phone-music-nav 1
    adb logcat -c
    $UI tap '^Next track$' >/dev/null
    if $UI wait 'E2E Test Song 2' 20; then echo "PHONE CONTROL: Next from the car skipped the song on the phone"; else echo "PHONE CONTROL: FAILED"; LINK_FAIL=1; fi
    adb logcat -d -s E2E_MEDIA | tail -3
    adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; sleep 3
    adb logcat -d -s E2E_MEDIA | grep -q "cmd=pause" && echo "PHONE CONTROL: steering-wheel play/pause reached the phone" || { echo "PHONE CONTROL: play/pause FAILED"; LINK_FAIL=1; }
    adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE; sleep 2
    adb shell am start -n $ACT --es screen music >/dev/null; shot music-phone 4
    # 1.8: where the sound plays, the app's own buttons, the phone's queue, messages.
    adb logcat -c
    if $UI wait 'Sound is on the phone' 15; then echo "PHONE AUDIO: the car shows the sound is on the phone"; else echo "PHONE AUDIO: route FAILED"; $UI text | head -20; LINK_FAIL=1; fi
    if $UI tap '^Like$'; then
      sleep 3; adb logcat -d -s E2E_MEDIA | grep -q "cmd=custom=like" && echo "PHONE BUTTONS: Like reached the music app" || { echo "PHONE BUTTONS: FAILED"; LINK_FAIL=1; }
    else echo "PHONE BUTTONS: Like not shown"; LINK_FAIL=1; fi
    if $UI tap '^Queue$' && sleep 2 && $UI tap 'Queue item 3'; then
      sleep 3; adb logcat -d -s E2E_MEDIA | grep -q "cmd=queue 103" && echo "PHONE QUEUE: picking a song in the car's queue played it on the phone" || { echo "PHONE QUEUE: FAILED"; LINK_FAIL=1; }
    else echo "PHONE QUEUE: queue not shown"; $UI text | head -20; LINK_FAIL=1; fi
    shot music-phone-queue 2
    if $UI tap 'Sound is on the phone' && $UI wait "Play the phone.s music in the car" 10; then
      echo "PHONE AUDIO: help shown on the car"; shot car-audio-help 1
      $UI tap '^(Close|CLOSE)$' >/dev/null; sleep 2
      adb logcat -d -s GlassLink | grep -q "play through its speakers" && echo "PHONE AUDIO: the phone shows its Bluetooth tip" || { echo "PHONE AUDIO: phone tip FAILED"; LINK_FAIL=1; }
    else echo "PHONE AUDIO: help FAILED"; LINK_FAIL=1; fi
    echo "-- Messages from the phone"
    adb shell am start -n com.waze/.TestActivity --ez msg true >/dev/null; sleep 1
    adb shell am start -n $ACT --es screen home >/dev/null
    if $UI wait 'Message from Alex' 20; then
      echo "MESSAGES: shown on the car"; shot message-banner 1
      if $UI tap '^Reply$' && sleep 1 && $UI tap '^On my way$'; then
        sleep 3; adb logcat -d -s E2E_MSG | grep -q "reply=On my way" && echo "MESSAGES: quick reply from the car reached the app" || { echo "MESSAGES: reply FAILED"; adb logcat -d -s E2E_MSG GlassLink | tail -6; LINK_FAIL=1; }
      else echo "MESSAGES: reply buttons FAILED"; $UI text | head -20; LINK_FAIL=1; fi
    else echo "MESSAGES: FAILED"; adb logcat -d -s GlassLink E2E_MSG | tail -6; LINK_FAIL=1; fi
    adb shell settings put secure enabled_notification_listeners "$LIS"
  fi

  adb shell am start -n $ACT --es screen home >/dev/null; shot home-phone-link 10
  adb shell am start -n $ACT --es screen settings --ei settings_section 3 >/dev/null; shot settings-phone-link 4

  echo "-- Reconnect after the car app restarts"
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -n $ACT --es screen home >/dev/null; sleep 3
  adb shell am start -n com.adnan.glasslink/.MainActivity >/dev/null; sleep 2
  adb shell input swipe 640 200 640 700 200
  if $UI wait '^Connected to' 75; then echo "RECONNECT: OK"; else echo "RECONNECT: FAILED"; LINK_FAIL=1; fi

  echo "-- Update the car app from the phone"
  BEFORE=$(adb shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d '\r')
  if $UI tap 'Send .* to car' --scroll && sleep 2 && $UI tap '^(Send|SEND)$'; then
    if $UI wait 'Tap Install on the car screen' 120; then
      echo "UPDATE: sent and accepted by the car"
      adb shell am start -n $ACT >/dev/null; sleep 3; shot update-dialog 1
      if $UI tap '^(Install|INSTALL)$'; then
        sleep 6; shot update-system-confirm 1
        $UI tap '^(Install|INSTALL|Update|UPDATE)$' || true
        sleep 20
        AFTER=$(adb shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d '\r')
        echo "before: $BEFORE"; echo "after:  $AFTER"
        [ "$BEFORE" != "$AFTER" ] && echo "UPDATE: installed by Android" || echo "UPDATE: Android's confirm screen wasn't completed by the test (see screenshot)"
      else
        echo "UPDATE: FAILED (no Install prompt on the car)"; LINK_FAIL=1
      fi
    else
      echo "UPDATE: FAILED (transfer not confirmed)"; $UI text | head -20; LINK_FAIL=1
    fi
  else
    echo "UPDATE: FAILED (send button not found)"; $UI text | head -20; LINK_FAIL=1
  fi
  adb shell input keyevent KEYCODE_HOME; sleep 4
fi
adb shell am start -n $ACT --es screen home >/dev/null; sleep 3
adb logcat -d > "$OUT/logcat-$LABEL.txt" || true

FAIL=0
# Crashes of our apps fail the run. One exception, reported but not counted: Android 8.0 can start
# the app's process while the update is replacing its package and die inside Android's own start-up
# (handleBindApplication, no app code); Android removes that half-started process right away.
python3 - "$OUT/logcat-$LABEL.txt" "$PKG" > "$OUT/crashes-$LABEL.txt" <<'PY'
import re, sys
lines = open(sys.argv[1], errors="replace").read().splitlines()
pkg = sys.argv[2]
for i, l in enumerate(lines):
    if "FATAL EXCEPTION" not in l: continue
    block = lines[i:i + 40]
    head = "\n".join(block[:3])
    if pkg not in head and "com.adnan.glasslink" not in head: continue
    trace = [b for b in block[1:] if "AndroidRuntime" in b]
    ours = any("com.adnan." in b and "Process:" not in b for b in trace)
    during_update = any("handleBindApplication" in b for b in trace) and any(
        "pkg removed" in b and pkg in b for b in lines[i:i + 25])
    print(("UPDATE-RACE " if (during_update and not ours) else "CRASH ") + str(i))
    for b in block[:25]: print("   " + b)
PY
if grep -q "^CRASH " "$OUT/crashes-$LABEL.txt"; then
  echo "CRASH found in logcat:"; head -60 "$OUT/crashes-$LABEL.txt"; FAIL=1
elif grep -q "^UPDATE-RACE " "$OUT/crashes-$LABEL.txt"; then
  echo "NOTE: Android killed a half-started launcher process while installing the update (Android-only start-up code, no app code):"
  head -8 "$OUT/crashes-$LABEL.txt"
fi
if grep -q -E "CRASH: $PKG|// CRASH" "$OUT/monkey-$LABEL.txt"; then echo "Monkey reported a crash"; FAIL=1; fi
if [ $LINK_FAIL = 1 ]; then echo "Phone link test failed"; FAIL=1; fi
if [ $HOME_FAIL = 1 ]; then echo "Home-on-power-on test failed"; FAIL=1; fi
if [ $RAIL_FAIL = 1 ]; then echo "Rail test failed"; FAIL=1; fi
if ! adb shell pidof $PKG >/dev/null 2>&1 && ! adb shell ps | grep -q $PKG; then echo "Launcher is not running at the end"; FAIL=1; fi
RECOVERED=$(adb shell run-as $PKG cat shared_prefs/glass_launcher.xml 2>/dev/null | grep -c last_error || true)
echo "Recovered (non-fatal) errors recorded: ${RECOVERED:-unknown}"
[ $FAIL = 0 ] && echo "RESULT $LABEL: PASS" || echo "RESULT $LABEL: FAIL"
exit $FAIL
