#!/usr/bin/env bash
# Measure the launcher's smoothness on the TV: the same remote-control scenarios as the WebView version's bench,
# run inside the app (adb may send them; nothing else can), timed from the frames Android really drew.
#
#   TV=<ip> tools/bench.sh          -> per scenario: frames, fps while animating, frame times, dropped frames
#
# Run it twice after installing: the first run also pays for Android compiling the app's code.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$(command -v adb || true)"
for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"; done
[ -n "$ADB" ] || { echo "Нужен adb." >&2; exit 1; }
t="${TV:-}"; case "$t" in "") ;; *:*) ;; *) t="$t:5555";; esac
adb_() { if [ -n "$t" ]; then "$ADB" -s "$t" "$@"; else "$ADB" "$@"; fi; }
run() { adb_ shell am broadcast -a app.tvos.home.BENCH --es seq "'$2'" --ei gap "$3" --es name "$1" >/dev/null; sleep "$4"; }
adb_ shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 1
adb_ logcat -c
run focus    "H W600 R R R R L L L L" 330 4.5
run rows     "D W600 D W600 U W600 U" 200 4.5
run cc       "C W1200 B" 200 3
run settings "G W1300 D D D D D D E W900 B W500 B" 320 7
run switcher "T W1200 B" 200 3.5
adb_ logcat -d -s tvos-bench | grep -v '^-' | sed 's/.*tvos-bench: //'
adb_ shell dumpsys meminfo app.tvos.home | grep -E 'TOTAL PSS' | sed 's/^ *//'
