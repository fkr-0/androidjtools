# Android DJ Tools

Android DJ Tools is a cloudless Android DJ-library preparation client for Sample Lib / Sample Lab. The app is built fixture-first so library browsing, collection preparation, waveform editing, playback/queue UX, analysis suggestions, and sync-state handling can be exercised deterministically before a real backend is connected.

## Status

`v0.1.1` is the current patch preview. It integrates the post-0.1.0 preparation, offline/sync, analysis-advisory, parity, and CI screenshot lanes while keeping Sample Lib as the canonical metadata authority. The sync protocol remains versioned under `contracts/`, deterministic fake-server qualification lives under `test-server/`, and the Android application lives under `app/`.

The release intentionally distinguishes fixture/fake-server, real Sample Lib, CI-emulator, and local physical-device evidence. See `CHANGELOG.md` and `docs/release/v0.1.1.md` for the exact release boundary.

## Build

Requirements:

- JDK 17
- Android SDK with platform 35
- Python 3 with `test-server/requirements-test.txt`

Run the release checks from the repository root:

    python -m pip install -r test-server/requirements-test.txt
    PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS='error::ResourceWarning' python -m unittest discover -s test-server -p 'test_*.py' -v
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug

The local debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Continuous integration

GitHub Actions runs the protocol and Android release checks on `main`, pull requests, SemVer-style `v*` tags, and manual dispatch. Successful runs upload the debug APK as a workflow artifact.

Documentation is built with MkDocs and deployed by GitHub Actions to <https://androiddjtools.fkr.dev/>.

## Repository layout

- `app/` — Kotlin/Jetpack Compose Android client and tests
- `contracts/` — sync and intelligence protocol contracts
- `test-server/` — deterministic fake-server and protocol qualification
- `docs/` — epics, workflow evidence, protocol decisions, and release notes
- `bridge.yml` — local ws-bridge collaboration/workflow configuration
