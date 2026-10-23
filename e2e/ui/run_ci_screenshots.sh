#!/usr/bin/env bash
set -euo pipefail

adb shell cmd uimode night no || true
adb shell settings put system font_scale 1.0
# connectedDebugAndroidTest uninstalls the app when it finishes, and the PNGs live in its private files directory,
# so keep the APKs installed until they have been pulled (`run-as` reported "unknown package" otherwise).
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.androidjtools.visual.CiScreenshotTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --stacktrace

mkdir -p artifacts/ui-screenshots
for image in \
  01-library.png \
  02-collections.png \
  03-preparation.png \
  04-suggestions.png \
  05-sync.png
do
  # Stage the file in a shell-readable place on the device and use `adb pull`, which is byte-exact and fails loudly
  # (the earlier `exec-out ... > file` captured run-as's error text as the "PNG").
  adb shell "run-as dev.androidjtools.debug cat files/ui-screenshots/${image} > /data/local/tmp/${image}"
  adb pull "/data/local/tmp/${image}" "artifacts/ui-screenshots/${image}"
  adb shell rm -f "/data/local/tmp/${image}"
  test -s "artifacts/ui-screenshots/${image}"
  head -c 16 "artifacts/ui-screenshots/${image}" | od -An -tx1
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
