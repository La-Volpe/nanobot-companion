#!/usr/bin/env bash
# Starts a throwaway nanobot gateway for contract tests (plan.md P0.5, C8.3).
#
# It writes everything under .dev/ and never touches ~/.nanobot:
#   .dev/config.json    gateway config (websocket channel + ollama provider)
#   .dev/workspace/     the agent's workspace
#   .dev/secret         the bootstrap secret (X-Nanobot-Auth)
#   .dev/profile.json   {url, secret} — what :core and :core-cli consume
#
# usage:
#   tools/nanobot-dev.sh start [--port 18765] [--model <ollama-model>]
#   tools/nanobot-dev.sh stop
#   tools/nanobot-dev.sh status
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/.dev"
NANOBOT_SRC="${NANOBOT_SRC:-$HOME/Dev/nanobot}"
PORT="${PORT:-18765}"
MODEL="${MODEL:-deepseek-v4.1-flash:cloud}"

die() { echo "error: $*" >&2; exit 1; }

# nanobot's runtime deps are not installed system-wide; keep them in .dev/venv.
ensure_venv() {
  if [ ! -x "$DEV/venv/bin/nanobot" ]; then
    echo "creating .dev/venv and installing nanobot from $NANOBOT_SRC"
    uv venv "$DEV/venv" --python 3.12 >/dev/null
    uv pip install --python "$DEV/venv/bin/python" -e "$NANOBOT_SRC" >/dev/null
  fi
}

start() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --port) PORT="$2"; shift 2 ;;
      --model) MODEL="$2"; shift 2 ;;
      *) die "unknown option: $1" ;;
    esac
  done

  mkdir -p "$DEV/workspace"
  [ -f "$DEV/secret" ] || head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$DEV/secret"
  local secret; secret="$(cat "$DEV/secret")"

  cat > "$DEV/config.json" <<JSON
{
  "providers": { "ollama": { "apiBase": "http://127.0.0.1:11434/v1" } },
  "modelPresets": {
    "primary": {
      "provider": "ollama",
      "model": "$MODEL",
      "maxTokens": 4096,
      "contextWindowTokens": 32768
    }
  },
  "agents": { "defaults": { "modelPreset": "primary" } },
  "channels": {
    "websocket": {
      "enabled": true,
      "host": "127.0.0.1",
      "port": $PORT,
      "tokenIssueSecret": "$secret",
      "websocketRequiresToken": true
    }
  }
}
JSON

  cat > "$DEV/profile.json" <<JSON
{ "url": "http://127.0.0.1:$PORT", "secret": "$secret" }
JSON

  if status >/dev/null 2>&1; then
    echo "already running on http://127.0.0.1:$PORT"
    return 0
  fi

  if curl -sf -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/webui/bootstrap"; then
    die "port $PORT is already serving something"
  fi

  ensure_venv

  echo "starting gateway on http://127.0.0.1:$PORT (model: $MODEL)"
  # Our client is not the browser: never rebuild the bundled WebUI here.
  export NANOBOT_SKIP_WEBUI_BUILD=1
  nohup "$DEV/venv/bin/nanobot" webui --port "$PORT" --config "$DEV/config.json" \
      --workspace "$DEV/workspace" --no-open --no-dock -y \
      > "$DEV/gateway.log" 2>&1 &
  echo $! > "$DEV/gateway.pid"

  for _ in $(seq 1 60); do
    if curl -sf -o /dev/null --max-time 1 -H "X-Nanobot-Auth: $secret" \
        "http://127.0.0.1:$PORT/webui/bootstrap"; then
      echo "ready:"
      echo "  url    http://127.0.0.1:$PORT"
      echo "  secret .dev/secret"
      echo "  log    .dev/gateway.log"
      return 0
    fi
    sleep 0.5
  done
  echo "gateway did not answer; last log lines:" >&2
  tail -20 "$DEV/gateway.log" >&2
  exit 1
}

stop() {
  if [ -f "$DEV/gateway.pid" ]; then
    # webui may fork a gateway child; kill the group the pid belongs to.
    kill "$(cat "$DEV/gateway.pid")" 2>/dev/null || true
    rm -f "$DEV/gateway.pid"
  fi
  pkill -f "nanobot.*--config $DEV/config.json" 2>/dev/null || true
  echo "stopped"
}

status() {
  local secret; secret="$(cat "$DEV/secret" 2>/dev/null || true)"
  curl -sf --max-time 2 -H "X-Nanobot-Auth: $secret" \
    "http://127.0.0.1:$PORT/webui/bootstrap" >/dev/null
}

case "${1:-}" in
  start) shift; start "$@" ;;
  stop) stop ;;
  status) if status; then echo "running on http://127.0.0.1:$PORT"; else echo "not running"; exit 1; fi ;;
  *) echo "usage: $0 {start [--port N] [--model M]|stop|status}" >&2; exit 2 ;;
esac
