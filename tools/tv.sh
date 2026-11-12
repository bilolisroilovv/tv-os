#!/usr/bin/env bash
# Put the launcher on an Android / Google TV over ADB, and take it off again.
#
#   tools/tv.sh connect <ip>      connect over the network (the TV asks to allow this computer the first time)
#   tools/tv.sh install           install or update the app (tools/build.sh first)
#   tools/tv.sh home              make it the home screen (the current one is remembered for "restore")
#   tools/tv.sh restore           give the home screen back to the launcher that was there before
#   tools/tv.sh uninstall         restore, then remove the app
#   tools/tv.sh shot [file.png]   screenshot of the TV
#   tools/tv.sh info              model, Android version, current home screen
#
# The TV: TV=<ip>[:port] in the environment, or the only device ADB sees. Nothing here deletes or disables
# anything on the TV; the previous home screen stays installed.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="$(command -v adb || true)"
for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -z "$ADB" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"; done
[ -n "$ADB" ] || { echo "Нужен adb (Android SDK platform-tools или brew install android-platform-tools)." >&2; exit 1; }
PKG=app.tvos.home
HOME_ACT=$PKG/.MainActivity
SAVED=.tv-previous-home   # in this folder, not in git
target() { if [ -n "${TV:-}" ]; then case "$TV" in *:*) echo "$TV";; *) echo "$TV:5555";; esac; fi; }
adb_() { local t; t="$(target)"; if [ -n "$t" ]; then "$ADB" -s "$t" "$@"; else "$ADB" "$@"; fi; }
current_home() { adb_ shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'; }
# Remember the home screen in use before ours, once (a real activity, not Android's "choose an app" dialog).
remember_home() {
  local cur; cur="$(current_home)"
  case "$cur" in */*) ;; *) return 0;; esac
  case "$cur" in "$HOME_ACT"|*ResolverActivity*) return 0;; esac
  [ -f "$SAVED" ] || { echo "$cur" > "$SAVED"; echo "Прежний главный экран запомнен: $cur"; }
}

case "${1:-}" in
  connect)
    ip="${2:?укажите IP телевизора: tools/tv.sh connect 192.168.x.x}"; case "$ip" in *:*) ;; *) ip="$ip:5555";; esac
    "$ADB" connect "$ip"; echo "Если ТВ спросил «Разрешить отладку?» — нажмите «Разрешить» и повторите команду.";;
  install)
    apk=android/app/build/outputs/apk/release/app-release.apk
    [ -f "$apk" ] || { echo "Сначала соберите: tools/build.sh" >&2; exit 1; }
    remember_home
    adb_ install -r "$apk"
    # Only so that Settings can show the Wi-Fi network's name (Android hides it from apps otherwise).
    adb_ shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    # So that the app switcher (Home twice) lists the apps used lately, also those opened by the remote's buttons.
    adb_ shell appops set $PKG GET_USAGE_STATS allow 2>/dev/null || true
    # Compiled ahead of time: the first start and the first animations do not wait for the JIT.
    adb_ shell cmd package compile -m speed -f $PKG >/dev/null 2>&1 || true
    echo "Установлено. Сделать главным экраном: tools/tv.sh home";;
  home)
    remember_home
    adb_ shell cmd package set-home-activity "$HOME_ACT"
    adb_ shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    echo "Главный экран: $(current_home)";;
  restore)
    if [ -f "$SAVED" ]; then adb_ shell cmd package set-home-activity "$(cat "$SAVED")"; adb_ shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    else echo "Прежний главный экран не записан: Android спросит, какой использовать, при нажатии «Домой»." ; adb_ shell pm clear-package-preferred-activities $PKG 2>/dev/null || true; fi
    echo "Главный экран: $(current_home)";;
  uninstall)
    "$0" restore || true; adb_ uninstall $PKG;;
  shot)
    out="${2:-tv-shot.png}"; adb_ exec-out screencap -p > "$out"; echo "$out";;
  info)
    adb_ shell 'echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release)"; wm size | tail -n 1'
    echo "Главный экран: $(current_home)";;
  *)
    sed -n '2,13p' "$0"; exit 1;;
esac
