# Android DJ Tools dogfood UI QA — 2026-09-28

## Qualification result

- Host qualification: **PASS**.
- Real-device qualification: **BLOCKED** — no adb target satisfies minSdk 28.
- Fresh screenshot capture: **BLOCKED** for the same device reason; no stale screenshot is promoted as current evidence.
- Product-code fixes: **none**. No reproducible host-side defect justified touching owned feature implementation.

## Exact evidence

### Device probe

Command:

    python3 e2e/ui/run_ui_e2e.py --require-device --skip-host --json-out evidence/ui-qa/device-probe.json

Result: exit 3, qualification `blocked-no-device`.

Observed adb targets:

- `479008862f109052`: state `recovery`, not runnable.
- `192.168.23.186:5555`: API 22, below application minSdk 28.

### Host UI/E2E gate

Command:

    python3 e2e/ui/run_ui_e2e.py --json-out evidence/ui-qa/host-ui-e2e.json --performance-json-out evidence/ui-qa/device-performance.json

Result: exit 0, qualification `host-qualified-device-pending`.

Passed Gradle tasks:

- `:app:testDebugUnitTest`
- `:app:compileDebugAndroidTestKotlin`
- `:app:lintDebug`
- `:app:assembleDebug`

The runner correctly did not claim device parity because instrumentation could not run.

### Runner self-tests

Command:

    python3 -m unittest e2e.ui.test_run_ui_e2e

Result: 6 tests passed.

## Requested dogfood areas vs current executable coverage

| Area | Current evidence | Status this pass |
| --- | --- | --- |
| Library | `LibraryScreenTest`, shell E2E, visual parity test | Host compile/JVM gates pass; device execution blocked |
| Player | `PlayerScreenTest` covers mini/full player, transport continuity, queue state, loading/error/offline-unavailable states | Host compile/JVM gates pass; device execution blocked |
| Queue | Player UI and queue-controller tests cover current item/empty queue and transport continuity | Host coverage present; tactile/device behavior blocked |
| Waveform | `WaveformScreenTest` covers overview/detail, zoom/pan/scrub, focused overlays and loop range editing; performance test exists | Host compile gate passes; device rendering/perf blocked |
| Cues | `CueEditorSemanticsTest` covers accessible cue identity/role/time and staged/delete state | Host compile gate passes; device interaction blocked |
| Loops | `LoopEditorSemanticsTest` plus waveform loop-range tests | Host compile gate passes; device interaction blocked |
| Beatgrid | `BeatGridEditorTest` covers half/double BPM, downbeat, phase, candidate acceptance, undo/cancel/commit intent | Host compile gate passes; device interaction blocked |
| Back navigation | Library detail has explicit "Back to library"; top-level shell back-stack behavior is not directly device-qualified in the current suite | Residual QA gap |
| Offline | Library, collections, player, offline screen and debug harness include offline/error states | Host compile/JVM gates pass; device transition behavior blocked |
| Accessibility | `TalkBackSemanticsTest`, cue/loop semantics and 2× font-scale navigation test | Host compile gate passes; actual TalkBack traversal blocked |
| Responsive | Library tests cover 360dp compact and 720dp roomy layouts; 2× font-scale shell navigation exists | Partial; whole-shell/device reflow not freshly exercised |
| Visual regressions | `CiScreenshotTest` and `LaneAVisualParityTest` exist; screenshot extraction script validates PNGs >=720×1280 | Fresh screenshots blocked by lack of compatible device |

## Residual risks / next device pass

When an API >=28 emulator/device is available, run:

    python3 e2e/ui/run_ui_e2e.py --require-device --json-out evidence/ui-qa/device-ui-e2e.json
    bash e2e/ui/run_ci_screenshots.sh

Then manually/dogfood-check:

1. Android system Back from each top-level destination and from expanded player/detail surfaces.
2. Queue reorder/remove/clear with touch, including focus/semantic announcements.
3. Waveform pinch/pan/scrub and cue/loop/beatgrid controls at compact phone width.
4. Network-off/on transitions while player and preparation edits are visible.
5. TalkBack traversal order and state announcements, not only semantic-node existence.
6. 320–390dp phone widths and large-font reflow for the complete shell, not only Library.
