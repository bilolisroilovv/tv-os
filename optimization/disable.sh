#!/usr/bin/env bash
# Disable the packages of a list (pm disable-user: nothing is deleted, restore.sh brings them back).
#
#   TV=<ip> optimization/disable.sh optimization/lists/tcl-ads.txt [package ...]
#
# Only packages that are installed and enabled are touched; each one is printed. Ask the TV's owner first.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$(command -v adb || true)"; for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"; done
t=""; if [ -n "${TV:-}" ]; then case "$TV" in *:*) t="$TV";; *) t="$TV:5555";; esac; fi
adb_() { if [ -n "$t" ]; then "$ADB" -s "$t" "$@"; else "$ADB" "$@"; fi; }
list="${1:?укажите список: optimization/lists/<имя>.txt}"; shift || true
pkgs="$(sed 's/#.*//' "$list" | awk 'NF{print $1}') $*"
enabled="$(adb_ shell pm list packages -e | tr -d '\r' | sed 's/^package://')"
for p in $pkgs; do
  if echo "$enabled" | grep -qx "$p"; then adb_ shell pm disable-user --user 0 "$p" >/dev/null && echo "выключено: $p"; else echo "пропуск (нет или уже выключено): $p"; fi
done
