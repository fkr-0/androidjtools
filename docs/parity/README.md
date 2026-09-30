# EPIC-18 parity and real-system E2E qualification

Canonical task: `ANDROIDJTOOLS-WAVE5-PARITY-E2E-20260915`.

This directory is the fail-closed parity authority for Wave Five. `parity.json` maps every catalog story through EPIC-18 to concrete automated evidence or to an explicit red gap. EPIC-19 is excluded because packaging/release depends on EPIC-18 and therefore cannot be a prerequisite for its own gate.

## Operator override boundary

The original EPIC-18 dependency gate was not satisfied. The operator subsequently requested an override and execution. That instruction is recorded as an **execution gate waiver only**:

- it permits this lane to build and run parity evidence inside `e2e/**` and `docs/parity/**`;
- it does **not** waive dependency acceptance for final qualification;
- it does **not** turn fake, JVM, or adapter-only evidence into real-system evidence;
- it does **not** promote lifecycle state into test evidence. For the current R44 refresh, live ws-bridge presence and claims were inspected before execution and no conflicting active claim was found. R44 ledger/evidence writes use narrow claims over the parity files and R44 evidence artifacts while protected runner/workflow and implementation paths remain untouched.

No upstream production implementation was modified by this lane.

Independent read-only review remains pending and is still a hard qualification requirement; this evidence refresh does not pre-claim approval.

## Current qualification snapshot

Refreshed on 2026-09-29 from the canonical story catalog and current executable evidence:

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
- `hosted_ci`: separately reported CI execution; the latest recorded API-35 run remains red at the historical release-path mismatch, while this local host run verifies the repaired release verifier.

A lower tier never satisfies a story whose verification explicitly requires a higher tier.

## Observed run evidence

The JVM suite passes when the project is run with Java 17 and an isolated writable Gradle cache. R44 used the repository-local `.gradle-local` cache and redirected Java temporary files to `.tmp-test-gate/r44` so host `/tmp` pressure cannot masquerade as a product failure:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
      GRADLE_USER_HOME=$PWD/.gradle-local \
      JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/.tmp-test-gate/r44" \
      ./gradlew testDebugUnitTest --no-daemon --console=plain

The host default Java is `26.0.2.1`, which this Gradle/Kotlin toolchain cannot parse. Neither host-toolchain constraint requires a repository mutation.

The protocol/fake-backend suites pass:

    cd test-server
    python3 -m unittest -v test_contracts test_fake_sync_server

    cd test-server/samplelib
    python3 -m unittest -v test_fake_samplelib_server

The real Sample Lib qualification now passes its strict production-sync gate:

    python3 e2e/djxml/qualify_samplelib.py --require-real-sync

It confirms the production sync facade (`/v1/sync/hello`, `/v1/sync/pair`, `/v1/sync/pull`, `/v1/sync/push`, receipt lookup), canonical playlist identity/order, stable asset identity, and DJXML preparation semantics. That is **server-side real-backend qualification**, not Android end-to-end proof. The real-core gate now additionally requires a `androidjtools.real-device-backend-evidence/v1` packet proving an attached Android serial actually used `SampleLibHttpTransport` to pair, hello, pull, push, recover/observe a receipt, and reconnect against the requested real Sample Lib checkout. Device presence alone cannot satisfy the gate.

The runner prefers the healthy SDK adb at `/opt/android-sdk/platform-tools/adb`. In the current 2026-09-29 emulator-profile run it saw one GT-I8190 target in `device` state at API 22, below the app minSdk 28. Therefore no compatible Android target exists for instrumentation, actual install/launch, lock-screen/Bluetooth behavior, font-scaling, TalkBack semantics, visual PNG captures or device performance. The complete `:app:compileDebugAndroidTestKotlin` source gate is green; this remains compile evidence only. The host-profile harness defect exposed by that device has been closed: `run_ui_e2e.py` now probes each ready serial's Android API level before instrumentation, records incompatible/non-ready targets explicitly, and does not invoke `connectedDebugAndroidTest` unless at least one minSdk-28-compatible target exists. Six focused host tests cover compatible, below-minSdk, non-ready, probe-failure and malformed-SDK cases; `--require-device` still fails closed with exit 3 when no compatible target exists.

The release-variant isolation gate is now observed green:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
      GRADLE_USER_HOME=/tmp/androidjtools-gradle \
      ./gradlew :app:assembleRelease --no-daemon --console=plain

    python3 app/src/debug/verify_debug_harness.py \
      --debug-apk app/build/outputs/apk/debug/app-debug.apk \
      --release-apk app/build/outputs/apk/release/app-release.apk

The verifier requires the real `AndroidDjToolsActivity` launcher in both variants, `DebugHarnessActivity` and `debug-harness-contract.json` only in debug, and a non-debuggable release APK. Artifact hashes are captured from the current build rather than hard-coded into the parity generator.

EPIC-17 performance uses a documented **instrumentation regression tier**, not a Macrobenchmark claim. `PerformanceRegressionTest` constructs 10,000 tracks, measures initial/search settle latency, captures `Window.FrameMetrics` across waveform gestures, applies explicit thresholds, and writes machine-readable device evidence. `e2e/ui/run_ui_e2e.py` now fails the emulator/device tier if that JSON cannot be pulled and validated after the complete instrumentation suite. No timing values are recorded in the ledger until an Android target actually executes the test.

## Validation and qualification

Regenerate the ledger from `docs/user-stories.yml`:

    python3 e2e/parity/generate_matrix.py

Validate exact story coverage, evidence references, tiers and fail-closed status rules:

    python3 e2e/parity/validate_matrix.py

The normal validator is expected to succeed even while parity is red; it proves the **matrix is complete and honest**, not that the product is qualified. The current R44 refresh records fresh host evidence in `artifacts/e2e/conformance-r44-20260929.json`, device-gate evidence in `artifacts/e2e/ui-e2e-r44.json`, real Sample Lib evidence in `artifacts/e2e/samplelib-real-r44.log`, real-core evidence in `artifacts/e2e/real-core-r44-20260929.log`, and strict qualification evidence in `artifacts/e2e/strict-matrix-r44-20260929.log`, all at HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7`. Ledger integrity, provider-boundary checks, 39 backend/protocol tests, debug/release APK assembly, debug-harness release exclusion, unit tests, Android-test compilation, lint, and the UI host gate all pass; all six UI-runner classification tests pass. Host conformance exits `0`. The device-required UI gate exits `3` because the only visible GT-I8190 is API 22, below minSdk 28. Strict real Sample Lib qualification exits `0`; the real-core probe exits `3` only for `android_real_backend_evidence_missing`; and strict qualification exits `2` with the canonical ledger still at 49 pass / 13 partial / 25 blocked, i.e. 38 non-pass stories, plus real-core, hosted-CI, and independent-review gates. No reproducible unowned implementation defect was found, so `e2e/parity/run_conformance.py`, `.github/workflows/android-e2e-conformance.yml`, application source, contracts and test-server implementation were not changed by this lane.

Subsequent independent read-only replay approved canonical closure packet `98cfe1e2-2453-4ea6-88a6-f95e459dadb9` at the same HEAD on 2026-09-29T16:20:47Z. That closes the review gate only; the ledger remains **not qualified** at 49 pass / 13 partial / 25 blocked because device, real-core, hosted-CI, and story-level evidence gates remain non-pass.

Require release-grade parity:

    python3 e2e/parity/validate_matrix.py --require-qualified

That command must remain non-zero until every required story is `pass`, each tier-sensitive story has **passing evidence at its required tier**, real-device + real-backend evidence is green, hosted-CI evidence itself is `pass`, and independent review is approved with an independent-review policy marker plus a concrete reviewed Git revision.

Probe real-system readiness without substituting fakes:

    python3 e2e/parity/run_core_e2e.py
    python3 e2e/parity/run_core_e2e.py --require-real

On this refresh the second form exits `3` with exactly one real-core blocker: `android_real_backend_evidence_missing`; the separate real Sample Lib qualification exits `0`. The real Sample Lib server side itself is qualified. ADB visibility alone is insufficient: the currently visible API-22 target is below minSdk 28 and no validated Android-to-real-Sample-Lib round-trip evidence packet exists. This distinction is intentional: merely seeing a device must not turn independent green probes into fabricated end-to-end evidence.

## Owning-lane follow-ups exposed by parity

The highest-value remaining red items are intentionally left as explicit gates rather than papered over: attach an authorized Android runtime and execute the complete instrumentation/performance/visual/real-core paths; provide MediaSession/Bluetooth device evidence; resolve the genuine `stem` transport-model gap for US-144 instead of inventing a CandidateDTO wire kind; and obtain a hosted API-35 CI result plus independent review. Production Sample Lib sync/playlist authority, namespaced-tag persistence, the provider-boundary proof, release debug-harness exclusion, related-track provider evidence, and the implementation of the 10k/frame-time measurement gate are no longer listed as missing work.

## R53 canonical E2E closure refresh

Canonical task: `ANDROIDJTOOLS-E2E-CLOSURE-R53-20260929`.

The current suite was replayed at HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7` without modifying `e2e/parity/run_conformance.py` or `.github/workflows/android-e2e-conformance.yml`. The machine ledger remains unchanged at **49 pass / 13 partial / 25 blocked / 0 fail / 0 missing** across 87 required stories.

- Host conformance: exit `0`, `host-preflight-passed-not-full-conformance`; ledger integrity, provider boundary, all 39 backend/protocol tests, debug/release APK assembly, release debug-harness exclusion, JVM tests, Android-test compilation, lint and host UI gate pass. Evidence: `artifacts/e2e/conformance-r53-host.json`.
- Emulator/device conformance: blocked, not a product-test failure. The only adb-visible target is `192.168.23.186:5555` at API 22, below minSdk 28, so instrumentation correctly does not run. Evidence: `artifacts/e2e/conformance-r53-emulator.json` and `artifacts/e2e/ui-e2e.json`.
- Real Sample Lib: exit `0`; production sync routes, stable asset identity and canonical playlist identity/order qualify.
- Real core: exit `3`, blocked only by `android_real_backend_evidence_missing`; `artifacts/e2e/real-device-backend.json` is still absent, so adb visibility is not promoted into Android-to-real-Sample-Lib proof.
- Strict qualification: exit `2`; 38 required stories remain non-pass (25 blocked + 13 partial), `real-core-e2e` is not pass, and hosted-CI evidence remains non-pass.

The compact machine-readable closure receipt is `artifacts/e2e/closure-r53.json`. No reproducible unowned implementation blocker was found, so no application, protocol, test-server, protected E2E runner, or workflow implementation was changed by R53.

## R55 canonical replay — 2026-09-29

A fresh evidence-first replay at HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7` reconfirmed the canonical ledger without changing protected runner/workflow implementation. The matrix remains **49 pass / 13 partial / 25 blocked / 0 fail / 0 missing** across 87 required stories.

- Host conformance exits `0` with `host-preflight-passed-not-full-conformance`; matrix integrity, provider-boundary checks, all 39 backend/protocol tests, debug/release APK assembly, debug-harness release exclusion, JVM tests, Android-test compilation, lint and host UI qualification pass. Evidence: `artifacts/e2e/conformance-r55-host.json`.
- Emulator conformance exits `1` only because its device-required UI step exits `3`: SDK adb sees `192.168.23.186:5555` / GT-I8190 at API 22, below minSdk 28, so instrumentation correctly remains not-run. Evidence: `artifacts/e2e/conformance-r55-emulator.json` and `artifacts/e2e/ui-e2e-r55.json`.
- UI-runner device classification tests pass **6/6**. Evidence: `artifacts/e2e/ui-runner-tests-r55.log`.
- Real Sample Lib qualification exits `0`; production sync routes, stable asset identity, cue/loop/region/tag semantics and canonical playlist identity/order qualify. Evidence: `artifacts/e2e/samplelib-real-r55.log`.
- Real-core exits `3` only for `android_real_backend_evidence_missing`; no validated Android-to-real-Sample-Lib pair/pull/push/receipt/reconnect evidence packet exists. Evidence: `artifacts/e2e/real-core-r55-20260929.log`.
- Strict matrix qualification exits `2`: exactly 38 required stories remain non-pass (25 blocked + 13 partial), and both `real-core-e2e` and hosted-CI evidence remain non-pass. Evidence: `artifacts/e2e/strict-matrix-r55-20260929.log`.
- No reproducible unowned implementation blocker was found. `e2e/parity/run_conformance.py` and `.github/workflows/android-e2e-conformance.yml` remain unchanged (SHA-256 `4ea8c95d9bac98d581b7032546f48cfdf438a35c07242b66d54d4dbe7ab47ab4` and `1fa4281c81873228594f238fa74687e880714fe58f798a95c79ac33dff780bfc`).

## R56 canonical replay — 2026-09-29

A fresh evidence-first replay at HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7` preserved the protected E2E runner, workflow, application, protocol, test-server and release-owner paths. The canonical matrix remains **49 pass / 13 partial / 25 blocked / 0 fail / 0 missing** across 87 required stories.

- Host conformance exits `0` with `host-preflight-passed-not-full-conformance`; ledger integrity, architecture boundary, all 39 backend/protocol tests, debug+release APK assembly, release debug-harness exclusion, JVM tests, Android-test compilation, lint and host UI all pass. Evidence: `artifacts/e2e/conformance-r56-host.json`.
- Emulator conformance exits `1` only because the device-required UI step exits `3`. SDK adb sees `192.168.23.186:5555` / GT-I8190 at API 22, below app minSdk 28, so instrumentation is correctly not run rather than misreported as a product failure. Evidence: `artifacts/e2e/conformance-r56-emulator.json` and `artifacts/e2e/ui-e2e-r56.json`.
- UI-runner compatibility/classification tests pass **6/6**. Evidence: `artifacts/e2e/ui-runner-tests-r56.log`.
- Real Sample Lib qualification exits `0`; production sync routes, stable asset identity, cue/loop/region/tag semantics and canonical playlist identity/order qualify. Evidence: `artifacts/e2e/samplelib-real-r56.log`.
- Real-core exits `3` with the sole blocker `android_real_backend_evidence_missing`; mere adb device presence is not promoted into Android-to-real-Sample-Lib evidence. Evidence: `artifacts/e2e/real-core-r56.log`.
- Strict matrix qualification exits `2`: 38 required stories remain non-pass (25 blocked + 13 partial), `real-core-e2e` is not pass, and hosted-CI evidence remains non-pass. Evidence: `artifacts/e2e/strict-matrix-r56.log`.
- No reproducible unowned implementation blocker was found. `e2e/parity/run_conformance.py` and `.github/workflows/android-e2e-conformance.yml` remain unchanged at SHA-256 `4ea8c95d9bac98d581b7032546f48cfdf438a35c07242b66d54d4dbe7ab47ab4` and `1fa4281c81873228594f238fa74687e880714fe58f798a95c79ac33dff780bfc`.

Machine-readable receipt: `artifacts/e2e/closure-r56.json`.

## R62 canonical replay — 2026-09-30

Canonical task: `ANDROIDJTOOLS-E2E-CLOSURE-R62-20260929`. A fresh replay at HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7` preserves the protected runner/workflow and all implementation lanes. The canonical matrix remains **49 pass / 13 partial / 25 blocked / 0 fail / 0 missing** across 87 required stories.

- Host conformance exits `0` after isolating Gradle cache/temp state under the repository. An initial attempt using the shared `/tmp/androidjtools-gradle` cache timed out on the Gradle wrapper lock; the isolated rerun passes ledger integrity, architecture boundary, all 39 backend/protocol tests, debug+release assembly, release isolation, host JVM/Android-test compilation/lint and host UI gates. Evidence: `artifacts/e2e/conformance-r62-host.json`.
- Emulator conformance exits `1` only at the device-required UI gate: SDK adb still sees GT-I8190 at API 22, below minSdk 28, so instrumentation is correctly not run. Evidence: `artifacts/e2e/conformance-r62-emulator.json` and `artifacts/e2e/ui-e2e.json`.
- Real Sample Lib qualification exits `0`; production sync routes, stable asset identity, cue/loop/region/tag semantics and canonical playlist identity/order qualify.
- Real-core exits `3` only for `android_real_backend_evidence_missing`; device presence is not promoted into Android-to-real-Sample-Lib proof.
- Strict matrix qualification exits `2`: 38 required stories remain non-pass, with real-core and hosted-CI evidence also non-pass. No reproducible unowned implementation defect was found.
- Protected `e2e/parity/run_conformance.py` and `.github/workflows/android-e2e-conformance.yml` remain unchanged at SHA-256 `4ea8c95d9bac98d581b7032546f48cfdf438a35c07242b66d54d4dbe7ab47ab4` and `1fa4281c81873228594f238fa74687e880714fe58f798a95c79ac33dff780bfc`.

Machine-readable receipt: `artifacts/e2e/closure-r62.json`.
