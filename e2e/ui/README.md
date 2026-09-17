# Android DJ Tools UI/UX E2E

This lane combines host-executable correctness gates with the complete debug Android instrumentation suite. It deliberately distinguishes compile-ready evidence from real-device evidence.

## Run

```sh
python3 e2e/ui/run_ui_e2e.py
```

The default run executes unit tests, compiles the complete `androidTest` source set, runs lint, and assembles the debug APK. If a ready `adb` device is attached, it then runs every `connectedDebugAndroidTest`.

For a release/parity gate that must prove an Android target actually executed the suite:

```sh
python3 e2e/ui/run_ui_e2e.py --require-device --json-out /tmp/androidjtools-ui-e2e.json
```

Exit codes:

- `0`: requested qualification passed.
- `1`: host/JVM/build gate failed.
- `2`: Android instrumentation ran and failed.
- `3`: `--require-device` was requested but no ready `adb` device exists.

`--skip-host` is only for quick device-presence diagnostics after a host gate has already been recorded. It must not be used as parity evidence by itself.

## Coverage added by this convergence lane

- full top-level route traversal: Library → Lists → Prep → Suggest → Sync → Library;
- persistent mini-player/audition across route changes;
- deterministic debug provider/network/failure injection with journal and receipt visibility;
- one-tap metadata rating that remains staged until a local save intent;
- advisory intelligence matrix for BPM, key, cue, loop, region, stem and related-track explanations;
- 2× font-scale navigation/discoverability;
- all existing feature-specific Android tests, because the device phase runs the complete debug instrumentation suite.

## Evidence rule

A successful host-only run is `host-qualified-device-pending`, never real-device parity. Only a successful run with an attached Android target may report `real-device-ui-e2e-passed`.
