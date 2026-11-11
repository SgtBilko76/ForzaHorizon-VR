#!/bin/sh
# Builds the built-in immersive camera mod (its head layers drive the VR
# head-tracked view) for the base game and installs it on the Quest, then
# enables it in the device's settings. Run with the game closed.
#   quest/push-vr-mods.sh [adb serial]
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL=${1:-192.168.178.115:5555}
STATE=${TMPDIR:-/tmp}/fhvr-modstate
DEV=/sdcard/Android/data/studio.deimos.pinyonshift/files/state
EXTRACT="$ROOT/out/build/linux-amd64-release/pinyon_shift_fh1_archive_extract"

rm -rf "$STATE"; mkdir -p "$STATE"
cd "$ROOT"
# 1: installs the built-in mods and enables immersive_camera; 2: merges its
# CameraPhysics.xml into camera.zip (zz-archive-patches).
python3 tools/build-mod-patches.py --game-root .local/game/base "$STATE" >/dev/null
python3 tools/build-mod-archives.py --game-root .local/game/base \
    --archive-extractor "$EXTRACT" "$STATE" >/dev/null

cd "$STATE/mods"
adb -s "$SERIAL" shell "rm -rf $DEV/mods/immersive_camera $DEV/mods/zz-archive-patches"
dirs=$(find immersive_camera zz-archive-patches -type d | sed "s|^|$DEV/mods/|" | tr '\n' ' ')
adb -s "$SERIAL" shell "mkdir -p $dirs"
for f in $(find immersive_camera zz-archive-patches -type f); do
  adb -s "$SERIAL" push "$f" "$DEV/mods/$f" </dev/null >/dev/null
done
# adb's files are the shell user's: the app must be able to read them, or
# the game treats the mods as unknown and plays a separate modded profile.
adb -s "$SERIAL" shell "find $DEV/mods -type d -exec chmod 777 {} +; find $DEV/mods -type f -exec chmod 666 {} +"

# The settings: the two mods on, the mod's own motion effects off.
TOML=$STATE/device.toml
adb -s "$SERIAL" pull "$DEV/config/pinyon_shift.toml" "$TOML" >/dev/null
grep -vE '^(enabled_mods|pinyon_shift_camera_(gforce|lean|shake|head)) ' "$TOML" > "$TOML.new" || true
cat >> "$TOML.new" <<'EOF'
enabled_mods = "zz-archive-patches,immersive_camera"
pinyon_shift_camera_gforce = 0.0
pinyon_shift_camera_lean = 0.0
pinyon_shift_camera_shake = 0.0
pinyon_shift_camera_head = 0.0
EOF
adb -s "$SERIAL" push "$TOML.new" "$DEV/config/pinyon_shift.toml" >/dev/null
adb -s "$SERIAL" shell "chmod 666 $DEV/config/pinyon_shift.toml"
echo "VR mods installed: $(adb -s "$SERIAL" shell "find $DEV/mods -type f | wc -l") files"
