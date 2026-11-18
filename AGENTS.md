# AGENTS.md — nanobot Companion

Guide for AI agents (and humans) working in this repo: what the boundaries are,
the commands that prove a change, and the gotchas already paid for. Read it
before changing anything.

## What this project is

A native iOS and Android client for a **nanobot** gateway. `:core` owns
everything that is not UI, in Kotlin Multiplatform; the two apps render it
natively: SwiftUI (`iosApp/`) and Jetpack Compose (`:app`).

```
core/       :core — KMP library, no UI, no Compose, no UIKit
            wire · transport · projection · engine · facade · platform (expect/actual) · bridge
core-cli/   :core-cli — drives :core against real gateways; records fixtures; hosts the instruments
app/        :app — Compose UI only (plain Android application module)
iosApp/     SwiftUI + a hand-written iosApp.xcodeproj (no CocoaPods, no XcodeGen, no Tuist)
tools/      dev gateway, device install, soak, session, parity, clef consultations
scripts/    gate.sh — runs a phase checklist kept outside the repo; refuses to pass while a box is open
```

Data flow, in one line: gateway socket → `wire` frames → `projection` reducer →
`engine` effects → `facade` state → SwiftUI/Compose. Stored history goes through
**the same reducer**.

Two rules shape every diff:

1. **If it is not UI, it lives in `:core`.** Adding logic to `:app` or
   `iosApp/` is a defect, even if it works. `ArchitectureTest` enforces the
   layers and `LayeringTests` enforces that only the adapter imports `core`.
2. **The gateway is the contract.** When the client and the plan disagree with a
   live gateway, the gateway wins, and the plan is amended with evidence. The
   plan, the checklists and the decision log that tracked this build are working
   files kept outside the repo; `git log` is what ships.

## Commands

```bash
# the five gate commands, in order, stopping at the first red
tools/all-tests.sh                      # --skip-device, --skip-ios, --with-contract, --dry-run

# or one at a time — everything that runs without a device
./gradlew :core:allTests :core-cli:test :app:testDebugUnitTest

# per target, when you need to see one
./gradlew :core:jvmTest --tests "*TurnReducer*"
./gradlew :core:iosSimulatorArm64Test --tests "*Keychain*"
./gradlew :core:testAndroidHostTest --tests "*Keystore*"

# coverage (85% floor on wire/projection/engine; measured 95.7%)
./gradlew :core:koverVerify

# live-gateway contract tests (needs tools/nanobot-dev.sh up)
./gradlew :core:jvmTest -Pcontract

# Android app: unit, then the connected suite on emulator-5554
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest

# iOS: unit + XCUITest on the simulator
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' test

# a gateway of your own (throwaway, under .dev/)
tools/nanobot-dev.sh start            # loopback
tools/nanobot-dev.sh start --lan      # binds 0.0.0.0, prints the LAN address, for a phone
tools/nanobot-dev.sh stop

# the instruments
tools/session20.sh                    # 20-minute live session through :core
tools/soak.sh                         # 30 min, 200 sends, 50 reconnects, leak check
tools/android-session.sh              # the same beats on the emulator, through the app
tools/parity-check.sh                 # CLI vs iOS vs Android transcript + error copy

# phases
scripts/gate.sh core|ios|android      # checklist status; --run runs the mechanical gates
```

There is no GUI Simulator on this machine (`Xcode.app/Contents/Developer/
Applications` is absent), so the iOS app is driven headlessly:

```bash
xcrun simctl boot "iPhone 17"
xcrun simctl launch "iPhone 17" de.arjmandi.nanobot.companion   # env via SIMCTL_CHILD_*
xcrun simctl io "iPhone 17" screenshot /tmp/s.png
xcrun simctl io "iPhone 17" recordVideo --codec=h264 /tmp/v.mp4
```

Launch environment the app understands (DEBUG builds): `NANOBOT_LIVE_URL`,
`NANOBOT_LIVE_SECRET`, `NANOBOT_LIVE_UNVERIFIED`, `NANOBOT_SCENARIO`,
`NANOBOT_START_CONNECTED`. On a device, `tools/run-on-device.sh` sets the first
two for you.

## Version matrix (verified together as of 2026-11; bump carefully)

| Layer | Choice |
|---|---|
| Kotlin | 2.4.21 |
| Gradle | 9.7.0 (wrapper) |
| AGP | 9.5.1 (`:core` uses `com.android.kotlin.multiplatform.library`) |
| Ktor | 3.6.1 (Darwin / OkHttp / Java engines), the only third-party runtime dependency |
| coroutines / serialization | 1.11.0 / 1.13.0 |
| Kover | 0.9.9, configured **inside `:core`** |
| compileSdk / minSdk | 37 / 26 |
| iOS | 17.0 minimum; Xcode 27.2, iOS 26.5 runtime |
| JVM target | 21 (the Gradle daemon runs on JBR 25, so `:core-cli` pins Java 21 explicitly) |
| Gateway of record | nanobot 0.4.1 @ `28d73b69`, protocol 1 |

All pins live in `gradle/libs.versions.toml`, with the resolution date in a comment.

## Gotchas already paid for (do not regress)
1. **compileSdk must be 37.** `okhttp-android:5.5.1`, pulled by Ktor's OkHttp
   engine, declares an AAR metadata floor of 37; 36 fails
   `checkAndroidMainAarMetadata`.

2. **AGP 9 for a KMP module**: `kotlin { androidLibrary { namespace; compileSdk;
   minSdk } }`, no top-level `android { }`. For a plain app module, AGP 9 has
   built-in Kotlin, so applying `org.jetbrains.kotlin.android` is a hard error.

3. **Android host tests exist only if you ask for them**: `androidLibrary {
   withHostTestBuilder {}.configure {} }` creates `:core:testAndroidHostTest`.
   The plan's old `testDebugUnitTest` name is wrong for `:core`.

4. **No `Dispatchers.Main` in host unit tests.** A view model that reads
   `Dispatchers.Main` in its constructor fails every test in that class with
   *"Dispatchers.Main was accessed when the platform dispatcher was absent"*.
   Inject the scope/dispatcher (this was a real defect in A5, not a test quirk).

5. **Kover configuration must live in the module.** A root-level `kover { }`
   only configures the root report, so class excludes silently do nothing until
   it moves into `core/build.gradle.kts`. The 85% floor is real: raise it
   to 100 and the rule fails naming the percentage.

6. **The layer guard scans file bodies, not just imports.** A fully-qualified
   reference (`projection.ThreadState::class`) escaped an import-only scan; the
   guard now looks for qualified paths too and ignores comments and strings. Test source sets are exempt by design.

7. **`explicitApi()` is on.** Every public declaration needs explicit visibility
   and type, because Swift and Compose both consume the facade. `EngineHost` is
   an abstract class for this reason: `explicitApi` rejects `internal` interface
   members.

8. **`expect/actual` is limited to four concerns**: `Clock`, `SecureStore`,
   `FileStore`, `CoreDeps`. `TlsPolicy`/`TlsTrustRule` live in `platform`
   because `platform` may not import `transport`.

9. **`ready` means the greeting was processed, not that it arrived.** The engine
   waits for the frame to be reduced before it reports `Open`, and
   `ready.chat_id` is a bare uuid while session rows are keyed `websocket:<uuid>`.
   Both were real bugs the live contract run found.

10. **A bootstrap token is single-use.** Every connect bootstraps again; reuse
    fails the handshake. Do not cache a token.

11. **`attach` carries no history.** An opened chat shows its past only after
    `GET /api/sessions/{key}/webui-thread` is read and reduced
    (`ChatHandle.open()`). Binding a handle is not opening it, and that
    distinction broke `core-cli watch --chat` for a while.

12. **Stored pages are compacted.** A turn replays as
    `user_message · reasoning_end · stream_end · turn_end`, with the answer text
    only in `stream_end.text` and the reasoning only in `reasoning_end.text`.
    Any reducer change must be checked against a *compacted* page, not only
    against the dev gateway's raw ones.

13. **`quoted_context` is a string.** An object-shaped quote is dropped by the
    gateway silently: no error, no quote.

14. **Unknown frames and unknown fields must never throw.** Events are sealed
    classes over the preserved raw JSON, `ignoreUnknownKeys = true`, and an
    unrecognised event is `UnknownFrame`. A future gateway must degrade the
    feature, not crash the app.

15. **Capabilities are at `terminal.webui.capabilities`.** `runtime_capabilities`
    is a dict of host action flags (`can_restart_engine`, …) and has nothing to
    do with protocol features.

16. **Timestamps are ISO-8601 strings on session rows** and epoch millis on
    event frames. The parser is hand-written (no date library) and pinned by a
    test at the boundary.

17. **Markdown is parsed here, rendered natively.** No markdown library: the
    parser returns a `MarkdownDocument`, and streaming uses the stable-blocks /
    unstable-tail split so half an answer does not flash as broken markup.

18. **iOS has no Simulator.app on this machine.** Drive it with `simctl`; the
    app is invisible unless you screenshot or record. Do not conclude an app is
    broken because you cannot see it.

19. **`CODE_SIGNING_ALLOWED = NO` breaks device installs.** The project carried
    it as a simulator-era shortcut and the .app shipped unsigned, and installd's
    words were "No code signature found". It is removed; do not put it back. A
    device install also needs `NSAllowsLocalNetworking` in `Info.plist` for
    plain HTTP to a LAN gateway, and an Apple ID signed into Xcode to mint a
    profile, otherwise the build stops at "No Account for Team". The project
    carries **no** `DEVELOPMENT_TEAM` on purpose (T25): it used to hold two
    team ids, which named two Apple accounts in a public repository. Pick your
    own team in Xcode for a device build, or sign with an xcconfig you keep
    out of git. Simulator builds and `build-for-testing` need nothing.

20. **Android blocks cleartext at targetSdk 37.** `network_security_config.xml`
    permits it for the dev gateway; without it the first connect fails as
    `unreachable`. The emulator reaches the host at `10.0.2.2`, not `127.0.0.1`.

21. **XCUITest traps.** A container `accessibilityIdentifier` hides every child
    id; `onChange` does not fire for a value set before the view exists; live
    tests need the `TEST_RUNNER_` prefix on their environment variables; and a
    fast model can finish a turn before a stop tap lands, which is a race in the
    instrument, so pick a prompt that stays open. The scripted launch follows the
    same rule with knobs instead of prose: `NANOBOT_SCRIPTED_TURN_SECONDS` and
    `NANOBOT_SCRIPTED_VOICE_SECONDS` hold a reply open when a test has to type
    during it. A four-second script is a constant a loaded runner can outlast —
    the queue and transcription cases failed on the hosted machine for exactly
    that reason and passed here. A row that dismisses a sheet also needs the
    dismissal waited for (`waitForNonExistence`) before the surface under it is
    read, not a longer poll on the surface.

22. **The dev gateway needs help to start.** `NANOBOT_SKIP_WEBUI_BUILD=1`
    (otherwise `nanobot webui` runs npm install/build), and a Python 3.12 venv
    under `.dev/` because the system `python3` has none of nanobot's deps.

23. **The instruments are not optional garnish.** `session20.sh`, `soak.sh` and
    `parity-check.sh` found the reconnect bug and the dropped-attachment
    bug that every unit test passed through. If a change touches the
    wire, the session is part of the proof.

24. **A gate box is ticked with evidence, not with a claim.** Re-run the
    command, paste its output under the item, then tick. Two gates in this
    project were deliberately left open until a defect was fixed.

25. **The glyphs are nanobot's, not the platform's.** `tools/webui-icons.py`
    reads the WebUI's own drawings (`components/icons/product-icons.tsx` plus
    the lucide icons it imports) and writes both the iOS template imagesets
    (`Assets.xcassets/Icon*.imageset`) and the Android vector drawables
    (`res/drawable/ic_webui_*.xml`). Adding an SF Symbol or a Unicode character
    where a WebUI glyph exists is a regression: the phone stops matching the
    browser. The generated files are committed; regenerating needs a gateway
    checkout. The only survivors on purpose are the debug ladybug (ours) and the
    `−N` diff marker (a number, not an icon).

26. **Colour has one source per platform and it is the design's tokens.**
    `Palette` (iOS, `Brand.swift`) and `NanobotTheme` (Android, `ui/Theme.kt`)
    carry the same values: charcoal `hsl(0 0% 19%)` in dark, warm neutrals in
    light, monochrome actions, orange only as identity. A
    `Color(.systemBackground)` in a screen or a hand-picked hex in a row is how
    the app drifts back to looking like a bare shell.

27. **The iosApp target is a file-system-synchronized group**, so a new asset
    catalog or a new Swift file needs no `project.pbxproj` entry, but a new
    *kind* of resource still needs its build setting
    (`ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`). Without it the bundle has
    no icon and SpringBoard draws a grey grid; `BrandTests` fails on exactly
    that, which is how the setting was proved necessary.

28. **A simulator or emulator can stop raising a software keyboard, and both do.**
   With no Simulator GUI, iOS reports a hardware keyboard attached
   (`HardwareKeyboardLastSeen=1`) and draws no software keyboard: the composer never
   moves and the keyboard element sits off-screen with a plausible frame. Android's
   emulator does the same: `mInputShown=false` after a tap that should raise it,
   with the IME listed and enabled, and neither `show_ime_with_hard_keyboard=1` nor
   an IME disable/enable cycle nor `adb reboot` bringing it back; a cold boot of the
   AVD does. A dismissal test failing there is describing the host, not the app.
   iOS `KeyboardTests` measures both facts (composer moved? keyboard frame inside the
   window?) and skips with the numbers, and Android's five keyboard cases fail as a
   class on the same tree that passed 80/0 an hour earlier. Check that before
   believing a regression.

## Conventions

- **TDD, and a red that means something.** Write the assertion, watch it fail
  for a behavioural reason, then implement. A compile error is not a red, so
  when it is the only red a task had, say so in the commit body (`[C3.2]`,
  `[C3.6]` do exactly that).
- **Prove a guard non-vacuous by breaking it.** Delete the teardown, import a
  higher layer, raise the coverage floor to 100, doctor one transcript line.
  Every guard in this repo has been broken on purpose once.
- **One commit per plan task**, message `[C4.2] reducer: streaming segments from
  frames`, body carrying the red evidence and, where files serve several tasks,
  which ones.
- **Effects are data.** Reducers return `Reduction(state, effects)`; the engine
  executes them. No clock, no logging, no coroutines inside a reducer.
- **Pure Kotlin in `commonMain`.** No `androidx`, no `UIKit`, no `SwiftUI`, no
  Compose. Checked by test, not by intention.
- **The apps are dumb.** One state in, commands out. Anything with a branch
  worth testing lives in a view model with `kotlinx-coroutines-test` (Android)
  or `swift-testing` (iOS).
- **A glyph is an asset, not a character.** `☰`, `⋮` and `📎` stood in for
  icons until the WebUI port; they render differently on every device and never
  match the browser. Generate from `tools/webui-icons.py`.
- **Strings live per platform, keyed the same.** `ConnectionCopy.swift` and
  `res/values/strings.xml` must agree key for key, and `tools/parity_copy.py`
  under `parity-check.sh` compares them.
- **Comments explain why.** The repo is full of short comments naming the
  gateway file or the incident that forced a line. Keep that habit; no banners,
  no doc-comment wallpaper.
- **Never edit `~/Dev/nanobot`.** It is the read-only gateway of record.
- **The durable record is `git log`**: one commit per task, with the red run in
  the body. The plan, checklists, decision log and open-questions list are
  working files and stay out of the repo; say the same thing in a commit body
  that you would have put in them.

## Code review standards

When asked to review this codebase, meet this bar:

- Read the source and the tests before judging. Never review from memory.
- Anchor every claim as `file:line`. An unanchored claim is an opinion.
- Judge against four criteria, each with a verdict:
  1. Layering and purity: `commonMain` free of UI and platform imports, one
     reducer serving socket and history, effects as data. Deviations named with
     severity.
  2. Protocol fidelity: every frame and field traced to the gateway source
     or a recorded fixture; capability-gated features degrade instead of
     crashing; no invented endpoints.
  3. Tests with red evidence: inventory the suites, name the real gaps,
     quote the coverage numbers, and say plainly where a red was a compile error
     or a probe rather than a first-run failure.
  4. Not overengineered: no speculative abstraction, no dependency that
     replaces ten lines of standard library, no test that passes with the
     implementation deleted.
- Review-only: change nothing. The deliverable is a ranked, sized list of
  findings. Flattery gets rejected; so does a finding without evidence.

## CI and releases

- **`main` is PR-only**, including for admins. Work on a branch and open a PR;
  never push, force-push, or commit directly on GitHub to `main`. The Linux
  `Fast checks / Linux tests` check is required before merging, and **Checks**
  runs it again on the merged `main`. This covers Core Android host tests,
  CLI tests, Android unit tests, compilation of the Android instrumented
  tests, and copy parity. It does not boot an emulator.
- **Apple checks** runs on relevant PRs (Core, iOS or shared build/CI changes),
  or on demand. It covers Core JVM and Kotlin/Native, Kover, and Swift unit
  tests. Review the result on relevant PRs; it is not required on every PR.
  For Android UI changes, run **Android UI tests** or use the local emulator.
- **Release** is the only supported publication path. Merge the changes, then
  dispatch it from `main` with a new `vMAJOR.MINOR.PATCH` version. Leave
  `publish` off for a dry run; enable it to tag and publish the exact tested
  commit only after the full offline suite passes. It runs the checks above,
  Android connected tests, XCUITest, and packaging: a debug-signed APK for
  testing, unsigned release APK/AAB, and CLI ZIP, all with license and notice
  checks. A missing or empty device suite is a failure;
  individual hardware-dependent and opt-in skips remain visible in reports.
  If publishing fails *after* tag creation, inspect the run and tag before
  taking any further release action; never retag a different commit.
- **Android release signing is four repository secrets**, and nothing else:
  `NANOBOT_KEYSTORE_BASE64` (the `.jks`, base64), `NANOBOT_KEYSTORE_PASSWORD`,
  `NANOBOT_KEY_ALIAS`, `NANOBOT_KEY_PASSWORD`. With all four the Release
  workflow signs both the APK and the AAB and refuses a debug certificate; with
  none it builds the same artefacts **unsigned** and says so in the run and the
  release notes. With some, `app/build.gradle.kts` stops at configuration
  rather than shipping half a key. Generate one with
  `keytool -genkeypair -v -keystore release.jks -alias nanobot -keyalg RSA
  -keysize 4096 -validity 10000` (the password and alias then go in as secrets),
  encode with `base64 -i release.jks | pbcopy`, and keep the original file out
  of the repository and out of your backups of it — losing it means every
  installed copy can no longer be updated. `versionCode` in
  `app/build.gradle.kts` still has to be raised by hand before a store upload.
- Hosted CI otherwise receives no gateway, signing, or store credentials beyond
  those four. Run the live contract and instruments locally when gateway
  behavior changes, before dispatching the release. A GitHub release is not an
  app-store upload: Google Play wants the `.aab` and its own upload key.

## QA standards

Before release or for full local verification, all applicable checks must pass:

1. `./gradlew :core:allTests :core-cli:test :app:testDebugUnitTest`
2. `./gradlew :app:connectedDebugAndroidTest` (emulator required)
3. `xcodebuild test` on the simulator (unit + XCUITest)
4. If the wire changed: `./gradlew :core:jvmTest -Pcontract`, `tools/session20.sh`,
   and `tools/parity-check.sh`
5. `./gradlew :core:koverVerify`

Beyond the suites, a UI change is swept by hand and recorded: connect with a bad
key, connect with a dead gateway, a turn with tools and a diff, a stop, a
reconnect mid-turn, an upload over the limit, a dismissed notice. Assert counts
and labels from the accessibility tree rather than trusting a screenshot; when
the environment changed (fresh install, rebooted device), start the sweep over.

## When adding a feature, the expected diff shape

1. **Wire**: the frame or payload in `:core/wire`, decoded tolerantly, with a
   fixture recorded by `:core-cli record`, not typed by hand.
2. **Projection**: the reducer branch, pure, with `Effect` values for anything
   that talks to the socket.
3. **Engine**: execute the effect, keep the one-writer rule, no new coroutine
   that outlives the chat.
4. **Facade**: state on `ChatHandle`/`NanobotCore`, a `suspend` command with a
   typed result (`Accepted`/`Rejected(reason)`), `@Throws` where Swift needs it.
5. **Both UIs**: view model + screen for Compose and SwiftUI, platform mapping
   from the design README (48 dp / 44 pt, M3 or iOS controls), strings keyed
   identically in both tables.
6. **Tests**: core test in `commonTest` (or `jvmTest` when it needs a server),
   app unit test, XCUITest only where a tap is the point.
7. **Gates**: the five QA commands above; a note in the decision log if the
   choice was not obvious; and no gate ticked before its command has been
   re-run.
