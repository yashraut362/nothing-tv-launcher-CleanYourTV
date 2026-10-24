#!/bin/bash
# Undo everything this project did to the TV:
#   - re-enable every package in debloat-xiaomi-mi-tv-4a.txt (the stock launcher comes back as Home with it)
#   - put Private DNS back to the Android default
#   - put the screensaver back to Google's Backdrop (it was the default before)
#   - uninstall the custom launcher
# Needs ADB on (see README). Packages that were disabled before we started are left alone.
cd "$(dirname "$0")"
while read -r p; do
  case "$p" in ""|\#*) continue ;; esac
  # </dev/null: adb shell would otherwise eat the rest of this file from stdin.
  echo "$p: $(tools/tv.sh "pm enable --user 0 $p" </dev/null 2>&1)"
done < debloat-xiaomi-mi-tv-4a.txt
tools/tv.sh 'settings put global private_dns_mode opportunistic' </dev/null
tools/tv.sh 'settings put secure screensaver_components com.google.android.backdrop/.Backdrop' </dev/null
tools/tv.sh 'pm uninstall com.clean.tvhome' </dev/null
tools/tv.sh 'input keyevent KEYCODE_HOME' </dev/null
echo "Done. Home is the stock launcher again."
