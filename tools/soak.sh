#!/usr/bin/env bash
# The soak rig. Thirty minutes of real wall clock against the dev gateway: 200 sends, 50
# reconnects, and a leak check after every teardown.
#
# It does not start or stop the gateway. Whoever owns the gateway owns its lifetime
# (tools/nanobot-dev.sh start / stop), so this script also works against a real host.
#
#   tools/soak.sh                    # 30 min, 200 sends, 50 reconnects
#   SOAK_MINUTES=1 tools/soak.sh     # a smoke run
set -euo pipefail

cd "$(dirname "$0")/.."

minutes="${SOAK_MINUTES:-30}"
sends="${SOAK_SENDS:-200}"
reconnects="${SOAK_RECONNECTS:-50}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="${SOAK_OUT:-.dev/soak-$stamp.log}"

mkdir -p .dev
echo "soak: $minutes min, $sends sends, $reconnects reconnects -> $out"

# `tee` keeps the log and the terminal identical; pipefail keeps the harness's exit code.
./gradlew -q :core-cli:run \
  --args="soak --sends $sends --reconnects $reconnects --minutes $minutes" 2>&1 \
  | grep -v '^SLF4J' \
  | tee "$out"

echo "soak log: $out"
