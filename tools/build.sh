#!/usr/bin/env bash
# Build the Android app with the launcher inside (web/ is packed as its assets).
#
#   tools/build.sh            -> android/app/build/outputs/apk/release/app-release.apk
#
# Needs a JDK 17 and the Android SDK (platform 34). Finds them in the usual places, or set JAVA_HOME / ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f web/data/aerials.js ] || echo "Подсказка: python3 -I tools/aerials.py скачает видео и фоны Apple для заставки (по желанию)." >&2

if [ -z "${JAVA_HOME:-}" ]; then
  for j in "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    if [ -n "$j" ] && [ -x "$j/bin/java" ] && "$j/bin/java" -version 2>&1 | grep -q 'version "17'; then JAVA_HOME="$j"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "Нужен JDK 17 (например: brew install openjdk@17), или укажите JAVA_HOME." >&2; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in ~/Library/Android/sdk ~/Android/Sdk /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
    [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "Нужен Android SDK (Android Studio или brew install --cask android-commandlinetools), или укажите ANDROID_HOME." >&2; exit 1; }
export ANDROID_HOME
[ -f android/local.properties ] || echo "sdk.dir=$ANDROID_HOME" > android/local.properties

(cd android && ./gradlew assembleRelease -q --console=plain)
apk=android/app/build/outputs/apk/release/app-release.apk
echo "Готово: $apk ($(du -h "$apk" | cut -f1))"
