#!/usr/bin/env bash
# The 20-minute scripted session G2 asks for (plan.md §7): connect, send, stream, attach,
# transcribe, stop, reconnect mid-turn, resume — against the live dev gateway, through the same
# :core the iOS app links.
#
# It is an instrument, not a test: it prints one plain line per beat so the log can be pasted
# into GATES.md, and it stops the gateway it started.
#
# usage: tools/session20.sh [--minutes 20] [--prompt TEXT]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/.dev"
LOG="$DEV/session-20min.log"
MINUTES=20
PROMPT="In one short sentence: what is a WebSocket?"

while [ $# -gt 0 ]; do
  case "$1" in
    --minutes) MINUTES="$2"; shift 2 ;;
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
./gradlew :core-cli:run --quiet --args="session --minutes $MINUTES --prompt \"$PROMPT\"" 2>&1 | tee "$LOG"
echo "log: $LOG"
