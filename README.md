# Android DJ Tools

Android DJ Tools is a cloudless Android DJ-library preparation client for Sample Lib / Sample Lab. The app is built fixture-first so library browsing, collection preparation, waveform editing, playback/queue UX, analysis suggestions, and sync-state handling can be exercised deterministically before a real backend is connected.

## Status

`v0.2.0-rc.2` is the prepared next release candidate (`v0.2.0-rc.1` was prepared but never tagged). It consolidates post-v0.1.3 Media3 playback hardening, library/search convergence, sync-protocol qualification, debug-harness isolation, and parity/E2E work. Sample Lib remains the canonical metadata authority. The sync protocol stays versioned under `contracts/`, deterministic fake-server qualification lives under `test-server/`, and the Android application lives under `app/`.

The project intentionally distinguishes fixture/fake-server, host, hosted-emulator, real Sample Lib, and physical-device evidence. The RC metadata is prepared, but tagging/publication remains gated on compatible Android runtime and hosted API-35 E2E/screenshot evidence. See `CHANGELOG.md` and `docs/release/v0.2.0-rc.2.md` for the exact boundary.

## Build

Requirements:

- JDK 17
- Android SDK with platform 35
- Python 3 with `test-server/requirements-test.txt`

Run the release checks from the repository root:

    python -m pip install -r test-server/requirements-test.txt
    PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS='error::ResourceWarning' python -m unittest discover -s test-server -p 'test_*.py' -v
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease

The local review APKs are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`. The release variant is non-debuggable. It is debug-signed (unofficial) unless release signing inputs are provided; official `v*` tag builds require the project release key. See `docs/release/signing.md`.

## Continuous integration

GitHub Actions runs the protocol and Android release checks on `main`, pull requests, SemVer-style `v*` tags, and manual dispatch. Successful runs upload the non-debuggable release APK as the primary artifact and the debug APK as a secondary diagnostic artifact; API-35 emulator conformance and screenshot jobs remain separate evidence tiers.

Documentation is built with MkDocs and deployed by GitHub Actions to <https://androiddjtools.fkr.dev/>.

## Repository layout

- `app/` — Kotlin/Jetpack Compose Android client and tests
- `contracts/` — sync and intelligence protocol contracts
- `test-server/` — deterministic fake-server and protocol qualification
- `docs/` — epics, workflow evidence, protocol decisions, and release notes
- `bridge.yml` — local ws-bridge collaboration/workflow configuration
