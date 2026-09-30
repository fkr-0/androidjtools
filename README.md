# Android DJ Tools

Android DJ Tools is a cloudless Android DJ-library preparation client for Sample Lib / Sample Lab. The app is built fixture-first so library browsing, collection preparation, waveform editing, playback/queue UX, analysis suggestions, and sync-state handling can be exercised deterministically before a real backend is connected.

## Status

`v0.1.3` is the current patch preview. It keeps the post-0.1.0 preparation, offline/sync, analysis-advisory, parity, and CI screenshot lanes while repairing the production launcher and real MediaStore/Media3 playback packaging introduced in `v0.1.2`/`v0.1.3`. Sample Lib remains the canonical metadata authority. The sync protocol stays versioned under `contracts/`, deterministic fake-server qualification lives under `test-server/`, and the Android application lives under `app/`.

The release intentionally distinguishes fixture/fake-server, real Sample Lib, CI-emulator, and local physical-device evidence. See `CHANGELOG.md` and `docs/release/v0.1.3.md` for the current review boundary.

## Build

Requirements:

- JDK 17
- Android SDK with platform 35
- Python 3 with `test-server/requirements-test.txt`

Run the release checks from the repository root:

    python -m pip install -r test-server/requirements-test.txt
    PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS='error::ResourceWarning' python -m unittest discover -s test-server -p 'test_*.py' -v
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease

The local review APKs are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`. The release variant is non-debuggable and currently development-signed for sideload review.

## Continuous integration

GitHub Actions runs the protocol and Android release checks on `main`, pull requests, SemVer-style `v*` tags, and manual dispatch. Successful runs upload the non-debuggable release APK as the primary artifact and the debug APK as a secondary diagnostic artifact; API-35 emulator conformance and screenshot jobs remain separate evidence tiers.

Documentation is built with MkDocs and deployed by GitHub Actions to <https://androiddjtools.fkr.dev/>.

## Repository layout

- `app/` — Kotlin/Jetpack Compose Android client and tests
- `contracts/` — sync and intelligence protocol contracts
- `test-server/` — deterministic fake-server and protocol qualification
- `docs/` — epics, workflow evidence, protocol decisions, and release notes
- `bridge.yml` — local ws-bridge collaboration/workflow configuration
