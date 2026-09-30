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

`Media3LiveAudioEngine` is the compatibility backend used by the current device-media runtime. It provides actual independent A/B playback, gain, playback-rate sync, seeking, position reporting, and A/B loop enforcement.

`LiveDspRack` implements the four requested effects as an allocation-free, in-place PCM processor:

- filter: one-pole low-pass with normalized cutoff
- echo: bounded feedback delay
- flanger: modulated short delay
- gate: threshold attenuation

The PCM rack is deliberately not claimed as audible in the Media3 compatibility backend. The production low-latency target is a native AAudio/OpenSL ES implementation of `LiveAudioEngine` that feeds decoded PCM through `LiveDspRack` (or a native equivalent) in the audio callback. No AAudio/OpenSL ES toolchain is configured in this checkout, so that backend remains the release blocker for a strict end-to-end sub-10-ms audio-I/O claim.

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
