# Android DJ Tools dogfood visual/device QA — 2026-09-27

Canonical task: `ANDROIDJTOOLS-DOGFOOD-VISUAL-20260927`

## Scope and ownership

- Read-only inspection of current app implementation.
- Writes restricted to the packet-owned evidence/visual-test paths.
- Existing dirty work in README, debug harness docs/scripts, parity files, release docs, artifacts, and other lanes was preserved.
- No changes were made under `app/src/main/**`.

## Runtime/device status

Actual device rendering is currently blocked on this host:

- `/usr/bin/adb` fails before device enumeration with:
  `undefined symbol: libusb_get_ssplus_usb_device_capability_descriptor`
- Retrying with `LD_LIBRARY_PATH` removed produces the same failure.
- `emulator -list-avds` returns no AVDs.
- No Android SDK system-image tree is present under `$ANDROID_SDK_ROOT/system-images`.

Because there is no functioning local Android runtime path, no new device screenshots were captured in this pass. Existing instrumentation screenshot tests remain compilable and are the current supported screenshot path for CI/device execution.

## Verification executed

Command:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
GRADLE_USER_HOME=$PWD/.gradle-local \
./gradlew \
  :app:compileDebugKotlin \
  :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest \
  :app:lintDebug \
  --stacktrace
```

Result: **PASS** — `BUILD SUCCESSFUL`.

This verifies the current main sources, instrumentation-test sources, JVM test suite, and lint model compile together.

## Dogfood coverage inventory

Current device/instrumentation coverage already exercises:

| Area | Current evidence |
|---|---|
| Library | library list, artwork, metadata density, search affordance, primary track actions |
| Player | mini-player state, expansion to full player, seek continuity, unavailable/offline state |
| Queue | queue list/item semantics, clear affordance, metadata/safety indicators |
| Waveform | overview/detail surfaces, canonical/candidate overlays, zoom, pan, scrub, marker selection |
| Cues | accessible cue semantics including number, role, label, millisecond position, staged/delete state |
| Loops | accessible loop semantics including role, bounds, active/staged/delete state |
| Beatgrid | half/double BPM, downbeat placement, phase adjustment, candidate acceptance, staged commit/cancel/undo |
| Offline | cached playback, download cancel/retry, playlist pin/unpin, offline-state banner |
| Accessibility | primary navigation semantics, primary library actions, waveform controls, 2.0x font-scale navigation smoke |
| Top-level navigation | Library → Lists → Prep → Suggest → Sync → Library with persistent mini-player |
| Visual evidence | top-level CI screenshots plus library/waveform parity screenshots when instrumentation runs |

## Findings

### 1. Device/render execution is blocked locally
Severity: **QA blocker for screenshot/device proof**, not a source-build blocker.

The application/test code compiles, but current host tooling cannot enumerate or launch a device. This prevents validating real back-stack behavior, touch ergonomics, clipping/overlap, system bars, keyboard interaction, and screenshot regressions on an actual rendered surface in this session.

### 2. No lint errors; 14 warnings
Lint reports **0 errors / 14 warnings**. UI-relevant warnings include:

- `BeatGridEditor`: Compose `ModifierParameter` ordering warning.
- Missing application icon.
- Several dependency-update notices.
- One library-screen primitive-state autoboxing informational item.

None of these warnings currently blocks device execution or proves a visual defect.

### 3. Existing automated coverage is broad but fragmented
The repository has focused tests for library, player, waveform, offline, accessibility, cues, loops, beatgrid, and top-level navigation. The main remaining confidence gap is a single real-device dogfood run that combines these flows under actual screen sizes/system chrome and captures screenshots.

## Recommended next runtime pass

On the next host/CI run with a working Android target:

1. Run the existing visual screenshot tests:
   - `dev.androidjtools.visual.CiScreenshotTest`
   - `dev.androidjtools.visual.LaneAVisualParityTest`
2. Run the app-shell, accessibility, offline, player, waveform, cue, loop, and beatgrid instrumentation tests.
3. Exercise phone-class viewports comparable to 360×800, 375×667, and 390×844.
4. Inspect captured PNGs for:
   - bottom-nav/system-bar collisions;
   - mini/full-player overlap;
   - queue truncation and unreachable controls;
   - waveform controls obscuring the plot;
   - cue/loop/beatgrid editor clipping;
   - offline banners pushing actions below the fold;
   - 2.0x font-scale truncation/overlap;
   - back navigation returning to the expected prior surface without losing queue/player state.
5. Record concrete screenshot paths and only then open narrow implementation fixes in unclaimed lanes.

## Outcome

**Source/test integration: PASS**

**Actual device dogfood/render proof: BLOCKED by host Android runtime tooling**

No app implementation files were modified.
