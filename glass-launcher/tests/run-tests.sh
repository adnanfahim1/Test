#!/usr/bin/env bash
# Runs the launcher on Android 5.0-15 (Robolectric) and renders screenshots at Nakamichi
# screen sizes into tests/shots/. Build first with ../build.sh. Needs Maven and JDK 17+.
# The androidx.test classes under src/test/java/androidx are tiny test-only stand-ins
# (the real ones live on Google's Maven, which isn't always reachable); they never ship in the APK.
set -euo pipefail
cd "$(dirname "$0")"
(cd ../build/classes && rm -f ../app-classes.jar && zip -q -r ../app-classes.jar .)
mvn -q ${MVN_ARGS:-} -Dshots="$PWD/shots" test "$@"
echo "All tests passed. Screenshots in tests/shots/"
