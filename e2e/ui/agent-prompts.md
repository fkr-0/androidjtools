# Canonical continuation prompts for UI/UX E2E convergence

These are continuation payloads for the existing durable OCP tasks named below. Do **not** mint duplicate task identities while those tasks remain nonterminal. Reconcile/recover the same task first, then use the matching prompt when native OCP lifecycle authority permits a retrigger.

## `ANDROIDJTOOLS-WAVE5-PARITY-E2E-20260915` — real-device UI/UX qualification

```text
Set repo: androidjtools.

Continue canonical task ANDROIDJTOOLS-WAVE5-PARITY-E2E-20260915 from its current EPIC-18 needs_retrigger checkpoint. Preserve concurrent work and the existing e2e/parity authority.

First inspect e2e/ui/**, the current parity checkpoint, all accepted dependency evidence, and live adb state. Run python3 e2e/ui/run_ui_e2e.py --require-device with a real emulator/device. Treat exit 3 as an external device blocker, not a pass. If instrumentation fails, repair only failures proven by the run and coordinate ownership before touching implementation lanes. Re-run the complete debug instrumentation suite until green.

Then re-run e2e/parity/generate_matrix.py and validate_matrix.py. Do not promote compile-only, fixture-only, or host-only evidence to real-device or real-backend tiers. Record exact Android serial/API, executed test classes, pass/fail counts, and artifact paths. Keep Sample Lib real-backend qualification separate and fail closed if it is still unavailable.

Acceptance: full UI/UX instrumentation passes on an actual Android target; parity evidence names the observed run; no story is upgraded beyond its evidence tier; remaining real-backend blockers are explicit.
```

## `ANDROIDJTOOLS-WAVE4-A11Y-PERFORMANCE-20260915` — accessibility and device performance

```text
Set repo: androidjtools.

Continue canonical task ANDROIDJTOOLS-WAVE4-A11Y-PERFORMANCE-20260915. Start from app/src/androidTest/java/dev/androidjtools/a11y/ScaledFontNavigationTest.kt and the current EPIC-17 parity gaps. Preserve product behavior unless a failing test demonstrates a concrete accessibility defect.

On a real emulator/device, run the scaled-font navigation test plus existing semantics tests. Add reproducible device-side evidence for a 10,000-track interaction workload and waveform frame-time regression without replacing device timing with JVM timing. Keep thresholds explicit and record device/API/build configuration. If benchmark infrastructure must be added, keep it isolated to the EPIC-17 ownership boundary.

Acceptance: US-170 through US-173 each have executed Android/device evidence; font scale does not hide core navigation/search; 10k-library responsiveness and waveform frame timing have reproducible bounded measurements; failures remain visible rather than waived.
```

## `ANDROIDJTOOLS-WAVE4-ANDROID-MEDIA-20260915` — system media controls

```text
Set repo: androidjtools.

Continue canonical task ANDROIDJTOOLS-WAVE4-ANDROID-MEDIA-20260915 from the current Media3 implementation. Use an attached Android target and the existing player/queue authority. Add or run instrumentation that proves MediaSession lock-screen/notification commands, media-button/Bluetooth transport mapping, and audio-focus loss/regain keep the same queue/current-track state as the in-app player.

Do not simulate success only at the controller unit-test layer. Capture executed instrumentation evidence and reconcile any lifecycle/service manifest issue against the existing implementation rather than introducing a second playback authority.

Acceptance: US-150, US-151 and US-152 have device-executed evidence; external transport operations map to the canonical PlayerQueueController/Media3 state; focus transitions are deterministic; no competing playback authority is introduced.
```

## `ANDROIDJTOOLS-WAVE3-MUTATION-JOURNAL-20260915` — safe-merge contract correction

```text
Set repo: androidjtools.

Continue canonical task ANDROIDJTOOLS-WAVE3-MUTATION-JOURNAL-20260915 and reconcile US-113 against docs/user-stories.yml and docs/epics.yml. Current parity evidence says SAFE_FIELDWISE still requires review while the story requires safe conflicts to merge automatically.

Inspect the accepted sync protocol, conflict resolver, tests, and any independent review. Choose the implementation that matches the already accepted product contract; do not weaken unsafe-conflict visibility. Add an explicit merge matrix proving disjoint safe fields auto-merge deterministically, overlapping/unsafe fields remain user-visible, and replay/idempotency still holds. Obtain independent review before returning evidence to EPIC-18.

Acceptance: story and implementation semantics agree; safe field-wise conflicts auto-merge if that remains the accepted contract; unsafe conflicts expose both local and authoritative values; replay and receipt tests remain green.
```

## Dispatch rule

Use native `chatgpt-ops` lifecycle only. If an existing task is still `running`, first correlate its conversation/checkpoint and recover or close that same identity as appropriate. Never copy these prompts into manual browser sends, never create `next.md`, and never replay an ambiguous delivery.
