#!/usr/bin/env bash
# Build, install and launch nanobot Companion on a connected iPhone.
#
#   tools/run-on-device.sh --list                 # what is plugged in
#   tools/run-on-device.sh --local                # connect to the LAN dev gateway (.dev)
#   tools/run-on-device.sh --url https://your-gateway.example --secret-file ~/.nanobot-companion.secret
#
# There is no default gateway: the address belongs to whoever runs this, and a committed one
# would be somebody's own gateway in a public repo (T23).
#
# The secret is handed to the app through its launch environment (DEVICECTL_CHILD_*), so it is
# never typed on the phone and never printed here. The app needs a signed build; signing is set
# to automatic with the personal team in the Xcode project, so a free Apple ID is enough.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS="$ROOT/iosApp"
BUNDLE="de.arjmandi.nanobot.companion"
URL=""
SECRET_FILE="$HOME/.nanobot-companion.secret"
DEVICE=""
MODE="run"

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --list) MODE="list"; shift ;;
    --local)
      [ -f "$ROOT/.dev/secret" ] || die "--local needs the dev gateway: tools/nanobot-dev.sh start --lan"
      SECRET_FILE="$ROOT/.dev/secret"
      LAN="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
      [ -n "$LAN" ] || die "no LAN address on en0/en1; use --url instead"
      URL="http://$LAN:18765"
      shift
      ;;
    --url) URL="$2"; shift 2 ;;
    --secret-file) SECRET_FILE="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

# A device is "usable" when CoreDevice sees it, it is physical, and the tunnel is up.
pick_device() {
  local json; json="$(mktemp)"
  xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1 || true
  python3 - "$json" "$DEVICE" <<'PY'
import json, sys
path, wanted = sys.argv[1], sys.argv[2].strip()
try:
    devices = json.load(open(path)).get("result", {}).get("devices", [])
except Exception:
    devices = []
rows = []
for d in devices:
    hw = d.get("hardwareProperties", {}) or {}
    if hw.get("reality") != "physical":
        continue
    conn = d.get("connectionProperties", {}) or {}
    rows.append((hw.get("udid") or d.get("identifier"), d.get("deviceProperties", {}).get("name", "?"),
                 conn.get("tunnelState", "?")))
if wanted:
    for udid, name, state in rows:
        if wanted in (udid, name):
            print(udid, name, sep="\t")
            sys.exit(0)
    sys.exit(1)
for udid, name, state in rows:
    if state == "connected":
        print(udid, name, sep="\t")
        sys.exit(0)
sys.exit(1)
PY
}

if [ "$MODE" = "list" ]; then
  echo "physical devices CoreDevice can see:"
  xcrun devicectl list devices 2>/dev/null | sed '1,2d' | awk -F'  +' 'NR>0 {print "  "$0}' | head -10
  echo
  echo "only 'reality = physical' rows can be installed to; simulators are driven by xcodebuild instead."
  exit 0
fi

[ -n "$URL" ] || die "pass --url with your gateway's address, or --local for the LAN dev gateway"

PICKED="$(pick_device || true)"
[ -n "$PICKED" ] || {
  cat >&2 <<'MSG'
error: no connected iPhone found.

  1. plug the iPhone in (or enable Wi-Fi sync in Finder), unlock it
  2. tap "Trust" on the phone when asked
  3. on the phone: Settings → Privacy & Security → Developer Mode → On (iOS 16+), then restart
  4. run this script again  (or: tools/run-on-device.sh --list)

If the phone appears but install fails, the first install also needs
Settings → General → VPN & Device Management → <your Apple ID> → Trust.
MSG
  exit 2
}

UDID="$(printf '%s' "$PICKED" | cut -f1)"
NAME="$(printf '%s' "$PICKED" | cut -f2)"
[ -f "$SECRET_FILE" ] || die "no secret at $SECRET_FILE"
SECRET="$(tr -d '\n' < "$SECRET_FILE")"
[ -n "$SECRET" ] || die "$SECRET_FILE is empty"

echo "device:  $NAME ($UDID)"
echo "gateway: $URL"

echo "building…"
xcodebuild -project "$IOS/iosApp.xcodeproj" -scheme iosApp -configuration Debug \
  -destination "platform=iOS,id=$UDID" -derivedDataPath "$ROOT/build/ios-device" \
  -allowProvisioningUpdates build > "$ROOT/build/ios-device-build.log" 2>&1 || {
    LOG="$ROOT/build/ios-device-build.log"
    if grep -q "No Account for Team" "$LOG"; then
      cat >&2 <<'MSG'
error: Xcode has no Apple ID signed in, so it cannot create a provisioning profile.

The signing certificate is on this Mac (Apple Development: your-apple-id@example.com,
team 4L99J8K75T) but a device install also needs a profile, and profiles come from an
account. One-time fix:

  1. open Xcode → Settings → Accounts → "+" → Apple ID → sign in (2FA)
  2. select the team "your-apple-id@example.com (Personal Team)" for the iosApp target
  3. run this script again

Nothing else is missing: the build, the framework, the LAN gateway and the app all work.
MSG
      exit 3
    fi
    echo "build failed; last lines:" >&2
    grep -E "error:|Signing|Provisioning" "$LOG" | tail -10 >&2
    echo "(full log: $LOG)" >&2
    exit 1
  }

APP="$ROOT/build/ios-device/Build/Products/Debug-iphoneos/iosApp.app"
[ -d "$APP" ] || die "no app at $APP"
echo "installing…"
xcrun devicectl device install app --device "$UDID" "$APP" >/dev/null || {
  echo "install failed. On the phone, trust the developer certificate first:" >&2
  echo "  Settings → General → VPN & Device Management → Apple Development: … → Trust" >&2
  exit 1
}

echo "launching…"
DEVICECTL_CHILD_NANOBOT_LIVE_URL="$URL" \
DEVICECTL_CHILD_NANOBOT_LIVE_SECRET="$SECRET" \
  xcrun devicectl device process launch --device "$UDID" --terminate-existing "$BUNDLE" >/dev/null \
  && echo "launched. The connect screen is prefilled with $URL — tap Connect."
