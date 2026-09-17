#!/usr/bin/env python3
"""Run the complete host protocol/fake-backend contract suite.

Plain unittest discovery from the repository root does not recurse into the
Sample Lib helper directory because of the test-server layout. Keep the exact
36-test convergence command in one executable entry point so conformance cannot
silently omit the authenticated fake Sample Lib HTTP tests.
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
TEST_SERVER = ROOT / "test-server"


def main() -> int:
    environment = os.environ.copy()
    samplelib = str(TEST_SERVER / "samplelib")
    inherited = environment.get("PYTHONPATH")
    environment["PYTHONPATH"] = samplelib if not inherited else f"{samplelib}{os.pathsep}{inherited}"
    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            "unittest",
            "-v",
            "test_fake_sync_server",
            "samplelib.test_fake_samplelib_server",
            "test_contracts",
        ],
        cwd=TEST_SERVER,
        env=environment,
        check=False,
    )
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
