# nanobot Companion

A native iOS and Android client for a [nanobot](https://github.com/HKUDS/nanobot)
gateway. One Kotlin Multiplatform module holds everything that is not UI. iOS
renders it with SwiftUI, Android with Jetpack Compose, both against the
gateway's own WebSocket protocol — the same one the browser WebUI speaks.

The gateway is the product here, not the app's backend. There is no companion
server: the app authenticates to your nanobot with the WebUI secret, opens the
same socket the browser opens, and reduces the same frames. If the WebUI can
show it, the app can show it; if the gateway cannot do it, the app says so
instead of inventing a spinner.

```text
┌─────────────────────────────── :core (KMP, no UI) ──────────────────────────────┐
│ wire        frame codec, outbound encoders, bootstrap + contract, session rows   │
│ transport   HTTP bootstrap, WebSocket session, uploads, backoff, TLS policy      │
│ projection  one reducer for socket frames and stored history, markdown parser    │
│ engine      executes effects, one writer, reconnect, thread cache                │
│ facade      NanobotCore · ChatHandle — everything the two apps consume           │
│ platform    Clock · SecureStore · FileStore · CoreDeps (expect/actual)           │
│ bridge      iOS/Android hand-off: typed subscribe, errors instead of traps       │
└──────────┬───────────────────────────────────────────────────────┬──────────────┘
           │ SwiftUI                                               │ Compose
   ┌───────▼────────┐                                     ┌────────▼──────────┐
   │ iosApp/        │                                     │ :app              │
   │ hand-built     │                                     │ Material 3        │
   │ .xcodeproj     │                                     │ one VM per screen │
   └────────────────┘                                     └───────────────────┘
                                  :core-cli
        record · connect · sessions · send · watch · session · soak
```

## What is hard here

- **Two feeds, one reducer.** The gateway replays the same event vocabulary for
  live frames and for stored history, so `:core` reduces both through
  `projection/ThreadReducer.kt`. A chat that is reopened after a reconnect
  looks exactly like the chat you were reading (`docs/decisions.md` D9, D46).
  The gateway's stored pages are *compacted*: the answer text survives only
  inside `stream_end.text` and the reasoning inside `reasoning_end.text` (D52).
- **The protocol is the contract and it is verified against the real thing.**
  `-Pcontract` runs four tests against a live nanobot 0.4.1; fixtures are
  recorded by `:core-cli record`, never hand-written, and carry provenance
  (gateway version, commit, redaction rules). See `docs/decisions.md` D18.
- **Nothing is allowed to be theatre.** The architecture guard, the Kover floor
  and the parity instrument are all proved non-vacuous by breaking them
  (D23, D36, D51). A test that passes with the implementation deleted is not a
  test — several tasks were rejected for exactly that.
- **Both platforms are compared mechanically, not by eye.** `tools/parity-check.sh`
  diffs the CLI's, iOS's and Android's reduction of the *same stored chat*, and
  compares nine error-copy keys across the two string tables.

## The gateway contract it depends on

| Piece | Where it comes from |
|---|---|
| `GET /webui/bootstrap` with `X-Nanobot-Auth: <secret>` → token, `ws_path`, `limits`, `terminal.webui` | `nanobot/webui/ws_http.py` |
| Protocol 1 + capability list (`terminal.webui.capabilities`) | `nanobot/webui/client_contract.py` |
| Socket frames: `ready`, `delta`, `reasoning_*`, `message`, `file_edit`, `turn_end`, `recovery_state`, … | `docs/websocket.md` in the gateway repo |
| History: `GET /api/sessions/{key}/webui-thread` | `nanobot/webui/transcript.py` |
| Writes: socket only, except token-gated `POST /api/attachments` | `nanobot/webui/ws_http.py` |

A bootstrap token is single-use, so every reconnect bootstraps again — that is
a rule the engine obeys, not a bug to paper over (D15).

## Building and running

Prerequisites: JDK 21+, the Android SDK, Xcode 27 with an iOS 17+ runtime, and
a nanobot gateway you can reach.

```bash
./gradlew :core:allTests :core-cli:test :app:testDebugUnitTest   # all unit suites
./gradlew :app:installDebug                                      # Android on the emulator/device
tools/run-on-device.sh --local                                   # iOS on a plugged-in iPhone
```

The Xcode project is checked in as a hand-written `project.pbxproj`; the
framework arrives through a Run Script phase that calls
`./gradlew :core:embedAndSignAppleFrameworkForXcode`. No CocoaPods, no
XcodeGen, no Tuist.

For a gateway of your own:

```bash
tools/nanobot-dev.sh start --lan     # real nanobot, throwaway config, LAN address printed
tools/nanobot-dev.sh stop
```

It writes everything to `.dev/` (venv, config, secret, profile, logs) and
never touches `~/.nanobot`. Point the app at `https://your-gateway.example` or at
that LAN address; the secret is the gateway's WebUI secret.

## Testing

```bash
./gradlew :core:allTests                    # 292 jvm · 185 iOS sim · 172 android host
./gradlew :core:jvmTest -Pcontract          # against a live gateway (4 tests)
./gradlew :core:koverVerify                 # 85% floor on wire/projection/engine (measured 95.7%)
./gradlew :app:testDebugUnitTest            # 79
./gradlew :app:connectedDebugAndroidTest    # 56 on the emulator
cd iosApp && xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' test
                                            # 95 swift-testing + 29 XCUITest
```

Instruments that are not unit tests, and earn their keep:

- `tools/session20.sh` — 20-minute live session through the core (`beats=8, failures=0`)
- `tools/soak.sh` — 30 minutes, 200 sends, 50 reconnects, leak check
- `tools/android-session.sh` — the same beats on the emulator, through the app
- `tools/parity-check.sh` — CLI vs iOS vs Android transcript, plus error copy
- `tools/consult.sh` — ask clef, a decision model, to choose between two options

## Layout

```
plan.md              the approved plan: tasks, TDD contract, gate criteria
GATES.md             G0–G3 checklists; a phase is done when its boxes are ticked with evidence
docs/decisions.md    D11–D52: every non-obvious choice, with the evidence that forced it
HEARTBEAT.md         the manager loop that drove this build (halted: goal reached)
core/                :core, the KMP library (no UI, no Compose, no UIKit)
core-cli/            :core-cli, the harness that talks to real gateways
app/                 :app, Jetpack Compose
iosApp/              SwiftUI + the Xcode project
tools/               dev gateway, devices, soak, parity, consultations
scripts/gate.sh      reads GATES.md and refuses to pass while anything is unchecked
```

## How this was built

With an agent loop that was held to a plan: `plan.md` first, TDD per task, one
commit per task with the red run in the message, and gate checklists that only
I could tick after re-running the evidence myself. Two gates were *held* rather
than ticked because the instruments found real defects — an app that did not
replay history on reconnect (P9) and an iOS composer that silently dropped
attachments (P13). Both were fixed with red-then-green tests.

The record is in the repo: `git log` is one commit per plan task, `GATES.md`
holds the evidence pasted under each item, `docs/decisions.md` explains the
choices, and `.pi/parked.md` lists what is still open and why.

## Notes for agents (and the curious)

`AGENTS.md` is the working guide: commands, the version matrix, twenty-odd
gotchas that were already paid for, and the review/QA bar. `HUMANS.md` is the
coffee-break version. The best code here is the code it does not contain: no
markdown library, no DI framework, no date library, no image loader, and one
third-party runtime dependency (Ktor, whose engines are the platforms' own).
