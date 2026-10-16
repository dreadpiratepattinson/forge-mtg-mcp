#!/usr/bin/env bash
# Copy the installed Forge jar and res/ to .tools/forge. Reads from /mnt/c are too slow to run Forge from there.
# Run again after each Forge update, then scripts/build.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${FORGE_INSTALL:-/mnt/c/Games/forge-installer-2.0.15}"
DST="$ROOT/.tools/forge"
rm -rf "$DST" && mkdir -p "$DST"
cp "$SRC"/forge-gui-desktop-*-jar-with-dependencies.jar "$SRC/build.txt" "$DST/"
cp -r "$SRC/res" "$DST/res"
echo "Synced Forge build $(cat "$DST/build.txt") from $SRC"
