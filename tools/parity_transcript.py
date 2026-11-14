#!/usr/bin/env python3
"""The transcript half of G3's side-by-side check (plan.md §8).

One stored chat, two renderings, compared line for line:

  argv[1]  `core-cli send` / `core-cli watch --chat <id>` output — the CLI's plain transcript
  argv[2]  a label for the app side: `android` or `ios`
  argv[3]  that app's own lines: `parity <label> turn N | prompt=... | answer=... | status=...`

The CLI's transcript is the socket's reduction of the chat; the app's lines are what the app
holds after opening the same chat through its bridge (the gateway's page through `HistoryReplay`
on both platforms). One core, two paths, so a difference is an app-path bug. Turn ids and clocks
are in neither rendering, which is what makes the diff meaningful.
"""
import re
import sys
from pathlib import Path

PROMPT = re.compile(r"^\s+prompt \|(?: \(unsettled\))? ?(.*)$")
ANSWER = re.compile(r"^\s+answer \| ?(.*)$")
STATUS = re.compile(r"^\s+status \| ?(.*)$")
TURN = re.compile(r"^turn (\d+)$")
APP = re.compile(r"^parity (?:\w+ )?turn (\d+) \| prompt=(.*?) \| answer=(.*?) \| status=(.*)$")


def unescape(text: str) -> str:
    """One line either way: the CLI escapes newlines, an app renders them as spaces."""
    return text.replace("\\n", "\n").replace("\n", " ").strip()


def cli_turns(path: Path) -> list:
    turns = []
    current = None
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            continue
        if TURN.match(line):
            current = {"prompt": "", "answer": "", "status": ""}
            turns.append(current)
            continue
        if current is None:
            continue
        prompt = PROMPT.match(line)
        if prompt:
            current["prompt"] = unescape(prompt.group(1))
            continue
        answer = ANSWER.match(line)
        if answer:
            # One answer per segment: the socket chunks, the page stores a block, so the segments
            # are joined rather than compared one by one.
            current["answer"] += unescape(answer.group(1))
            continue
        status = STATUS.match(line)
        if status:
            current["status"] = status.group(1).strip()
    return turns


def app_turns(path: Path) -> list:
    turns = []
    if not path.exists():
        return turns
    for line in path.read_text().splitlines():
        match = APP.match(line.strip())
        if not match:
            continue
        turns.append(
            {
                "prompt": unescape(match.group(2)),
                "answer": unescape(match.group(3)),
                "status": match.group(4).strip(),
            }
        )
    return turns


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: parity_transcript.py <cli.txt> <label: android|ios> <app.txt>")
        return 2
    label = sys.argv[2]
    cli = cli_turns(Path(sys.argv[1]))
    app = app_turns(Path(sys.argv[3]))
    if not cli or not app:
        # An empty side is a failed instrument, not a pass: it would compare nothing to nothing.
        print(f"parity: {label} FAIL — cli turns={len(cli)} {label} turns={len(app)}")
        return 1
    failures = 0
    if len(cli) != len(app):
        failures += 1
        print(f"parity: {label} turn count differs: cli={len(cli)} {label}={len(app)}")
    for index, (left, right) in enumerate(zip(cli, app), start=1):
        if left == right:
            print(f"parity: {label} turn {index}: same ({len(left['answer'])} chars)")
            continue
        failures += 1
        print(f"parity: {label} turn {index}: DIFFER")
        for field in ("prompt", "answer", "status"):
            if left[field] != right[field]:
                print(f"  {field} cli       | {left[field]!r}")
                print(f"  {field} {label:<9} | {right[field]!r}")
    print(f"parity: {label} transcript {'PASS' if failures == 0 else 'FAIL'} ({len(cli)} turns, {failures} differ)")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
