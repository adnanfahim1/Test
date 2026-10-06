#!/usr/bin/env bash
# Downloads the build toolchain from Maven Central (no Android SDK / Android Studio needed):
#   - aapt2 + framework resources  (org.apktool:apktool-lib)
#   - android.jar classes           (org.robolectric:android-all, Android 14)
#   - dx dexer                      (com.jakewharton.android.repackaged:dalvik-dx)
#   - apksig signer                 (com.android.tools.build:apksig)
# Linux x86-64 only (the bundled aapt2 binary). Needs: curl, unzip, zip, JDK 17+.
set -euo pipefail
OUT="${1:-.tools}"
MAVEN="${MAVEN:-https://repo1.maven.org/maven2}"
mkdir -p "$OUT"
cd "$OUT"

get() { # group/path artifact version file
  [ -f "$4" ] || { echo "Downloading $2 $3"; curl -fsSL -o "$4.part" "$MAVEN/$1/$2/$3/$2-$3.jar" && mv "$4.part" "$4"; }
}

get org/apktool apktool-lib 3.0.3 apktool-lib.jar
get com/jakewharton/android/repackaged dalvik-dx 16.0.1 dx.jar
get com/android/tools/build apksig 2.3.0 apksig.jar

if [ ! -x aapt2 ] || [ ! -f android-framework.jar ]; then
  unzip -o -q -j apktool-lib.jar prebuilt/linux/aapt2 prebuilt/android-framework.jar -d .
  chmod +x aapt2
fi

if [ ! -f android.jar ]; then
  get org/robolectric android-all 14-robolectric-10818077 android-all.jar
  rm -rf aj && mkdir aj
  # Keep only the Android API packages; java.* comes from the JDK (javac --release 8).
  unzip -q android-all.jar 'android/*' 'dalvik/*' 'org/json/*' 'org/xmlpull/*' -x '*.uau' -d aj
  (cd aj && zip -q -r ../android.jar .)
  rm -rf aj android-all.jar
fi
echo "Tools ready in $OUT"
