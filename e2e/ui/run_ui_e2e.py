#!/usr/bin/env python3
"""Fail-closed Android DJ Tools UI/UX E2E runner.

Host/JVM gates are always useful. Android instrumentation is executed only when
adb exposes at least one ready device; --require-device turns device absence into
a distinct non-zero qualification result instead of silently promoting compile
coverage to real-device evidence.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JAVA_HOME = Path("/usr/lib/jvm/java-17-openjdk")
DEFAULT_GRADLE_HOME = Path("/tmp/androidjtools-gradle")
DEFAULT_PERFORMANCE_JSON = ROOT / "artifacts" / "e2e" / "device-performance.json"
DEBUG_APPLICATION_ID = "dev.androidjtools.debug"
DEVICE_PERFORMANCE_FILE = "files/epic17-performance.json"


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "--require-device",
        action="store_true",
        help="exit 3 when no adb device is available; required for real-device qualification",
    )
    result.add_argument(
        "--skip-host",
        action="store_true",
        help="skip host Gradle gates (diagnostic/device-presence checks only)",
    )
    result.add_argument("--json-out", type=Path, help="optional machine-readable result path")
    result.add_argument(
        "--performance-json-out",
        type=Path,
        default=DEFAULT_PERFORMANCE_JSON,
        help="aggregate JSON pulled from the EPIC-17 device performance instrumentation gate",
    )
    return result


def environment() -> dict[str, str]:
    env = os.environ.copy()
    if "JAVA_HOME" not in env and DEFAULT_JAVA_HOME.exists():
        env["JAVA_HOME"] = str(DEFAULT_JAVA_HOME)
    env.setdefault("GRADLE_USER_HOME", str(DEFAULT_GRADLE_HOME))
    return env


def run(command: list[str], env: dict[str, str]) -> dict[str, Any]:
    print("+", " ".join(command), flush=True)
    started = time.monotonic()
    completed = subprocess.run(command, cwd=ROOT, env=env, text=True)
    return {
        "command": command,
        "exit_code": completed.returncode,
        "duration_seconds": round(time.monotonic() - started, 3),
    }


def resolve_adb(env: dict[str, str]) -> str | None:
    candidates: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = env.get(variable)
        if sdk:
            candidates.append(Path(sdk) / "platform-tools" / "adb")
    # Arch/system packaging can occasionally leave a host adb linked against an
    # incompatible libusb while the SDK copy remains healthy. Prefer the SDK.
    candidates.append(Path("/opt/android-sdk/platform-tools/adb"))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return shutil.which("adb", path=env.get("PATH"))


def adb_devices(env: dict[str, str]) -> tuple[list[str], str | None, str | None]:
    adb = resolve_adb(env)
    if adb is None:
        return [], "adb-not-found", None
    completed = subprocess.run(
        [adb, "devices", "-l"],
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        return [], f"adb-failed:{completed.stderr.strip() or completed.returncode}", adb
    serials: list[str] = []
    for line in completed.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            serials.append(fields[0])
    return serials, None, adb


def write_result(path: Path | None, payload: dict[str, Any]) -> None:
    if path is None:
        return
    destination = path if path.is_absolute() else ROOT / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {destination}")


def pull_performance_evidence(
    serials: list[str], env: dict[str, str], output: Path, adb: str
) -> tuple[bool, dict[str, Any]]:
    device_results: list[dict[str, Any]] = []
    ok = True
    for serial in serials:
        completed = subprocess.run(
            [
                adb,
                "-s",
                serial,
                "exec-out",
                "run-as",
                DEBUG_APPLICATION_ID,
                "cat",
                DEVICE_PERFORMANCE_FILE,
            ],
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        record: dict[str, Any] = {
            "serial": serial,
            "exit_code": completed.returncode,
        }
        try:
            evidence = json.loads(completed.stdout) if completed.returncode == 0 else None
        except json.JSONDecodeError as exc:
            evidence = None
            record["error"] = f"invalid-json:{exc}"
        if evidence is None:
            ok = False
            record.setdefault("error", completed.stderr.strip() or "performance-evidence-missing")
        else:
            valid = (
                evidence.get("schema") == "androidjtools.epic17-performance/v1"
                and evidence.get("library", {}).get("status") == "pass"
                and evidence.get("waveform", {}).get("status") == "pass"
            )
            record["evidence"] = evidence
            record["status"] = "pass" if valid else "fail"
            ok = ok and valid
        device_results.append(record)

    payload = {
        "schema": "androidjtools.device-performance-evidence/v1",
        "application_id": DEBUG_APPLICATION_ID,
        "devices": device_results,
        "status": "pass" if ok else "fail",
    }
    write_result(output, payload)
    return ok, payload


def main() -> int:
    args = parser().parse_args()
    env = environment()
    result: dict[str, Any] = {
        "schema": "androidjtools.ui-e2e/v1",
        "root": str(ROOT),
        "host": {"status": "skipped" if args.skip_host else "pending"},
        "device": {"required": args.require_device},
        "instrumentation": {"status": "not-run"},
    }

    if not args.skip_host:
        host = run(
            [
                "./gradlew",
                ":app:testDebugUnitTest",
                ":app:compileDebugAndroidTestKotlin",
                ":app:lintDebug",
                ":app:assembleDebug",
                "--no-daemon",
                "--console=plain",
            ],
            env,
        )
        result["host"] = {**host, "status": "passed" if host["exit_code"] == 0 else "failed"}
        if host["exit_code"] != 0:
            result["qualification"] = "host-failed"
            write_result(args.json_out, result)
            return 1

    devices, device_error, adb = adb_devices(env)
    result["device"].update(
        {
            "serials": devices,
            "count": len(devices),
            "status": "available" if devices else "unavailable",
            "error": device_error,
            "adb": adb,
        }
    )

    if not devices:
        result["instrumentation"] = {
            "status": "not-run",
            "reason": device_error or "no-ready-adb-device",
        }
        result["qualification"] = "blocked-no-device" if args.require_device else "host-qualified-device-pending"
        write_result(args.json_out, result)
        print("Android instrumentation not run: no ready adb device.", file=sys.stderr)
        return 3 if args.require_device else 0

    instrumentation = run(
        [
            "./gradlew",
            ":app:connectedDebugAndroidTest",
            "--no-daemon",
            "--console=plain",
        ],
        env,
    )
    result["instrumentation"] = {
        **instrumentation,
        "status": "passed" if instrumentation["exit_code"] == 0 else "failed",
        "scope": "all debug Android instrumentation tests",
    }
    if instrumentation["exit_code"] != 0:
        result["qualification"] = "real-device-ui-e2e-failed"
        write_result(args.json_out, result)
        return 2

    assert adb is not None
    performance_ok, performance = pull_performance_evidence(devices, env, args.performance_json_out, adb)
    result["performance_evidence"] = {
        "status": performance["status"],
        "path": str(args.performance_json_out),
    }
    result["qualification"] = (
        "real-device-ui-e2e-passed" if performance_ok else "device-performance-evidence-failed"
    )
    write_result(args.json_out, result)
    return 0 if performance_ok else 4


if __name__ == "__main__":
    raise SystemExit(main())
