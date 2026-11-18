# nanobot Companion

[![Fast checks](https://github.com/La-Volpe/nanobot-companion/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/La-Volpe/nanobot-companion/actions/workflows/ci.yml)
[![Core line coverage snapshot: 95.64%](https://img.shields.io/badge/Core%20line%20coverage%20snapshot-95.64%25-brightgreen)](#testing)

A native iOS and Android client for a [nanobot](https://github.com/HKUDS/nanobot)
gateway. One Kotlin Multiplatform module holds everything that is not UI. iOS
renders it with SwiftUI, Android with Jetpack Compose, both against the
gateway's own WebSocket protocol, the same one the browser WebUI speaks.

**Not affiliated with HKUDS/nanobot.** This is an independent client for the
gateway that project publishes; the credits and licences for the artwork it
borrows are in [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).

The gateway is the product here, not the app's backend. There is no companion
server: the app authenticates to your nanobot with the WebUI secret, opens the
same socket the browser opens, and reduces the same frames. If the WebUI can
show it, the app can show it; if the gateway cannot do it, the app says so
instead of inventing a spinner.

```mermaid
flowchart TB
  subgraph apps["apps — render only"]
    ios["iosApp/ · SwiftUI"]
    android[":app · Compose"]
  end

  subgraph core[":core — KMP, no UI"]
    bridge["bridge<br/>typed subscribe"]
    facade["facade<br/>NanobotCore · ChatHandle"]
    engine["engine<br/>effects · one writer"]
    projection["projection<br/>reducer · markdown"]
    transport["transport<br/>socket · uploads · TLS"]
    wire["wire<br/>frames · encoders · contract"]
    platform["platform<br/>Clock · stores · deps"]
  end

  cli[":core-cli<br/>the instruments"]

  ios --> bridge
  android --> bridge
  cli --> facade
  bridge --> facade
  facade --> engine
  engine --> projection
  engine --> transport
  projection --> wire
  transport --> wire
  platform -.->|expect/actual| transport
  platform -.-> engine
```

The arrow is the dependency direction: nothing below points back up, and
`commonMain` never reaches for a UI toolkit (enforced by `ArchitectureTest`).

## What is hard here

- Two feeds, one reducer. The gateway replays the same event vocabulary for
  live frames and for stored history, so `:core` reduces both through
  `projection/ThreadReducer.kt`, and a chat reopened after a reconnect looks
  exactly like the chat you were reading. The stored pages are *compacted*:
  the answer text survives only inside `stream_end.text` and the reasoning
  inside `reasoning_end.text`.
- The protocol is the contract, and it is verified against the real thing.
  `-Pcontract` runs four tests against a live nanobot 0.4.1. Fixtures are
  recorded by `:core-cli record`, never hand-written, and carry provenance:
  gateway version, commit, redaction rules.
- Nothing here is allowed to be theatre. The architecture guard, the Kover
  floor and the parity instrument were each proved non-vacuous by breaking
  them. A test that passes with the implementation deleted is not a test;
  several tasks were rejected for exactly that.
- Both platforms are compared mechanically, not by eye.
  `tools/parity-check.sh` diffs the CLI's, iOS's and Android's reduction of the
  *same stored chat*, and compares nine error-copy keys across the two string
  tables.

One turn arrives twice, and both routes end in the same place:

```mermaid
flowchart LR
  socket["socket frames<br/>delta · reasoning · tools · turn_end"] --> reducer
  history["history page<br/>/webui-thread, compacted"] --> reducer
  reducer["ThreadReducer<br/>pure · effects as data"] --> state["ThreadState<br/>StateFlow"]
  state --> ios["SwiftUI"]
  state --> android["Compose"]
  state --> cli[":core-cli"]
```

## The gateway contract it depends on

| Piece | Where it comes from |
|---|---|
| `GET /webui/bootstrap` with `X-Nanobot-Auth: <secret>` → token, `ws_path`, `limits`, `terminal.webui` | `nanobot/webui/ws_http.py` |
| Protocol 1 + capability list (`terminal.webui.capabilities`) | `nanobot/webui/client_contract.py` |
| Socket frames: `ready`, `delta`, `reasoning_*`, `message`, `file_edit`, `turn_end`, `recovery_state`, … | `docs/websocket.md` in the gateway repo |
| History: `GET /api/sessions/{key}/webui-thread` | `nanobot/webui/transcript.py` |
| Writes: socket only, except token-gated `POST /api/attachments` | `nanobot/webui/ws_http.py` |

A bootstrap token is single-use, so every reconnect bootstraps again. The
engine obeys that rule; it is not a bug to paper over.

## Building and running

Prerequisites: JDK 21+, the Android SDK, Xcode 27 with an iOS 17+ runtime, and
a nanobot gateway you can reach.

```bash
tools/all-tests.sh                                               # every gate, in order, stops at the first red
./gradlew :core:allTests :core-cli:test :app:testDebugUnitTest   # or just the unit suites
./gradlew :app:installDebug                                      # Android on the emulator/device
tools/run-on-device.sh --local                                   # iOS on a plugged-in iPhone
```

The Xcode project is checked in as a hand-written `project.pbxproj`. The
framework arrives through a Run Script phase that calls
`./gradlew :core:embedAndSignAppleFrameworkForXcode`. No CocoaPods, no
XcodeGen, no Tuist.

### The iOS app on your own Mac

There is no App Store build, by choice: distribution needs a paid Apple account, and this app talks to a gateway
you run yourself. What you can do is build it on your own Mac in a few minutes — **the simulator needs no Apple
account at all**, and a plugged-in iPhone needs nothing more than your free Apple ID.

```bash
git clone https://github.com/La-Volpe/nanobot-companion && cd nanobot-companion
tools/ios-self-build.sh --check          # Xcode, JDK 21, a simulator runtime: what is missing, if anything
tools/ios-self-build.sh --simulator      # build, install and launch on the iPhone 17 simulator
tools/ios-self-build.sh --device --team YOURTEAMID   # or your own iPhone
tools/ios-self-build.sh --open           # the by-hand path: Xcode, scheme iosApp, Run
```

Requirements are Xcode with an iOS runtime and **JDK 21** (the Kotlin framework is built by Gradle inside Xcode's
run-script phase). The first build downloads Gradle and the Kotlin/Native toolchain once, so it takes a few
minutes; after that it is seconds.

The project carries no `DEVELOPMENT_TEAM` on purpose — it once named two Apple accounts in a public repository — so
a device build asks *you* for a team. Your free Apple ID's personal team works: Xcode → target `iosApp` →
Signing & Capabilities → Team. That profile expires after seven days; rebuild from Xcode to renew it. An app you
installed that way stops launching the moment the profile lapses, which is the one surprising thing about the free
route.

A simulator reaches a gateway on the same Mac at `http://127.0.0.1:18765`; a phone needs the LAN address
(`tools/nanobot-dev.sh start --lan` prints it) and both machines on the same network.

For a gateway of your own:

```bash
tools/nanobot-dev.sh start --lan     # real nanobot, throwaway config, LAN address printed
tools/nanobot-dev.sh stop
```

It writes everything to `.dev/` (venv, config, secret, profile, logs) and
never touches `~/.nanobot`. Point the app at your own gateway or at that LAN
address; the secret is the gateway's WebUI secret.

## Testing

The coverage badge is a snapshot: **95.64%** (1,951 of 2,040 lines) on 2026-11-18 from the
[Kover report for `38f4834`](https://github.com/La-Volpe/nanobot-companion/actions/runs/44242359151/job/134593084193).
It covers `wire`, `projection`, and `engine` in `:core`, not either app's UI or the whole repository.

Counts are what the suites reported on 2026-11-17, read from `core/build/test-results/`,
`app/build/test-results/` and the Xcode `.xcresult` of a full run; re-read them the same way if you want to
know whether they are still true.

```bash
./gradlew :core:allTests                    # 444 jvm · 326 iOS sim · 312 android host
./gradlew :core:jvmTest -Pcontract          # against a live gateway (10 tests across three suites)
./gradlew :core:koverVerify                 # 85% floor on wire/projection/engine
./gradlew :app:testDebugUnitTest            # 173
./gradlew :app:connectedDebugAndroidTest    # 83 on the emulator
cd iosApp && xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' test
                                            # 210 swift-testing + 57 XCUITest
```

### What CI runs, and what it deliberately does not

| Workflow | When it runs | What it checks |
|---|---|---|
| [Checks](https://github.com/La-Volpe/nanobot-companion/actions/workflows/ci.yml) | Every PR to `main`, every merge to `main`, or on demand | Core Android host tests, CLI and Android unit tests, instrumented test compilation, and string parity. This is the required PR check. |
| [Apple checks](https://github.com/La-Volpe/nanobot-companion/actions/workflows/apple-checks.yml) | Relevant Core, iOS, or build/CI PRs, or on demand | Core JVM and Kotlin/Native tests, Kover, and Swift unit tests. |
| [Android UI tests](https://github.com/La-Volpe/nanobot-companion/actions/workflows/android-ui.yml) | On demand | Compose instrumented tests on an emulator. |
| [Release](https://github.com/La-Volpe/nanobot-companion/actions/workflows/release.yml) | Manually from `main` | All of the above, XCUITest, and the two published assets: a signed universal APK and the CLI ZIP. It refuses an unsigned APK and checks both archives for the license and third-party notices. |

`main` is protected; submit changes through a PR. To cut a GitHub release, run **Release** with a new
`vMAJOR.MINOR.PATCH` tag and turn on `publish`. The workflow tags and publishes the exact tested commit only
when every job passes. Leave `publish` off to run the same tests without creating a tag or release.

A release has three assets:

- `nanobot-companion.apk` — signed with the repository's release key, universal: arm64-v8a, armeabi-v7a,
  x86_64 and x86 in one file, so it installs on a phone, a tablet and an emulator alike. Android 8 or later.
- `core-cli.zip` — the command-line client.
- `nanobot-companion-ios-<version>.zip` — the tested source, one folder, with `tools/ios-self-build.sh` and a
  `BUILD-IOS.txt` at the root: unzip it on a Mac, run the script, and the iOS app builds, installs and launches.
  No Apple account is needed for the simulator.

Both need the four `NANOBOT_*` signing secrets (see `AGENTS.md`); without them the packaging job still builds the
APK for the gate to inspect, and the publish job refuses to attach an unsigned one rather than shipping an
uninstallable APK.

Hosted jobs use no gateway or signing credentials. The live contract (`:core:jvmTest -Pcontract`) and the
instruments below run separately on a machine with a dev gateway. Use them before releasing a change to
gateway behavior.

Instruments that are not unit tests, and earn their keep:

- `tools/session20.sh`: 20-minute live session through the core (`beats=8, failures=0`)
- `tools/soak.sh`: 30 minutes, 200 sends, 50 reconnects, leak check
- `tools/android-session.sh`: the same beats on the emulator, through the app
- `tools/parity-check.sh`: CLI vs iOS vs Android transcript, plus error copy
- `tools/consult.sh`: ask clef, a decision model, to choose between two options

## Layout

```
core/                :core, the KMP library (no UI, no Compose, no UIKit)
core-cli/            :core-cli, the harness that talks to real gateways
app/                 :app, Jetpack Compose
iosApp/              SwiftUI + the Xcode project
tools/               dev gateway, devices, soak, parity, consultations
scripts/gate.sh      runs a phase checklist kept outside the repo; refuses to pass while a box is open
docs/diagrams/       the diagrams above, rendered to SVG (regenerate with mmdc)
```

The working record (plan, phase checklists, decision log, open questions) stays
outside the repository. It is how the work was tracked, not what the project
ships.

## How this was built

With an agent loop held to a written plan: TDD per task, one commit per task
with the failing run in the message, and phase gates that were only ticked after
the evidence had been re-run. Two gates were *held* rather than ticked because
the instruments found real defects: an app that did not replay history on
reconnect, and an iOS composer that silently dropped attachments. Both were
fixed with red-then-green tests.

`git log` is the durable record: one commit per task, each naming what it
changed and, where a red was weak, saying so. The plan, the checklists, the
decision log and the list of open questions were working files and are not
shipped.

## Licence

MIT, see [LICENSE](LICENSE). The work of the projects this app is built on or
copies from is in [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt): nanobot's
MIT notice for the day/night mark and the product glyphs, Lucide's ISC plus the
Feather attribution for the seventeen WebUI icons the generator reads, Open
Sans' OFL, pele's MIT with the Lucide icon bundled inside it, the Apache-2.0
runtime set (Kotlin, kotlinx, Ktor, OkHttp/Okio, AndroidX and what Ktor pulls
in) and SLF4J's MIT. Both apps bundle those two files, so the text travels with
the binary rather than only with the repository.

## Notes for agents (and the curious)

`AGENTS.md` is the working guide: commands, the version matrix, twenty-odd
gotchas that were already paid for, and the review/QA bar. `HUMANS.md` is the
coffee-break version. The best code here is the code it does not contain: no
markdown library, no DI framework, no date library, no image loader, and one
third-party runtime dependency (Ktor, whose engines are the platforms' own).
