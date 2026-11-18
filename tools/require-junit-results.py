"""Reject a missing, empty, all-skipped, or failed JUnit suite in CI."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(directory: Path) -> bool:
    cases = [
        case
        for report in sorted(directory.rglob("TEST-*.xml"))
        for case in ET.parse(report).iter("testcase")
    ]
    skipped = sum(case.find("skipped") is not None for case in cases)
    failed = sum(any(case.find(kind) is not None for kind in ("failure", "error")) for case in cases)
    print(f"{directory}: {len(cases)} tests, {failed} failed, {skipped} skipped")
    return len(cases) > skipped and failed == 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: require-junit-results.py RESULT_DIRECTORY ...")
    raise SystemExit(0 if all([verify(Path(arg)) for arg in sys.argv[1:]]) else 1)
