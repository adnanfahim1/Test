#!/usr/bin/env bash
# Glass Launcher – head unit setup over ADB (Linux / macOS).
#
# Installs or updates the launcher and applies the theme to the head unit. Every step first
# checks what this unit's Android version and firmware allow: what it can change it changes,
# everything else is left exactly as it is and reported as "left as is". Nothing is deleted
# and the stock launcher stays installed.
#
# Usage: tools/setup-headunit.sh [--theme Violet|Ocean|Emerald|Rose]
#                                [--layout dashboard|showcase|minimal|drive]
#                                [--apk path/to/GlassLauncher.apk] [--no-dark] [--no-wallpaper] [--no-shade]
#                                [--no-default-home] [--fresh] [--serial DEVICE]
set -u
PKG=com.adnan.glasslauncher
ACT=$PKG/.MainActivity
LISTENER=$PKG/$PKG.MediaListenerService
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../dist/GlassLauncher.apk"
THEME=Violet; LAYOUT=""; DARK=1; WALL=1; SHADE=1; SETHOME=1; FRESH=0; SERIAL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --theme) THEME="$2"; shift ;;
    --layout) LAYOUT="$2"; shift ;;
    --apk) APK="$2"; shift ;;
    --no-dark) DARK=0 ;;
    --no-wallpaper) WALL=0 ;;
    --no-shade) SHADE=0 ;;
    --no-default-home) SETHOME=0 ;;
    --fresh) FRESH=1 ;;
    --serial) SERIAL="$2"; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
  shift
done

case "$(echo "$THEME" | tr 'A-Z' 'a-z')" in
  violet) THEME=Violet ;; ocean) THEME=Ocean ;; emerald) THEME=Emerald ;; rose) THEME=Rose ;;
  *) echo "Theme must be Violet, Ocean, Emerald or Rose"; exit 2 ;;
esac
LAYOUT_N=""
case "$(echo "$LAYOUT" | tr 'A-Z' 'a-z')" in
  "") ;; dashboard) LAYOUT_N=0 ;; showcase|car*) LAYOUT_N=1 ;; minimal) LAYOUT_N=2 ;; drive*) LAYOUT_N=3 ;;
  *) echo "Layout must be dashboard, showcase, minimal or drive"; exit 2 ;;
esac

ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
CHANGED=(); LEFT=()
ok()   { echo "  ✔ $1"; CHANGED+=("$1"); }
skip() { echo "  – $1 (left as is: $2)"; LEFT+=("$1: $2"); }
sh_()  { "${ADB[@]}" shell "$@" 2>&1 | tr -d '\r'; }

command -v adb >/dev/null 2>&1 || { echo "adb not found. Install Android platform-tools and enable USB debugging on the head unit."; exit 1; }
if [ "$("${ADB[@]}" get-state 2>/dev/null)" != "device" ]; then
  echo "No head unit found over ADB. Connect USB, enable USB debugging (often in the head unit's"
  echo "developer/factory settings, sometimes behind a code) and accept the prompt on the screen."
  exit 1
fi

SDK=$(sh_ getprop ro.build.version.sdk); REL=$(sh_ getprop ro.build.version.release)
echo "Head unit: $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)"
echo "Android:   $REL (API $SDK) · build $(sh_ getprop ro.build.display.id)"
echo "Screen:    $(sh_ wm size | tail -1 | sed 's/.*: //') · density $(sh_ wm density | tail -1 | sed 's/.*: //')"
echo
case "$SDK" in ''|*[!0-9]*) SDK=0 ;; esac
if [ "$SDK" -lt 21 ]; then
  echo "This unit runs Android older than 5.0, which Glass Launcher can't run on."; exit 1
fi

echo "1. Install / update"
[ -f "$APK" ] || { echo "APK not found at $APK (build it with ./build.sh or pass --apk)"; exit 1; }
if [ "$FRESH" = 1 ]; then "${ADB[@]}" uninstall $PKG >/dev/null 2>&1; fi
OUT=$("${ADB[@]}" install -r "$APK" 2>&1 | tr -d '\r')
if echo "$OUT" | grep -q Success; then ok "Glass Launcher installed/updated"
elif echo "$OUT" | grep -q -E "UPDATE_INCOMPATIBLE|signatures do not match"; then
  echo "  ✘ The installed copy was signed with a different key. Run again with --fresh"
  echo "    (uninstalls it first; the launcher's own settings are reset)."; exit 1
else echo "  ✘ Install failed: $OUT"; exit 1; fi

echo "2. Permissions"
if [ "$SDK" -ge 23 ]; then
  for P in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do
    if sh_ pm grant $PKG android.permission.$P | grep -q -i -E "exception|error"; then skip "$P" "not grantable on this firmware"
    else ok "$P granted (weather)"; fi
  done
  if [ "$SDK" -ge 31 ]; then
    if sh_ pm grant $PKG android.permission.BLUETOOTH_CONNECT | grep -q -i -E "exception|error"; then skip "Nearby devices" "not grantable"
    else ok "Nearby devices granted (phone status)"; fi
  fi
else
  skip "Runtime permissions" "Android $REL grants them at install"
fi

if [ "$SDK" -ge 31 ] && [ "$SHADE" = 1 ]; then
  if sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS | grep -q -i -E "exception|error"; then skip "Pull-down panel colour permission" "not grantable on this firmware"
  else ok "Pull-down panel colour permission granted"; fi
fi

echo "3. Now-playing access (notification listener)"
DONE=0
if [ "$SDK" -ge 27 ] && ! sh_ cmd notification allow_listener $LISTENER | grep -q -i -E "exception|error|unknown"; then DONE=1; fi
if [ $DONE = 0 ]; then
  CUR=$(sh_ settings get secure enabled_notification_listeners)
  case "$CUR" in *"$LISTENER"*) DONE=1 ;;
    *) if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW=$LISTENER; else NEW="$CUR:$LISTENER"; fi
       sh_ settings put secure enabled_notification_listeners "$NEW" >/dev/null ;;
  esac
fi
if sh_ settings get secure enabled_notification_listeners | grep -q "$LISTENER"; then ok "Now-playing access on"
else skip "Now-playing access" "firmware blocks it; allow it once from the music bar"; fi

echo "4. Keep running in the background"
if [ "$SDK" -ge 23 ] && sh_ dumpsys deviceidle whitelist +$PKG | grep -q -i added; then ok "Battery optimisation off for the launcher"
else skip "Battery optimisation" "not available on this firmware"; fi

echo "5. Default home app"
if [ "$SETHOME" = 1 ]; then
  if [ "$SDK" -ge 24 ] && sh_ cmd package set-home-activity $ACT | grep -q -i success; then ok "Glass Launcher is the default home"
  else skip "Default home" "press Home on the unit and choose Glass Launcher › Always"; fi
else skip "Default home" "--no-default-home"; fi

echo "6. Theme"
EXTRA=(--es theme "$THEME")
[ -n "$LAYOUT_N" ] && EXTRA+=(--ei home_layout "$LAYOUT_N")
[ "$WALL" = 1 ] && EXTRA+=(--ez apply_system_theme true)
[ "$SDK" -ge 31 ] && [ "$SHADE" = 1 ] && EXTRA+=(--ez match_shade true)
if sh_ am start -n $ACT "${EXTRA[@]}" | grep -q -i error; then skip "Launcher theme" "launcher didn't start"
else
  ok "Launcher theme set to $THEME${LAYOUT:+, home layout $LAYOUT}"
  [ "$WALL" = 1 ] && ok "System wallpaper matched to $THEME (the launcher shows what it could change)"
fi
if [ "$SHADE" = 1 ]; then
  if [ "$SDK" -lt 31 ]; then skip "Pull-down panel colour" "Android $REL can't recolour it (needs 12+)"
  else
    sleep 2
    if sh_ settings get secure theme_customization_overlay_packages | grep -q -i "system_palette"; then ok "Pull-down panel colour matched to $THEME"
    else skip "Pull-down panel colour" "this firmware ignores it"; fi
  fi
fi
if [ "$DARK" = 1 ]; then
  if [ "$SDK" -ge 29 ]; then
    sh_ cmd uimode night yes >/dev/null
    if sh_ cmd uimode night | grep -q -i yes; then ok "System dark mode on"
    else skip "Dark mode" "firmware doesn't allow changing it"; fi
  else skip "Dark mode" "Android $REL has no system dark mode"; fi
fi
skip "Other apps' colours, icons and fonts" "Android doesn't allow this without root"
skip "Boot logo, button backlight, steering keys, EQ" "vendor settings; open Car settings in the launcher"

echo
echo "Done. Changed ${#CHANGED[@]} item(s); left ${#LEFT[@]} as is."
echo "To go back to the stock launcher: Android Settings › Apps › Default apps › Home app."
