#!/usr/bin/env python3
"""Fail closed if product UI depends directly on backend implementation packages.

US-004 is specifically about replaceable providers rather than about forbidding every
cross-package dependency.  UI code may depend on core model/provider contracts and on
other UI/presentation helpers, but it must not import concrete fixture, remote, sync,
debug, or interoperability implementations.
"""

from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = ROOT / "app/src/main/java/dev/androidjtools/ui"
PROVIDERS = ROOT / "app/src/main/java/dev/androidjtools/core/provider/Providers.kt"
DEFAULT_REPORT = ROOT / "artifacts/e2e/architecture-boundary.json"

FORBIDDEN_PREFIXES = (
    "dev.androidjtools.fixture.",
    "dev.androidjtools.remote.",
    "dev.androidjtools.sync.",
    "dev.androidjtools.debug.",
    "dev.androidjtools.interop.",
)
IMPORT_RE = re.compile(r"^import\s+(dev\.androidjtools\.[A-Za-z0-9_.]+)", re.MULTILINE)


def main() -> int:
    violations: list[dict[str, str]] = []
    inspected = 0
    for path in sorted(UI_ROOT.rglob("*.kt")):
        inspected += 1
        text = path.read_text(encoding="utf-8")
        for imported in IMPORT_RE.findall(text):
            if imported.startswith(FORBIDDEN_PREFIXES):
                violations.append(
                    {
                        "file": str(path.relative_to(ROOT)),
                        "import": imported,
                    }
                )

    provider_text = PROVIDERS.read_text(encoding="utf-8")
    required_contracts = (
        "interface AppRuntimeProvider",
        "interface LibraryProvider",
        "interface PreparationProvider",
        "interface PlaybackProvider",
        "interface PlaylistProvider",
        "interface DownloadProvider",
        "interface MutationJournalProvider",
        "interface SyncProvider",
        "interface AnalysisProvider",
        "data class AppProviders",
    )
    missing_contracts = [contract for contract in required_contracts if contract not in provider_text]

    payload = {
        "schema": "androidjtools.architecture-boundary/v1",
        "story": "US-004",
        "ui_files_inspected": inspected,
        "forbidden_backend_prefixes": list(FORBIDDEN_PREFIXES),
        "violations": violations,
        "missing_provider_contracts": missing_contracts,
        "status": "pass" if not violations and not missing_contracts else "fail",
    }
    DEFAULT_REPORT.parent.mkdir(parents=True, exist_ok=True)
    DEFAULT_REPORT.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0 if payload["status"] == "pass" else 2


if __name__ == "__main__":
    raise SystemExit(main())
