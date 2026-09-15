#!/usr/bin/env python3
"""Real Sample Lib DJXML qualification gate for Android EPIC-13.

This script deliberately distinguishes three things:
1. the real Sample Lib DJXML adapter and its canonical semantic round-trip tests;
2. the production Android sync facade required to traverse that authority from Android;
3. canonical playlist/item identity and ordering, which crate-tag compatibility does not provide.

A blocked result is a successful qualification outcome: it means the gate detected a missing real
capability instead of promoting fixture/fake evidence into end-to-end success. Use
--require-real-sync to turn that blocker into a non-zero CI exit once real-system E2E is mandatory.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
from dataclasses import asdict, dataclass


REQUIRED_SYNC_ROUTES = {
    "/v1/sync/hello",
    "/v1/sync/pull",
    "/v1/sync/push",
}

ADAPTER_TESTS = [
    "tests/test_djxml_adapter.py::test_djxml_export_projects_performance_annotations",
    "tests/test_djxml_adapter.py::test_djxml_import_matches_existing_asset_and_is_idempotent",
    "tests/test_djxml_adapter.py::test_djxml_import_accepts_documented_aliases_preserves_metadata_and_nested_crates",
    "tests/test_djxml_adapter.py::test_djxml_preserves_track_end_loops_and_distinct_same_time_cues",
    "tests/test_djxml_adapter.py::test_djxml_same_library_round_trip_converges_on_human_state",
]


@dataclass(frozen=True)
class Qualification:
    sample_lib_root: str
    adapter_tests_passed: bool
    stable_asset_identity: bool
    cue_loop_region_tags: bool
    crate_membership_projection: bool
    canonical_playlist_identity_and_order: bool
    production_sync_routes: list[str]
    missing_production_sync_routes: list[str]
    android_real_round_trip: str
    playlist_round_trip: str


def run_checked(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def fastapi_routes(sample_lib_root: pathlib.Path) -> set[str]:
    probe = (
        "import json; from sample_lib.api import app; "
        "print(json.dumps(sorted({getattr(route, 'path', '') for route in app.routes})))"
    )
    completed = run_checked(["uv", "run", "python", "-c", probe], sample_lib_root)
    return set(json.loads(completed.stdout.strip().splitlines()[-1]))


def assert_adapter_contract(sample_lib_root: pathlib.Path) -> tuple[bool, bool, bool, bool]:
    contract = (sample_lib_root / "docs/contracts/djxml-adapter.md").read_text(encoding="utf-8")
    stable_identity = (
        'Grouping="sample-lib:<asset-id>"' in contract
        and "DJXML is not allowed to manufacture Sample Lib identities" in contract
    )
    prep_semantics = all(
        phrase in contract
        for phrase in (
            "Performance marker contract",
            "Ranged cues and loops",
            "samplelib.interval.<namespace>",
        )
    )
    crate_projection = "Sample Lib uses `crate` tags as the canonical lightweight DJ collection primitive" in contract
    playlist_authority = not (
        "does not make DJXML folder structure canonical" in contract
        or "playlist | `crate` asset tag" in contract
    )
    return stable_identity, prep_semantics, crate_projection, playlist_authority


def qualify(sample_lib_root: pathlib.Path) -> Qualification:
    run_checked(["uv", "run", "python", "-m", "pytest", "-q", *ADAPTER_TESTS], sample_lib_root)
    routes = fastapi_routes(sample_lib_root)
    stable_identity, prep_semantics, crate_projection, playlist_authority = assert_adapter_contract(sample_lib_root)
    missing = sorted(REQUIRED_SYNC_ROUTES - routes)
    real_sync = not missing
    return Qualification(
        sample_lib_root=str(sample_lib_root),
        adapter_tests_passed=True,
        stable_asset_identity=stable_identity,
        cue_loop_region_tags=prep_semantics,
        crate_membership_projection=crate_projection,
        canonical_playlist_identity_and_order=playlist_authority,
        production_sync_routes=sorted(route for route in routes if route.startswith("/v1/sync")),
        missing_production_sync_routes=missing,
        android_real_round_trip="qualified" if real_sync and stable_identity and prep_semantics else "blocked_missing_production_sync_facade",
        playlist_round_trip="qualified" if real_sync and playlist_authority else "blocked_no_canonical_playlist_identity_order",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--sample-lib-root",
        type=pathlib.Path,
        default=pathlib.Path("/home/user/code/sample-lab/third_party/sample-lib"),
    )
    parser.add_argument("--require-real-sync", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    root = args.sample_lib_root.resolve()
    result = qualify(root)
    payload = json.dumps(asdict(result), indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    sys.stdout.write(payload)
    if args.require_real_sync and result.android_real_round_trip != "qualified":
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
