#!/usr/bin/env bash
# Android's animation speed: 0.5 (twice as fast, the usual choice on slow TVs) or 1 (default).
#
#   TV=<ip> optimization/animations.sh 0.5
set -euo pipefail
ADB="$(command -v adb || true)"; for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"; done
t=""; if [ -n "${TV:-}" ]; then case "$TV" in *:*) t="$TV";; *) t="$TV:5555";; esac; fi
adb_() { if [ -n "$t" ]; then "$ADB" -s "$t" "$@"; else "$ADB" "$@"; fi; }
v="${1:-0.5}"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb_ shell settings put global $k "$v"; done
echo "Скорость анимаций: ${v}x"
