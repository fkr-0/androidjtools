#!/usr/bin/env bash
set -euo pipefail

mkdir -p artifacts/e2e
adb shell cmd uimode night no || true
adb shell settings put system font_scale 1.0
python3 e2e/parity/run_conformance.py \
  --profile emulator \
  --report artifacts/e2e/conformance.json
