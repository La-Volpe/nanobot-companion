#!/usr/bin/env python3
"""The transcript half of G3's side-by-side check (plan.md §8).

Two renderings of one stored chat, compared line for line:

  argv[1]  `core-cli watch --chat <id>` output — the CLI's plain transcript of the replayed page
  argv[2]  `parity turn N | prompt=... | answer=... | status=...` — what the Android app holds

Both sides are :core's `HistoryReplay` output for the same chat, so a difference is an app-path
bug. Turn ids and clocks are not in either rendering, which is what makes the diff meaningful.
"""
import re
import sys
from pathlib import Path

PROMPT = re.compile(r"^\s+prompt \|(?: \(unsettled\))? ?(.*)$")
ANSWER = re.compile(r"^\s+answer \| ?(.*)$")
STATUS = re.compile(r"^\s+status \| ?(.*)$")
TURN = re.compile(r"^turn (\d+)$")
ANDROID = re.compile(r"^parity turn (\d+) \| prompt=(.*?) \| answer=(.*?) \| status=(.*)$")


def unescape(text: str) -> str:
    return text.replace("\\n", "\n").strip()


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


def android_turns(path: Path) -> list:
    turns = []
    for line in path.read_text().splitlines():
        match = ANDROID.match(line.strip())
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
    if len(sys.argv) != 3:
        print("usage: parity_transcript.py <cli.txt> <android.txt>")
        return 2
    cli = cli_turns(Path(sys.argv[1]))
    android = android_turns(Path(sys.argv[2]))
    if not cli or not android:
        print(f"parity: FAIL — cli turns={len(cli)} android turns={len(android)}")
        return 1
    failures = 0
    if len(cli) != len(android):
        failures += 1
        print(f"parity: turn count differs: cli={len(cli)} android={len(android)}")
    for index, (left, right) in enumerate(zip(cli, android), start=1):
        if left == right:
            print(f"parity: turn {index}: same ({len(left['answer'])} chars)")
            continue
        failures += 1
        print(f"parity: turn {index}: DIFFER")
        for field in ("prompt", "answer", "status"):
            if left[field] != right[field]:
                print(f"  {field} cli     | {left[field]!r}")
                print(f"  {field} android | {right[field]!r}")
    print(f"parity: transcript {'PASS' if failures == 0 else 'FAIL'} ({len(cli)} turns, {failures} differ)")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
