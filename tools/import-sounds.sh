#!/bin/bash
# Put real sound files into the launcher.
#
#   tools/import-sounds.sh <folder>
#
# The folder holds audio files named after the sounds (any format macOS can read: wav, caf, aif, m4a, mp3):
#   tick select back bump key del pop open close launch zoom fling chime error
# (what each one is for: web/assets/sounds/README.md). Each file found is converted to 16-bit 48 kHz WAV into
# web/assets/sounds/, and web/assets/sounds/sounds.json is rewritten; the launcher then plays these files instead of
# its synthesised sounds. Names that have no file keep the synthesised sound. Uses only afconvert (built into macOS).
set -euo pipefail
src="${1:?Укажите папку со звуками: tools/import-sounds.sh <папка>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
dst="$here/web/assets/sounds"
mkdir -p "$dst"
json=""; n=0
for name in tick select back bump key del pop open close launch zoom fling chime error; do
  f="$(find "$src" -maxdepth 1 -type f -iname "$name.*" | head -1)"
  [ -z "$f" ] && continue
  afconvert -f WAVE -d LEI16@48000 "$f" "$dst/$name.wav"
  json="$json${json:+,}\"$name\":\"$name.wav\""; n=$((n + 1))
  echo "  $name  ←  $(basename "$f")"
done
printf '{"files":{%s}}\n' "$json" > "$dst/sounds.json"
echo "Готово: $n из 14. Обновите страницу лаунчера."
