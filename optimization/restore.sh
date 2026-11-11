#!/usr/bin/env bash
# Turn packages back on (the opposite of disable.sh).
#
#   TV=<ip> optimization/restore.sh optimization/lists/tcl-ads.txt [package ...]
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$(command -v adb || true)"; for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"; done
t=""; if [ -n "${TV:-}" ]; then case "$TV" in *:*) t="$TV";; *) t="$TV:5555";; esac; fi
adb_() { if [ -n "$t" ]; then "$ADB" -s "$t" "$@"; else "$ADB" "$@"; fi; }
list="${1:?укажите список: optimization/lists/<имя>.txt}"; shift || true
for p in $(sed 's/#.*//' "$list" | awk 'NF{print $1}') "$@"; do adb_ shell pm enable "$p" >/dev/null 2>&1 && echo "включено: $p" || echo "нет на ТВ: $p"; done
