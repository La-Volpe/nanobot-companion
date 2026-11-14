# nanobot Companion

A native iOS and Android client for a [nanobot](https://github.com/HKUDS/nanobot)
gateway. Kotlin Multiplatform core with no UI, then SwiftUI, then Jetpack Compose —
in that order, gated. See [plan.md](plan.md) for the whole plan, including the TDD
contract and the phase gates in [GATES.md](GATES.md).

Design of record: `~/sandbox/nanobot-app-design/index.html`.
Gateway of record: `~/Dev/nanobot` at `28d73b69` (v0.4.1).

## Layout

```
core/       KMP library — protocol, projection engine, state. No UI.
core-cli/   JVM harness: drives :core against a real gateway, records fixtures.
gradle/     version catalog (libs.versions.toml)
tools/      nanobot-dev.sh — throwaway local gateway for contract tests
scripts/    gate.sh — reads GATES.md, refuses to pass while unchecked
iosApp/     SwiftUI app            (phase 2, not created yet)
app/        Jetpack Compose app    (phase 3, not created yet)
```

## Commands

```bash
./gradlew :core:allTests                        # jvm + iosSimulatorArm64 + android host
./gradlew :core:jvmTest -Pcontract              # against a live local gateway
./gradlew :core-cli:run --args=--help

tools/nanobot-dev.sh start                      # .dev/gateway on 127.0.0.1:18765
tools/nanobot-dev.sh status
tools/nanobot-dev.sh stop

scripts/gate.sh core                            # phase gate status
scripts/gate.sh core --run                      # run the mechanical checks first
```

The dev gateway writes its state to `.dev/` (gitignored): config, workspace,
bootstrap secret, profile, venv, log. It never touches `~/.nanobot`.

## Current state

Phase 0 (scaffolding) is in place: build, three test targets, CLI, dev gateway,
gate checker. No product code yet — the first Red test is `FrameCodecTest` (plan
C2.1).
