#!/usr/bin/env sh
# Builds dist/cocovalt-landing.zip for Plugins → Add New → Upload Plugin.
set -eu
cd "$(dirname "$0")"
mkdir -p dist
rm -f dist/cocovalt-landing.zip
zip -rq -X dist/cocovalt-landing.zip cocovalt-landing -x '*.DS_Store'
echo "Built dist/cocovalt-landing.zip"
