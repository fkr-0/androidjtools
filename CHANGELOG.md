# Changelog

All notable changes to Android DJ Tools are documented here.

## Unreleased

### Sample Lab integration (S1: connect and read)

- Pair with a Sample Lib server from the Sync screen; the server's library, cues and loops are synced into a local cache and shown in the Library. The Sync screen lists which server capabilities (library, playlists, editing, analysis, audio) are and are not available, and why.
- The credential is encrypted with an Android Keystore key and stored outside backups; `allowBackup` is now `false`, and cleartext traffic is limited to loopback.
- Without server audio (older servers, or before S2), server tracks cannot be played; device tracks stay visible and playable. Play and Load controls are disabled for server tracks with a reason text on every screen, and a banner above the navigation explains why. Tapping a server track through the playback provider still triggers the explanation as a safety net.
- Server addresses with a reverse-proxy path prefix now work (the sync endpoints no longer drop the prefix).
- Periodic background sync (unmetered networks) and manual refresh; Forget server removes the profile, credential and cache.

### Known limitations

- Non-loopback servers need HTTPS (plain `http://` is refused except loopback).
- `allowBackup=false` means app preferences such as the playback queue are not restored from backups.
- Server tracks can only be played when the server advertises audio (`asset_file`).
- The local copy of the server library is cleared and re-downloaded when the server resets its sync state, so the library can briefly appear empty.
- Keystore encryption, background sync scheduling, the Sync/Library/Prep/Offline/Suggest/Live screen behaviour on a device, network-config enforcement and backup exclusion have been built and unit-tested but NOT yet run on a device.

### Sample Lab integration (S2: server audio)

- Tracks on a Sample Lib server that advertises `asset_file` can now be played: they stream through Media3 over an authenticated, redirect-free HTTP client (the preview file when the server has one). The bearer is added only for the paired server's `/v1/sync/media/` route and is never placed on a media item, in work data, or in logs; player error text is scrubbed of server addresses.
- Server tracks can be pinned for offline use from the Offline screen: downloads are resumable (Range with the server's quoted validator), SHA-256 verified before they count as available, stored outside backups, and refused when the device is nearly out of space. Unpinning removes the local copy.
- Live decks accept device files and downloaded copies only; a server track that is only streamable shows "Download this track to load it on a deck."
- Forget server (now with a confirmation) and switching to a different server also remove downloaded audio and stop running downloads.
- Not in this release: matching server tracks to identical device files by content hash, and a cache that stays readable while a new snapshot loads (both follow in separate changes).

### Known limitations (S2)

- Right after launch, before the first successful connection of the session, the server's audio support is inferred from the cached library; a server that withdrew audio since the last sync is noticed at the next connection.
- Cancelling an in-flight download keeps its partial file (so it can resume) until the track is unpinned; pruning skips pinned tracks.
- Media3 session streaming, `OkHttpDataSource`, download scheduling through WorkManager, deck audio output and the new Forget confirmation have been built and unit-tested where possible but NOT run on a device.

### Release engineering

- Release builds can be signed with a project-owned key supplied through `ANDROID_KEYSTORE_*` environment variables or a git-ignored `keystore.properties`; `-PrequireReleaseSigning=true` fails the build instead of falling back to the debug key. Tag CI requires it.
- New `scripts/verify_release_signature.py` pins the release certificate SHA-256, rejects the Android debug certificate, and runs in both CI and the release workflow. Release provenance now records the signing certificate fingerprint.
- The release workflow publishes only from the CI run triggered by the tag itself, closing a path where a same-commit `main` run could have supplied a debug-signed APK.
- Added `SampleLibLiveContractTest` and `scripts/run_live_samplelib_contract.sh`, which run the real `SampleLibClient`/`SampleLibHttpTransport` against a disposable real Sample Lib server (pairing, capability negotiation, snapshot/incremental pull, idempotent editor push, receipt recovery, stale-revision conflict). Skipped unless the live environment is configured.
- Added `docs/release/signing.md` and `docs/release/keystore-manual.md`. Keystore, password and `*.jks`/`*.keystore`/`*.p12` files are now git-ignored.

## 0.2.0-rc.1 - 2026-09-30

First release candidate for the 0.2.x line, consolidating the post-v0.1.3 convergence work.

### Added

- Playback diagnostics for unavailable media, Media3 transport/service errors, and audio-focus suppression, plus offline-playback integration coverage.
- Expanded library/search/filter regression coverage, including Compose instrumentation scenarios and state restoration behavior.
- Machine-readable Wave Two/Three and parity qualification evidence for the converged feature set.

### Changed

- Media3 playback now converges on the MediaSession/MediaController service boundary with stronger transport, queue, seek, and state synchronization behavior.
- Library browsing/search behavior and player controls are reconciled across the primary Compose surfaces.
- Sync protocol v1 schemas, capability negotiation, opaque revision handling, receipt semantics, and deterministic fake-server qualification are aligned with the current Sample Lib authority model.
- Parity/E2E tooling now fails closed on Android targets below minSdk 28 and distinguishes host qualification from real-device execution.
- Debug-harness verification now checks both production launchers and release isolation directly from built APKs.

### Qualification

- Fresh local protocol/fake-server tests: 32 passed.
- Fresh UI-runner tests: 6 passed.
- Android JVM tests, instrumentation-source compilation, lint, debug assembly, and release assembly passed under JDK 17.
- Debug/release launcher and debug-harness isolation checks passed.
- Host conformance passed as `host-preflight-passed-not-full-conformance`.
- The canonical parity ledger validates at 49 pass / 13 partial / 25 blocked across 87 required stories.

### Release-candidate limitations

- No compatible adb target was available during the 2026-09-30 local qualification run, so connected instrumentation and real-device Media3 playback were not re-verified.
- The API-35 screenshot/E2E phase is a separate hosted/device gate and must pass on the exact candidate revision before tag publication.
- Full parity is not claimed while 38 required stories remain partial or blocked.
- The release build remains development/debug signed for sideload review rather than store distribution.
- AGP 8.5.2 still warns that compileSdk 35 is newer than its certified compileSdk 34 range.

## 0.1.3 - 2026-09-18

Production-launcher and audible-playback packaging repair.

### Fixed

- The normal debug launcher no longer replaces the app with the dogfood harness. The harness remains internal to debug builds, but ordinary launches now use the real Android DJ Tools activity.
- Removed the provider/debug status overlay from the production activity. Library/runtime state remains represented by the normal screens instead of a developer banner pinned over the UI.
- The primary downloadable APK is now built from the `release` variant, so it uses the real MediaStore library and Media3/ExoPlayer playback path instead of fixture playback.

### Release packaging

- CI now assembles both variants, verifies the release APK launches `AndroidDjToolsActivity`, rejects any release APK containing `DebugHarnessActivity`, verifies the APK signature, and uploads the release APK as the primary artifact.
- GitHub Release publication now attaches `androidjtools-vVERSION.apk` from the release artifact; the debug APK remains a secondary CI-only diagnostic artifact.
- The release variant is non-debuggable and currently uses Android's development signing configuration for sideload installation. This is a production-UI/runtime build, not a Play Store signing setup.

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
