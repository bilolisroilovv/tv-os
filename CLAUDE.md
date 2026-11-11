# Installing TV OS on someone's TV (instructions for Claude Code)

The person wants this launcher on their Android TV / Google TV. Talk to them in their language (the project is
Russian-first). Work from this repository's folder. Everything that changes the TV needs their explicit OK first.

## Ground rules

- **Ask before every change on the TV**: installing, making it the home screen, disabling packages, changing
  animation speed. Say what will happen and how to undo it.
- **Never uninstall or clear data of any package** on the TV, and never factory-reset. Disabling
  (`pm disable-user`, `optimization/disable.sh`) is the most this project does, and only with consent.
- The previous home screen stays installed; `tools/tv.sh home` remembers it so `tools/tv.sh restore` can bring it back.
- Never commit or publish the person's local files: `.tv-previous-home`, `web/data/aerials.js`,
  `web/assets/wallpapers/*` (photos, Apple stills), `web/assets/sounds/*`, screenshots of their TV. `.gitignore`
  already covers them. Don't put their IP address, network name or device details into the repository.

## 1. Check the computer

Needed: `adb`, a JDK 17 and the Android SDK with platform 34 (Android Studio provides all three; on macOS also
`brew install --cask android-commandlinetools` + `brew install openjdk@17 android-platform-tools`, then
`sdkmanager "platforms;android-34" "build-tools;34.0.0"`). `tools/build.sh` finds them in the usual places, or
takes `JAVA_HOME` / `ANDROID_HOME`. Python 3 is needed for `tools/aerials.py`.

## 2. Connect to the TV

1. Ask for the TV's IP address (TV: *Settings → Network*). On the TV, developer options must be on
   (*Settings → System → About*: press *Android TV OS build* 7 times) with *USB debugging* enabled.
2. `tools/tv.sh connect <ip>` — the TV shows "Allow USB debugging?": the person presses *Allow* (tick "Always").
   Run it again if the first try says "unauthorized".
3. `TV=<ip> tools/tv.sh info` — note the model and Android version; minSdk is 24 (Android 7.0).

From here on, every command takes `TV=<ip>`.

## 3. Build

1. Optional but recommended: `python3 -I tools/aerials.py` downloads Apple's Aerial catalog (114 films with
   Russian captions) and 16 stills as wallpapers into the local, git-ignored folders (~10 MB). Without it the
   screen saver uses the gradient wallpapers.
2. Optional: the person's own photos as wallpapers — see `web/assets/wallpapers/README.md`.
3. `tools/build.sh` → `android/app/build/outputs/apk/release/app-release.apk` (signed with this computer's debug
   key; that is fine for sideloading).

## 4. Install (ask first)

1. `TV=<ip> tools/tv.sh install` — installs or updates the app and grants location access (only used to show the
   Wi-Fi name in Settings). `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a copy signed on another computer is
   installed: ask, then `adb uninstall app.tvos.home` and install again.
2. `TV=<ip> tools/tv.sh home` — makes it the home screen and starts it.
3. Check it: `TV=<ip> tools/tv.sh shot shot.png` and look at the picture (the first start takes a few seconds while
   it draws the app banners). Ask the person to try the remote: arrows, OK, Back, Home, Home twice (app switcher),
   Menu (Control Center), long OK on an icon (menu).
4. Show them where things are: *Settings → Apps* to hide/show apps (phone-style apps and other launchers start
   hidden), long OK → *Move* to reorder, *Settings → Screen saver*, *Settings → Video and Audio → Sound set*.

The launcher is a native Android app (`android/`, Java, plain Views); `web/` is only the browser prototype of the
design. Debugging: `adb logcat -s tvos tvos-slow` (errors, slow key handling); smoothness on the TV:
`TV=<ip> tools/bench.sh` (run it twice: the first run also pays for Android compiling the code); memory:
`adb shell dumpsys meminfo app.tvos.home`.

## 5. Make the TV faster (optional, ask for each step)

Read `optimization/README.md` with the person.
- `optimization/animations.sh 0.5` — Android animations twice as fast.
- `optimization/disable.sh optimization/lists/<list>.txt` — only after showing them the list and removing what they
  use. The lists are for TCL Google TV; for other brands, look at `adb shell pm list packages -e` together and only
  propose packages whose purpose is clear (ads, telemetry, recommendation rows). Each one can be undone with
  `optimization/restore.sh`.

## Undo

- `TV=<ip> tools/tv.sh restore` — previous home screen back.
- `TV=<ip> tools/tv.sh uninstall` — restore, then remove the launcher.
- `TV=<ip> optimization/restore.sh <list>` — packages back on; `optimization/animations.sh 1` — normal animations.

## Known issues

- Some brands' firmware sends the Home button to their own launcher no matter what the default is (TCL with an
  accessibility "home key" helper, some Xiaomi/ColorOS models with an allowlist). Then Home opens the old
  launcher: tell the person, offer to keep ours reachable as an app tile, and don't fight the firmware.
- No picture in the screen saver: no internet, or Apple's server is slow; it falls back to the wallpapers after 25 s.
- The Wi-Fi name shows only when location access is granted (`tools/tv.sh install` grants it) and location is on.
