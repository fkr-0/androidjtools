#!/usr/bin/env python3
"""Fail-closed EPIC-18 real-system readiness probe.

The probe never substitutes fake evidence for a real device or a production Sample
Lib sync facade.  It is safe to run without either resource and returns structured
blocked evidence.  --require-real turns that blocked result into exit code 3.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DJXML_QUALIFIER = ROOT / "e2e" / "djxml" / "qualify_samplelib.py"
DEFAULT_DEVICE_BACKEND_EVIDENCE = ROOT / "artifacts" / "e2e" / "real-device-backend.json"


def run(command: list[str], cwd: Path = ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def resolve_adb() -> str | None:
    candidates: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(variable)
        if sdk:
            candidates.append(Path(sdk) / "platform-tools" / "adb")
    candidates.append(Path("/opt/android-sdk/platform-tools/adb"))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return shutil.which("adb")


def attached_devices() -> tuple[list[dict[str, str]], str | None, str | None]:
    adb = resolve_adb()
    if adb is None:
        return [], "adb-not-found", None
    completed = run([adb, "devices", "-l"])
    if completed.returncode != 0:
        return [], f"adb-failed:{completed.stderr.strip() or completed.returncode}", adb
    devices: list[dict[str, str]] = []
    for line in completed.stdout.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        devices.append({"serial": parts[0], "detail": " ".join(parts[2:])})
    return devices, None, adb


def real_sample_lib(sample_lib_root: Path) -> tuple[dict, int, str]:
    completed = run(
        [sys.executable, str(DJXML_QUALIFIER), "--sample-lib-root", str(sample_lib_root)]
    )
    if completed.returncode != 0:
        return ({"qualification": "probe_failed"}, completed.returncode, completed.stderr.strip())
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        return ({"qualification": "invalid_probe_output"}, 2, str(exc))
    return payload, 0, completed.stderr.strip()


def git_revision() -> str | None:
    completed = run(["git", "rev-parse", "HEAD"])
    return completed.stdout.strip() if completed.returncode == 0 else None


def load_device_backend_evidence(
    path: Path,
    devices: list[dict[str, str]],
    sample_lib_root: Path,
    revision: str | None,
) -> tuple[dict | None, str | None]:
    """Validate evidence produced by an actual Android -> real Sample Lib run.

    Device presence plus a healthy backend is intentionally insufficient.  The
    evidence producer is expected to be a dedicated Android instrumentation/E2E
    lane and must prove the same device performed pair/hello/pull/push/receipt/
    reconnect against the requested real Sample Lib checkout.
    """
    if not path.is_file():
        return None, "missing"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"unreadable:{exc}"

    if payload.get("schema") != "androidjtools.real-device-backend-evidence/v1":
        return payload, "schema-mismatch"
    if payload.get("status") != "pass":
        return payload, "status-not-pass"
    if revision is None or payload.get("git_revision") != revision:
        return payload, "git-revision-mismatch"

    serial = payload.get("device_serial")
    attached_serials = {device.get("serial") for device in devices}
    if not isinstance(serial, str) or serial not in attached_serials:
        return payload, "device-serial-not-attached"

    backend = payload.get("backend")
    if not isinstance(backend, dict) or backend.get("kind") != "real-sample-lib":
        return payload, "backend-kind-mismatch"
    evidence_root = backend.get("sample_lib_root")
    if not isinstance(evidence_root, str) or Path(evidence_root).resolve() != sample_lib_root.resolve():
        return payload, "sample-lib-root-mismatch"

    android = payload.get("android")
    if not isinstance(android, dict) or android.get("transport") != "SampleLibHttpTransport":
        return payload, "android-transport-mismatch"
    if android.get("producer") != "android-instrumentation":
        return payload, "producer-mismatch"

    round_trip = payload.get("round_trip")
    required_steps = ("pair", "hello", "pull", "push", "receipt", "reconnect")
    if not isinstance(round_trip, dict) or not all(round_trip.get(step) is True for step in required_steps):
        return payload, "round-trip-incomplete"
    return payload, None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-real", action="store_true")
    parser.add_argument(
        "--sample-lib-root",
        type=Path,
        default=Path("/home/user/code/sample-lab/third_party/sample-lib"),
        help="Real Sample Lib checkout passed through to the DJXML qualifier.",
    )
    parser.add_argument(
        "--device-backend-evidence",
        type=Path,
        default=DEFAULT_DEVICE_BACKEND_EVIDENCE,
        help="Machine-readable evidence from a real Android -> real Sample Lib instrumentation run.",
    )
    args = parser.parse_args()

    devices, adb_error, adb = attached_devices()
    sample_lib_root = args.sample_lib_root.resolve()
    sample_lib, probe_exit, probe_stderr = real_sample_lib(sample_lib_root)
    sync_ready = sample_lib.get("android_real_round_trip") == "qualified"
    revision = git_revision()
    device_evidence, device_evidence_error = load_device_backend_evidence(
        args.device_backend_evidence.resolve(),
        devices,
        sample_lib_root,
        revision,
    )
    device_round_trip_ready = device_evidence is not None and device_evidence_error is None
    real_ready = bool(devices) and adb_error is None and sync_ready and device_round_trip_ready

    blockers: list[str] = []
    if adb_error:
        blockers.append("adb_probe_failed")
    elif not devices:
        blockers.append("no_attached_android_device")
    if not sync_ready:
        blockers.append("production_sample_lib_sync_facade_unavailable")
    if sample_lib.get("canonical_playlist_identity_and_order") is False:
        blockers.append("canonical_playlist_identity_and_order_unavailable")
    if probe_exit != 0:
        blockers.append("real_sample_lib_probe_failed")
    if device_evidence_error == "missing":
        blockers.append("android_real_backend_evidence_missing")
    elif device_evidence_error is not None:
        blockers.append("android_real_backend_evidence_invalid")

    payload = {
        "schema": "androidjtools.core-e2e-readiness/v1",
        "git_revision": revision,
        "android_devices": devices,
        "adb": adb,
        "adb_error": adb_error,
        "real_sample_lib": sample_lib,
        "device_backend_evidence_path": str(args.device_backend_evidence.resolve()),
        "device_backend_evidence": device_evidence,
        "device_backend_evidence_error": device_evidence_error,
        "real_core_e2e": "qualified" if real_ready else "blocked",
        "blockers": blockers,
        "probe_stderr": probe_stderr or None,
        "note": (
            "Fake/emulator evidence and mere Android-device presence are not promoted into this real-system result; "
            "qualification requires explicit Android -> real Sample Lib round-trip evidence."
        ),
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    if args.require_real and not real_ready:
        return 3
    return 0 if probe_exit == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
