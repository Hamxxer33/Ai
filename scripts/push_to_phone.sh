#!/usr/bin/env bash
# Copies models and knowledge packs to the app's folder on a USB-connected phone.
#   usage: scripts/push_to_phone.sh <models dir> [<pack dir> ...]
# Only *.gguf files and complete pack directories (manifest.json + corpus.sqlite) are pushed.
set -euo pipefail
PKG=io.kestrel.research
DST=/sdcard/Android/data/$PKG/files
MODELS="${1:?usage: push_to_phone.sh <models dir> [<pack dir> ...]}"; shift || true
adb get-state >/dev/null
adb shell mkdir -p "$DST/models" "$DST/packs"
for f in "$MODELS"/*.gguf; do
  [ -e "$f" ] || continue
  echo "model: $(basename "$f")"
  adb push "$f" "$DST/models/"
done
for p in "$@"; do
  [ -f "$p/manifest.json" ] && [ -f "$p/corpus.sqlite" ] || { echo "skip $p (not a pack)"; continue; }
  name=$(basename "$p")
  echo "pack: $name"
  adb shell mkdir -p "$DST/packs/$name"
  for f in manifest.json corpus.sqlite vectors.kvec; do
    [ -f "$p/$f" ] && adb push "$p/$f" "$DST/packs/$name/"
  done
done
adb shell du -sh "$DST/models" "$DST/packs" || true
echo "Done. In the app: Library -> Rescan."
