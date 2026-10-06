#!/usr/bin/env bash
# Builds ../dist/GlassLink.apk (the phone app) with the same toolchain and key as the launcher.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
T="${TOOLS:-$ROOT/.tools}"
"$ROOT/tools/fetch-tools.sh" "$T" >/dev/null
B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/signer" "$ROOT/dist"

echo "1/5 Resources"
# The current car app travels inside Glass Link, so the phone can update the car over Bluetooth.
mkdir -p "$B/assets/car"
if [ -f "$ROOT/dist/GlassLauncher.apk" ]; then cp "$ROOT/dist/GlassLauncher.apk" "$B/assets/car/GlassLauncher.apk"
else echo "   (no ../dist/GlassLauncher.apk: build the launcher first to include it)"; fi
"$T/aapt2" compile --dir res -o "$B/res.zip"
"$T/aapt2" link -I "$T/android-framework.jar" --manifest AndroidManifest.xml -A "$B/assets" -0 apk \
  --min-sdk-version 23 --target-sdk-version 35 --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "2/5 Java"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$T/android.jar" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') 2>&1 | grep -v -E '^(Note:|warning: \[options\]|Picked up)' || true
[ -f "$B/classes/com/adnan/glasslink/MainActivity.class" ] || { echo "Java compile failed"; exit 1; }

echo "3/5 Dex"
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"

echo "4/5 Package"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -j unsigned.apk classes.dex)

echo "5/5 Sign"
KS="${KEYSTORE:-$ROOT/signing/release.p12}"
PASS="${KEYSTORE_PASS:-glasslauncher}"
ALIAS="${KEY_ALIAS:-glass}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -storetype PKCS12 -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Launcher" >/dev/null 2>&1
fi
cp "$B/unsigned.apk" "$B/v1.apk"
jarsigner -keystore "$KS" -storetype PKCS12 -storepass "$PASS" -digestalg SHA-256 -sigalg SHA256withRSA \
  "$B/v1.apk" "$ALIAS" >/dev/null 2>&1
python3 "$ROOT/tools/zipalign.py" "$B/v1.apk" "$B/v1-aligned.apk"
javac -nowarn -cp "$T/apksig.jar" -d "$B/signer" "$ROOT/tools/SignApk.java" 2>&1 | grep -v "^Picked up" || true
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk \
  "$B/v1-aligned.apk" "$ROOT/dist/GlassLink.apk" "$KS" "$PASS" "$ALIAS" 2>&1 | grep -v "^Picked up" || true
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk --verify "$ROOT/dist/GlassLink.apk" 2>&1 | grep -v "^Picked up"
python3 "$ROOT/tools/zipalign.py" --check "$ROOT/dist/GlassLink.apk"
ls -la "$ROOT/dist/GlassLink.apk"
