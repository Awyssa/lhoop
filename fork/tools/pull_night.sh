#!/usr/bin/env bash
# Export a backup from the app on the phone and pull it into whoop-data/lhoop-backups/ (private, git-ignored).
#
#   fork/tools/pull_night.sh            # saves night-<today>.lhoopbak and unpacks it beside itself
#
# The phone must be plugged in, UNLOCKED and awake: it locks after 60 seconds and adb cannot unlock it.
# This taps through the app's own Strap tab and Android's file picker, finding each button by its label.
# The exported file stays in the phone's Downloads folder; it is health data.
#
# Set APP_PACKAGE to pull from a build installed under another package name than this code's.
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UI="$ROOT/fork/tools/phone_ui.py"
PKG="${APP_PACKAGE:-com.lhoop.whoop.staging}"
export APP_PACKAGE="$PKG"
OUT="$ROOT/whoop-data/lhoop-backups"
DAY="$(date +%Y-%m-%d)"

if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL="$("$ADB" devices | sed -n '2,$p' | grep -v emulator | awk '$2=="device"{print $1}' | head -1)"
  export ANDROID_SERIAL
fi
[ -n "$ANDROID_SERIAL" ] || { echo "No phone attached." >&2; exit 1; }

"$ADB" shell input keyevent KEYCODE_WAKEUP
if "$ADB" shell dumpsys trust | grep -q 'deviceLocked=1'; then
  echo "The phone is locked. Unlock it, keep it awake, and run this again." >&2
  exit 1
fi

tap() {  # tap the first element whose label matches $1; $2 = "bottom" picks one in the navigation bar
  local xy
  if [ "${2:-}" = bottom ]; then xy="$(python3 "$UI" "$1" | awk '$2 > 2000 {print $1, $2}' | head -1)"
  else xy="$(python3 "$UI" "$1" | head -1 | awk '{print $1, $2}')"; fi
  [ -n "$xy" ] || { echo "Could not find '$1' on screen." >&2; exit 1; }
  # shellcheck disable=SC2086
  "$ADB" shell input tap $xy
}

"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
tap "Strap" bottom; sleep 2
"$ADB" shell input swipe 540 1800 540 600 200; sleep 1
tap "Export backup"; sleep 3
tap "SAVE"

result=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 3
  result="$(python3 "$UI" | grep -iE 'Backup exported|Backup problem' | head -1 || true)"
  [ -n "$result" ] && break
  "$ADB" shell input keyevent KEYCODE_WAKEUP
done
case "$result" in *"Backup exported"*) ;; *) echo "The export did not report success: ${result:-no message}" >&2; exit 1;; esac

file="$("$ADB" shell ls -t /sdcard/Download/ | tr -d '\r' | grep -- "-backup-$DAY" | head -1)"
[ -n "$file" ] || { echo "No backup for $DAY found in the phone's Downloads." >&2; exit 1; }
mkdir -p "$OUT"
"$ADB" pull "/sdcard/Download/$file" "$OUT/night-$DAY.lhoopbak" >/dev/null
rm -rf "$OUT/night-$DAY" && mkdir "$OUT/night-$DAY"
unzip -q -o "$OUT/night-$DAY.lhoopbak" -d "$OUT/night-$DAY"
echo "Saved $OUT/night-$DAY.lhoopbak ($(du -h "$OUT/night-$DAY.lhoopbak" | cut -f1)). Next: fork/tools/night_report.py $OUT/night-$DAY"
