#!/usr/bin/env python3
"""The error-copy half of G3's side-by-side check (plan.md §8, §9).

The eight taxonomy sentences are the core's copy keys and each platform's own words. This parses
both platforms' sources and compares them one key at a time, so drift is caught by a command
instead of by reading two files:

  iosApp/iosApp/ConnectionCopy.swift                     -> connection.<key> -> sentence
  app/src/main/res/values/strings.xml                     -> connection_<key>   -> sentence

Exit code 0 when both sides agree on every key, 1 otherwise.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IOS = ROOT / "iosApp/iosApp/ConnectionCopy.swift"
ANDROID = ROOT / "app/src/main/res/values/strings.xml"

KEYS = [
    "authFailed",
    "tlsRejected",
    "unreachable",
    "timeout",
    "protocolTooNew",
    "missingCapability",
    "missingField",
    "serverError",
]


def unescape(text: str) -> str:
    # Android's XML escapes apostrophes; the sentence a user reads does not carry the backslash.
    return text.replace("\\'", "'").replace("&amp;", "&").strip()


def ios_copies() -> dict:
    source = IOS.read_text()
    found = {}
    for key in KEYS:
        if key == "serverError":
            # The one entry whose sentence depends on the status: 5xx may be a bad minute, a 4xx
            # is the same answer every time (D29 §9), so both variants are compared.
            default = re.search(r'default: "(.*?)"', source, re.S)
            retry = re.search(r"status >= 500: \"(.*?)\"", source)
            if not default or not retry:
                print("copy: FAIL — iOS has no serverError sentences")
                sys.exit(1)
            found[key] = default.group(1).strip()
            found["serverErrorRetryable"] = retry.group(1).strip()
            continue
        match = re.search(rf"case \.{key}:\s*\n\s*\"(.*?)\"", source, re.S)
        if not match:
            print(f"copy: FAIL — iOS has no sentence for {key}")
            sys.exit(1)
        found[key] = match.group(1).strip()
    return found


def android_copies() -> dict:
    source = ANDROID.read_text()
    found = {}
    for key in KEYS:
        match = re.search(rf'<string name="connection_{key}">(.*?)</string>', source, re.S)
        if not match:
            print(f"copy: FAIL — Android has no string for connection_{key}")
            sys.exit(1)
        found[key] = unescape(match.group(1))
    match = re.search(r'<string name="connection_serverErrorRetryable">(.*?)</string>', source, re.S)
    if match:
        found["serverErrorRetryable"] = unescape(match.group(1))
    return found


def main() -> int:
    ios = ios_copies()
    android = android_copies()
    failures = 0
    for key in sorted(set(ios) | set(android)):
        left, right = ios.get(key), android.get(key)
        if left == right:
            print(f"copy: {key}: same")
        else:
            failures += 1
            print(f"copy: {key}: DIFFER")
            print(f"  ios     | {left}")
            print(f"  android | {right}")
    print(f"copy: {'PASS' if failures == 0 else 'FAIL'} ({len(ios)} keys, {failures} differ)")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
