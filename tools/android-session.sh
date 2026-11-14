#!/usr/bin/env bash
# The Android half of G3's scripted session (plan.md §8): the same beats tools/session20.sh
# carries through :core-cli, driven by the app's own bridge and view models on the emulator
# against the live dev gateway.
#
# It is an instrument, not a test: it prints one "live android: beat ..." line per beat so the
# log can be pasted into GATES.md, and it stops the gateway it started.
#
# usage: tools/android-session.sh [--prompt TEXT]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/.dev"
LOG="$DEV/android-session.log"
PROMPT="In one short sentence: what is a WebSocket?"

while [ $# -gt 0 ]; do
  case "$1" in
    --prompt) PROMPT="$2"; shift 2 ;;
    *) echo "error: unknown argument $1" >&2; exit 2 ;;
  esac
done

started_gateway=0
if ! "$ROOT/tools/nanobot-dev.sh" status >/dev/null 2>&1; then
  "$ROOT/tools/nanobot-dev.sh" start
  started_gateway=1
fi

cleanup() {
  if [ "$started_gateway" = "1" ]; then
    "$ROOT/tools/nanobot-dev.sh" stop >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

mkdir -p "$DEV"
cd "$ROOT"
# One run, one block of beats: an old logcat would otherwise mix two sessions together.
adb logcat -c
# The emulator reaches the host's loopback through 10.0.2.2; the key travels as an instrumentation
# argument and is never echoed.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.arjmandi.nanobot.companion.LiveSessionTest \
  -Pandroid.testInstrumentationRunnerArguments.nanobot.live.url="http://10.0.2.2:18765" \
  -Pandroid.testInstrumentationRunnerArguments.nanobot.live.secret="$(cat "$DEV/secret")" \
  2>&1 | tee "$LOG" || true

# The beats are println output, which the instrumentation writes to logcat.
adb logcat -d -s System.out:* | grep -a "live android:" | sed 's/.*live android:/live android:/' | tee -a "$LOG"

echo "log: $LOG"
