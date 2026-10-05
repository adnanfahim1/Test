#!/usr/bin/env bash
# Builds dist/codexa-landing.zip — install via Plugins → Add New → Upload Plugin.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf dist && mkdir -p dist
zip -r -X -q dist/codexa-landing.zip codexa-landing -x '*.DS_Store' -x '*/.git*'
echo "Built dist/codexa-landing.zip ($(du -h dist/codexa-landing.zip | cut -f1))"
