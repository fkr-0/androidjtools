# Changelog

All notable changes to Android DJ Tools are documented here.

## Unreleased

Post-0.1.0 integration and autonomous continuation.

### Added

- Durable offline mutation journal and conflict-resolution foundations with process-safe/idempotent receipt semantics.
- Direct Sample Lib client/fake-server qualification including pairing/auth, opaque cursor reset, receipt recovery, and resumable integrity-checked media access.
- Offline download/cache foundations, DJXML interoperability qualification, and cue/loop preparation editing/test surfaces.
- Canonical Wave Four and Wave Five guarded work lanes for analysis/related intelligence, Android media integration, accessibility/performance, parity E2E, and packaging/upgrade release work.
- Durable Wave Three/Four preflight and operator handoff documentation.

### Verification

- Sync protocol/fake-server suite: 26/26 passing.
- Sample Lib fake integration suite: 7/7 passing.
- JDK 17 Android qualification passes `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug`, and `:app:assembleDebug`.
- `git diff --check` passes.

### Still gated / not claimed

- Running canonical agents still own waveform/library closeout, beatgrid, cue/loop, mutation journal, Sample Lib, offline-download, DJXML and the guarded Wave Four/Five dependency chain. A running OCP task is dispatch evidence, not independent acceptance.
- Real-device/emulator runtime, TalkBack, lock-screen/notification/Bluetooth/audio-focus, frame-time/jank, and real Sample Lib production evidence remain unclaimed unless a named target is actually exercised.
- AGP 8.5.2 continues to warn that compileSdk 35 is newer than its certified compileSdk 34 range; the verified build remains green.

## 0.1.0 - 2026-09-15

First preview release.

### Included

- Fixture-first Kotlin/Jetpack Compose application shell for library, collections, preparation, analysis, and sync surfaces.
- Dense library/search preparation UX, collections and metadata editing workflows, waveform preparation interactions, and player/queue foundations.
- Debug-only deterministic dogfood harness for provider, dataset, sync-state, failure, mutation, receipt, and backend-health inspection.
- Versioned cloudless Sample Lib sync protocol v1 with opaque revisions/cursors, explicit compatibility negotiation, idempotent mutation receipts, conflict semantics, and deterministic fake-server qualification.
- GitHub Actions verification using JDK 17 with protocol tests, Android unit tests, instrumentation-test compilation, lint, debug APK assembly, and APK artifact upload.

### Known limitations

- No Android device or emulator target was available during local qualification, so device-backed instrumentation and runtime-launch evidence are not claimed.
- The concurrently developed cue/loop, beatgrid, direct Sample Lib, offline-download/cache, and DJXML implementation lanes are not part of the `v0.1.0` release snapshot. They continue under their canonical workflow tasks after this tag.
- Real Sample Lib connectivity is therefore not claimed by this release; fixture/fake-server behavior remains the reproducible backend baseline.
- The release APK is an unsigned debug artifact intended for dogfooding and development, not Play Store distribution.
