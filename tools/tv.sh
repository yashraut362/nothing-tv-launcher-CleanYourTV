#!/bin/bash
# Run a shell command on the TV:   TV_HOST=192.168.1.50 tools/tv.sh 'getprop ro.product.model'
# Press keys then screenshot:      TV_HOST=192.168.1.50 tools/tv.sh --screenshot shots/x.png DPAD_RIGHT DPAD_DOWN
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
if [ -z "$TV_HOST" ]; then echo "Set TV_HOST to the TV's IP address (Settings → Network on the TV)." >&2; exit 2; fi
TV="$TV_HOST:${TV_PORT:-5555}"
if [ "$1" = "--screenshot" ]; then
  out="$2"; shift 2
  for k in "$@"; do "$ADB" -s "$TV" shell input keyevent "KEYCODE_$k"; sleep 0.6; done
  sleep 0.8
  "$ADB" -s "$TV" exec-out screencap -p > "$out"
  if [ ! -s "$out" ]; then echo "empty screenshot (protected content: Live TV/HDMI/DRM app)"; exit 1; fi
  echo "$out"
else
  exec "$ADB" -s "$TV" shell "$@"
fi
