# CI-rendered Android UI screenshots

`Android CI` contains a dedicated `Render Android UI screenshots` job. It is
device-backed visual evidence, not a Compose Preview, HTML mock, or synthetic
image generator.

The job boots an Android 15 / API 35 Google APIs emulator with the Pixel 7 Pro
hardware profile. API 35 deliberately matches the application's current
`compileSdk` and `targetSdk`, so screenshot production does not silently require
an SDK migration. The emulator is configured for a deterministic light theme,
1.0 font scale and disabled animations.

`dev.androidjtools.visual.CiScreenshotTest` renders the normal
`AndroidDjToolsApp` with the canonical deterministic fixture provider and the
real player/queue controller. Android `UiAutomation.takeScreenshot()` captures
the complete emulator display at device pixel resolution after Compose reaches
idle. The workflow then extracts the PNGs from the debuggable app's private
files directory using `adb run-as`.

The uploaded `androidjtools-android15-ui-screenshots` artifact contains:

- `01-library.png`
- `02-collections.png`
- `03-preparation.png`
- `04-suggestions.png`
- `05-sync.png`

CI fails if any expected image is missing, empty, not a PNG, or unexpectedly
small. On instrumentation failure it also uploads Android test reports/results
as `androidjtools-ui-screenshot-diagnostics`.

This lane proves how the fixture-backed application UI is rendered by a modern
Android runtime. It does not by itself prove physical-device-only behavior such
as OEM system UI differences, Bluetooth/media-key delivery, or hardware frame
timing.

## API-35 conformance workflow

`.github/workflows/android-e2e-conformance.yml` is a separate fail-closed API-35
emulator lane. Its name intentionally says **not full parity**: emulator success
must never be confused with EPIC-18 real-device + real-backend qualification.

The workflow boots a Pixel 7 Pro profile on Android 15 / API 35 and invokes:

    python3 e2e/parity/run_conformance.py \
      --profile emulator \
      --report artifacts/e2e/conformance.json

The emulator profile delegates to `e2e/ui/run_ui_e2e.py`, which runs the
**unfiltered complete** `:app:connectedDebugAndroidTest` task. It does not select
only screenshot classes. After instrumentation succeeds, the runner also pulls
`files/epic17-performance.json` from the debug application sandbox. The emulator
tier fails if that performance evidence is absent, invalid, or contains a failed
10k-library/waveform threshold result.

Machine-readable and diagnostic artifacts include `artifacts/e2e/**`, connected
Android test reports, connected Android test result XML, unit-test reports and
lint output. A successful emulator workflow therefore proves the configured
fixture/emulator tier only. `--require-qualified` still separately requires all
87 stories to pass, the real-device + real-Sample-Lib tier, hosted-CI evidence in
the canonical ledger, and independent review.
