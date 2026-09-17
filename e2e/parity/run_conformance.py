#!/usr/bin/env python3
"""Execute Android DJ Tools parity evidence as a fail-closed conformance suite.

Profiles deliberately distinguish three evidence tiers:

* host: catalog/matrix integrity + protocol/JVM/compile/lint/APK qualification.
* emulator: host qualification plus the complete Android instrumentation suite.
* full: emulator qualification plus the real Sample Lib/system probe and the
  all-stories-green parity gate.

A lower profile can be green without claiming full proprietary-feature parity.
Only ``--profile full`` may emit ``full-conformance-passed`` and it can do so only
when every required user story is green in the canonical parity matrix.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORT = ROOT / "artifacts" / "e2e" / "conformance.json"


def cli() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("host", "emulator", "full"), default="host")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument(
        "--sample-lib-root",
        type=Path,
        default=Path("/home/user/code/sample-lab/third_party/sample-lib"),
        help="real Sample Lib checkout used by the full profile",
    )
    parser.add_argument(
        "--device-backend-evidence",
        type=Path,
        default=ROOT / "artifacts" / "e2e" / "real-device-backend.json",
        help="real Android -> real Sample Lib evidence required by the full profile",
    )
    parser.add_argument(
        "--output-tail",
        type=int,
        default=12000,
        help="maximum stdout/stderr characters retained per step in the JSON report",
    )
    return parser.parse_args()


def env() -> dict[str, str]:
    result = os.environ.copy()
    java17 = Path("/usr/lib/jvm/java-17-openjdk")
    if "JAVA_HOME" not in result and java17.exists():
        result["JAVA_HOME"] = str(java17)
    result.setdefault("GRADLE_USER_HOME", "/tmp/androidjtools-gradle")
    result.setdefault("PYTHONDONTWRITEBYTECODE", "1")
    return result


def display(command: list[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def execute(name: str, command: list[str], environment: dict[str, str], output_tail: int) -> dict[str, Any]:
    print(f"\n=== {name} ===\n+ {display(command)}", flush=True)
    started = time.time()
    completed = subprocess.run(
        command,
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.stdout:
        print(completed.stdout, end="" if completed.stdout.endswith("\n") else "\n")
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="" if completed.stderr.endswith("\n") else "\n")
    return {
        "name": name,
        "command": command,
        "exit_code": completed.returncode,
        "duration_seconds": round(time.time() - started, 3),
        "stdout_tail": completed.stdout[-output_tail:],
        "stderr_tail": completed.stderr[-output_tail:],
    }


def write_report(path: Path, payload: dict[str, Any]) -> None:
    destination = path if path.is_absolute() else ROOT / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"\nConformance report: {destination}")


def main() -> int:
    args = cli()
    environment = env()
    report: dict[str, Any] = {
        "schema": "androidjtools.conformance-run/v1",
        "profile": args.profile,
        "started_epoch_seconds": int(time.time()),
        "git": {},
        "environment": {
            "ci": bool(environment.get("CI")),
            "github_actions": bool(environment.get("GITHUB_ACTIONS")),
            "java_home": environment.get("JAVA_HOME"),
        },
        "steps": [],
    }

    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    report["git"]["revision"] = revision.stdout.strip() if revision.returncode == 0 else None

    # The matrix validator is the traceability gate: every required story must be
    # present exactly once and point at typed evidence. It intentionally permits
    # red stories unless the final full-profile step asks for qualification.
    commands: list[tuple[str, list[str]]] = [
        ("parity-ledger-integrity", [sys.executable, "e2e/parity/validate_matrix.py"]),
        ("architecture-provider-boundary", [sys.executable, "e2e/parity/check_architecture_boundary.py"]),
        (
            "protocol-and-backend-contracts",
            [sys.executable, "e2e/parity/run_backend_contracts.py"],
        ),
        (
            "release-variant-build",
            [
                "./gradlew",
                ":app:assembleDebug",
                ":app:assembleRelease",
                "--no-daemon",
                "--console=plain",
            ],
        ),
        (
            "debug-harness-release-exclusion",
            [
                sys.executable,
                "app/src/debug/verify_debug_harness.py",
                "--debug-apk",
                "app/build/outputs/apk/debug/app-debug.apk",
                "--release-apk",
                "app/build/outputs/apk/release/app-release-unsigned.apk",
            ],
        ),
    ]

    ui_command = [sys.executable, "e2e/ui/run_ui_e2e.py", "--json-out", "artifacts/e2e/ui-e2e.json"]
    if args.profile in {"emulator", "full"}:
        ui_command.append("--require-device")
    commands.append(("android-host-and-ui", ui_command))

    if args.profile == "full":
        commands.extend(
            [
                (
                    "real-device-plus-real-backend",
                    [
                        sys.executable,
                        "e2e/parity/run_core_e2e.py",
                        "--require-real",
                        "--sample-lib-root",
                        str(args.sample_lib_root.resolve()),
                        "--device-backend-evidence",
                        str(args.device_backend_evidence.resolve()),
                    ],
                ),
                (
                    "all-required-stories-qualified",
                    [sys.executable, "e2e/parity/validate_matrix.py", "--require-qualified"],
                ),
            ]
        )

    failed = False
    for name, command in commands:
        step = execute(name, command, environment, args.output_tail)
        report["steps"].append(step)
        if step["exit_code"] != 0:
            failed = True
            # Fail closed and stop: later tiers cannot repair a lower-tier failure.
            break

    if failed:
        report["qualification"] = f"{args.profile}-conformance-failed"
    elif args.profile == "host":
        report["qualification"] = "host-preflight-passed-not-full-conformance"
    elif args.profile == "emulator":
        report["qualification"] = "emulator-e2e-passed-not-full-conformance"
    else:
        report["qualification"] = "full-conformance-passed"

    report["finished_epoch_seconds"] = int(time.time())
    write_report(args.report, report)
    print(json.dumps({"profile": args.profile, "qualification": report["qualification"]}, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
