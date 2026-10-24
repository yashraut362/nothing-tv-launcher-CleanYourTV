#!/bin/bash
# Disable packages one at a time (pm disable-user --user 0), log them for restore.sh.
# Usage: tools/disable.sh pkg1 pkg2 ...
cd "$(dirname "$0")/.."
PROTECTED='^(android|com\.android\.(systemui|shell|phone|settings|tv\.settings|tv|vending|bluetooth|inputdevices|permissioncontroller|packageinstaller|extservices|externalstorage|defcontainer|keychain|se)|com\.(android|google\.android)\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|com\.google\.android\.(gms|gsf|inputmethod\.latin|tv\.remote\.service|webview)|com\.droidlogic.*|mitv\.service|com\.android\.providers\.(settings|media|downloads|tv|telephony))$'
for p in "$@"; do
  if echo "$p" | grep -Eq "$PROTECTED"; then echo "REFUSED (core/load-bearing): $p"; exit 2; fi
done
for p in "$@"; do
  r=$(tools/tv.sh "pm disable-user --user 0 $p" 2>&1)
  echo "$p -> $r"
  if tools/tv.sh 'pm list packages -d' | grep -qx "package:$p"; then
    grep -qx "$p" debloat-xiaomi-mi-tv-4a.txt 2>/dev/null || echo "$p" >> debloat-xiaomi-mi-tv-4a.txt
  else echo "  !! not in disabled list"; fi
done
