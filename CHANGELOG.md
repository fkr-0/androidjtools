# Changelog

All notable changes to Android DJ Tools are documented here.

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
