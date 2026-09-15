#!/usr/bin/env python3
"""Secret-safe fake-vs-real Sample Lib sync qualification probe.

Real mode is intentionally opt-in. The bearer token is read from SAMPLE_LIB_TOKEN and
is never printed. A legacy Sample Lib checkout that only exposes /healthz is reported as
sync_facade_missing rather than being misrepresented as EPIC-12-ready.
"""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request


def probe(base_url: str, token: str | None) -> dict[str, object]:
    base = base_url.rstrip("/")
    result: dict[str, object] = {"target": base, "healthz": None, "sync": None}
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=2) as response:
            result["healthz"] = response.status
    except Exception:
        result["healthz"] = "unavailable"

    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(
        {
            "type": "hello_request",
            "protocol_versions": ["1"],
            "client_id": "qualification-probe",
            "installation_id": "qualification-probe",
            "capabilities": [],
        }
    ).encode()
    request = urllib.request.Request(base + "/v1/sync/hello", data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            payload = json.loads(response.read().decode())
            result["sync"] = {
                "status": response.status,
                "type": payload.get("type"),
                "server_id": payload.get("server_id"),
                "authority_generation": payload.get("authority_generation"),
            }
    except urllib.error.HTTPError as exc:
        if exc.code in {404, 405}:
            result["sync"] = {"status": exc.code, "classification": "sync_facade_missing"}
        elif exc.code in {401, 403}:
            result["sync"] = {"status": exc.code, "classification": "authorization_required"}
        else:
            result["sync"] = {"status": exc.code, "classification": "sync_probe_failed"}
        exc.close()
    except Exception:
        result["sync"] = {"classification": "unavailable"}
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    args = parser.parse_args()
    print(json.dumps(probe(args.base_url, os.environ.get("SAMPLE_LIB_TOKEN")), sort_keys=True))


if __name__ == "__main__":
    main()
