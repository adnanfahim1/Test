#!/usr/bin/env bash
# Builds dist/GlassLauncher.apk without Gradle or the Android SDK. See README.md.
# Needs: JDK 17+, python3, curl, zip, unzip (Linux x86-64).
#
# Signing: set KEYSTORE / KEYSTORE_PASS to your saved key so every new build installs as an
# update over the old one (same key = settings kept). Without a key a new one is created.
set -euo pipefail
cd "$(dirname "$0")"
T="${TOOLS:-.tools}"
./tools/fetch-tools.sh "$T" >/dev/null

MIN_SDK=21
TARGET_SDK=35
B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/compat" "$B/signer" dist signing

echo "1/6 Resources"
"$T/aapt2" compile --dir res -o "$B/res.zip"
"$T/aapt2" link -I "$T/android-framework.jar" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK -0 webp -0 ttf \
  --java "$B/gen" -o "$B/base.apk" "$B/res.zip"

echo "2/6 Java"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$T/android.jar" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') 2>&1 | grep -v -E '^(Note:|warning: \[options\])' || true
[ -f "$B/classes/com/adnan/glasslauncher/MainActivity.class" ] || { echo "Java compile failed"; exit 1; }

echo "3/6 Compatibility check (Android 5.0 / API $MIN_SDK)"
# Everything except NewApi.java must compile against the API 21 framework. NewApi.java is the
# one place allowed to use newer APIs, always behind an SDK_INT check.
mkdir -p "$B/newapi-cp/com/adnan/glasslauncher" && cp "$B/classes/com/adnan/glasslauncher/NewApi.class" "$B/newapi-cp/com/adnan/glasslauncher/"
if ! javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -cp "$T/android-21.jar:$B/newapi-cp" -d "$B/compat" \
  $(find src "$B/gen" -name '*.java' ! -name NewApi.java) > "$B/compat.log" 2>&1; then
  grep -v "^Picked up" "$B/compat.log" | head -40 || true
  echo "Compatibility check FAILED: code above uses APIs newer than Android 5.0 outside NewApi.java"
  exit 1
fi

echo "4/6 Dex"
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=$MIN_SDK --output="$B/classes.dex" "$B/classes"

echo "5/6 Package"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -j unsigned.apk classes.dex)

echo "6/6 Sign (v1 for Android 5-6, v2 for 7+)"
KS="${KEYSTORE:-signing/release.p12}"
PASS="${KEYSTORE_PASS:-glasslauncher}"
ALIAS="${KEY_ALIAS:-glass}"
if [ ! -f "$KS" ]; then
  echo "   (creating a new signing key at $KS — keep it to install future updates over this one)"
  keytool -genkeypair -storetype PKCS12 -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Launcher" >/dev/null 2>&1
fi
# v1 (JAR) signature with the JDK's jarsigner, then apksig adds v2 on top and aligns the APK.
cp "$B/unsigned.apk" "$B/v1.apk"
jarsigner -keystore "$KS" -storetype PKCS12 -storepass "$PASS" -digestalg SHA-256 -sigalg SHA256withRSA \
  "$B/v1.apk" "$ALIAS" >/dev/null
python3 tools/zipalign.py "$B/v1.apk" "$B/v1-aligned.apk"
javac -nowarn -cp "$T/apksig.jar" -d "$B/signer" tools/SignApk.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk \
  "$B/v1-aligned.apk" dist/GlassLauncher.apk "$KS" "$PASS" "$ALIAS"
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$T/apksig.jar:$B/signer" SignApk --verify dist/GlassLauncher.apk
python3 tools/zipalign.py --check dist/GlassLauncher.apk
ls -la dist/GlassLauncher.apk
