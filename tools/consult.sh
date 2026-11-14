#!/usr/bin/env bash
# Ask clef (ollama decision model) to choose between options, with evidence.
#
#   tools/consult.sh "Which of these is the right shape for X?" \
#       --option "label one=what this option costs and buys" \
#       --option "label two=what this option costs and buys" \
#       --state-file plan.md --state "extra context, one line"
#
# clef is a decision model, not a chat model: ollama only exposes it through
# POST /v1/systemone with a `state` and typed `questions` (choice | noul | score).
# Its answer is a pick plus calibrated probabilities, which is why it is useful for
# "pick one of these" and useless for "explain this code".
#
# Output: the choice, its probability, and the full probability table.
set -euo pipefail

Q=""
declare -a OPTIONS=() STATE_FILES=()
STATE_EXTRA=""

while [ $# -gt 0 ]; do
  case "$1" in
    --option) OPTIONS+=("$2"); shift 2 ;;
    --state-file) STATE_FILES+=("$2"); shift 2 ;;
    --state) STATE_EXTRA="$2"; shift 2 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) Q="${Q:+$Q }$1"; shift ;;
  esac
done

[ -n "$Q" ] || { echo "usage: $0 \"question\" --option \"label=criteria\" [--option ...] [--state-file F] [--state text]" >&2; exit 2; }
[ ${#OPTIONS[@]} -ge 2 ] || { echo "error: clef needs at least two --option entries to choose between" >&2; exit 2; }

# ${arr[@]+...} keeps `set -u` happy when the state file list is empty.
PAYLOAD="$(python3 - "$Q" "$STATE_EXTRA" "${#STATE_FILES[@]}" \
  ${STATE_FILES[@]+"${STATE_FILES[@]}"} "${OPTIONS[@]}" <<'PY'
import json, sys

q, extra, n_files = sys.argv[1], sys.argv[2], int(sys.argv[3])
files = sys.argv[4:4 + n_files]
options = sys.argv[4 + n_files:]

state_parts = []
if extra:
    state_parts.append(extra)
for path in files:
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            state_parts.append(f"--- {path} ---\n{fh.read()}")
    except OSError as exc:
        state_parts.append(f"--- {path} ---\n<unreadable: {exc}>")

criteria = {}
order = []
for raw in options:
    if "=" not in raw:
        sys.exit(f"error: --option needs label=criteria, got {raw!r}")
    label, text = raw.split("=", 1)
    label = label.strip()
    criteria[label] = text.strip()
    order.append(label)

print(json.dumps({
    "model": "clef:cloud",
    "state": "\n\n".join(state_parts) or "No additional context supplied.",
    "questions": {
        "decision": {
            "type": "choice",
            "instructions": q,
            "criteria": criteria,
        }
    },
}, ensure_ascii=False))
PY
)"

RESPONSE="$(curl -sf --max-time 300 http://127.0.0.1:11434/v1/systemone \
  -H 'Content-Type: application/json' -d "$PAYLOAD")" || {
    echo "error: clef request failed (is ollama running? is clef:cloud pulled?)" >&2
    exit 1
  }

python3 - "$RESPONSE" <<'PY'
import json, sys

payload = json.loads(sys.argv[1])
answer = (payload.get("answers") or {}).get("decision") or {}
choice = answer.get("choice")
probs = answer.get("probabilities") or {}

if not choice:
    print("clef returned no choice:", json.dumps(payload)[:400])
    sys.exit(1)

print(f"clef chose: {choice}  (confidence {answer.get('confidence')})")
for label, p in sorted(probs.items(), key=lambda kv: -kv[1]):
    mark = "->" if label == choice else "  "
    print(f" {mark} {p:6.1%}  {label}")
PY
