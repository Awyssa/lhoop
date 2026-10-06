#!/usr/bin/env bash
# Capture the page Garmin Connect is showing on the phone into whoop-data/garmin/<today>/ (private, git-ignored).
#
#   fork/tools/capture_garmin.sh            # saves sleep-1.png, sleep-1.txt, sleep-2.png, ... and prints the text
#   fork/tools/capture_garmin.sh hrv        # the same under another name, for another page
#
# The owner opens the page first. For a night: Garmin Connect -> More -> Health Stats -> Sleep, on the night
# wanted. The home screen only gives resting heart rate.
#
# The phone must be plugged in, UNLOCKED and awake: it locks after 60 seconds and adb cannot unlock it.
# Nothing is captured unless Garmin Connect is in front, so no other app's screen is ever read. The page is
# scrolled down a screen at a time until it stops changing. If the page has a Timeline / Stages switch (the
# sleep page does), the Stages view is captured too as <name>-stages, and the switch is put back.
#
# On the sleep page the text holds the score, time asleep, stage totals, heart rate, HRV and breathing.
# When the sleep started and ended, and when the wearer was awake, are only in the timeline picture.
# What it saves and prints is health data.
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UI="$ROOT/fork/tools/phone_ui.py"
GARMIN=com.garmin.android.apps.connectmobile
NAME="${1:-sleep}"
OUT="$ROOT/whoop-data/garmin/$(date +%Y-%m-%d)"
MAX_SCREENS=12

if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL="$("$ADB" devices | sed -n '2,$p' | grep -v emulator | awk '$2=="device"{print $1}' | head -1)"
  export ANDROID_SERIAL
fi
[ -n "$ANDROID_SERIAL" ] || { echo "No phone attached." >&2; exit 1; }

in_front() {  # prints nothing about any other app: only whether Garmin Connect is the one in front
  "$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -q "$GARMIN"
}

if "$ADB" shell dumpsys trust | grep -q 'deviceLocked=1'; then
  echo "The phone is locked. Unlock it, open the page in Garmin Connect, and run this again." >&2
  exit 1
fi
in_front || { echo "Garmin Connect is not in front. Open the page in it and run this again." >&2; exit 1; }

mkdir -p "$OUT"
read -r width height <<<"$("$ADB" shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
previous=""
for n in $(seq 1 "$MAX_SCREENS"); do
  in_front || { echo "Garmin Connect is no longer in front; stopped after $((n - 1)) screen(s)." >&2; break; }
  text="$(python3 "$UI" --app garmin)"
  if [ "$text" = "$previous" ]; then break; fi
  "$ADB" exec-out screencap -p > "$OUT/$NAME-$n.png"
  printf '%s\n' "$text" > "$OUT/$NAME-$n.txt"
  echo "=== $NAME-$n"
  printf '%s\n' "$text"
  previous="$text"
  "$ADB" shell input swipe $((width / 2)) $((height * 70 / 100)) $((width / 2)) $((height * 30 / 100)) 400
  sleep 1
done

to_top() {
  for _ in 1 2 3; do
    "$ADB" shell input swipe $((width / 2)) $((height * 30 / 100)) $((width / 2)) $((height * 80 / 100)) 300
    sleep 1
  done
}
tap_label() {  # tap the element of Garmin Connect labelled exactly $1; fails when there is none on screen
  local xy
  xy="$(python3 "$UI" --app garmin "$1" | head -1 | awk '{print $1, $2}')"
  [ -n "$xy" ] || return 1
  # shellcheck disable=SC2086
  "$ADB" shell input tap $xy
}

to_top
if in_front && tap_label "Stages"; then
  sleep 2
  "$ADB" shell input swipe $((width / 2)) $((height * 70 / 100)) $((width / 2)) $((height * 35 / 100)) 400
  sleep 1
  if in_front; then
    "$ADB" exec-out screencap -p > "$OUT/$NAME-stages.png"
    python3 "$UI" --app garmin > "$OUT/$NAME-stages.txt"
    echo "=== $NAME-stages"
    cat "$OUT/$NAME-stages.txt"
  fi
  to_top
  in_front && tap_label "Timeline" || true
fi
echo "Saved to $OUT"
