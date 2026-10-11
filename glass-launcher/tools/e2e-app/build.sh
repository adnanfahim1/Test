#!/usr/bin/env bash
# TEST ONLY: builds emulator-out/e2e-app.apk used by tools/emulator-check.sh (never shipped).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../..
T="${TOOLS:-$ROOT/.tools}"
B=build; rm -rf "$B"; mkdir -p "$B/gen" "$B/classes"
"$T/aapt2" compile --dir res -o "$B/res.zip"
"$T/aapt2" link -I "$T/android-framework.jar" --manifest AndroidManifest.xml --min-sdk-version 23 --target-sdk-version 35 \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$T/android.jar" -d "$B/classes" $(find src "$B/gen" -name '*.java') 2>&1 | grep -v -E '^(Note:|warning:|Picked up)' || true
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"
cp "$B/base.apk" "$B/unsigned.apk"; (cd "$B" && zip -q -j unsigned.apk classes.dex)
mkdir -p "$B/key"; keytool -genkeypair -storetype PKCS12 -keystore "$B/key/test.p12" -storepass testtest -keypass testtest \
  -alias t -keyalg RSA -keysize 2048 -validity 100 -dname "CN=E2E test" >/dev/null 2>&1
python3 "$ROOT/tools/zipalign.py" "$B/unsigned.apk" "$B/aligned.apk"
javac -nowarn -cp "$T/apksig.jar" -d "$B/signer" "$ROOT/tools/SignApk.java" 2>&1 | grep -v "^Picked up" || true
jarsigner -keystore "$B/key/test.p12" -storetype PKCS12 -storepass testtest -digestalg SHA-256 -sigalg SHA256withRSA "$B/aligned.apk" t >/dev/null 2>&1
python3 "$ROOT/tools/zipalign.py" "$B/aligned.apk" "$B/v1.apk"
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk "$B/v1.apk" "$ROOT/emulator-out/e2e-app.apk" "$B/key/test.p12" testtest t 2>&1 | grep -v "^Picked up" || true
ls -la "$ROOT/emulator-out/e2e-app.apk"
