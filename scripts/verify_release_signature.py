#!/usr/bin/env python3
"""Fail-closed release APK signature check.

Runs `apksigner verify --print-certs -v` and requires exactly one signer whose
SHA-256 certificate fingerprint matches the pinned release fingerprint. The shared
"Android Debug" certificate is rejected unless --allow-debug is passed explicitly.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


class SignatureError(Exception):
    pass


@dataclass(frozen=True)
class SignatureResult:
    sha256: str
    dn: str
    debug: bool


def _normalize_fingerprint(value: str) -> str:
    normalized = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise SignatureError("expected fingerprint must be 64 hex characters (SHA-256)")
    return normalized


def evaluate(output: str, expected_sha256: str | None, allow_debug: bool = False) -> SignatureResult:
    # apksigner labels a signer "#1", with the SDK range its v3 signature covers, or (current builds) once per
    # verified scheme as "V2 Signer:". The same certificate may therefore be listed several times.
    signer = r"(?:Signer (?:#\d+|\([^)]*\))|V\d(?:\.\d)? Signer:)"
    digests = set(re.findall(rf"^{signer} certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$", output, re.M))
    dns = set(re.findall(rf"^{signer} certificate DN: (.*)$", output, re.M))
    reported = re.search(r"^Number of signers: (\d+)\s*$", output, re.M)
    if len(digests) != 1 or len(dns) != 1 or (reported is not None and int(reported.group(1)) != 1):
        excerpt = " | ".join(line.strip() for line in output.splitlines() if line.strip())[:600]
        raise SignatureError(f"expected exactly one signer, found {len(digests)}; apksigner printed: {excerpt}")
    digests, dns = list(digests), list(dns)

    if not re.search(r"^Verified using v[23] scheme \(.*\): true\s*$", output, re.M):
        raise SignatureError("APK is not verified with signature scheme v2 or v3")

    sha256, dn = digests[0].lower(), dns[0].strip()
    debug = "CN=Android Debug" in dn
    if debug and not allow_debug:
        raise SignatureError("APK is signed with the shared Android debug certificate")

    if expected_sha256 is None:
        if not allow_debug:
            raise SignatureError("an expected release certificate fingerprint is required")
    else:
        expected = _normalize_fingerprint(expected_sha256)
        if sha256 != expected:
            raise SignatureError(f"certificate fingerprint {sha256} does not match pinned {expected}")
    return SignatureResult(sha256=sha256, dn=dn, debug=debug)


def find_apksigner() -> str:
    found = shutil.which("apksigner")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted(Path(sdk, "build-tools").glob("*/apksigner"))
        if candidates:
            return str(candidates[-1])
    raise SignatureError("apksigner not found; set ANDROID_HOME or add it to PATH")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-sha256", default=os.environ.get("ANDROID_RELEASE_CERT_SHA256") or None)
    parser.add_argument("--allow-debug", action="store_true", help="accept the Android debug certificate (unofficial builds only)")
    args = parser.parse_args(argv)
    try:
        if not args.apk.is_file():
            raise SignatureError(f"APK not found: {args.apk}")
        proc = subprocess.run(
            [find_apksigner(), "verify", "--print-certs", "-v", str(args.apk)],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SignatureError(f"apksigner verify failed: {proc.stderr.strip() or proc.stdout.strip()}")
        result = evaluate(proc.stdout, args.expected_sha256, allow_debug=args.allow_debug)
    except SignatureError as error:
        print(f"release signature check FAILED: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"apk": str(args.apk), "sha256": result.sha256, "dn": result.dn, "debug": result.debug}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
