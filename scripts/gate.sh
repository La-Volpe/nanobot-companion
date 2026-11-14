#!/usr/bin/env bash
# Gate checker (plan.md §3.5). Reads the matching checklist out of GATES.md and
# refuses to pass while any item is unchecked.
#
#   scripts/gate.sh core            # check G1
#   scripts/gate.sh core --run      # run the mechanical suites first, then check
#   scripts/gate.sh ios | android | scaffold
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATES="$ROOT/GATES.md"

case "${1:-}" in
  scaffold) section="G0" ;;
  core)     section="G1" ;;
  ios)      section="G2" ;;
  android)  section="G3" ;;
  *) echo "usage: $0 {scaffold|core|ios|android} [--run]" >&2; exit 2 ;;
esac

run_checks() {
  case "$section" in
    G0)
      "$ROOT/gradlew" -q :core:allTests
      "$ROOT/gradlew" -q :core:assemble
      "$ROOT/gradlew" -q :core-cli:run --args=--help
      ;;
    G1)
      "$ROOT/gradlew" :core:allTests :core:jvmTest -Pcontract --continue
      ;;
    *)
      echo "no mechanical checks for $section yet (UI suites land with that phase)"
      ;;
  esac
}

if [ "${2:-}" = "--run" ]; then
  echo "== running mechanical checks for $section"
  run_checks
  echo
fi

# Extract the section: from the "## <section>" heading to the next "## " heading.
items="$(awk -v s="## $section" '
  $0 ~ "^"s" " {inside=1; next}
  inside && /^## / {inside=0}
  inside && /^- \[/ {print}
' "$GATES")"

if [ -z "$items" ]; then
  echo "error: no checklist found for $section in GATES.md" >&2
  exit 2
fi

open_count="$(printf '%s\n' "$items" | grep -c '^- \[ \]' || true)"
total="$(printf '%s\n' "$items" | grep -c '^- \[' || true)"
echo "== $section: $((total - open_count))/$total checked"

if [ "$open_count" -gt 0 ]; then
  echo
  printf '%s\n' "$items" | grep '^- \[ \]'
  echo
  echo "gate CLOSED: $open_count item(s) unverified"
  exit 1
fi

echo "gate OPEN: $section complete"
