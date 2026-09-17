#!/usr/bin/env python3
"""Validate EPIC-18 story/evidence coverage without silently waiving gaps."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = ROOT / "docs" / "parity" / "parity.json"
STORIES_PATH = ROOT / "docs" / "user-stories.yml"
STORY_RE = re.compile(r"^\s*-\s*\{id:\s*(US-\d+),\s*epic:\s*([^,]+),.*?verify:\s*([^}]+)\}\s*$")
ALLOWED_STORY_STATUS = {"pass", "partial", "blocked", "fail", "missing"}
ALLOWED_EVIDENCE_STATUS = {"pass", "partial", "blocked", "fail", "missing"}
REAL_SYSTEM_STORIES = {
    "US-120", "US-121", "US-122", "US-123",
    "US-130", "US-131", "US-132",
    "US-150", "US-151",
    "US-181",
}
REQUIRED_PASS_TIERS = {
    "US-004": {"host_static"},
    "US-160": {"emulator_or_real_device", "real_device"},
    "US-161": {"emulator_or_real_device", "real_device"},
    "US-162": {"emulator_or_real_device", "real_device"},
    "US-163": {"release_artifact"},
    "US-170": {"emulator_or_real_device", "real_device"},
    "US-171": {"emulator_or_real_device", "real_device"},
    "US-172": {"emulator_or_real_device", "real_device"},
    "US-173": {"emulator_or_real_device", "real_device"},
    "US-120": {"real_device_plus_real_backend"},
    "US-121": {"real_device_plus_real_backend"},
    "US-122": {"real_device_plus_real_backend"},
    "US-123": {"real_device_plus_real_backend"},
    "US-130": {"real_device_plus_real_backend"},
    "US-131": {"real_device_plus_real_backend"},
    "US-132": {"real_device_plus_real_backend"},
    "US-150": {"real_device"},
    "US-151": {"real_device"},
    "US-181": {"real_device_plus_real_backend"},
}


def source_story_map() -> dict[str, str]:
    stories: dict[str, str] = {}
    for line in STORIES_PATH.read_text(encoding="utf-8").splitlines():
        match = STORY_RE.match(line)
        if match:
            story_id, epic, _verify = match.groups()
            if story_id in stories:
                raise ValueError(f"duplicate story in source catalog: {story_id}")
            stories[story_id] = epic.strip()
    if not stories:
        raise ValueError("no stories parsed from docs/user-stories.yml")
    return stories


def validate(matrix: dict) -> list[str]:
    errors: list[str] = []
    if matrix.get("schema") != "androidjtools.parity/v1":
        errors.append("matrix schema must be androidjtools.parity/v1")

    source = source_story_map()
    required = {story_id for story_id, epic in source.items() if epic != "EPIC-19-PACKAGING-RELEASE"}
    expected_excluded = {story_id for story_id, epic in source.items() if epic == "EPIC-19-PACKAGING-RELEASE"}

    story_rows = matrix.get("stories")
    evidence = matrix.get("evidence")
    if not isinstance(story_rows, list):
        return errors + ["stories must be a list"]
    if not isinstance(evidence, dict):
        return errors + ["evidence must be an object"]

    ids = [row.get("id") for row in story_rows if isinstance(row, dict)]
    duplicates = sorted(story_id for story_id, count in Counter(ids).items() if count > 1)
    if duplicates:
        errors.append(f"duplicate story rows: {duplicates}")
    actual = set(ids)
    missing = sorted(required - actual)
    extra = sorted(actual - required)
    if missing:
        errors.append(f"required stories missing from matrix: {missing}")
    if extra:
        errors.append(f"unexpected story rows: {extra}")

    excluded_rows = matrix.get("excluded_stories", [])
    excluded_ids = {row.get("id") for row in excluded_rows if isinstance(row, dict)}
    if excluded_ids != expected_excluded:
        errors.append(
            f"excluded stories must be exactly downstream EPIC-19 stories: expected={sorted(expected_excluded)} actual={sorted(excluded_ids)}"
        )

    for evidence_id, record in evidence.items():
        if not isinstance(record, dict):
            errors.append(f"evidence {evidence_id} must be an object")
            continue
        status = record.get("status")
        if status not in ALLOWED_EVIDENCE_STATUS:
            errors.append(f"evidence {evidence_id} has invalid status {status!r}")
        if status == "pass" and not record.get("command"):
            errors.append(f"passing evidence {evidence_id} must have an executable command")
        if not record.get("tier"):
            errors.append(f"evidence {evidence_id} must declare a tier")
        if not record.get("environment"):
            errors.append(f"evidence {evidence_id} must declare an environment")

    for row in story_rows:
        if not isinstance(row, dict):
            errors.append("every story row must be an object")
            continue
        story_id = row.get("id")
        status = row.get("status")
        if status not in ALLOWED_STORY_STATUS:
            errors.append(f"{story_id}: invalid story status {status!r}")
        refs = row.get("evidence")
        if not isinstance(refs, list) or not refs:
            errors.append(f"{story_id}: must reference at least one evidence record")
            continue
        unknown = sorted(set(refs) - set(evidence))
        if unknown:
            errors.append(f"{story_id}: unknown evidence refs {unknown}")
            continue
        if status == "pass" and not any(evidence[ref].get("status") == "pass" for ref in refs):
            errors.append(f"{story_id}: pass has no passing evidence")
        required_tiers = REQUIRED_PASS_TIERS.get(story_id)
        if status == "pass" and required_tiers and not any(
            evidence[ref].get("status") == "pass" and evidence[ref].get("tier") in required_tiers
            for ref in refs
        ):
            errors.append(
                f"{story_id}: pass lacks passing required-tier evidence; required one of {sorted(required_tiers)}"
            )
        if status != "pass" and not row.get("gap"):
            errors.append(f"{story_id}: non-pass story must explain its gap")
        if story_id in REAL_SYSTEM_STORIES:
            tiers = {evidence[ref].get("tier") for ref in refs}
            if not ({"real_backend", "real_device", "real_device_plus_real_backend"} & tiers):
                errors.append(f"{story_id}: real-system story lacks real backend/device evidence tier")

    counts = Counter(row.get("status") for row in story_rows if isinstance(row, dict))
    declared_counts = matrix.get("status_counts", {})
    if dict(counts) != declared_counts:
        errors.append(f"status_counts mismatch: computed={dict(counts)} declared={declared_counts}")

    if matrix.get("operator_override", {}).get("dependency_acceptance_waived_for_qualification") is not False:
        errors.append("dependency acceptance must not be waived for qualification")
    review = matrix.get("independent_review", {})
    if review.get("requested") is not True:
        errors.append("independent review must be requested and recorded")
    if review.get("qualification_requires_approval") is not True:
        errors.append("independent review approval must remain a qualification requirement")

    return errors


def qualification_errors(matrix: dict) -> list[str]:
    errors: list[str] = []
    non_pass = [row["id"] for row in matrix.get("stories", []) if row.get("status") != "pass"]
    if non_pass:
        errors.append(f"stories not qualified: {non_pass}")
    evidence = matrix.get("evidence", {})
    for evidence_id in ("samplelib-real-djxml", "real-core-e2e"):
        if evidence.get(evidence_id, {}).get("status") != "pass":
            errors.append(f"required real-system evidence is not pass: {evidence_id}")
    if evidence.get("hosted-ci", {}).get("status") != "pass":
        errors.append("required hosted-CI evidence is not pass: hosted-ci")
    review = matrix.get("independent_review", {})
    if review.get("status") != "approved":
        errors.append("independent review is not approved")
    else:
        if review.get("policy") != "independent-review":
            errors.append("independent review approval lacks independent-review policy marker")
        if review.get("reviewer_independent") is not True:
            errors.append("independent review approval does not attest reviewer independence")
        reviewed_revision = review.get("reviewed_revision")
        if not isinstance(reviewed_revision, str) or re.fullmatch(r"[0-9a-f]{40}", reviewed_revision) is None:
            errors.append("independent review approval lacks a 40-hex reviewed_revision")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=MATRIX_PATH)
    parser.add_argument("--require-qualified", action="store_true")
    args = parser.parse_args()

    matrix_path = args.matrix if args.matrix.is_absolute() else ROOT / args.matrix
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    errors = validate(matrix)
    if args.require_qualified:
        errors.extend(qualification_errors(matrix))

    rows = matrix.get("stories", [])
    payload = {
        "matrix": str(matrix_path.relative_to(ROOT)),
        "required_story_count": len(rows),
        "status_counts": dict(Counter(row.get("status") for row in rows)),
        "qualified": not errors and all(row.get("status") == "pass" for row in rows),
        "validation_errors": errors,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
