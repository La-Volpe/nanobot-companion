#!/usr/bin/env bash
# All the gate commands, in the order AGENTS.md lists them, stopping at the first red.
#
#   tools/all-tests.sh                        # unit suites, coverage, connected, iOS
#   tools/all-tests.sh --skip-device --skip-ios
#   tools/all-tests.sh --with-contract       # adds :core:jvmTest -Pcontract (needs a gateway)
#   tools/all-tests.sh --dry-run             # prints the five commands and exits
#
# The instruments are deliberately not here: session20.sh, soak.sh, android-session.sh and
# parity-check.sh each take 20-30 minutes against a live gateway, and nobody wants them to
# start because they typed this. They are named at the end instead.
#
# Why a script at all: the five commands were copy-pasted for months, and a copy-paste that
# skips koverVerify is a gate that quietly stops being a gate.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_DEVICE=0
SKIP_IOS=0
WITH_CONTRACT=0
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --skip-device)   SKIP_DEVICE=1 ;;
    --skip-ios)      SKIP_IOS=1 ;;
    --with-contract) WITH_CONTRACT=1 ;;
    --dry-run)       DRY_RUN=1 ;;
    -h|--help)       sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

FAILED_AT=""
STEP=0
TOTAL=5

# JUnit XML is the only place the counts actually live; Gradle's console summary is a
# different number on a --continue run.
report() {
  local label="$1"; shift
  python3 - "$label" "$@" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
label, patterns = sys.argv[1], sys.argv[2:]
tests = fails = skips = 0
seen = 0
for pat in patterns:
    for path in glob.glob(pat, recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        seen += 1
        if root.tag == "testsuite":
            suites = [root]
        else:
            suites = root.findall("testsuite")
        for s in suites:
            tests += int(s.get("tests") or 0)
            fails += int(s.get("failures") or 0) + int(s.get("errors") or 0)
            skips += int(s.get("skipped") or 0)
if seen == 0:
    print(f"   {label}: no test results on disk")
else:
    verdict = "ok" if fails == 0 else f"{fails} FAILED"
    print(f"   {label}: {tests} tests, {verdict}, {skips} skipped  ({seen} result files)")
PY
}

step() {
  STEP=$((STEP + 1))
  echo
  echo "== $STEP/$TOTAL  $1"
}

# `set -e` is off: every command is checked, so the message names the step instead of the
# shell exiting on a number.
stop_if_red() {
  if [ "$1" -ne 0 ]; then
    FAILED_AT="$2"
    echo
    echo "STOPPED at $2 (exit $1). Nothing after it ran."
    exit "$1"
  fi
}

run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "   \$ $*"
    return 0
  fi
  echo "   \$ $*"
  "$@"
}

# --- 1. the suites that need no device ------------------------------------------------------
step "unit suites — :core (jvm, iosSimulatorArm64, android host), :core-cli, :app"
run ./gradlew --console=plain :core:allTests :core-cli:test :app:testDebugUnitTest
stop_if_red $? "the unit suites"
if [ "$DRY_RUN" -eq 0 ]; then
  report ":core jvm"              "core/build/test-results/jvmTest/TEST-*.xml"
  report ":core iosSimulatorArm64" "core/build/test-results/iosSimulatorArm64Test/TEST-*.xml"
  report ":core android host"      "core/build/test-results/testAndroidHostTest/TEST-*.xml"
  report ":core-cli"               "core-cli/build/test-results/test/TEST-*.xml"
  report ":app unit"               "app/build/test-results/testDebugUnitTest/TEST-*.xml"
fi

# --- 2. the coverage floor ------------------------------------------------------------------
step "coverage — the 85% floor lives inside :core"
run ./gradlew --console=plain :core:koverVerify
stop_if_red $? "koverVerify"

# --- 3. the live-gateway contract run, only when asked --------------------------------------
if [ "$WITH_CONTRACT" -eq 1 ]; then
  step "contract — :core:jvmTest -Pcontract (a dev gateway must be up)"
  run ./gradlew --console=plain :core:jvmTest -Pcontract
  stop_if_red $? "the contract run"
  if [ "$DRY_RUN" -eq 0 ]; then
    report ":core contract" "core/build/test-results/jvmTest/TEST-*.xml"
  fi
else
  step "contract — skipped (--with-contract to include it)"
  echo "   needs tools/nanobot-dev.sh start"
fi

# --- 4. the instrumented suite, if a device answers ----------------------------------------
step "Android connected — :app:connectedDebugAndroidTest"
if [ "$SKIP_DEVICE" -eq 1 ]; then
  echo "   skipped (--skip-device)"
elif ! command -v adb >/dev/null 2>&1; then
  echo "   skipped: no adb on PATH"
else
  # The pin is the whole point: unpinned, Gradle takes whichever device is attached first and
  # a run that reaches a real phone fails wholesale with "No compose hierarchies found".
  if [ -z "${ANDROID_SERIAL:-}" ]; then
    serial="$(adb devices | awk '$2 == "device" {print $1}' | grep '^emulator-' | head -1)"
    [ -z "$serial" ] && serial="$(adb devices | awk '$2 == "device" {print $1}' | head -1)"
    if [ -z "$serial" ]; then
      echo "   skipped: no device attached (boot one: emulator -avd <name> -no-snapshot-load)"
      serial=""
    fi
    export ANDROID_SERIAL="$serial"
  fi
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "   device: $ANDROID_SERIAL"
    run ./gradlew --console=plain :app:connectedDebugAndroidTest
    stop_if_red $? "the instrumented suite"
    if [ "$DRY_RUN" -eq 0 ]; then
      report ":app connected" "app/build/outputs/androidTest-results/connected/**/TEST-*.xml"
    fi
  fi
fi

# --- 5. iOS: unit + XCUITest on the simulator ----------------------------------------------
step "iOS — xcodebuild test on the iPhone 17 simulator"
if [ "$SKIP_IOS" -eq 1 ]; then
  echo "   skipped (--skip-ios)"
elif ! command -v xcodebuild >/dev/null 2>&1; then
  echo "   skipped: no xcodebuild (this is not a Mac)"
else
  # CODE_SIGNING_ALLOWED=NO is for the simulator only; on a device it produces an unsigned
  # .app and installd says "No code signature found" (AGENTS gotcha 19).
  run xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination 'platform=iOS Simulator,name=iPhone 17' \
    -derivedDataPath /tmp/dd-alltests -resultBundlePath /tmp/dd-alltests/result.xcresult \
    CODE_SIGNING_ALLOWED=NO test
  stop_if_red $? "the iOS suites"
  if [ "$DRY_RUN" -eq 0 ]; then
    xcrun xcresulttool get test-results summary --path /tmp/dd-alltests/result.xcresult 2>/dev/null |
      python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("   iOS: no summary in the .xcresult")
    raise SystemExit
print("   iOS: {} passed, {} failed, {} skipped".format(
    d.get("passedTests", 0), d.get("failedTests", 0), d.get("skippedTests", 0)))
'
  fi
fi

echo
if [ "$DRY_RUN" -eq 1 ]; then
  echo "dry run: $STEP step(s) printed, nothing executed."
  exit 0
fi
if [ -n "$FAILED_AT" ]; then
  echo "RED at $FAILED_AT."
  exit 1
fi
echo "GREEN — every step above passed."
echo
echo "Not run here, on purpose (each needs a live gateway and 20-30 minutes):"
echo "  tools/session20.sh · tools/soak.sh · tools/android-session.sh · tools/parity-check.sh"
