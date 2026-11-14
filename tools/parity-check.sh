#!/usr/bin/env bash
# G3's third item (plan.md §8): iOS and Android side by side on one gateway, same transcript,
# same error copy — compared mechanically rather than by looking at two emulators.
#
# Two halves, both instruments rather than eyeballs:
#
#   1. the transcript. One chat is created on the dev gateway and a turn settled through
#      :core-cli; then `core-cli watch --chat` prints that stored thread as the CLI's plain
#      transcript, and the Android live test opens the *same* chat through the app's bridge and
#      prints the same thread as the app holds it. Both sides come from :core's HistoryReplay,
#      so a difference is an app-path bug and nothing else.
#   2. the error copy. The eight sentences in iOS's ConnectionCopy.swift and the eight strings
#      in app/src/main/res/values/strings.xml are parsed and compared, key for key.
#
# usage: tools/parity-check.sh [--prompt TEXT]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/.dev"
LOG="$DEV/parity.log"
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
: > "$LOG"

echo "parity: copy"
python3 "$ROOT/tools/parity_copy.py" | tee -a "$LOG"

echo "parity: transcript"
# One chat, one turn, through :core-cli; the live transcript it prints is the reference. The
# Android half then reads the same stored chat back through the app's bridge, so the two sides are
# the socket's reduction and the page replay of the same chat.
CLI_OUT="$DEV/parity-cli.txt"
./gradlew :core-cli:run --quiet --args="send --prompt \"$PROMPT\"" 2>&1 | tee "$CLI_OUT" | tee -a "$LOG"
CHAT_ID="$(grep -a '^# chat ' "$CLI_OUT" | head -1 | awk '{print $3}')"
if [ -z "$CHAT_ID" ]; then
  echo "parity: FAIL — the CLI printed no chat id" | tee -a "$LOG"
  exit 1
fi
echo "parity: chat $CHAT_ID" | tee -a "$LOG"

# The Android half: the app's own bridge opens the same chat and prints what its thread holds.
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.arjmandi.nanobot.companion.LiveSessionTest \
  -Pandroid.testInstrumentationRunnerArguments.nanobot.live.url="http://10.0.2.2:18765" \
  -Pandroid.testInstrumentationRunnerArguments.nanobot.live.secret="$(cat "$DEV/secret")" \
  -Pandroid.testInstrumentationRunnerArguments.nanobot.live.chat="$CHAT_ID" \
  2>&1 | tail -3 | tee -a "$LOG" || true
adb logcat -d -s System.out:* | grep -a "parity turn" | sed 's/.*parity turn/parity turn/' > "$DEV/parity-android.txt"

python3 "$ROOT/tools/parity_transcript.py" "$CLI_OUT" "$DEV/parity-android.txt" | tee -a "$LOG"

echo "log: $LOG"
