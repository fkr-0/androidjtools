# Android DJ Tools

Android DJ Tools is a cloudless Android DJ-library preparation client for Sample Lib and Sample Lab. It is built fixture-first so browsing, preparation, waveform editing, playback, analysis suggestions, and sync behavior can be developed and verified without making a remote backend mandatory.

## v0.1.0 preview

The first SemVer release establishes the Android application shell, dense library and collection workflows, player/queue foundations, interactive preparation surfaces, protocol contracts, deterministic fake-server qualification, and the first offline/sync implementation lanes.

The project deliberately separates verified fixture and fake-server behavior from real-device or real-backend claims. The release notes record those evidence boundaries explicitly.

## Get the APK

The release APK is produced by GitHub Actions from the exact release revision. Use the [v0.1.0 GitHub release](https://github.com/fkr-0/androidjtools/releases/tag/v0.1.0) for the published dogfood APK and checksums.

## Source and CI

- [Repository](https://github.com/fkr-0/androidjtools)
- [GitHub Actions](https://github.com/fkr-0/androidjtools/actions)
- [v0.1.0 release notes](release/v0.1.0.md)
- [Sync protocol decision](protocol-negotiation/decision.md)

## Qualification boundary

CI runs protocol/fake-server tests, Android JVM tests, instrumentation-source compilation, lint, and debug APK assembly under JDK 17. Device-backed behavior remains a separate evidence class and is not implied by a successful host or CI build.

