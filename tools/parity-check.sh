#!/usr/bin/env bash
# iOS and Android side by side on one gateway: same transcript, same error copy, compared
# mechanically instead of by looking at two screens.
#
# Three comparisons, all mechanical:
#
#   1. the transcript, CLI reference. One chat is created on the dev gateway and a turn settled
#      through :core-cli; `core-cli send` prints that thread as the CLI's plain transcript.
#   2. the transcript, the two apps. The Android live test and the iOS `ParityTranscriptTests`
#      each open the *same* chat through their own bridge and print the thread they hold in the
#      same plain fields; `parity_transcript.py` diffs each against the CLI. Both app sides are
#      :core: the socket's reduction in the CLI, the gateway's page through `HistoryReplay` in
#      the apps. A difference is therefore an app-path bug and nothing else.
#   3. the error copy. The eight sentences in iOS's ConnectionCopy.swift and the eight strings
#      in app/src/main/res/values/strings.xml are parsed and compared, key for key.
#
# usage: tools/parity-check.sh [--prompt TEXT] [--ios-destination 'platform=iOS Simulator,name=iPhone 17']
#                              [--skip-ios] [--skip-android]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/.dev"
LOG="$DEV/parity.log"
PROMPT="In one short sentence: what is a WebSocket?"
IOS_DESTINATION="platform=iOS Simulator,name=iPhone 17"
RUN_IOS=1
RUN_ANDROID=1

while [ $# -gt 0 ]; do
  case "$1" in
    --prompt) PROMPT="$2"; shift 2 ;;
    --ios-destination) IOS_DESTINATION="$2"; shift 2 ;;
    --skip-ios) RUN_IOS=0; shift ;;
    --skip-android) RUN_ANDROID=0; shift ;;
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

failures=0

echo "parity: copy"
python3 "$ROOT/tools/parity_copy.py" | tee -a "$LOG" || failures=$((failures + 1))

echo "parity: transcript (CLI is the reference)"
# One chat, one turn, through :core-cli; the transcript it prints is the reference both apps are
# diffed against. The apps then open the same stored chat, so the two sides are the socket's
# reduction and the page replay of the same turns.
CLI_OUT="$DEV/parity-cli.txt"
./gradlew :core-cli:run --quiet --args="send --prompt \"$PROMPT\"" 2>&1 | tee "$CLI_OUT" | tee -a "$LOG"
CHAT_ID="$(grep -a '^# chat ' "$CLI_OUT" | head -1 | awk '{print $3}')"
if [ -z "$CHAT_ID" ]; then
  echo "parity: FAIL — the CLI printed no chat id" | tee -a "$LOG"
  exit 1
fi
echo "parity: chat $CHAT_ID" | tee -a "$LOG"

if [ "$RUN_ANDROID" = "1" ]; then
  # The Android half: the app's own bridge opens the same chat and prints what its thread holds.
  # The beats are println output, which the instrumentation writes to logcat.
  echo "parity: transcript (android)"
  adb logcat -c
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=de.arjmandi.nanobot.companion.LiveSessionTest \
    -Pandroid.testInstrumentationRunnerArguments.nanobot.live.url="http://10.0.2.2:18765" \
    -Pandroid.testInstrumentationRunnerArguments.nanobot.live.secret="$(cat "$DEV/secret")" \
    -Pandroid.testInstrumentationRunnerArguments.nanobot.live.chat="$CHAT_ID" \
    2>&1 | tail -3 | tee -a "$LOG" || true
  adb logcat -d -s System.out:* | grep -a "parity turn" | sed 's/.*parity turn/parity turn/' \
    | sed 's/^parity turn/parity android turn/' > "$DEV/parity-android.txt"
  python3 "$ROOT/tools/parity_transcript.py" "$CLI_OUT" android "$DEV/parity-android.txt" | tee -a "$LOG" \
    || failures=$((failures + 1))
fi

if [ "$RUN_IOS" = "1" ]; then
  # The iOS half: the simulator runs `ParityTranscriptTests` in the app's own test target, which
  # opens the same chat through `CoreHolder`/`CoreChat` and prints the same fields. The gateway
  # is the host's loopback, which the simulator shares; the key travels as TEST_RUNNER_ env and
  # is never echoed.
  echo "parity: transcript (ios)"
  IOS_LOG="$DEV/parity-ios-xcodebuild.log"
  # A file from an earlier run must not stand in for a run that never wrote one.
  rm -f "$DEV/parity-ios.txt" "$DEV/parity-ios-log.txt"
  if ( cd "$ROOT/iosApp" && \
       TEST_RUNNER_NANOBOT_LIVE_URL="http://127.0.0.1:18765" \
       TEST_RUNNER_NANOBOT_LIVE_SECRET="$(cat "$DEV/secret")" \
       TEST_RUNNER_NANOBOT_LIVE_CHAT="$CHAT_ID" \
       TEST_RUNNER_NANOBOT_PARITY_OUT="$DEV/parity-ios.txt" \
       xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination "$IOS_DESTINATION" \
         test -only-testing:iosAppTests/ParityTranscriptTests ) > "$IOS_LOG" 2>&1; then
    grep -E "Test run with|TEST SUCCEEDED" "$IOS_LOG" | tail -2 | tee -a "$LOG"
  else
    echo "parity: FAIL — the iOS half did not run (log: $IOS_LOG)" | tee -a "$LOG"
    failures=$((failures + 1))
  fi
  # The test writes its lines to NANOBOT_PARITY_OUT; the log is the fallback, because an
  # xcodebuild log is not a diffable artifact but it does carry the same prints.
  grep -a "parity ios turn" "$IOS_LOG" | sed 's/.*parity ios turn/parity ios turn/' > "$DEV/parity-ios-log.txt" || true
  if [ ! -s "$DEV/parity-ios.txt" ]; then
    cp "$DEV/parity-ios-log.txt" "$DEV/parity-ios.txt"
  fi
  python3 "$ROOT/tools/parity_transcript.py" "$CLI_OUT" ios "$DEV/parity-ios.txt" | tee -a "$LOG" \
    || failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "parity: FAIL ($failures instrument(s))" | tee -a "$LOG"
  exit 1
fi
echo "parity: PASS (copy + every transcript)" | tee -a "$LOG"
echo "log: $LOG"
