#!/usr/bin/env python3
"""Generate the EPIC-18 parity evidence ledger from the canonical story catalog.

This generator is intentionally fail-closed.  It records evidence that was actually
observed in the 2026-09-15 qualification run and keeps missing, partial, failed, or
blocked coverage visible.  An operator gate waiver permits this EPIC-18 lane to run;
it does not convert missing dependency acceptance or missing real-system evidence
into a pass.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STORIES_PATH = ROOT / "docs" / "user-stories.yml"
OUTPUT_PATH = ROOT / "docs" / "parity" / "parity.json"
TASK_ID = "ANDROIDJTOOLS-WAVE5-PARITY-E2E-20260915"
OBSERVED_AT = "2026-09-17T10:57:00Z"

STORY_RE = re.compile(
    r"^\s*-\s*\{id:\s*(US-\d+),\s*epic:\s*([^,]+),.*?verify:\s*([^}]+)\}\s*$"
)


def story_ids(*numbers: int) -> list[str]:
    return [f"US-{number:03d}" for number in numbers]


def evidence(
    *,
    tier: str,
    status: str,
    command: str | None,
    refs: list[str],
    result: str,
    environment: str,
) -> dict:
    return {
        "tier": tier,
        "status": status,
        "environment": environment,
        "command": command,
        "test_refs": refs,
        "observed_result": result,
    }


EVIDENCE = {
    "fixture-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --no-daemon --console=plain --rerun-tasks"
        ),
        refs=["app/src/test/java/dev/androidjtools/fixture/FixtureProvidersTest.kt"],
        result="Covered by the passing debug JVM unit suite; deterministic fixture providers are exercised.",
    ),
    "library-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.library.LibraryLogicTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/library/LibraryLogicTest.kt"],
        result="Covered by the passing debug JVM unit suite, including 10,000-track deterministic filtering.",
    ),
    "library-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.library.LibraryScreenTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/library/LibraryScreenTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "playback-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.playback.PlayerQueueControllerTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/playback/PlayerQueueControllerTest.kt"],
        result="Covered by the passing debug JVM unit suite; queue mutations, seek and recreation restore are tested.",
    ),
    "player-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.player.PlayerScreenTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/player/PlayerScreenTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "waveform-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.waveform.WaveformModelTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/waveform/WaveformModelTest.kt"],
        result="Covered by the passing JVM suite; viewport, grid, quantize, beat-jump and overlay model behavior is tested.",
    ),
    "waveform-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.waveform.WaveformScreenTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/waveform/WaveformScreenTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "beatgrid-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:testDebugUnitTest --no-daemon --console=plain"
        ),
        refs=["app/src/test/java/dev/androidjtools/prep/beatgrid/BeatGridPreparationTest.kt"],
        result="Observed in the passing full debug JVM suite; beat indexing, half/double BPM, phase/downbeat edits, candidate acceptance, undo and revision-safe restore are covered.",
    ),
    "beatgrid-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.prep.beatgrid.BeatGridEditorTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/prep/beatgrid/BeatGridEditorTest.kt"],
        result="Instrumentation source compiles in the fresh host gate; execution remains blocked because adb reports no attached device.",
    ),
    "cues-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.prep.cues.CueEditorTest "
            "--tests dev.androidjtools.prep.cues.PreparationStagingTest --no-daemon"
        ),
        refs=[
            "app/src/test/java/dev/androidjtools/prep/cues/CueEditorTest.kt",
            "app/src/test/java/dev/androidjtools/prep/cues/PreparationStagingTest.kt",
        ],
        result="Covered by the passing JVM suite; cue CRUD, roles, undo, staging and snap semantics are tested.",
    ),
    "cues-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.prep.cues.CueEditorSemanticsTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/prep/cues/CueEditorSemanticsTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "loops-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.prep.loops.LoopEditorTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/prep/loops/LoopEditorTest.kt"],
        result="Covered by the passing JVM suite; loop CRUD, resizing, roles, undo and canonical staging gates are tested.",
    ),
    "loops-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.prep.loops.LoopEditorSemanticsTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/prep/loops/LoopEditorSemanticsTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "metadata-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.metadata.MetadataLogicTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/metadata/MetadataLogicTest.kt"],
        result="Covered by the passing JVM suite; staged edits, validation, proposal acceptance and batch patching are tested.",
    ),
    "metadata-lane-c-host": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.ui.metadata.*' --no-daemon"
        ),
        refs=[
            "app/src/test/java/dev/androidjtools/ui/metadata/MetadataLogicTest.kt",
            "app/src/test/java/dev/androidjtools/ui/metadata/MetadataQuickActionTest.kt",
        ],
        result="Lane C observed the focused metadata host suite passing, including quick-rating actions and namespaced-tag staging/validation.",
    ),
    "metadata-quick-rating-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.metadata.MetadataQuickRatingTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/metadata/MetadataQuickRatingTest.kt"],
        result="One-tap five-star rating and namespaced-tag UI instrumentation exists and compiles; SDK adb reports zero attached devices, so it has not executed.",
    ),
    "namespaced-tag-roundtrip": evidence(
        tier="fake_backend",
        status="pass",
        environment="host-jvm+fake-sample-lib",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:testDebugUnitTest "
            "--tests dev.androidjtools.sync.journal.MutationJournalTest "
            "--tests dev.androidjtools.remote.samplelib.SampleLibClientTest "
            "--tests dev.androidjtools.offline.download.OfflineDownloadEngineTest --no-daemon"
        ),
        refs=[
            "app/src/test/java/dev/androidjtools/sync/journal/MutationJournalTest.kt",
            "app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibClientTest.kt",
        ],
        result=(
            "Lane B observed the focused suite passing. A namespaced mood:night tag is journaled offline, "
            "survives process recreation, is pushed through SampleLibClient, receives the authoritative canonical "
            "receipt, and retains exact-body retry semantics."
        ),
    ),
    "playlist-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.playlist.PlaylistLogicTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/playlist/PlaylistLogicTest.kt"],
        result="Covered by the passing JVM suite; membership/order, hierarchy, CRUD recovery and smart-rule preview are tested.",
    ),
    "collections-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.collections.CollectionsScreenTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/collections/CollectionsScreenTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "offline-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.offline.download.OfflineDownloadEngineTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/offline/download/OfflineDownloadEngineTest.kt"],
        result="Covered by the passing JVM suite; range resume, process-death restore, integrity and pin-safe quota behavior are tested.",
    ),
    "sync-contract": evidence(
        tier="fake_backend",
        status="pass",
        environment="host-python",
        command="cd test-server && python3 -m unittest -v test_contracts test_fake_sync_server",
        refs=["test-server/test_contracts.py", "test-server/test_fake_sync_server.py"],
        result="Observed 26 tests passing, including idempotent replay, receipt recovery, conflict and protocol mismatch states.",
    ),
    "journal-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.sync.journal.MutationJournalTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/sync/journal/MutationJournalTest.kt"],
        result="Covered by the passing JVM suite; durable-before-success, process death, immutable replay and compaction are tested.",
    ),
    "conflict-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.sync.conflict.ConflictResolverTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/sync/conflict/ConflictResolverTest.kt"],
        result=(
            "Observed in the passing JVM suite. Disjoint protocol-authorized SAFE_FIELDWISE edits auto-merge, including deletion; "
            "same-field, undeclared-field, ordered-collection, delete/edit, identity and schema conflicts still fail closed to user review."
        ),
    ),
    "samplelib-client-unit": evidence(
        tier="fake_backend",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.remote.samplelib.SampleLibClientTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibClientTest.kt"],
        result="Covered by the passing JVM suite; pairing, pull reset, ambiguous delivery recovery, range resume and integrity are tested against a fake transport.",
    ),
    "samplelib-fake-http": evidence(
        tier="fake_backend",
        status="pass",
        environment="host-python-loopback",
        command="cd test-server/samplelib && python3 -m unittest -v test_fake_samplelib_server",
        refs=["test-server/samplelib/test_fake_samplelib_server.py"],
        result="Observed 7 authenticated fake Sample Lib HTTP tests passing, including disconnect-after-commit idempotent recovery and range integrity.",
    ),
    "djxml-policy-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.interop.DjxmlInteropQualificationTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/interop/DjxmlInteropQualificationTest.kt"],
        result="Covered by the passing JVM suite; policy fails closed when real sync or canonical playlist authority is absent.",
    ),
    "samplelib-real-djxml": evidence(
        tier="real_backend",
        status="pass",
        environment="local-real-sample-lib",
        command="python3 e2e/djxml/qualify_samplelib.py --require-real-sync",
        refs=["e2e/djxml/qualify_samplelib.py", "/home/user/code/sample-lab/third_party/sample-lib"],
        result=(
            "Fresh --require-real-sync qualification passed against the real Sample Lib checkout: adapter and playlist-authority tests pass, "
            "stable asset/cue/loop/region/tag semantics are preserved, canonical playlist identity/order is present, and production "
            "/v1/sync/hello, /v1/sync/pair, /v1/sync/pull, /v1/sync/push and receipt routes are exposed."
        ),
    ),
    "analysis-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.analysis.AnalysisPresentationTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/analysis/AnalysisPresentationTest.kt"],
        result="Covered by the passing JVM suite; provenance, stale/offline review, capability degradation, timeline candidates and safety separation are tested.",
    ),
    "analysis-lane-c-host": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:testDebugUnitTest "
            "--tests 'dev.androidjtools.remote.samplelib.SampleLibAnalysisTest' "
            "--tests 'dev.androidjtools.sync.journal.AnalysisSuggestionAcceptanceTest' "
            "--tests 'dev.androidjtools.ui.analysis.AnalysisPresentationTest' "
            "--tests 'dev.androidjtools.ui.analysis.AnalysisCoverageE2ETest' --no-daemon"
        ),
        refs=[
            "app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibAnalysisTest.kt",
            "app/src/test/java/dev/androidjtools/sync/journal/AnalysisSuggestionAcceptanceTest.kt",
            "app/src/test/java/dev/androidjtools/ui/analysis/AnalysisPresentationTest.kt",
            "app/src/test/java/dev/androidjtools/ui/analysis/AnalysisCoverageE2ETest.kt",
        ],
        result=(
            "Lane C observed the focused host suite passing. It covers related-track dimensions/provenance, "
            "CUE/LOOP/REGION transport decoding, stale fail-closed refresh, journal-only acceptance, receipt-gated "
            "canonicalization and the core/UI multi-kind candidate matrix."
        ),
    ),
    "analysis-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.analysis.AnalysisAssistantContentTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/analysis/AnalysisAssistantContentTest.kt"],
        result="Not run: adb reported no attached devices.",
    ),
    "audio-focus-unit": evidence(
        tier="fixture",
        status="pass",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.playback.AudioFocusPolicyTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/playback/AudioFocusPolicyTest.kt"],
        result="Covered by the passing JVM suite; transient/permanent loss, duck and gain behavior are tested.",
    ),
    "system-media-device": evidence(
        tier="real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle ./gradlew connectedDebugAndroidTest"
        ),
        refs=[
            "app/src/main/java/dev/androidjtools/playback/Media3PlaybackService.kt",
            "app/src/main/AndroidManifest.xml",
        ],
        result="No attached device and no dedicated MediaSession/Bluetooth instrumentation test was found.",
    ),
    "ui-host-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command="python3 e2e/ui/run_ui_e2e.py --require-device",
        refs=[
            "e2e/ui/run_ui_e2e.py",
            "app/src/androidTest/java/dev/androidjtools/e2e/AppShellE2ETest.kt",
        ],
        result="Fresh host gate passes unit tests, Android-test compilation, lint and assemble; instrumentation remains blocked because adb reports no attached device.",
    ),
    "lane-a-visual-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.visual.LaneAVisualParityTest"
        ),
        refs=[
            "app/src/androidTest/java/dev/androidjtools/visual/LaneAVisualParityTest.kt",
            "app/src/androidTest/java/dev/androidjtools/ui/library/LibraryScreenTest.kt",
        ],
        result=(
            "Lane A added device-rendered PNG evidence for US-011 and US-040/043 plus semantic assertions for "
            "artwork/compact metadata. The complete Android-test source set compiles, but zero attached devices means "
            "these visual assertions have not executed."
        ),
    ),
    "androidtest-compile": evidence(
        tier="host_compile",
        status="pass",
        environment="host-jdk17",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew :app:compileDebugAndroidTestKotlin --no-daemon --console=plain"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools"],
        result=(
            "Fresh full debug Android-test Kotlin source compilation passed after all four lanes converged. "
            "This proves source/build integration only and is not device execution evidence."
        ),
    ),
    "architecture-provider-boundary": evidence(
        tier="host_static",
        status="pass",
        environment="host-python",
        command="python3 e2e/parity/check_architecture_boundary.py",
        refs=[
            "e2e/parity/check_architecture_boundary.py",
            "app/src/main/java/dev/androidjtools/core/provider/Providers.kt",
            "app/src/main/java/dev/androidjtools/ui",
        ],
        result=(
            "Fresh dependency-boundary scan inspected 17 product UI Kotlin files, found no direct imports from "
            "fixture/remote/sync/debug/interop implementation packages, and verified the provider contract seam."
        ),
    ),
    "debug-harness-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.debug.DebugHarnessActivityTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/debug/DebugHarnessActivityTest.kt"],
        result="Debug provider/failure injection plus journal/receipt instrumentation exists and compiles, but cannot execute without an attached Android device.",
    ),
    "scaled-font-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.a11y.ScaledFontNavigationTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/a11y/ScaledFontNavigationTest.kt"],
        result="The 2x font-scale navigation/discoverability instrumentation exists and compiles; execution remains blocked by device absence.",
    ),
    "debug-harness-release-exclusion": evidence(
        tier="release_artifact",
        status="pass",
        environment="host-apkanalyzer",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --console=plain && "
            "python3 app/src/debug/verify_debug_harness.py "
            "--debug-apk app/build/outputs/apk/debug/app-debug.apk "
            "--release-apk app/build/outputs/apk/release/app-release-unsigned.apk"
        ),
        refs=[
            "app/src/debug/verify_debug_harness.py",
            "app/src/debug/AndroidManifest.xml",
            "app/src/debug/assets/debug-harness-contract.json",
        ],
        result=(
            "Fresh release build and APK inspection passed: DebugHarnessActivity and debug-harness-contract.json "
            "are present in debug and absent from release. Release APK sha256 "
            "3974f1e0debd6080d78bec32745fb5d8440c4e11becb4b0787bafa7a9e0b0d6c."
        ),
    ),
    "a11y-shell-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "./gradlew :app:connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.a11y.TalkBackSemanticsTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/a11y/TalkBackSemanticsTest.kt"],
        result=(
            "Primary shell/library/waveform accessibility semantics assertions exist and compile in the full "
            "Android-test source set, but no attached Android target exists to execute them."
        ),
    ),
    "a11y-library-device": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew connectedDebugAndroidTest "
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.ui.library.LibraryScreenTest"
        ),
        refs=["app/src/androidTest/java/dev/androidjtools/ui/library/LibraryScreenTest.kt::keyControlsHaveAccessibilitySemantics"],
        result="Semantics assertion exists but was not run because adb reported no attached devices.",
    ),
    "performance-10k-unit": evidence(
        tier="fixture",
        status="partial",
        environment="host-jvm",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk "
            "GRADLE_USER_HOME=/tmp/androidjtools-gradle "
            "./gradlew testDebugUnitTest --tests dev.androidjtools.ui.library.LibraryLogicTest --no-daemon"
        ),
        refs=["app/src/test/java/dev/androidjtools/ui/library/LibraryLogicTest.kt::large library filtering is deterministic and preserves stable ids"],
        result="10,000-track logic coverage passes, but it is not a frame/latency benchmark and therefore cannot prove interactive performance.",
    ),
    "performance-device-gate": evidence(
        tier="emulator_or_real_device",
        status="blocked",
        environment="android-device",
        command="python3 e2e/ui/run_ui_e2e.py --require-device",
        refs=[
            "app/src/androidTest/java/dev/androidjtools/performance/PerformanceRegressionTest.kt",
            "e2e/ui/run_ui_e2e.py",
        ],
        result=(
            "A reproducible Android instrumentation regression tier now compiles: it measures 10,000-track "
            "initial/search latency and Window FrameMetrics during waveform gestures with explicit fail-closed "
            "thresholds, then emits device JSON. The SDK adb probe resolved /opt/android-sdk/platform-tools/adb "
            "but found zero attached devices, so no timing result is claimed."
        ),
    ),
    "resilience-host-integration": evidence(
        tier="fake_backend",
        status="pass",
        environment="host-jvm+fake-sample-lib",
        command=(
            "JAVA_HOME=/usr/lib/jvm/java-17-openjdk GRADLE_USER_HOME=$PWD/.gradle-local "
            "./gradlew :app:testDebugUnitTest "
            "--tests dev.androidjtools.sync.journal.MutationJournalTest "
            "--tests dev.androidjtools.remote.samplelib.SampleLibClientTest "
            "--tests dev.androidjtools.offline.download.OfflineDownloadEngineTest --no-daemon"
        ),
        refs=[
            "app/src/test/java/dev/androidjtools/sync/journal/MutationJournalTest.kt",
            "app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibClientTest.kt",
            "app/src/test/java/dev/androidjtools/offline/download/OfflineDownloadEngineTest.kt",
        ],
        result=(
            "Lane B observed the focused resilience suite passing for offline journaling, process recreation, "
            "reconnect/push, canonical receipts, exact replay, and offline download integrity. The complementary "
            "fake HTTP/protocol suite passed 36/36; real-device traversal remains separate."
        ),
    ),
    "parity-validator": evidence(
        tier="hosted_ci_compatible",
        status="pass",
        environment="host-python",
        command="python3 e2e/parity/validate_matrix.py",
        refs=["e2e/parity/validate_matrix.py", "docs/parity/parity.json"],
        result="Structural validator is required to pass before this generated ledger is accepted; qualification mode still fails while red stories remain.",
    ),
    "real-core-e2e": evidence(
        tier="real_device_plus_real_backend",
        status="blocked",
        environment="android-device+real-sample-lib",
        command="python3 e2e/parity/run_core_e2e.py --require-real",
        refs=["e2e/parity/run_core_e2e.py"],
        result=(
            "Fresh real-core probe reports the real Sample Lib server side fully qualified, but no attached Android "
            "device and no androidjtools.real-device-backend-evidence/v1 packet from an actual Android -> real "
            "Sample Lib pair/pull/push/receipt/reconnect traversal are available. Device presence alone is rejected."
        ),
    ),
    "hosted-ci": evidence(
        tier="hosted_ci",
        status="missing",
        environment="hosted-ci",
        command=None,
        refs=[],
        result="No hosted-CI EPIC-18 execution evidence was available in this run.",
    ),
}

PLAN: dict[str, dict] = {}


def assign(ids: list[str], status: str, evidence_ids: list[str], gap: str | None = None) -> None:
    for story_id in ids:
        if story_id in PLAN:
            raise RuntimeError(f"duplicate parity plan for {story_id}")
        PLAN[story_id] = {
            "status": status,
            "evidence": evidence_ids,
            **({"gap": gap} if gap else {}),
        }


# EPIC-00 foundation
assign(story_ids(1), "blocked", ["ui-host-device"], "The fixture-first APK host gate passes, but required install/launch evidence cannot run without an attached Android device.")
assign(story_ids(2), "blocked", ["ui-host-device"], "AppShellE2ETest traverses every major fixture-backed route and compiles, but device instrumentation cannot execute without an attached Android device.")
assign(story_ids(3), "pass", ["fixture-unit"])
assign(story_ids(4), "pass", ["architecture-provider-boundary"])

# EPIC-01 library
assign(story_ids(10), "partial", ["library-unit", "library-device"], "Dense-list logic is covered; actual device interaction remains unexecuted.")
assign(story_ids(11), "blocked", ["androidtest-compile", "lane-a-visual-device"], "Artwork/compact-metadata semantic and PNG capture assertions now exist and compile, but no Android target is attached to render the visual evidence.")
assign(story_ids(12, 13, 14), "blocked", ["library-device"], "Matching Compose instrumentation exists but no Android device is attached.")

# EPIC-02 search/filter
assign(story_ids(20, 21, 22, 23), "pass", ["library-unit"])

# EPIC-03 playback/queue
assign(story_ids(30, 31), "blocked", ["player-device"], "The user-facing playback surfaces require instrumentation evidence; no Android device is attached.")
assign(story_ids(32, 33, 34), "pass", ["playback-unit"])

# EPIC-04 waveform
assign(story_ids(40), "blocked", ["waveform-device", "lane-a-visual-device"], "Waveform density/render and device-rendered overview/detail/overlay evidence exist, but no Android target is attached.")
assign(story_ids(41, 42), "partial", ["waveform-unit", "waveform-device"], "Deterministic model behavior passes; gesture/render instrumentation remains blocked by device availability.")
assign(story_ids(43), "partial", ["waveform-unit", "waveform-device", "lane-a-visual-device"], "Overlay model behavior passes and device-rendered canonical/candidate legend evidence exists, but Android execution remains blocked by device availability.")

# EPIC-05 cues/loops
assign(story_ids(50, 51), "pass", ["cues-unit"])
assign(story_ids(52), "pass", ["loops-unit"])
assign(story_ids(53, 54), "pass", ["cues-unit", "loops-unit"])
assign(story_ids(55), "partial", ["resilience-host-integration", "cues-device", "loops-device"], "Cue/loop preparation now survives durable journaling, process recreation, reconnect and authoritative receipt at the host/backend tier; the integrated offline editor interaction still has not run on an Android target.")

# EPIC-06 beatgrid
assign(story_ids(60), "partial", ["beatgrid-unit", "beatgrid-device"], "Beat-grid model/editor semantics pass on the host; rendered device interaction remains blocked by device absence.")
assign(story_ids(61), "pass", ["beatgrid-unit"])
assign(story_ids(62), "pass", ["beatgrid-unit"])
assign(story_ids(63), "pass", ["beatgrid-unit", "cues-unit"])

# EPIC-07 metadata
assign(story_ids(70), "pass", ["metadata-unit"])
assign(story_ids(71), "partial", ["metadata-lane-c-host", "androidtest-compile", "metadata-quick-rating-device"], "One-tap five-star rating instrumentation exists and compiles, while host quick-action logic passes; device interaction remains unexecuted.")
assign(story_ids(72), "pass", ["metadata-unit", "namespaced-tag-roundtrip"])
assign(story_ids(73), "pass", ["metadata-unit"])

# EPIC-08 playlists
assign(story_ids(80, 81, 82, 83, 84), "pass", ["playlist-unit"])

# EPIC-09 offline downloads
assign(story_ids(90), "pass", ["offline-unit"])
assign(story_ids(91), "partial", ["offline-unit"], "Progress/error engine state is covered, but no device UI progress assertion is present.")
assign(story_ids(92, 93), "pass", ["offline-unit"])
assign(story_ids(94), "partial", ["offline-unit", "resilience-host-integration"], "Cached-media resolution plus offline metadata/preparation journal persistence and reconnect pass on the host; actual cached playback/editing remains unexecuted on Android hardware/emulator.")

# EPIC-10 protocol
assign(story_ids(100, 101, 102, 103, 104), "pass", ["sync-contract"])

# EPIC-11 mutation journal/conflicts
assign(story_ids(110, 111, 112), "pass", ["journal-unit", "sync-contract"])
assign(story_ids(113), "pass", ["conflict-unit"])
assign(story_ids(114), "pass", ["conflict-unit"])

# EPIC-12 Sample Lib integration
assign(story_ids(120, 121, 122, 123), "blocked", ["samplelib-client-unit", "samplelib-fake-http", "samplelib-real-djxml", "real-core-e2e"], "Pairing/pull/push/receipt/reconnect behavior passes at client/fake tiers and the real Sample Lib production sync facade qualifies; Android-to-real-backend traversal remains unobserved and requires both an attached Android target and a validated real-device-backend evidence packet.")

# EPIC-13 DJXML interoperability
assign(story_ids(130, 131, 132), "blocked", ["djxml-policy-unit", "samplelib-real-djxml", "real-core-e2e"], "Real Sample Lib adapter, production sync, and canonical playlist identity/order qualify; Android-to-real-backend traversal still lacks an attached target plus validated real-device-backend evidence.")

# EPIC-14 advisory intelligence
assign(story_ids(140), "pass", ["analysis-unit"])
assign(story_ids(141), "pass", ["analysis-lane-c-host"])
assign(story_ids(142, 143), "pass", ["analysis-unit"])
assign(story_ids(144), "partial", ["analysis-lane-c-host", "analysis-device"], "BPM/key/cue/loop/region/related kinds are covered through transport and BPM/key/cue/loop/region/stem/related at core/UI, but the current analysis-candidate/v1 transport contract deliberately has no stem kind; the decoder fails that unsupported wire shape closed rather than inventing it.")
assign(story_ids(145), "pass", ["analysis-unit"])
assign(story_ids(146), "partial", ["analysis-lane-c-host", "analysis-device"], "Journal-only acceptance and APPLIED/NO_OP receipt-gated canonicalization pass on the host, including stale/unknown fail-closed behavior; explicit accept/reject UI instrumentation compiles but is blocked by device availability.")
assign(story_ids(147, 148, 149), "pass", ["analysis-unit"])

# EPIC-15 system media
assign(story_ids(150, 151), "blocked", ["system-media-device"], "Media3 service wiring exists, but no real-device MediaSession/Bluetooth evidence is available.")
assign(story_ids(152), "pass", ["audio-focus-unit"])

# EPIC-16 debug harness
assign(story_ids(160, 161, 162), "blocked", ["androidtest-compile", "debug-harness-device"], "Dedicated debug-harness instrumentation compiles and covers provider/failure injection plus journal/receipt inspection, but cannot execute without an attached Android device.")
assign(story_ids(163), "pass", ["debug-harness-release-exclusion"])

# EPIC-17 accessibility/performance
assign(story_ids(170), "blocked", ["androidtest-compile", "a11y-shell-device", "a11y-library-device", "cues-device", "loops-device"], "TalkBack-oriented semantics assertions compile across primary surfaces, but no Android target is attached to execute traversal/semantics behavior.")
assign(story_ids(171), "blocked", ["androidtest-compile", "scaled-font-device"], "The 2x font-scale test now requires essential navigation/search actions to remain displayed and compiles, but cannot execute without an attached Android target.")
assign(story_ids(172), "partial", ["performance-10k-unit", "androidtest-compile", "performance-device-gate"], "10,000-track deterministic host logic passes and a thresholded device interaction gate is implemented/compiled, but no device timing has been observed.")
assign(story_ids(173), "blocked", ["androidtest-compile", "performance-device-gate"], "A thresholded FrameMetrics waveform gesture gate is implemented and compiles, but no attached Android target exists to produce frame-time evidence.")

# EPIC-18 parity acceptance itself
assign(story_ids(180), "pass", ["parity-validator"])
assign(story_ids(181), "blocked", ["samplelib-real-djxml", "real-core-e2e"], "Real Sample Lib adapter, sync facade, and playlist authority qualify, but no actual Android -> real Sample Lib traversal has produced the required validated device-backend evidence packet.")
assign(story_ids(182), "partial", ["resilience-host-integration", "conflict-unit", "samplelib-fake-http", "real-core-e2e"], "Offline/reconnect/process-death resilience passes at integrated host/fake-backend tiers; the required real-device plus real-backend traversal remains blocked.")


def load_stories() -> list[dict]:
    stories: list[dict] = []
    for line in STORIES_PATH.read_text(encoding="utf-8").splitlines():
        match = STORY_RE.match(line)
        if not match:
            continue
        story_id, epic, verify = match.groups()
        stories.append({"id": story_id, "epic": epic.strip(), "verify": verify.strip()})
    if not stories:
        raise RuntimeError(f"no user stories parsed from {STORIES_PATH}")
    return stories


def main() -> int:
    catalog = load_stories()
    required = [story for story in catalog if story["epic"] != "EPIC-19-PACKAGING-RELEASE"]
    excluded = [story for story in catalog if story["epic"] == "EPIC-19-PACKAGING-RELEASE"]

    required_ids = {story["id"] for story in required}
    planned_ids = set(PLAN)
    missing_plan = sorted(required_ids - planned_ids)
    extra_plan = sorted(planned_ids - required_ids)
    if missing_plan or extra_plan:
        raise RuntimeError(f"parity plan mismatch: missing={missing_plan}, extra={extra_plan}")

    stories = []
    for source in required:
        planned = PLAN[source["id"]]
        unknown_evidence = sorted(set(planned["evidence"]) - set(EVIDENCE))
        if unknown_evidence:
            raise RuntimeError(f"{source['id']} references unknown evidence: {unknown_evidence}")
        stories.append({**source, **planned})

    status_counts: dict[str, int] = {}
    for story in stories:
        status_counts[story["status"]] = status_counts.get(story["status"], 0) + 1

    matrix = {
        "schema": "androidjtools.parity/v1",
        "task_id": TASK_ID,
        "observed_at": OBSERVED_AT,
        "qualification": "not_qualified",
        "required_story_rule": (
            "Every catalog story through EPIC-18 is required. EPIC-19 is excluded because it is downstream of EPIC-18."
        ),
        "operator_override": {
            "dependency_gate_waived_for_execution": True,
            "dependency_acceptance_waived_for_qualification": False,
            "ocp_authenticated_binding_obtained": False,
            "authority_note": (
                "This refresh runs through the authenticated project manager under workflow packet "
                "a0655a7e-9ced-4145-acd6-301b2cbbc3bd and its active path claim; no separate canonical OCP task binding is asserted. "
                "The execution waiver still does not promote red evidence."
            ),
        },
        "independent_review": {
            "requested": True,
            "status": "pending_workflow_review",
            "reason": "The refreshed EPIC-18 packet requires independent read-only review after submission; approval is not pre-claimed.",
            "qualification_requires_approval": True,
            "policy": "independent-review",
            "reviewer_independent": False,
            "reviewed_revision": None,
        },
        "dependency_gate": {
            "dependencies": [
                "EPIC-02-SEARCH-FILTER",
                "EPIC-05-CUES-LOOPS",
                "EPIC-06-BEATGRID",
                "EPIC-08-PLAYLISTS",
                "EPIC-09-OFFLINE-DOWNLOADS",
                "EPIC-11-MUTATION-JOURNAL-CONFLICTS",
                "EPIC-12-SAMPLELIB-INTEGRATION",
                "EPIC-14-ANALYSIS-RELATED",
                "EPIC-15-ANDROID-MEDIA-INTEGRATION",
                "EPIC-17-ACCESSIBILITY-PERFORMANCE",
            ],
            "execution_override": True,
            "qualification_requires_normal_acceptance": True,
        },
        "environment": {
            "host_java": "17 (explicit /usr/lib/jvm/java-17-openjdk; system default 26.0.2.1 is incompatible with this Gradle/Kotlin toolchain)",
            "gradle_user_home": "$PWD/.gradle-local (repository-local isolated writable cache for the fresh integrated gate)",
            "android_device": "blocked: /opt/android-sdk/platform-tools/adb is healthy but reports zero attached devices; /usr/bin/adb is ignored because its host libusb linkage is broken",
            "real_sample_lib": "/home/user/code/sample-lab/third_party/sample-lib",
            "real_sample_lib_sync": "qualified: /v1/sync/hello, /v1/sync/pair, /v1/sync/pull, /v1/sync/push and receipt routes present",
            "real_sample_lib_playlist_authority": "qualified: canonical playlist identity/order tests pass",
            "hosted_ci": "not observed in this run",
        },
        "status_counts": status_counts,
        "evidence": EVIDENCE,
        "stories": stories,
        "excluded_stories": [
            {**story, "reason": "EPIC-19 is downstream of EPIC-18 and cannot be a prerequisite for its own parity gate."}
            for story in excluded
        ],
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(matrix, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(OUTPUT_PATH.relative_to(ROOT)), "required_stories": len(stories), "status_counts": status_counts}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
