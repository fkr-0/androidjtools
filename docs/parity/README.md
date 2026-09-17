# EPIC-18 parity and real-system E2E qualification

Canonical task: `ANDROIDJTOOLS-WAVE5-PARITY-E2E-20260915`.

This directory is the fail-closed parity authority for Wave Five. `parity.json` maps every catalog story through EPIC-18 to concrete automated evidence or to an explicit red gap. EPIC-19 is excluded because packaging/release depends on EPIC-18 and therefore cannot be a prerequisite for its own gate.

## Operator override boundary

The original EPIC-18 dependency gate was not satisfied. The operator subsequently requested an override and execution. That instruction is recorded as an **execution gate waiver only**:

- it permits this lane to build and run parity evidence inside `e2e/**` and `docs/parity/**`;
- it does **not** waive dependency acceptance for final qualification;
- it does **not** turn fake, JVM, or adapter-only evidence into real-system evidence;
- it does **not** invent a separate canonical OCP task binding. This refresh is executing through authenticated project-manager workflow packet `a0655a7e-9ced-4145-acd6-301b2cbbc3bd` and its scoped path claim.

No upstream production implementation was modified by this lane.

Independent read-only review is required by the active workflow packet and remains pending until this refreshed evidence is submitted. Approval is therefore still a hard qualification requirement rather than being pre-claimed.

## Current qualification snapshot

Refreshed on 2026-09-17 from the canonical story catalog and current executable evidence:

| Story state | Count | Meaning |
| --- | ---: | --- |
| `pass` | 49 | The named verification is covered by observed passing evidence at an appropriate tier. |
| `partial` | 13 | Useful executable evidence passes, but a required integration/device/contract dimension is still absent. |
| `blocked` | 25 | Matching evidence exists and is buildable/executable, but the required runtime environment is unavailable. |
| `missing` | 0 | Every required story now points at concrete evidence or an explicit environment/integration blocker. |
| `fail` | 0 | Current executable evidence does not demonstrate a known story-level contradiction. |

Notable convergence changes in this refresh are evidence-backed rather than inferred: US-004 now passes a dedicated provider-boundary dependency scan; US-072 passes an offline/recreate/reconnect namespaced-tag round trip; US-141 passes explicit related-track capability/dimension/provenance tests; and US-163 passes fresh debug-vs-release APK exclusion inspection. Lane A's artwork/compact-metadata PNG assertion means US-011 is now device-blocked rather than missing. EPIC-17 has a compiled, thresholded Android instrumentation measurement tier for 10,000-track interactions and waveform `FrameMetrics`, but those stories remain red until that measurement actually runs on an Android target.

## Evidence tiers

The ledger distinguishes the following tiers rather than collapsing them into one green state:

- `fixture` / `host-jvm`: deterministic local model and state-machine tests;
- `host_static` / `host_compile`: dependency-boundary and Android-test source-integration checks that do not pretend to be runtime evidence;
- `release_artifact`: inspection of freshly built APK contents and manifests;
- `fake_backend`: protocol and authenticated loopback Sample Lib simulations;
- `emulator_or_real_device`: Android instrumentation that must actually run on an Android target;
- `real_backend`: tests against the real local Sample Lib checkout;
- `real_device`: hardware/system-media behavior that cannot be established from JVM tests;
- `real_device_plus_real_backend`: the actual EPIC-18 integration tier;
- `hosted_ci`: separately reported CI execution; no such evidence was observed in this run.

A lower tier never satisfies a story whose verification explicitly requires a higher tier.

## Observed run evidence

The JVM suite passes when the project is run with Java 17 and an isolated writable Gradle cache:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
      GRADLE_USER_HOME=$PWD/.gradle-local \
      ./gradlew testDebugUnitTest --no-daemon --console=plain --rerun-tasks

The host default Java is `26.0.2.1`, which this Gradle/Kotlin toolchain cannot parse. The fresh integrated run used the repository-local `.gradle-local` cache to keep Gradle state writable and isolated; neither host-toolchain constraint requires a repository mutation.

The protocol/fake-backend suites pass:

    cd test-server
    python3 -m unittest -v test_contracts test_fake_sync_server

    cd test-server/samplelib
    python3 -m unittest -v test_fake_samplelib_server

The real Sample Lib qualification now passes its strict production-sync gate:

    python3 e2e/djxml/qualify_samplelib.py --require-real-sync

It confirms the production sync facade (`/v1/sync/hello`, `/v1/sync/pair`, `/v1/sync/pull`, `/v1/sync/push`, receipt lookup), canonical playlist identity/order, stable asset identity, and DJXML preparation semantics. That is **server-side real-backend qualification**, not Android end-to-end proof. The real-core gate now additionally requires a `androidjtools.real-device-backend-evidence/v1` packet proving an attached Android serial actually used `SampleLibHttpTransport` to pair, hello, pull, push, recover/observe a receipt, and reconnect against the requested real Sample Lib checkout. Device presence alone cannot satisfy the gate.

The host `/usr/bin/adb` is linked against an incompatible `libusb`, but the runner now prefers the healthy SDK copy at `/opt/android-sdk/platform-tools/adb`. That SDK adb reported zero attached devices. Therefore Android instrumentation, actual install/launch, lock-screen/Bluetooth behavior, font-scaling, TalkBack semantics, visual PNG captures and device performance were not promoted to passing evidence. The complete `:app:compileDebugAndroidTestKotlin` source gate is green; this is deliberately recorded as compile evidence only.

The release-variant isolation gate is now observed green:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
      GRADLE_USER_HOME=/tmp/androidjtools-gradle \
      ./gradlew :app:assembleRelease --no-daemon --console=plain

    python3 app/src/debug/verify_debug_harness.py \
      --debug-apk app/build/outputs/apk/debug/app-debug.apk \
      --release-apk app/build/outputs/apk/release/app-release-unsigned.apk

The verifier proved `DebugHarnessActivity` and `debug-harness-contract.json` are present only in debug. The final host-conformance rebuild's inspected unsigned release APK SHA-256 was `3974f1e0debd6080d78bec32745fb5d8440c4e11becb4b0787bafa7a9e0b0d6c`.

EPIC-17 performance uses a documented **instrumentation regression tier**, not a Macrobenchmark claim. `PerformanceRegressionTest` constructs 10,000 tracks, measures initial/search settle latency, captures `Window.FrameMetrics` across waveform gestures, applies explicit thresholds, and writes machine-readable device evidence. `e2e/ui/run_ui_e2e.py` now fails the emulator/device tier if that JSON cannot be pulled and validated after the complete instrumentation suite. No timing values are recorded in the ledger until an Android target actually executes the test.

## Validation and qualification

Regenerate the ledger from `docs/user-stories.yml`:

    python3 e2e/parity/generate_matrix.py

Validate exact story coverage, evidence references, tiers and fail-closed status rules:

    python3 e2e/parity/validate_matrix.py

The normal validator is expected to succeed even while parity is red; it proves the **matrix is complete and honest**, not that the product is qualified.

Require release-grade parity:

    python3 e2e/parity/validate_matrix.py --require-qualified

That command must remain non-zero until every required story is `pass`, each tier-sensitive story has **passing evidence at its required tier**, real-device + real-backend evidence is green, hosted-CI evidence itself is `pass`, and independent review is approved with an independent-review policy marker plus a concrete reviewed Git revision.

Probe real-system readiness without substituting fakes:

    python3 e2e/parity/run_core_e2e.py
    python3 e2e/parity/run_core_e2e.py --require-real

On this refresh the second form exits `3` because no real Android target is attached **and** no validated Android-to-real-Sample-Lib round-trip evidence packet exists. The real Sample Lib server side itself is qualified. This distinction is intentional: merely attaching a device must not turn two independent green probes into fabricated end-to-end evidence.

## Owning-lane follow-ups exposed by parity

The highest-value remaining red items are intentionally left as explicit gates rather than papered over: attach an authorized Android runtime and execute the complete instrumentation/performance/visual/real-core paths; provide MediaSession/Bluetooth device evidence; resolve the genuine `stem` transport-model gap for US-144 instead of inventing a CandidateDTO wire kind; and obtain a hosted API-35 CI result plus independent review. Production Sample Lib sync/playlist authority, namespaced-tag persistence, the provider-boundary proof, release debug-harness exclusion, related-track provider evidence, and the implementation of the 10k/frame-time measurement gate are no longer listed as missing work.
