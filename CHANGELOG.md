# Changelog

All notable changes to Android DJ Tools are documented here.

## Unreleased

No unreleased changes are documented after `v0.1.2` yet.

## 0.1.2 - 2026-09-18

Playback repair release.

### Fixed

- The shipping launcher no longer wires the state-only fixture playback provider. It now discovers the phone's local music through Android MediaStore and resolves stable device-media track IDs to playable content URIs.
- Library, detail, waveform, queue, and offline Play actions now reach a real Media3/ExoPlayer transport with audio focus, becoming-noisy handling, seek, pause/resume, and live position publication.
- Android 13+ now requests `READ_MEDIA_AUDIO`; Android 9-12 use the legacy read-audio permission path. Permission denial is surfaced as an explicit provider error instead of presenting simulated playable tracks.
- Hosted screenshot extraction now copies PNG bytes directly through binary-safe `adb exec-out` instead of an Android/base64 round-trip that could corrupt CI extraction after the screenshot test had already passed.

### Verification

- Fixture providers remain isolated for deterministic UI and instrumentation tests.
- Added JVM regression coverage proving that a resolved track causes the playback engine to load and start the requested URI, while an unresolved track cannot report itself as playing.
- The normal release gate still requires JVM tests, instrumentation-test compilation, lint, APK assembly, hosted API-35 conformance, and version/tag consistency before GitHub Release publication.

## 0.1.1 - 2026-09-17

Patch release integrating the post-0.1.0 preparation, sync, qualification, and CI hardening lanes.

### Added

- Durable offline mutation journal and conflict-resolution foundations with process-safe/idempotent receipt semantics, deterministic no-overtake replay ordering, and process-recreation recovery.
- Direct Sample Lib transport and deterministic fake-server qualification for pairing/auth, opaque cursor reset, receipt recovery, offline/unavailable/ambiguous delivery recovery, and resumable integrity-checked media access.
- Cue/loop and beat-grid preparation editing, including fail-closed half/double BPM correction and locale-stable BPM entry.
- Offline download/cache foundations plus DJXML interoperability qualification across the Sample Lib authority boundary, including offline cue/loop/metadata/namespaced-tag intent surviving process recreation and reconciling only from authoritative receipts.
- Sanitized advisory analysis transport for BPM/key/related candidates with provenance/freshness identity and receipt-gated acceptance semantics.
- Android 15 / Pixel 7 Pro CI screenshot rendering for the five primary app surfaces, with PNG validation and failure diagnostics.
- Release-gate evidence and CI qualification surfaces; parity refresh and device-only accessibility/performance work remain explicit post-release follow-ups.

### Changed

- CI now checks that `versionName`, changelog release metadata, and a pushed `v*` tag agree before building release artifacts.
- APK workflow artifact names now derive from the app version instead of embedding an old release number.
- DJXML qualification fails closed when canonical playlist/item identity and ordering are unavailable or regress upstream.
- Safe field-wise conflict auto-merge now requires authoritative versioned `merge_safe_fields` evidence, disjoint local/remote edits, and declared-safe keys; all unsafe/ambiguous conflict classes remain user-visible.

### Verification

The final `v0.1.1` release gate requires all of the following on the integrated tree before tag/publish:

- sync protocol/fake-server tests;
- focused real Sample Lib DJXML adapter and playlist-authority qualification;
- Android JVM unit tests;
- Android instrumentation-test source compilation;
- Android lint;
- debug APK assembly and SHA-256 capture;
- Git whitespace/diff validation;
- release metadata/tag consistency validation.

### Known limitations

- The attached APK remains an unsigned debug dogfood artifact, not a Play Store distribution build.
- Local host qualification does not claim physical-device TalkBack, lock-screen/notification/Bluetooth/audio-focus, or sustained frame-time/jank behavior unless the final evidence packet names an exercised target.
- The refreshed EPIC-18 ledger covers all 87 required stories as 49 pass, 13 partial, and 25 blocked, with no missing or failing entries. The blocked/partial stories are kept explicit rather than promoted: the local run has no attached Android device and hosted API-35 CI evidence is not yet observed at this release-preparation commit. The release-critical Lane B workflow review is independently approved; the separate exact-snapshot parity review remains a full-parity qualification gate rather than a claim made by this patch release. v0.1.1 is therefore not presented as full product parity.
- AGP 8.5.2 warns that compileSdk 35 is newer than its certified compileSdk 34 range; this warning is retained rather than hidden.

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
