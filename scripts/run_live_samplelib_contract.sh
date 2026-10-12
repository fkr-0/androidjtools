#!/usr/bin/env bash
# Run SampleLibLiveContractTest against a real, disposable Sample Lib server.
#
# The server is built from a clean `git archive HEAD` export of the Sample Lib checkout, so local
# uncommitted work there is neither used nor modified. Data, secrets and the pairing code are
# throwaway and live in a temp directory that is removed on exit.
#
# Usage: scripts/run_live_samplelib_contract.sh [SAMPLE_LIB_CHECKOUT]
#   default checkout: ../sample-lab/third_party/sample-lib (relative to this repository)
# Extra Gradle arguments (for example --offline) can be passed in GRADLE_ARGS.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checkout="${1:-$repo/../sample-lab/third_party/sample-lib}"
checkout="$(cd "$checkout" && pwd)"
test -d "$checkout/src/sample_lib" || { echo "not a Sample Lib checkout: $checkout" >&2; exit 2; }

work="$(mktemp -d)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

echo "Exporting clean HEAD of $checkout ($(git -C "$checkout" rev-parse --short HEAD))"
mkdir "$work/src-export"
git -C "$checkout" archive HEAD src pyproject.toml | tar -x -C "$work/src-export"

uv_run=(uv run --quiet --project "$checkout")
export PYTHONDONTWRITEBYTECODE=1
"${uv_run[@]}" python -I "$repo/scripts/seed_live_samplelib.py" "$work/src-export" "$work/data"

port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
code="$(python3 -c 'import secrets; print(f"{secrets.randbelow(10**6):06d}")')"
secret="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
SAMPLE_LIB_DATA_ROOT="$work/data" \
SAMPLE_LIB_DATABASE_PATH="$work/data/sample-lib.sqlite3" \
SAMPLE_LIB_ANDROID_SYNC_SECRET="$secret" \
SAMPLE_LIB_ANDROID_PAIRING_CODE="$code" \
PYTHONPATH="$work/src-export/src" \
  "${uv_run[@]}" python -m uvicorn sample_lib.api:app --host 127.0.0.1 --port "$port" >"$work/server.log" 2>&1 &
server_pid=$!

ready=""
for _ in $(seq 1 60); do
  if curl -fs -m 1 "http://127.0.0.1:$port/healthz" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$work/server.log" >&2; echo "server exited early" >&2; exit 1; fi
  sleep 0.5
done
[[ -n "$ready" ]] || { cat "$work/server.log" >&2; echo "server did not become ready" >&2; exit 1; }
echo "Sample Lib listening on 127.0.0.1:$port"

cd "$repo"
export SAMPLE_LIB_LIVE_URL="http://127.0.0.1:$port" SAMPLE_LIB_LIVE_PAIRING_CODE="$code"
# cleanTest forces a rerun: the live environment is not a Gradle task input.
# shellcheck disable=SC2086
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests '*SampleLibLiveContractTest' --console=plain ${GRADLE_ARGS:-}
python3 -I - <<'PY'
import glob
import sys
import xml.etree.ElementTree as ET

bad = ran = 0
for path in glob.glob("app/build/test-results/testDebugUnitTest/*SampleLibLiveContractTest.xml"):
    root = ET.parse(path).getroot()
    ran += int(root.get("tests")) - int(root.get("skipped"))
    bad += int(root.get("failures")) + int(root.get("errors"))
print(f"live contract: {ran} executed, {bad} failed")
sys.exit(1 if ran == 0 or bad else 0)
PY
