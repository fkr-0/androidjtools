# Android DJ Tools

Android DJ Tools is a cloudless Android DJ-library preparation client for Sample Lib and Sample Lab. It is built fixture-first so browsing, preparation, waveform editing, playback, analysis suggestions, and sync behavior can be developed and verified without making a remote backend mandatory.

## v0.2.0-rc.1 release candidate

The prepared candidate includes the Android application shell, dense library and collection workflows, player/queue and Media3 playback hardening, interactive preparation surfaces, offline/sync and intelligence flows, converged protocol contracts, deterministic fake-server qualification, parity tooling, and the hosted API-35 screenshot pipeline.

The project deliberately separates verified fixture/fake-server and host-build evidence from emulator, physical-device, and real-backend claims. The release notes record those evidence boundaries explicitly.

## Get the APK

GitHub Actions builds a non-debuggable release APK as the primary review artifact and a debug APK as a secondary diagnostic artifact. For local review, the release APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Source and CI

- [Repository](https://github.com/fkr-0/androidjtools)
- [GitHub Actions](https://github.com/fkr-0/androidjtools/actions)
- [v0.2.0-rc.1 candidate notes](release/v0.2.0-rc.1.md)
- [v0.1.3 review notes](release/v0.1.3.md)
- [v0.1.1 release notes](release/v0.1.1.md)
- [v0.1.0 release notes](release/v0.1.0.md)
- [Sync protocol decision](protocol-negotiation/decision.md)

## Qualification boundary

CI runs protocol/fake-server tests, Android JVM tests, instrumentation-source compilation, lint, debug/release APK assembly, release-launcher/debug-harness isolation checks, and separate API-35 emulator jobs under JDK 17. Physical-device and real-device-plus-real-backend behavior remain separate evidence classes and are not implied by a successful host build.

