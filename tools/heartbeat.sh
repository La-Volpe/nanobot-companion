#!/usr/bin/env bash
# One heartbeat tick, as text: five lines, read-only, no arguments.
#
# The tick used to be a list of commands that a small model reassembled each time, and it
# mangled them: a retyped uuid path, an `ls` pipeline that failed silently, a `cd` that did not
# survive into the next call. This script exists so the tick has ONE thing to run and nothing to
# assemble. It prints; it never writes, builds, tests or dispatches.
set -uo pipefail

ROOT="$HOME/Dev/v2-nanobot-companion"
cd "$ROOT" 2>/dev/null || { echo "repo: unreachable at $ROOT"; exit 0; }

dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
head=$(git log --oneline -1 2>/dev/null | cut -c1-60)
# The queue line moved into a status sentence when tasks.md was rewritten for
# reading; read both shapes so a tidy-up cannot silently turn this into "?".
queue=$(sed -n 's/^\*\*Status: \(.*\)\*\*.*/\1/p' tasks.md 2>/dev/null | head -1)
[ -n "$queue" ] || queue=$(sed -n 's/^Done: //p' tasks.md 2>/dev/null | head -1)
gateway=$(tools/nanobot-dev.sh status >/dev/null 2>&1 && echo up || echo down)
run=$(stat -L -f '%z B, mtime %Sm' -t '%H:%M' .pi/current-run.output 2>/dev/null || echo none)
next=$(grep -m1 '^Next:' HEARTBEAT.md 2>/dev/null | sed 's/^Next: //')

echo "repo: ${dirty:-?} dirty, HEAD ${head:-unknown}"
echo "queue: ${queue:-?}"
echo "gateway: ${gateway}"
echo "run: ${run:-none}"
echo "next: ${next:-no Next: line in HEARTBEAT.md}"
