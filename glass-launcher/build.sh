#!/usr/bin/env bash
# Builds dist/GlassLauncher.apk without Gradle or the Android SDK. See README.md.
set -euo pipefail
cd "$(dirname "$0")"
T="${TOOLS:-.tools}"
./tools/fetch-tools.sh "$T" >/dev/null

B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/signer" dist signing

echo "1/5 Resources"
"$T/aapt2" compile --dir res -o "$B/res.zip"
"$T/aapt2" link -I "$T/android-framework.jar" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 29 -0 webp -0 ttf \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "2/5 Java"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$T/android.jar" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') 2>&1 | grep -v -E '^(Note:|warning: \[options\])' || true
[ -f "$B/classes/com/adnan/glasslauncher/MainActivity.class" ] || { echo "Java compile failed"; exit 1; }

echo "3/5 Dex"
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=24 --output="$B/classes.dex" "$B/classes"

echo "4/5 Package"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -j unsigned.apk classes.dex)

echo "5/5 Sign"
KS="${KEYSTORE:-signing/release.p12}"
PASS="${KEYSTORE_PASS:-glasslauncher}"
if [ ! -f "$KS" ]; then
  echo "   (creating a new signing key at $KS — keep it to install future updates over this one)"
  keytool -genkeypair -storetype PKCS12 -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias glass -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Launcher" >/dev/null 2>&1
fi
javac -nowarn -cp "$T/apksig.jar" -d "$B/signer" tools/SignApk.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk "$B/unsigned.apk" dist/GlassLauncher.apk "$KS" "$PASS" glass
ls -la dist/GlassLauncher.apk
