#!/bin/bash
set -euo pipefail

REPOSITORY="Noobhero1508/atcn-wifi"
case "$(uname -m)" in
  arm64) ARCH="arm64" ;;
  x86_64) ARCH="x64" ;;
  *) echo "Kien truc macOS chua duoc ho tro: $(uname -m)" >&2; exit 1 ;;
esac

ASSET="ATCN-Wifi-macOS-${ARCH}.dmg"
BASE_URL="https://github.com/${REPOSITORY}/releases/latest/download"
TEMP_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/atcn-wifi.XXXXXX")"
MOUNT_POINT=""

cleanup() {
  if [ -n "$MOUNT_POINT" ] && mount | grep -Fq "on $MOUNT_POINT "; then
    hdiutil detach "$MOUNT_POINT" -quiet || true
  fi
  rm -rf "$TEMP_DIRECTORY"
}
trap cleanup EXIT

curl -fL --retry 3 "$BASE_URL/$ASSET" -o "$TEMP_DIRECTORY/$ASSET"
curl -fL --retry 3 "$BASE_URL/$ASSET.sha256" -o "$TEMP_DIRECTORY/$ASSET.sha256"
(
  cd "$TEMP_DIRECTORY"
  shasum -a 256 -c "$ASSET.sha256"
)

mkdir -p "$TEMP_DIRECTORY/mount" "$HOME/Applications"
MOUNT_POINT="$TEMP_DIRECTORY/mount"
hdiutil attach "$TEMP_DIRECTORY/$ASSET" -nobrowse -readonly -mountpoint "$MOUNT_POINT" -quiet

SOURCE_APP="$MOUNT_POINT/ATCN Wifi.app"
TARGET_APP="$HOME/Applications/ATCN Wifi.app"
BACKUP_APP="$TEMP_DIRECTORY/ATCN Wifi.previous.app"

if [ -e "$TARGET_APP" ]; then
  mv "$TARGET_APP" "$BACKUP_APP"
fi
if ! ditto "$SOURCE_APP" "$TARGET_APP"; then
  rm -rf "$TARGET_APP"
  if [ -e "$BACKUP_APP" ]; then mv "$BACKUP_APP" "$TARGET_APP"; fi
  echo "Cai dat that bai; da khoi phuc phien ban cu." >&2
  exit 1
fi

echo "[OK] Da cai ATCN Wifi vao: $TARGET_APP"
echo "Mo Launchpad hoac thu muc ~/Applications de chay ung dung."
echo "Ban chua ky so co the can bam chuot phai > Open trong lan chay dau."

