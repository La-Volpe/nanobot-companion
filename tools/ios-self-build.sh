#!/usr/bin/env bash
# Build and run nanobot Companion on your own Mac, without anyone else's signing identity.
#
#   tools/ios-self-build.sh --check              what this Mac has, and what it needs
#   tools/ios-self-build.sh --simulator          build, install and launch on an iOS simulator
#   tools/ios-self-build.sh --device --team ABCDE12345
#   tools/ios-self-build.sh --open                open the project in Xcode for the click-Run path
#
# The simulator needs no Apple account at all: that is the whole point of this script for a
# reader who wants to try the app. A real iPhone needs *some* team, and a free Apple ID's
# personal team is enough — the profile it mints expires after seven days, which is a fact to
# say out loud rather than a bug to file.
#
# `--device` takes the team from `--team` or `NANOBOT_DEVELOPMENT_TEAM`, and never invents one:
# the project carries no `DEVELOPMENT_TEAM` on purpose (AGENTS gotcha 19), because it once named
# two Apple accounts in a public repository.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/iosApp/iosApp.xcodeproj"
BUNDLE_ID="de.arjmandi.nanobot.companion"
DERIVED="$ROOT/.dev/ios-build"

MODE=check
TEAM="${NANOBOT_DEVELOPMENT_TEAM:-}"
DEVICE_NAME=""
SIM_NAME="${NANOBOT_SIMULATOR:-iPhone 17}"

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --check)     MODE=check; shift ;;
    --simulator) MODE=simulator; shift ;;
    --device)    MODE=device; shift ;;
    --open)      MODE=open; shift ;;
    --team)      TEAM="$2"; shift 2 ;;
    --sim)       SIM_NAME="$2"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; }
note() { printf '    %s\n' "$1"; }

missing=0
need() { bad "$1"; note "$2"; missing=$((missing + 1)); }

echo "== what this Mac has"

if [ "$(uname -s)" != "Darwin" ]; then
  bad "not a Mac (this needs macOS and Xcode)"
  note "the Android app builds anywhere: tools/all-tests.sh --skip-ios"
  exit 1
fi
ok "macOS $(sw_vers -productVersion)"

developer="$(xcode-select -p 2>/dev/null || true)"
case "$developer" in
  */Xcode*.app/Contents/Developer) ok "Xcode at ${developer%/Contents/Developer}" ;;
  *) need "no full Xcode selected (found: ${developer:-nothing})" \
          "install Xcode from the App Store, then: sudo xcode-select -s /Applications/Xcode.app" ;;
esac

if xcodebuild -version >/dev/null 2>&1; then
  ok "xcodebuild $(xcodebuild -version | head -1 | awk '{print $2}')"
else
  need "xcodebuild does not run" "open Xcode once so it can install its components"
fi

jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -n "$jdk" ]; then
  ok "JDK 21 at $jdk"
else
  need "no JDK 21" "brew install --cask temurin@21   (the Kotlin framework is built by Gradle)"
fi

runtime="$(xcrun simctl list runtimes 2>/dev/null | grep -E '^iOS [0-9]' | tail -1 | sed 's/^\(iOS [0-9.]*\).*/\1/')"
if [ -n "$runtime" ]; then
  ok "simulator runtime $runtime"
else
  need "no iOS simulator runtime" "Xcode → Settings → Components → download an iOS runtime"
fi

if [ "$missing" -gt 0 ]; then
  echo
  echo "$missing thing(s) to fix before a build. Nothing here is a secret and nothing needs an Apple account."
  exit 1
fi

case "$MODE" in
  check)
    echo
    echo "== ready"
    echo "  tools/ios-self-build.sh --simulator        build and run, no Apple account needed"
    echo "  tools/ios-self-build.sh --device --team    build and run on a plugged-in iPhone"
    echo "  tools/ios-self-build.sh --open             do it by hand in Xcode"
    ;;

  open)
    # The click-Run path, which is what most readers will actually use.
    open "$PROJECT"
    echo
    echo "In Xcode: pick the iosApp scheme, choose a simulator or your iPhone, press Run."
    echo "For a device, choose your team first: target iosApp → Signing & Capabilities → Team."
    echo "A free Apple ID gives you a personal team; its profile expires after seven days."
    ;;

  simulator)
    echo
    mkdir -p "$DERIVED"
    echo "== building for the simulator ($SIM_NAME)"
    # A simulator needs no signing: this is the same invocation the gate uses.
    if ! xcodebuild -project "$PROJECT" -scheme iosApp -configuration Debug \
      -destination "platform=iOS Simulator,name=$SIM_NAME" -derivedDataPath "$DERIVED" \
      CODE_SIGNING_ALLOWED=NO build >"$DERIVED/build.log" 2>&1; then
      echo "build failed; the last lines of $DERIVED/build.log:" >&2
      grep -E 'error:|BUILD FAILED' "$DERIVED/build.log" | tail -15 >&2
      exit 1
    fi
    app="$DERIVED/Build/Products/Debug-iphonesimulator/iosApp.app"
    [ -d "$app" ] || { echo "built, but $app is missing" >&2; exit 1; }
    device="$(xcrun simctl list devices available | grep -m1 "$SIM_NAME (" | sed 's/.*(\([0-9A-F-]*\)).*/\1/')"
    if [ -z "$device" ]; then
      echo "no simulator named $SIM_NAME; available:" >&2
      xcrun simctl list devices available | grep -E '^ +iPhone' | head -8 >&2
      exit 1
    fi
    xcrun simctl boot "$device" 2>/dev/null || true
    xcrun simctl bootstatus "$device" -b >/dev/null 2>&1 || true
    xcrun simctl install "$device" "$app"
    xcrun simctl launch "$device" "$BUNDLE_ID"
    echo
    echo "running on the $SIM_NAME simulator. Screenshot it with:"
    echo "  xcrun simctl io $SIM_NAME screenshot /tmp/nanobot.png"
    ;;

  device)
    echo
    if [ -z "$TEAM" ]; then
      bad "no team given, and a device build needs one"
      note "tools/ios-self-build.sh --device --team YOURTEAMID"
      note "or open the project and pick your team: target iosApp → Signing & Capabilities → Team"
      note "the id is in Xcode → Settings → Accounts → your Apple ID → Team, or at developer.apple.com"
      note "a free Apple ID has a personal team, and its provisioning profile lasts seven days"
      exit 2
    fi
    target="${DEVICE_NAME:-$(xcrun xctrace list devices 2>/dev/null | grep -m1 -E 'iPhone' | sed 's/ (.*//' | xargs)}"
    if [ -z "$target" ]; then
      bad "no iPhone found"
      note "plug it in, unlock it, and tap Trust on the phone"
      exit 1
    fi
    ok "team $TEAM, device $target"
    mkdir -p "$DERIVED"
    echo "== building for the device"
    if ! xcodebuild -project "$PROJECT" -scheme iosApp -configuration Debug \
      -destination "platform=iOS,id=$(xcrun devicectl list devices 2>/dev/null | grep -m1 "$target" | awk '{print $NF}')" \
      -derivedDataPath "$DERIVED" DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic \
      -allowProvisioningUpdates build >"$DERIVED/device-build.log" 2>&1; then
      grep -E 'error:|No Account for Team|No profiles for' "$DERIVED/device-build.log" | tail -15 >&2
      note "for 'No Account for Team': sign into your Apple ID in Xcode → Settings → Accounts"
      exit 1
    fi
    echo "installed. If the app stops launching after seven days, that is the free profile"
    echo "expiring: rebuild from Xcode to renew it."
    ;;
esac
