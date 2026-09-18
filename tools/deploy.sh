#!/usr/bin/env bash
#
# Deploy to the car, and prove it worked.
#
#   tools/deploy.sh            install, verify, prime the offline cache
#   tools/deploy.sh --offline  the above, plus the WiFi-off test
#
# Run it with the car powered and on the garage WiFi.
#
# ## Why this exists
#
# Every step below has cost real time at least once, and most of them fail
# quietly. The ADB session is over WiFi and only exists while the car is on, so
# the window is short and the mistakes are expensive.
#
# ## What TUNER needs from this, and what it does not
#
# TUNER is a Trusted Web Activity: nothing of it is installed on the unit, so
# there is no APK to push and no version to match. What it does need is one
# launch WITH network after a deploy, to download the offline cache. That is the
# `prime` step. After that it starts with no signal.
set -uo pipefail

DEV="${DEV:-192.168.11.14:5555}"
ADB="${ADB:-/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe}"
APK="${APK:-app-launcher/app/build/outputs/apk/release/app-release.apk}"
SITE="${SITE:-https://mss54hp-csl-convert-tuner.tsunagi.app}"
OUTDIR="${OUTDIR:-/tmp/e46m3-deploy}"

# Git Bash rewrites anything that looks like a POSIX path into a Windows one
# before it reaches the device, which turns /sdcard/x into C:/.../sdcard/x and
# fails with a message that does not mention paths at all.
adb() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" "$@"; }

# Only needed if this script ends up building, but sourcing it costs nothing and
# keeps the two scripts agreeing on where Java is. Failure is not fatal here:
# deploying a previously built APK needs no JDK at all.
# shellcheck source=tools/jdk.sh
. "$(dirname "$0")/jdk.sh" 2>/dev/null || true

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m  %s\n' "$*"; }
bad() { printf '   \033[31mNG\033[0m  %s\n' "$*"; }

mkdir -p "$OUTDIR"

# ── 1. The site ──────────────────────────────────────────────────────────────
# Checked before touching the car, because if the site is wrong there is no
# point spending the car's uptime on it. Both of these have been served as 404
# by a deploy that reported success.
say "site"
for path in /.well-known/assetlinks.json /sw.js; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' "$SITE$path" || echo 000)
    [ "$code" = "200" ] && ok "$path $code" || bad "$path $code"
done

# ── 2. Connect ───────────────────────────────────────────────────────────────
say "connect $DEV"
for _ in $(seq 1 20); do
    "$ADB" connect "$DEV" >/dev/null 2>&1
    [ "$("$ADB" -s "$DEV" get-state 2>/dev/null | tr -d '\r')" = "device" ] && break
    sleep 2
done
[ "$("$ADB" -s "$DEV" get-state 2>/dev/null | tr -d '\r')" = "device" ] \
    || { bad "no device — is the car on?"; exit 1; }
ok "$(adb shell getprop ro.product.model | tr -d '\r')"

# The ring is 256Kb out of the box and rotates within a minute of boot, which is
# how the first attempt at reading MainUI's CAN init line was lost. Persistent,
# so it survives the next key cycle too.
adb shell "setprop persist.logd.size 4M; logcat -G 4M" >/dev/null 2>&1
ok "log ring $(adb shell 'logcat -g' 2>/dev/null | head -1 | tr -d '\r')"

# ── 3. Install ───────────────────────────────────────────────────────────────
say "launcher"
[ -f "$APK" ] || { bad "no APK at $APK — run gradlew assembleRelease"; exit 1; }
adb install -r -d "$APK" 2>&1 | tail -1

# `Success` above is not proof the unit still uses us as HOME, and
# `cmd package resolve-activity` answers from a stale cache. This is the check
# that is actually true. Piping dumpsys through grep ON the device gives Broken
# pipe, so it goes via a file.
adb shell "dumpsys package app.tsunagi.e46m3.launcher > /sdcard/p.txt 2>&1"
if adb shell "grep -A 4 'Preferred Activities' /sdcard/p.txt" | grep -q "tsunagi"; then
    ok "still HOME"
else
    bad "NOT HOME — set it back before driving:"
    echo "       adb -s $DEV shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity"
fi
adb shell "rm -f /sdcard/p.txt"

# The OTA subsystem can find and verify an update on its own, but applying one
# needs REQUEST_INSTALL_PACKAGES — a manifest permission backed by an app-op
# that starts un-granted. Without it the update pane says so and offers a route
# to Settings; with it, applying an update is one tap on the console.
#
# Granted here because this script is already the channel that has the device.
# Idempotent, and a failure is not fatal: the unit still updates itself right up
# to the point of installing.
if adb shell "appops set app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES allow" 2>/dev/null; then
    state=$(adb shell "appops get app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES" | tr -d '
')
    case "$state" in
        *allow*) ok "install permission: $state" ;;
        *)       bad "install permission not granted: ${state:-no answer}" ;;
    esac
else
    bad "could not set the install app-op — OTA will find updates but not apply them"
fi

adb shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null 2>&1
sleep 5
adb exec-out screencap -p > "$OUTDIR/home.png" && ok "home.png"

# ── 4. Prime TUNER's offline cache ───────────────────────────────────────────
# Coordinates are the console grid: M is column 5 row 2, TUNER is column 2 row 1
# of the M console. See res/values/design.xml.
say "prime TUNER (needs network — this is the download)"
adb shell input tap 920 419    # M
sleep 6
adb shell input tap 307 339    # TUNER
sleep 25                       # 5.7 MB over garage WiFi
focus=$(adb shell "dumpsys window | grep mCurrentFocus" | tr -d '\r')
echo "   $focus"
case "$focus" in
    *chrome*) ok "TUNER is up" ;;
    *)        bad "TUNER did not open" ;;
esac

# Closing and reopening is not tidiness, it is the second half of the install.
# There is no unconditional skipWaiting(), so when a worker is already present a
# newly downloaded one parks in `waiting` and the page keeps showing the old
# build. It activates when the last page controlled by the old worker goes away
# — which is exactly what force-stopping Chrome does. Without this the deploy
# is downloaded but not yet in use, and the screenshot below would show the
# previous version while everything reported success.
say "close and reopen, so the new worker takes over"
adb shell "am force-stop com.android.chrome"
sleep 2
adb shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null 2>&1
sleep 5
adb shell input tap 920 419    # M
sleep 6
adb shell input tap 307 339    # TUNER
sleep 15
adb exec-out screencap -p > "$OUTDIR/tuner.png" && ok "tuner.png"

# ── 5. Offline test ──────────────────────────────────────────────────────────
# The catch: this ADB session runs over the WiFi being switched off, so the test
# cannot be driven from here step by step. It is handed to the device as one
# detached script that outlives the disconnection, and the result is collected
# afterwards.
if [ "${1:-}" = "--offline" ]; then
    say "offline test (ADB will drop — expected)"
    adb shell "nohup sh -c '
        svc wifi disable
        sleep 5
        am force-stop com.android.chrome
        am start -a android.intent.action.MAIN -c android.intent.category.HOME
        sleep 4
        input tap 920 419
        sleep 6
        input tap 307 339
        sleep 20
        screencap -p /sdcard/offline.png
        svc wifi enable
    ' >/dev/null 2>&1 &" >/dev/null 2>&1
    echo "   running on the device, ~60s..."
    sleep 70
    for _ in $(seq 1 30); do
        "$ADB" connect "$DEV" >/dev/null 2>&1
        [ "$("$ADB" -s "$DEV" get-state 2>/dev/null | tr -d '\r')" = "device" ] && break
        sleep 3
    done
    if adb pull /sdcard/offline.png "$OUTDIR/offline.png" >/dev/null 2>&1; then
        adb shell "rm -f /sdcard/offline.png"
        ok "offline.png — TUNER rendering here means the cache works"
    else
        bad "could not collect offline.png"
    fi
fi

say "done — $OUTDIR"
ls -la "$OUTDIR"
