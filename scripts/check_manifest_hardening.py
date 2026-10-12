#!/usr/bin/env python3
"""Fail unless the (apkanalyzer-printed) manifest keeps credentials out of backups and blocks cleartext."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def check(manifest_xml: str) -> list[str]:
    application = ET.fromstring(manifest_xml).find("application")
    if application is None:
        return ["manifest has no <application> element"]
    problems = []
    if application.get(ANDROID + "allowBackup") != "false":
        problems.append('android:allowBackup must be "false" (credentials and cache must not enter backups)')
    if application.get(ANDROID + "networkSecurityConfig") is None:
        problems.append("android:networkSecurityConfig is required (cleartext only for loopback)")
    if application.get(ANDROID + "usesCleartextTraffic") == "true":
        problems.append('android:usesCleartextTraffic must not be "true"')
    return problems


def main(argv: list[str]) -> int:
    with open(argv[1], encoding="utf-8") as handle:
        problems = check(handle.read())
    for problem in problems:
        print(f"manifest hardening FAILED: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
