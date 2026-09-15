#!/usr/bin/env python3
"""Focused EPIC-16 checks for matrix coverage and debug/release isolation."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / "app/src/debug/assets/debug-harness-contract.json"
DOMAIN = ROOT / "app/src/main/java/dev/androidjtools/core/model/Domain.kt"
PROVIDERS = ROOT / "app/src/debug/java/dev/androidjtools/debug/DebugHarnessProviders.kt"
DEBUG_MANIFEST = ROOT / "app/src/debug/AndroidManifest.xml"


def enum_values(source: str, enum_name: str) -> list[str]:
    match = re.search(rf"enum class {re.escape(enum_name)}\s*\{{([^}}]*)\}}", source, re.S)
    if not match:
        raise AssertionError(f"enum {enum_name} not found")
    body = match.group(1)
    return [token.strip() for token in body.replace("\n", " ").split(",") if token.strip()]


def apkanalyzer(*args: str) -> str:
    proc = subprocess.run(
        ["apkanalyzer", *args],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return proc.stdout


def check_matrix() -> None:
    contract = json.loads(CONTRACT.read_text())
    domain = DOMAIN.read_text()
    providers = PROVIDERS.read_text()

    assert contract["contract_version"] == 1
    assert contract["sync_states"] == enum_values(domain, "SyncState")
    assert contract["provider_kinds"] == enum_values(providers, "DebugProviderKind")
    assert contract["datasets"] == enum_values(providers, "DebugDataset")
    assert contract["network_states"] == enum_values(providers, "DebugNetworkState")
    assert contract["failure_modes"] == enum_values(providers, "DebugFailureMode")
    assert contract["large_library_size"] == 512
    assert contract["deterministic_seed"] == "androidjtools-debug-v1"

    required_failures = {
        "CONFLICT",
        "DOWNLOAD_FAILURE",
        "MUTATION_REJECTED",
        "AUTH_REQUIRED",
        "SERVER_INCOMPATIBLE",
        "LOCAL_MIGRATION_REQUIRED",
        "BACKEND_UNHEALTHY",
    }
    assert required_failures.issubset(set(contract["failure_modes"]))
    assert "MutableStateFlow(config.effectiveSyncState())" in providers
    assert "List(512)" in providers
    assert "Instant.EPOCH" in providers
    assert "Injected deterministic download failure" in providers
    assert "ReceiptOutcome.CONFLICT" in providers

    manifest = DEBUG_MANIFEST.read_text()
    assert ".debug.DebugHarnessActivity" in manifest
    assert "tools:node=\"remove\"" in manifest
    print("PASS matrix: debug contract exactly covers production SyncState and declared selectors")
    print("PASS determinism: fixed seed, fixed timestamps, fixed large-library cardinality, explicit failure injection")


def check_apks(debug_apk: Path, release_apk: Path) -> None:
    debug_manifest = apkanalyzer("manifest", "print", str(debug_apk))
    release_manifest = apkanalyzer("manifest", "print", str(release_apk))
    debug_dex = apkanalyzer("dex", "packages", str(debug_apk))
    release_dex = apkanalyzer("dex", "packages", str(release_apk))

    assert "dev.androidjtools.debug.DebugHarnessActivity" in debug_manifest
    assert "dev.androidjtools.debug.DebugHarnessActivity" not in release_manifest
    assert "DebugHarnessActivity" in debug_dex
    assert "DebugHarnessActivity" not in release_dex

    with zipfile.ZipFile(debug_apk) as archive:
        assert "assets/debug-harness-contract.json" in archive.namelist()
    with zipfile.ZipFile(release_apk) as archive:
        assert "assets/debug-harness-contract.json" not in archive.namelist()

    print("PASS isolation: debug launcher/classes/contract asset are present only in debug APK")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug-apk", type=Path)
    parser.add_argument("--release-apk", type=Path)
    args = parser.parse_args()

    check_matrix()
    if bool(args.debug_apk) != bool(args.release_apk):
        parser.error("--debug-apk and --release-apk must be supplied together")
    if args.debug_apk and args.release_apk:
        check_apks(args.debug_apk, args.release_apk)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
