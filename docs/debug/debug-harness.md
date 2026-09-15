# EPIC-16 debug harness

The Android DJ Tools debug build has a dedicated dogfood launcher that is compiled only from `app/src/debug/**`. It wraps the normal backend-neutral `AndroidDjToolsApp` with deterministic diagnostics and state injection; release builds continue to use the production launcher and contain none of the debug activity, debug contract asset, or controls.

## Controls

The top debug panel cycles five independent selectors:

- **Provider** — local fixture or a backend simulator identity. Neither option performs ambient network I/O; the simulator is a deterministic provider surface for dogfood UX.
- **Fixture dataset** — nominal, empty, a deterministic 512-track large library, or a failure dataset.
- **Network** — online, offline, or degraded.
- **Declared sync state** — every value of the production `SyncState` enum. With no overriding failure and an online network, the selected value is injected exactly.
- **Failure mode** — provider error, conflict, download failure, mutation rejection, auth required, server incompatibility, local migration requirement, or backend-health failure.

The panel also exposes effective sync state, backend endpoint/protocol/health/latency, a deterministic download probe, pending mutations, and recent mutation receipts. Conflict and mutation-rejection modes add matching receipts, and download-failure mode forces a failed `TrackDownloadState` with a stable diagnostic message.

## Determinism contract

`app/src/debug/assets/debug-harness-contract.json` is the machine-checkable source-set contract. The debug activity fails fast if its selector enums or the production `SyncState` enum drift from that contract. Fixed IDs, `Instant.EPOCH`, a fixed large-library cardinality of 512, and deterministic arithmetic track generation make repeated injections byte-for-behavior stable for a given configuration.

Failure modes may intentionally override the selected sync state: conflict, auth-required, server-incompatible, and local-migration failures force their matching sync states; offline forces `OFFLINE`; degraded networking converts `ONLINE_IDLE` to `DEGRADED`. With `NONE` + `ONLINE`, all production sync states remain directly injectable.

## Focused verification

From the repository root, after both variants build:

    ./gradlew :app:assembleDebug :app:assembleRelease
    python app/src/debug/verify_debug_harness.py \
      --debug-apk app/build/outputs/apk/debug/app-debug.apk \
      --release-apk app/build/outputs/apk/release/app-release-unsigned.apk

The verifier checks that the JSON matrix exactly covers the production sync enum and all debug selectors, that deterministic/failure hooks remain present, and that the debug launcher class plus contract asset exist in the debug APK but not the release APK.
