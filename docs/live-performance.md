# Live performance architecture

The live-performance surface is intentionally split into a deterministic control plane and an audio backend.

## Control path

`LivePerformanceController` owns the two deck states and accepts synchronous control calls. It does not suspend, perform I/O, or launch work from touch/MIDI callbacks. This is designed to keep UI feedback within the 10 ms control-path budget; the current automated suite verifies deterministic behavior and build integration, not a device-level timing guarantee.

Implemented controls:

- two-deck BPM sync with a bounded playback-rate ratio
- linear, equal-power, and adjustable-power crossfader curves
- filter, echo, flanger, and gate rack parameters
- 1/2/4/8/16-beat quantized loops
- eight hot-cue slots per deck
- waveform rendering with beat-grid, loop, hot-cue, and playhead overlays
- Android MIDI note/CC routing with replaceable bindings

When the active provider has no BPM/beat-grid metadata, the Live screen exposes a manual BPM fallback. This affects only the live session and does not mutate canonical preparation data.

## Audio backends

`LiveAudioEngine` is the real-time boundary. Calls from the controller must return quickly and hand work to their engine rather than blocking the UI thread.

`Media3LiveAudioEngine` is the compatibility backend used by the current device-media runtime. It provides actual independent A/B playback, gain, playback-rate sync, seeking, position reporting, A/B loop enforcement, and an audible per-deck PCM effects chain.

`Media3LiveDspAudioProcessor` inserts `LiveDspRack` into each deck's Media3 audio sink. The adapter reuses its scratch storage after warm-up and accepts both 16-bit and float PCM. `LiveDspRack` implements the four requested effects as an in-place processor:

- filter: one-pole low-pass with normalized cutoff
- echo: bounded feedback delay
- flanger: modulated short delay
- gate: threshold attenuation

The production low-latency target remains a direct native AAudio/OpenSL ES implementation of `LiveAudioEngine`. This project does not yet configure an external-native build or a direct native engine; an NDK is present on this host but is outside the configured `ANDROID_HOME` SDK tree, and no device AAudio/OpenSL qualification lane is wired here. The current Media3 backend ultimately writes through Android's platform audio sink and now exercises the requested FX end to end, but it is not presented as proof of a direct AAudio/OpenSL ES path or a sub-10-ms audio-I/O round trip.

## MIDI defaults

The initial mapping is intentionally small and replaceable at runtime:

| Input | Action |
| --- | --- |
| CC 1 ch 1 | Crossfader |
| Note 36 / 37 ch 1 | Deck A / B play |
| Note 38 / 39 ch 1 | Deck A / B sync |
| Note 40 / 41 ch 1 | Deck A / B hot cue 1 |
| Note 42 / 43 ch 1 | Deck A / B 4-beat loop |
| CC 16 / 17 ch 1 | Deck A / B filter mix |

Use `LivePerformanceController.bindMidi` to replace any physical control collision.

## Verification

Run with JDK 17. This project currently fails under the system JDK 26 before Gradle configuration.

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
    GRADLE_USER_HOME=/tmp/androidjtools-live-gradle \
    ./gradlew testDebugUnitTest

The isolated Gradle cache avoids relying on the host user's shared cache permissions.
