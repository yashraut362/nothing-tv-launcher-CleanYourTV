#!/bin/bash
# Build the launcher, install it on the TV and make it the default Home.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
(cd launcher && ./gradlew -q assembleDebug)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
if [ -z "$TV_HOST" ]; then echo "Set TV_HOST to the TV's IP address: TV_HOST=192.168.1.50 ./build-install.sh" >&2; exit 2; fi
TV="$TV_HOST:${TV_PORT:-5555}"
"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" push "$APK" /data/local/tmp/tvhome.apk >/dev/null
tools/tv.sh 'pm install -r /data/local/tmp/tvhome.apk'
tools/tv.sh 'rm /data/local/tmp/tvhome.apk'
tools/tv.sh 'cmd package set-home-activity com.clean.tvhome/.HomeActivity'
tools/tv.sh 'am force-stop com.clean.tvhome'
tools/tv.sh 'input keyevent KEYCODE_HOME'
echo "Installed. Focused app now: $(tools/tv.sh 'dumpsys activity activities' | grep -m1 mResumedActivity)"
