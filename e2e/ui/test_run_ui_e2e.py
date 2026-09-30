#!/usr/bin/env python3
"""Host tests for compatible-device classification in run_ui_e2e."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("run_ui_e2e.py")
SPEC = importlib.util.spec_from_file_location("run_ui_e2e", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def completed(args: list[str], *, code: int = 0, out: str = "", err: str = "") -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(args=args, returncode=code, stdout=out, stderr=err)


class AdbDeviceCompatibilityTests(unittest.TestCase):
    def probe(self, responses: list[subprocess.CompletedProcess[str]]):
        with patch.object(runner, "resolve_adb", return_value="/sdk/adb"), patch.object(
            runner.subprocess, "run", side_effect=responses
        ):
            return runner.adb_devices({})

    def test_compatible_ready_device_is_admitted(self):
        devices = completed([], out="List of devices attached\nemulator-5554 device product:sdk model:Pixel\n")
        sdk = completed([], out="35\n")

        compatible, discovered, error, adb = self.probe([devices, sdk])

        self.assertEqual(["emulator-5554"], compatible)
        self.assertIsNone(error)
        self.assertEqual("/sdk/adb", adb)
        self.assertEqual(
            [{"serial": "emulator-5554", "state": "device", "compatible": True, "api_level": 35}],
            discovered,
        )

    def test_ready_device_below_min_sdk_is_rejected(self):
        devices = completed([], out="List of devices attached\nlegacy device model:GT-I8190\n")
        sdk = completed([], out="22\n")

        compatible, discovered, error, _ = self.probe([devices, sdk])

        self.assertEqual([], compatible)
        self.assertIsNone(error)
        self.assertEqual("api-22-below-min-sdk-28", discovered[0]["reason"])
        self.assertEqual(22, discovered[0]["api_level"])

    def test_non_ready_states_are_reported_without_sdk_probe(self):
        devices = completed(
            [],
            out=(
                "List of devices attached\n"
                "recovering recovery model:Pixel\n"
                "offline-one offline model:Pixel\n"
                "unauth unauthorized usb:1-1\n"
            ),
        )

        compatible, discovered, error, _ = self.probe([devices])

        self.assertEqual([], compatible)
        self.assertIsNone(error)
        self.assertEqual(
            ["adb-state-recovery", "adb-state-offline", "adb-state-unauthorized"],
            [record["reason"] for record in discovered],
        )

    def test_sdk_probe_failure_fails_closed(self):
        devices = completed([], out="List of devices attached\nserial-1 device model:Pixel\n")
        sdk = completed([], code=1, err="transport error")

        compatible, discovered, error, _ = self.probe([devices, sdk])

        self.assertEqual([], compatible)
        self.assertIsNone(error)
        self.assertEqual("sdk-probe-failed:transport error", discovered[0]["reason"])

    def test_invalid_sdk_probe_fails_closed(self):
        devices = completed([], out="List of devices attached\nserial-1 device model:Pixel\n")
        sdk = completed([], out="unknown\n")

        compatible, discovered, error, _ = self.probe([devices, sdk])

        self.assertEqual([], compatible)
        self.assertIsNone(error)
        self.assertEqual("sdk-probe-invalid:unknown", discovered[0]["reason"])

    def test_adb_list_failure_is_distinct(self):
        devices = completed([], code=1, err="daemon unavailable")

        compatible, discovered, error, adb = self.probe([devices])

        self.assertEqual([], compatible)
        self.assertEqual([], discovered)
        self.assertEqual("adb-failed:daemon unavailable", error)
        self.assertEqual("/sdk/adb", adb)


if __name__ == "__main__":
    unittest.main()
