#!/usr/bin/env bash
set -euo pipefail

adb shell cmd uimode night no || true
adb shell settings put system font_scale 1.0
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.visual.CiScreenshotTest \
  --stacktrace

mkdir -p artifacts/ui-screenshots
for image in \
  01-library.png \
  02-collections.png \
  03-preparation.png \
  04-suggestions.png \
  05-sync.png
do
  adb exec-out run-as dev.androidjtools.debug base64 "files/ui-screenshots/${image}" \
    | tr -d '\r' \
    | base64 --decode > "artifacts/ui-screenshots/${image}"
  test -s "artifacts/ui-screenshots/${image}"
done

python3 - <<'PY'
from pathlib import Path
import struct

root = Path("artifacts/ui-screenshots")
files = sorted(root.glob("*.png"))
if len(files) != 5:
    raise SystemExit(f"expected 5 screenshots, found {len(files)}")

for image in files:
    data = image.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 24:
        raise SystemExit(f"invalid PNG: {image}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 720 or height < 1280:
        raise SystemExit(f"unexpected screenshot dimensions for {image}: {width}x{height}")
    print(f"{image.name}: {width}x{height} ({len(data)} bytes)")
PY
