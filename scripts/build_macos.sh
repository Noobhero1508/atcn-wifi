#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
ARCH_SLUG="${1:-$(uname -m)}"

python3 scripts/make_icons.py
python3 -m PyInstaller \
  --noconfirm \
  --clean \
  --windowed \
  --name "ATCN Wifi" \
  --icon "assets/atcn-wifi.icns" \
  --add-data "assets/icon-preview.png:assets" \
  --osx-bundle-identifier "vn.edu.vnu.atcnwifi" \
  wifi_app.py

mkdir -p release dmg-staging
rm -rf dmg-staging/*
ditto "dist/ATCN Wifi.app" "dmg-staging/ATCN Wifi.app"
ln -s /Applications "dmg-staging/Applications"

DMG="release/ATCN-Wifi-macOS-${ARCH_SLUG}.dmg"
hdiutil create -quiet -volname "ATCN Wifi" -srcfolder dmg-staging -ov -format UDZO "$DMG"
(
  cd release
  shasum -a 256 "ATCN-Wifi-macOS-${ARCH_SLUG}.dmg" > "ATCN-Wifi-macOS-${ARCH_SLUG}.dmg.sha256"
)
