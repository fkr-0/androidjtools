# Sample Lab Integration S2 (Server Audio, Android Side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make tracks from a paired Sample Lib server playable: stream them through Media3 over authenticated HTTP, let people pin them for offline use through the existing resumable, hash-verified download engine, and keep Live decks on local audio only.

**Architecture:** The server now pulls an `asset_file` family (one audio file per asset and role) and serves `GET /v1/sync/media/{asset_file_id}` with `Range`. The sync cache already stores `asset_file` rows untouched; a pure parser turns them into per-asset `ServerAudio`, and the library projection marks a track playable only when the server advertises audio and a valid file exists. One authenticating OkHttp client (bearer added by an interceptor only for the paired origin's media path, redirects disabled) backs both Media3 streaming and a `RangeTransport` for the offline engine. A single resolver maps a track id to a local file, a stream URL, or nothing, and is shared by the main player and the Live engines (decks get local files only).

**Tech Stack:** Kotlin, Media3 1.3.1 (`media3-exoplayer`, `media3-session`, new `media3-datasource-okhttp`), OkHttp 4.12, WorkManager, kotlinx-coroutines (+test), JUnit4; Python `unittest` for the contract fakes.

**Spec:** `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (stage S2). Backend half (already implemented, reviewed and merged in the sample-lib repo): `third_party/sample-lib/docs/contracts/android-sync-v1.md`. S1 plan and its deviations: `docs/superpowers/plans/2026-10-12-samplelab-s1-connect-and-read.md`.

## Decisions beyond the spec (reviewer: please confirm)

1. **Scope.** This plan is *server audio only*. Two items the S1 documents said would arrive "with S2" are split into their own later plans: the **staged snapshot swap** (S1 deviation 4) and **content-hash de-duplication of device tracks against server tracks** (S1 deviation 2). Both are independent of audio delivery; each needs its own design. Task 10 updates the S1 wording accordingly.
2. **Live decks never stream.** A deck loads only a device file or a *downloaded* copy of a server track. Streaming into a deck risks stalls and seeks against a network source during a performance. A server track that is not downloaded shows "Download this track to load it on a deck."
3. **Which file.** Streaming uses the `preview` file when the server has one, else `original`. Downloads use `original`, else `preview`.
4. **One new production dependency:** `androidx.media3:media3-datasource-okhttp:1.3.1` (first-party AndroidX, same version family as the other Media3 modules). It is not in the offline Gradle cache, so Task 7's first build must be online once. It lets us reuse the authenticating OkHttp client (no redirects, header only for the paired origin) instead of Media3's `DefaultHttpDataSource`, which follows redirects and would re-send added headers.
5. **Credential handling.** The bearer is never placed on a `MediaItem`, in `Data`, in IPC extras, or in a log. It is read from the in-memory session (falling back to the Keystore-backed store on a cold service start) at request time by an interceptor.
6. **Forget removes downloaded audio too**, behind a confirmation dialog. After Forget the library rows that give the audio meaning are gone, so the files would be orphans that cannot be managed.
7. **The offline cache moves from `filesDir` to `noBackupFilesDir`.** `OfflineDownloadRuntime` is unwired in the shipped app today, so there is nothing to migrate.
8. **Download cache key = `asset_file` id; pin owner = the track (asset) id.** If the server re-issues a file with a different sha256 under the same id, the engine already deletes the stale bytes; the projection meanwhile treats a local copy as downloaded only when its sha256 equals the current descriptor's (Task 2).
9. **Free-space guard:** refuse to start a pin when usable space is below the file size plus 256 MiB, with a visible `FAILED/QUOTA` state.

## Global Constraints

Carried over from the S1 plan and still binding:

- Credential and pairing code are never logged, never in user-visible text, `MediaItem`s, work `Data`, saved state or diagnostics. Error text from the player must not contain the server URL.
- Non-loopback hosts must use HTTPS (transport constructor + network security config). No cleartext except `127.0.0.1` and `localhost`.
- `allowBackup="false"`; credential, library cache and (new) offline media live under `noBackupFilesDir`.
- No broad `runCatching`/`catch (Throwable)`; rethrow `CancellationException`.
- No new build plugins or annotation processors. `minSdk = 28`. Lint must report 0 errors.
- Blocking disk, hash or Keystore work must never run on the main thread.
- Evidence honesty: anything needing a device (Media3 session playback, WorkManager scheduling, Keystore, Compose screens, notification/lock-screen) stays "compile-only / not run" in every report, commit message and changelog line. Do not edit `docs/parity/parity.json` story statuses.

New for S2 (from the backend contract):

- Descriptor fields are exactly `id, asset_id, file_role, resource_uri, sha256, size_bytes, mime_type, etag`; roles exposed are `original` and `preview`; `resource_uri` is `"<role>://<id>"`; `etag` is the quoted strong validator, kept **verbatim with its quotes**.
- Media route: `GET /v1/sync/media/{asset_file_id}`; 401/403/404; `Range: bytes=N-` and `N-M` return 206 with `Content-Range`; `If-Range` mismatch returns the whole 200; unsatisfiable ranges (including an oversize suffix) return 416; `If-None-Match` is **not** honoured (always 200). Clients use the pulled `etag`/`sha256` for change detection.
- Redirects are never followed on media requests, and the bearer is added only when the URL's scheme, host, port and path prefix match the paired base URL's `/v1/sync/media/`.

## Shell setup (every command below assumes this; run from a worktree of `main`)

```bash
git worktree add ../androidjtools-s2 -b feat/samplelab-s2 main   # once, then:
cd ../androidjtools-s2
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/home/user/code/androidjtools/.gradle-local
```

Task 7 Step 1 must run Gradle **without** `--offline` once to fetch `media3-datasource-okhttp`. After that `--offline` works.

## Review Focus

Failure modes the spec implies that no happy-path test would catch. Each has a test in the task named.

1. **Bearer leakage.** A redirect, a different port, a different path on the same host, or a non-media URL must never receive the `Authorization` header (Task 3).
2. **Stale or foreign ids.** After Forget, a re-pair to another server, or a new file revision, a remembered track id must fail cleanly (unavailable, no crash, no request to the wrong server) (Tasks 2, 5, 7).
3. **Truncated or corrupt download.** A short or wrong-hash body must never become playable or `AVAILABLE`, and a low-disk device must show a clear failure instead of a half download (Tasks 4, 6).
4. **Decks.** A server track that is only streamable (not downloaded) must be refused by the deck controller *and* its buttons, and the Live engine resolver must never return an `https` URL (Task 8).
5. **Main-thread work.** The URI resolver used by `play()` must not hash files, read the Keystore or touch SQLite (Tasks 5, 7).
6. **Forget while downloading.** In-flight and queued downloads must stop and their files must be gone after Forget; nothing may re-create them (Task 9).

---

### Task 1: Contracts, fake server and live fixture shape (Python)

**Files:**
- Modify: `contracts/sync/v1/protocol.json`
- Modify: `contracts/sync/v1/sync-contract.yml`
- Modify: `test-server/samplelib/fake_samplelib_server.py`
- Test: `test-server/test_contracts.py`
- Test: `test-server/samplelib/test_fake_samplelib_server.py`

**Interfaces:**
- Consumes: `FakeSyncServer.entities[(type, id)] = {"revision", "schema", "value"}` from `fake_sync_server.py`; `ProtocolHttpError`, `_error` helpers there.
- Produces (later tasks and the Kotlin live test rely on these): fake entities `("asset_file", "file-1")` (role `original`, bytes `MEDIA_BYTES`) and `("asset_file", "file-2")` (role `preview`, bytes `PREVIEW_BYTES`); route `GET|HEAD /v1/sync/media/{id}`; POST hook `/__samplelib__/deny-media` that makes the next media requests answer 403; helper `parse_single_range(header, size)`.

- [ ] **Step 1: Write the failing contract test**

Add to `class SyncContractSchemaTests` in `test-server/test_contracts.py` (after `test_capability_negotiation_withholds_playlist_writes`):

```python
    def test_asset_file_family_and_media_route_are_declared(self) -> None:
        self.assertIn("asset_file", self.protocol["capabilities"]["pull"])
        self.assertEqual("/v1/sync/media/{asset_file_id}", self.protocol["http"]["media"])
        prose = (CONTRACT_DIR / "sync-contract.yml").read_text(encoding="utf-8")
        self.assertIn("asset_file", prose)
        self.assertIn("media_fetch: GET /v1/sync/media/{asset_file_id}", prose)

    def test_asset_file_pull_entity_validates(self) -> None:
        digest = "a" * 64
        self.validate(
            {
                "type": "pull_response",
                "protocol_version": "1",
                "mode": "snapshot",
                "next_cursor": "opaque:cursor:8",
                "has_more": False,
                "changes": [
                    {
                        "entity_type": "asset_file",
                        "entity_id": "file-1",
                        "revision": "rev:asset_file:file-1:1",
                        "schema": "sample-lib.asset-file/v1",
                        "value": {
                            "id": "file-1",
                            "asset_id": "asset-1",
                            "file_role": "original",
                            "resource_uri": "original://file-1",
                            "sha256": digest,
                            "size_bytes": 2688,
                            "mime_type": "audio/wav",
                            "etag": f'"{digest}"',
                        },
                    }
                ],
                "tombstones": [],
                "authority_generation": "generation:1",
                "server_change_revision": 8,
            }
        )
```

- [ ] **Step 2: Write the failing fake-server tests**

In `test-server/samplelib/test_fake_samplelib_server.py` extend the import block:

```python
from fake_samplelib_server import (
    ACCESS_TOKEN,
    MEDIA_BYTES,
    MEDIA_SHA256,
    PAIRING_CODE,
    PREVIEW_BYTES,
    SampleLibFakeHttpServer,
    parse_single_range,
)
```

and add these methods to `AuthenticatedSampleLibFakeTests` (after `test_authenticated_range_media_is_resumable_and_integrity_checkable`):

```python
    def raw(self, method: str, path: str, *, auth: bool = True, headers: dict | None = None) -> tuple[int, bytes, dict]:
        """Like get() but never raises on 4xx, and supports HEAD."""
        connection = http.client.HTTPConnection(self.host, self.port, timeout=2)
        request_headers = dict(headers or {})
        if auth:
            request_headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
        connection.request(method, path, headers=request_headers)
        response = connection.getresponse()
        body = response.read()
        result = (response.status, body, {k: v for k, v in response.getheaders()})
        connection.close()
        return result

    def library_snapshot(self) -> list[dict]:
        self.pair()
        _, hello = self.post("/v1/sync/hello", {"type": "hello_request", "protocol_versions": ["1"], "client_id": "android-client", "installation_id": "installation-a", "capabilities": []}, auth=True)
        self.assertIn("asset_file", hello["capabilities"]["pull"])
        _, page = self.post("/v1/sync/pull", {"type": "pull_request", "protocol_version": "1", "cursor": None, "scopes": ["library"], "limit": 100}, auth=True)
        return page["changes"]

    def test_library_snapshot_carries_asset_file_entities_in_the_documented_shape(self) -> None:
        files = {c["entity_id"]: c for c in self.library_snapshot() if c["entity_type"] == "asset_file"}
        self.assertEqual({"file-1", "file-2"}, set(files))
        original = files["file-1"]
        self.assertEqual("sample-lib.asset-file/v1", original["schema"])
        value = original["value"]
        self.assertEqual(
            {"id", "asset_id", "file_role", "resource_uri", "sha256", "size_bytes", "mime_type", "etag"},
            set(value),
        )
        self.assertEqual("original://file-1", value["resource_uri"])
        self.assertEqual("original", value["file_role"])
        self.assertEqual(MEDIA_SHA256, value["sha256"])
        self.assertEqual(len(MEDIA_BYTES), value["size_bytes"])
        self.assertEqual(f'"{MEDIA_SHA256}"', value["etag"])
        self.assertEqual("preview://file-2", files["file-2"]["value"]["resource_uri"])

    def test_media_requires_a_credential_and_the_media_read_scope(self) -> None:
        self.pair()
        status, _, _ = self.raw("GET", "/v1/sync/media/file-1", auth=False)
        self.assertEqual(401, status)
        self.post("/__samplelib__/deny-media", {}, auth=True)
        status, body, _ = self.raw("GET", "/v1/sync/media/file-1")
        self.assertEqual(403, status)
        self.assertEqual("scope_forbidden", json.loads(body)["error"]["code"])

    def test_media_unknown_id_is_404_without_leaking_paths(self) -> None:
        self.pair()
        status, body, _ = self.raw("GET", "/v1/sync/media/nope")
        self.assertEqual(404, status)
        payload = json.loads(body)
        self.assertEqual("media_not_found", payload["detail"]["error"]["code"])
        self.assertNotIn("/", payload["detail"]["error"]["message"])

    def test_media_full_and_ranged_responses_match_the_descriptor(self) -> None:
        self.pair()
        size = len(MEDIA_BYTES)
        etag = f'"{MEDIA_SHA256}"'

        status, body, headers = self.raw("GET", "/v1/sync/media/file-1")
        self.assertEqual(200, status)
        self.assertEqual(MEDIA_BYTES, body)
        self.assertEqual(etag, headers["ETag"])
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual(str(size), headers["Content-Length"])
        self.assertEqual("audio/wav", headers["Content-Type"])

        status, body, headers = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=10-"})
        self.assertEqual(206, status)
        self.assertEqual(MEDIA_BYTES[10:], body)
        self.assertEqual(f"bytes 10-{size - 1}/{size}", headers["Content-Range"])

        status, body, headers = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=10-19"})
        self.assertEqual(206, status)
        self.assertEqual(MEDIA_BYTES[10:20], body)
        self.assertEqual(f"bytes 10-19/{size}", headers["Content-Range"])

        status, body, _ = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=-5"})
        self.assertEqual(206, status)
        self.assertEqual(MEDIA_BYTES[-5:], body)

        # bytes=0- is a legitimate 206, unlike the legacy fixture route.
        status, body, headers = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=0-"})
        self.assertEqual(206, status)
        self.assertEqual(f"bytes 0-{size - 1}/{size}", headers["Content-Range"])

    def test_media_if_range_mismatch_restarts_and_match_resumes(self) -> None:
        self.pair()
        etag = f'"{MEDIA_SHA256}"'
        status, body, headers = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=73-", "If-Range": '"stale"'})
        self.assertEqual(200, status)
        self.assertEqual(MEDIA_BYTES, body)
        self.assertNotIn("Content-Range", headers)
        status, body, _ = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": "bytes=73-", "If-Range": etag})
        self.assertEqual(206, status)
        self.assertEqual(MEDIA_BYTES[73:], body)

    def test_media_unsatisfiable_ranges_answer_416_with_the_total_size(self) -> None:
        self.pair()
        size = len(MEDIA_BYTES)
        for header in (f"bytes={size}-", f"bytes=-{size + 1}", "bytes=5-2", "bytes=abc", "bytes=0-1,4-5"):
            with self.subTest(range=header):
                status, _, headers = self.raw("GET", "/v1/sync/media/file-1", headers={"Range": header})
                self.assertEqual(416, status)
                self.assertEqual(f"bytes */{size}", headers["Content-Range"])

    def test_media_does_not_honour_if_none_match_like_the_real_server(self) -> None:
        self.pair()
        status, body, _ = self.raw("GET", "/v1/sync/media/file-1", headers={"If-None-Match": f'"{MEDIA_SHA256}"'})
        self.assertEqual(200, status)
        self.assertEqual(MEDIA_BYTES, body)

    def test_media_head_returns_headers_without_a_body(self) -> None:
        self.pair()
        status, body, headers = self.raw("HEAD", "/v1/sync/media/file-2")
        self.assertEqual(200, status)
        self.assertEqual(b"", body)
        self.assertEqual(str(len(PREVIEW_BYTES)), headers["Content-Length"])
        self.assertEqual("audio/mpeg", headers["Content-Type"])

    def test_parse_single_range_edge_cases(self) -> None:
        self.assertEqual((0, 9), parse_single_range("bytes=0-9", 100))
        self.assertEqual((90, 99), parse_single_range("bytes=-10", 100))
        self.assertEqual((0, 99), parse_single_range("bytes=-100", 100))
        self.assertEqual((95, 99), parse_single_range("bytes=95-500", 100))
        self.assertIsNone(parse_single_range("bytes=-101", 100))
        self.assertIsNone(parse_single_range("bytes=100-", 100))
        self.assertIsNone(parse_single_range("items=0-9", 100))
        self.assertIsNone(parse_single_range("bytes=0-1,2-3", 100))
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd /home/user/code/androidjtools-s2   # the worktree
PYTHONDONTWRITEBYTECODE=1 uv run --quiet --with-requirements test-server/requirements-test.txt python -I -m unittest discover -s test-server -p 'test_*.py' 2>&1 | tail -6
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=test-server/samplelib uv run --quiet --with-requirements test-server/requirements-test.txt python -m unittest discover -s test-server/samplelib -p 'test_*.py' 2>&1 | tail -6
```

Expected: FAIL. The contract tests fail on the missing `asset_file`/`media` entries, and the fake tests fail with `ImportError: cannot import name 'PREVIEW_BYTES'`.

- [ ] **Step 4: Update the machine and prose contracts**

`contracts/sync/v1/protocol.json`, in the top-level `capabilities` block change the pull list (this is the only line with `analysis_suggestion` at that indentation inside `capabilities`; use an exact match):

```diff
-    "pull": ["asset", "interval", "marker", "tag", "tag_attachment", "analysis_suggestion"],
+    "pull": ["asset", "interval", "marker", "tag", "tag_attachment", "analysis_suggestion", "asset_file"],
```

and in the `http` block add the route after `receipt`:

```diff
     "receipt": "/v1/sync/receipts/{mutation_id}",
+    "media": "/v1/sync/media/{asset_file_id}",
     "websocket": "/v1/sync/events",
```

`contracts/sync/v1/sync-contract.yml`:

```diff
   receipt: GET /v1/sync/receipts/{mutation_id}
+  media_fetch: GET /v1/sync/media/{asset_file_id}
   websocket_optional: WS /v1/sync/events
```

```diff
-  pull_families: [asset, interval, marker, tag, tag_attachment, analysis_suggestion]
+  pull_families: [asset, interval, marker, tag, tag_attachment, analysis_suggestion, asset_file]
```

and replace the trailing `media:` block with:

```yaml
media:
  durable_identity: resource_uri
  integrity: content_sha256
  resumable_when_advertised: byte_range
  host_paths_on_mobile_boundary: forbidden
  asset_file:
    schema: sample-lib.asset-file/v1
    value_fields: [id, asset_id, file_role, resource_uri, sha256, size_bytes, mime_type, etag]
    roles_exposed: [original, preview]
    resource_uri_form: "<role>://<id>"
    etag: quoted strong validator (the quoted sha256 when a hash exists); keep verbatim
    fetch_requires_scope: media.read
    fetch_statuses: [200, 206, 401, 403, 404, 416]
    conditional_requests: If-Range honoured; If-None-Match NOT honoured (always 200)
    suffix_range_larger_than_file: 416
```

- [ ] **Step 5: Implement the fake server additions**

In `test-server/samplelib/fake_samplelib_server.py`:

(a) After `MEDIA_ETAG = '"fixture-media-v1"'` add:

```python
PREVIEW_BYTES = MEDIA_BYTES[:512]
ASSET_FILE_SCHEMA = "sample-lib.asset-file/v1"


def parse_single_range(header: str, size: int) -> tuple[int, int] | None:
    """RFC 9110 single byte range (N-, N-M, -N). None means unsatisfiable or unsupported.

    Mirrors the real server, where an oversize suffix (bytes=-N with N > size) is a 416.
    """
    if not header.startswith("bytes=") or "," in header:
        return None
    spec = header.removeprefix("bytes=").strip()
    first, _, last = spec.partition("-")
    try:
        if first == "":
            length = int(last)
            if length <= 0 or length > size:
                return None
            return size - length, size - 1
        start = int(first)
        end = int(last) if last != "" else size - 1
    except ValueError:
        return None
    if start < 0 or start >= size or end < start:
        return None
    return start, min(end, size - 1)
```

(b) In `AuthenticatedSampleLibFake.__init__`, after the `asset["resources"] = [...]` block add:

```python
        self.media_scope_revoked = False
        self.asset_files: dict[str, dict[str, Any]] = {
            "file-1": {"role": "original", "bytes": MEDIA_BYTES, "mime": "audio/wav"},
            "file-2": {"role": "preview", "bytes": PREVIEW_BYTES, "mime": "audio/mpeg"},
        }
        for file_id, spec in self.asset_files.items():
            digest = hashlib.sha256(spec["bytes"]).hexdigest()
            self.sync.entities[("asset_file", file_id)] = {
                "revision": f"rev:asset_file:{file_id}:1",
                "schema": ASSET_FILE_SCHEMA,
                "value": {
                    "id": file_id,
                    "asset_id": "asset-1",
                    "file_role": spec["role"],
                    "resource_uri": f'{spec["role"]}://{file_id}',
                    "sha256": digest,
                    "size_bytes": len(spec["bytes"]),
                    "mime_type": spec["mime"],
                    "etag": f'"{digest}"',
                },
            }
```

(c) In `authorize`, before the `allowed = ...` line add:

```python
        if scope == "media.read" and self.media_scope_revoked:
            raise ProtocolHttpError(403, _error("scope_forbidden", "Authorization does not grant the required scope."))
```

(d) In `do_POST`, next to the `/__samplelib__/drop-after-commit` branch add (the endpoint needs no credential, like the other `__samplelib__` hooks; the test posts `{}` because `do_POST` reads a JSON body first):

```python
            if self.path == "/__samplelib__/deny-media":
                self.server.engine.media_scope_revoked = True
                self._json(200, {"media_scope_revoked": True})
                return
```

(e) In `do_GET`, before the final `self._json(404, ...)`, add:

```python
            if self.path.startswith("/v1/sync/media/"):
                self._serve_asset_file(include_body=True)
                return
```

and in `do_HEAD`, replace the first statement so media is routed before the legacy check:

```python
    def do_HEAD(self) -> None:  # noqa: N802
        try:
            if self.path.startswith("/v1/sync/media/"):
                self._serve_asset_file(include_body=False)
                return
            if self.path != "/v1/resources/audio-fixture":
                self._json(404, _error("not_found", "Unknown Sample Lib fake endpoint."))
                return
            self._auth("media.read")
            self._send_media(include_body=False)
        except ProtocolHttpError as exc:
            self._json(exc.status, exc.payload)
```

(f) Add these methods to `SampleLibFakeHandler` (after `_send_media`):

```python
    def _serve_asset_file(self, *, include_body: bool) -> None:
        # Authorization first, before any lookup, like the real route.
        self._auth("media.read")
        file_id = unquote(self.path.removeprefix("/v1/sync/media/").split("?", 1)[0])
        spec = self.server.engine.asset_files.get(file_id)
        if spec is None:
            self._json(404, {"detail": {"error": {"code": "media_not_found", "message": "No such media file."}}})
            return
        data: bytes = spec["bytes"]
        size = len(data)
        etag = f'"{hashlib.sha256(data).hexdigest()}"'
        status, start, end = 200, 0, size - 1
        range_header = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        # A stale If-Range validator makes byte offsets unsafe: ignore Range and send everything.
        if range_header and (not if_range or if_range == etag):
            parsed = parse_single_range(range_header, size)
            if parsed is None:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            start, end = parsed
            status = 206
        payload = data[start : end + 1]
        self.send_response(status)
        self.send_header("Content-Type", spec["mime"])
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Content-Length", str(len(payload)))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if include_body:
            self.wfile.write(payload)
```

(`If-None-Match` is deliberately never read, matching the real server.)

- [ ] **Step 6: Run the tests to verify they pass**

```bash
PYTHONDONTWRITEBYTECODE=1 uv run --quiet --with-requirements test-server/requirements-test.txt python -I -m unittest discover -s test-server -p 'test_*.py' 2>&1 | tail -4
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=test-server/samplelib uv run --quiet --with-requirements test-server/requirements-test.txt python -m unittest discover -s test-server/samplelib -p 'test_*.py' 2>&1 | tail -4
```

Expected: `OK` for both. Contract tests 34 (32 + 2), fake tests 16 (7 + 9). If a pre-existing test now fails because an `asset_file` entity appeared in a snapshot (for example a test that counts pulled changes), fix the *test's* expectation only if it hard-codes the old entity set; do not remove the entities.

- [ ] **Step 7: Mutation check, then commit**

Prove the 403 and the suffix rule are really tested: temporarily delete the `media_scope_revoked` check in `authorize` (the 403 test must go RED) and temporarily change `length > size` to `length > size + 1000` in `parse_single_range` (the 416 and edge-case tests must go RED). Restore both exactly.

```bash
git add contracts test-server
git commit -m "feat(contracts): add asset_file family and authenticated media route to the fakes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Parse `asset_file` rows and project playable server tracks

**Files:**
- Modify: `app/src/main/java/dev/androidjtools/core/model/Domain.kt` (`Track`)
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/ProjectionJson.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/ServerAudio.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/LibraryProjection.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt` (`SampleLibProjectionState`)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/ServerAudioTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/LibraryProjectionTest.kt` (append)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt` (append)

**Interfaces:**
- Consumes: `CachedEntity(entityType, entityId, revision, schema, valueJson)`; `LibraryCache.snapshot(vararg types)`; `ServerCapabilities(pull, mutations, playlists, analysis, media)`.
- Produces (Tasks 4-9 rely on these exact names):
  - `AssetFileDescriptor(id, assetId, role, sha256, sizeBytes, mimeType, etag)`; `sha256` is lowercase, `etag` verbatim.
  - `ServerAudio(stream: AssetFileDescriptor, download: AssetFileDescriptor)`.
  - `ServerAudioSupport(advertised: Boolean, range: Boolean)` and `serverAudioSupport(ServerCapabilities?)`.
  - `AssetFiles.parse(CachedEntity): AssetFileDescriptor?`, `AssetFiles.audioByAsset(List<CachedEntity>): Map<String, ServerAudio>`.
  - `ASSET_FILE_ENTITY = "asset_file"`, `NO_SERVER_AUDIO_NOTICE`, `DECK_NEEDS_DOWNLOAD_NOTICE`.
  - `Track.deckBlockedReason: String?` (null for device tracks).
  - `LibraryProjection.audio: Map<String, ServerAudio>` and `.audioAdvertised: Boolean`.
  - `LibraryProjector.project(assets, intervals, assetFiles = emptyList(), audioAdvertised = false, downloaded: Map<String, String> = emptyMap())` where `downloaded` maps asset_file id to the lowercase sha256 of its verified local copy.
  - `SampleLibProjectionState(cache, scope, dispatcher = Dispatchers.IO, capabilities = MutableStateFlow(null), downloaded: Flow<Map<String, String>> = flowOf(emptyMap()))`.

- [ ] **Step 1: Write the failing parser tests**

Create `app/src/test/java/dev/androidjtools/sync/samplelib/ServerAudioTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAudioTest {
    private val digest = "ab".repeat(32)

    private fun file(
        id: String = "file-1",
        assetId: String = "asset-1",
        role: String = "original",
        sha: String = digest,
        size: String = "2688",
        mime: String = "\"audio/wav\"",
        uri: String = "$role://$id",
    ) = CachedEntity(
        "asset_file", id, "r", "sample-lib.asset-file/v1",
        """{"id":"$id","asset_id":"$assetId","file_role":"$role","resource_uri":"$uri","sha256":"$sha","size_bytes":$size,"mime_type":$mime,"etag":"\"$sha\""}""",
    )

    @Test
    fun aWellFormedRowKeepsTheEtagVerbatimWithItsQuotes() {
        val parsed = AssetFiles.parse(file())!!
        assertEquals("file-1", parsed.id)
        assertEquals("asset-1", parsed.assetId)
        assertEquals("original", parsed.role)
        assertEquals(digest, parsed.sha256)
        assertEquals(2688L, parsed.sizeBytes)
        assertEquals("audio/wav", parsed.mimeType)
        assertEquals("\"$digest\"", parsed.etag)
    }

    @Test
    fun theDigestIsNormalisedToLowercase() {
        assertEquals(digest, AssetFiles.parse(file(sha = digest.uppercase()))!!.sha256)
    }

    @Test
    fun malformedRowsAreDroppedNotGuessedAt() {
        assertNull("role outside original/preview", AssetFiles.parse(file(role = "stem", uri = "stem://file-1")))
        assertNull("uri that disagrees with role and id", AssetFiles.parse(file(uri = "original://other")))
        assertNull("short digest", AssetFiles.parse(file(sha = "abc")))
        assertNull("non-hex digest", AssetFiles.parse(file(sha = "zz".repeat(32))))
        assertNull("zero size", AssetFiles.parse(file(size = "0")))
        assertNull("negative size", AssetFiles.parse(file(size = "-5")))
        assertNull("string size", AssetFiles.parse(file(size = "\"2688\"")))
        assertNull("blank mime", AssetFiles.parse(file(mime = "\"\"")))
        assertNull("entity id differs from value id", AssetFiles.parse(file().copy(entityId = "file-9")))
        assertNull("not json", AssetFiles.parse(file().copy(valueJson = "{")))
        assertNull("not an object", AssetFiles.parse(file().copy(valueJson = "[]")))
    }

    @Test
    fun streamingPrefersThePreviewAndDownloadingPrefersTheOriginal() {
        val audio = AssetFiles.audioByAsset(
            listOf(file("f-orig", role = "original"), file("f-prev", role = "preview")),
        ).getValue("asset-1")
        assertEquals("f-prev", audio.stream.id)
        assertEquals("f-orig", audio.download.id)
    }

    @Test
    fun aLoneFileServesBothPurposes() {
        val onlyPreview = AssetFiles.audioByAsset(listOf(file("f-prev", role = "preview"))).getValue("asset-1")
        assertEquals("f-prev", onlyPreview.stream.id)
        assertEquals("f-prev", onlyPreview.download.id)
        val onlyOriginal = AssetFiles.audioByAsset(listOf(file("f-orig", role = "original"))).getValue("asset-1")
        assertEquals("f-orig", onlyOriginal.stream.id)
        assertEquals("f-orig", onlyOriginal.download.id)
    }

    @Test
    fun filesAreGroupedPerAssetAndBadRowsDoNotHideGoodOnes() {
        val audio = AssetFiles.audioByAsset(
            listOf(file("f1", assetId = "a1"), file("f2", assetId = "a2"), file("bad", assetId = "a3", sha = "x")),
        )
        assertEquals(setOf("a1", "a2"), audio.keys)
    }

    @Test
    fun twoOriginalsForOneAssetResolveDeterministicallyToTheLowestId() {
        val audio = AssetFiles.audioByAsset(listOf(file("f-b"), file("f-a"))).getValue("asset-1")
        assertEquals("f-a", audio.download.id)
    }

    private fun caps(pull: Set<String>, media: Set<String>) = ServerCapabilities(pull, emptySet(), "canonical_v1", emptySet(), media)

    @Test
    fun audioIsAdvertisedOnlyWithTheFamilyAndTheMediaFeatures() {
        val all = setOf("resource_uri", "content_hash", "range")
        assertEquals(ServerAudioSupport(true, true), serverAudioSupport(caps(setOf("asset", "asset_file"), all)))
        assertEquals(ServerAudioSupport(true, false), serverAudioSupport(caps(setOf("asset_file"), setOf("resource_uri", "content_hash"))))
        assertFalse(serverAudioSupport(caps(setOf("asset"), all)).advertised)
        assertFalse(serverAudioSupport(caps(setOf("asset_file"), setOf("resource_uri"))).advertised)
        assertFalse(serverAudioSupport(caps(setOf("asset_file"), setOf("content_hash"))).advertised)
        assertFalse(serverAudioSupport(null).advertised)
        assertTrue(serverAudioSupport(caps(setOf("asset_file"), all)).range)
    }
}
```

- [ ] **Step 2: Write the failing projection tests**

Append inside `class LibraryProjectionTest` (the file already has `asset()`, `interval()` helpers):

```kotlin
    private val digest = "cd".repeat(32)

    private fun assetFile(id: String, assetId: String, role: String = "original", sha: String = digest) = CachedEntity(
        "asset_file", id, "r", "sample-lib.asset-file/v1",
        """{"id":"$id","asset_id":"$assetId","file_role":"$role","resource_uri":"$role://$id","sha256":"$sha","size_bytes":100,"mime_type":"audio/wav","etag":"\"$sha\""}""",
    )

    private val oneAsset = listOf(asset("a1", """{"title":"Alpha","duration_ms":10000}"""))

    @Test
    fun anAdvertisedServerWithAnAudioFileMakesTheTrackPlayableButNotDeckReady() {
        val projection = LibraryProjector.project(oneAsset, emptyList(), listOf(assetFile("f1", "a1")), audioAdvertised = true)
        val track = projection.tracks.single()
        assertTrue(track.playable)
        assertNull(track.unplayableReason)
        assertFalse(track.offlineAvailable)
        assertEquals(DECK_NEEDS_DOWNLOAD_NOTICE, track.deckBlockedReason)
        assertEquals("f1", projection.audio.getValue("a1").download.id)
        assertTrue(projection.audioAdvertised)
    }

    @Test
    fun aVerifiedLocalCopyMakesTheTrackOfflineAndDeckReady() {
        val projection = LibraryProjector.project(
            oneAsset, emptyList(), listOf(assetFile("f1", "a1")), audioAdvertised = true, downloaded = mapOf("f1" to digest),
        )
        val track = projection.tracks.single()
        assertTrue(track.offlineAvailable)
        assertNull(track.deckBlockedReason)
        assertTrue(track.playable)
    }

    @Test
    fun aLocalCopyOfAnOlderRevisionDoesNotCountAsDownloaded() {
        val projection = LibraryProjector.project(
            oneAsset, emptyList(), listOf(assetFile("f1", "a1", sha = digest)), audioAdvertised = true,
            downloaded = mapOf("f1" to "ee".repeat(32)),
        )
        assertFalse(projection.tracks.single().offlineAvailable)
        assertEquals(DECK_NEEDS_DOWNLOAD_NOTICE, projection.tracks.single().deckBlockedReason)
    }

    @Test
    fun aDownloadedTrackStaysPlayableWhenTheServerIsNotAdvertisingAudio() {
        val projection = LibraryProjector.project(
            oneAsset, emptyList(), listOf(assetFile("f1", "a1")), audioAdvertised = false, downloaded = mapOf("f1" to digest),
        )
        assertTrue(projection.tracks.single().playable)
        assertFalse(projection.audioAdvertised)
    }

    @Test
    fun withoutAdvertisedAudioAStreamOnlyTrackExplainsWhy() {
        val projection = LibraryProjector.project(oneAsset, emptyList(), listOf(assetFile("f1", "a1")), audioAdvertised = false)
        val track = projection.tracks.single()
        assertFalse(track.playable)
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, track.unplayableReason)
        assertNull(track.deckBlockedReason)
    }

    @Test
    fun anAdvertisedServerWithoutAFileForTheTrackSaysThereIsNoAudio() {
        val projection = LibraryProjector.project(oneAsset, emptyList(), emptyList(), audioAdvertised = true)
        val track = projection.tracks.single()
        assertFalse(track.playable)
        assertEquals(NO_SERVER_AUDIO_NOTICE, track.unplayableReason)
    }

    @Test
    fun audioForAssetsThatAreNotInTheLibraryIsNotExposed() {
        val projection = LibraryProjector.project(oneAsset, emptyList(), listOf(assetFile("f9", "ghost")), audioAdvertised = true)
        assertTrue(projection.audio.isEmpty())
    }

    @Test
    fun theDefaultsKeepEveryServerTrackUnplayableLikeStageS1() {
        val track = LibraryProjector.project(oneAsset, emptyList()).tracks.single()
        assertFalse(track.playable)
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, track.unplayableReason)
    }
```

Append inside `class SampleLibProvidersTest` (it has `asset()`, `json()`, `tmp`):

```kotlin
    private fun assetFileChange(id: String, assetId: String) = json(
        "asset_file", id,
        """{"id":"$id","asset_id":"$assetId","file_role":"original","resource_uri":"original://$id","sha256":"${"ab".repeat(32)}","size_bytes":9,"mime_type":"audio/wav","etag":"\"${"ab".repeat(32)}\""}""",
    )

    private fun caps(vararg pull: String) = dev.androidjtools.remote.samplelib.ServerCapabilities(
        pull.toSet(), emptySet(), "canonical_v1", emptySet(), setOf("resource_uri", "content_hash", "range"),
    )

    @Test
    fun tracksBecomePlayableWhenTheServerAdvertisesAudioAndFollowTheDownloadedFiles() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val capabilities = MutableStateFlow<dev.androidjtools.remote.samplelib.ServerCapabilities?>(null)
        val downloaded = MutableStateFlow<Map<String, String>>(emptyMap())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler), capabilities, downloaded)
        cache.applyPage(listOf(asset("a1", "Alpha"), assetFileChange("f1", "a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        // Capabilities not known yet (cold start): cached file rows are the evidence, so tracks are not locked out.
        assertTrue(state.tracks.value.single().playable)

        capabilities.value = caps("asset", "interval")
        advanceUntilIdle()
        assertTrue("server without asset_file", !state.tracks.value.single().playable)

        capabilities.value = caps("asset", "asset_file")
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().playable)
        assertTrue(!state.tracks.value.single().offlineAvailable)

        downloaded.value = mapOf("f1" to "ab".repeat(32))
        advanceUntilIdle()
        assertTrue(state.tracks.value.single().offlineAvailable)
        assertEquals("f1", state.projection.value.audio.getValue("a1").download.id)
    }
```

Add `import kotlinx.coroutines.flow.MutableStateFlow` only if not already imported (it is).

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*ServerAudioTest' --tests '*LibraryProjectionTest' --tests '*SampleLibProvidersTest' 2>&1 | tail -15
```
Expected: compilation FAILS (`AssetFiles`, `ServerAudioSupport`, `deckBlockedReason`, `DECK_NEEDS_DOWNLOAD_NOTICE` unresolved).

- [ ] **Step 4: Implement**

`Domain.kt`, add the field to `Track` after `unplayableReason`:

```kotlin
    val playable: Boolean = true,
    val unplayableReason: String? = null,
    /** Why this track cannot go on a Live deck even though it can be played (for example: not downloaded). */
    val deckBlockedReason: String? = null,
)
```

Create `ProjectionJson.kt` (moved out of `LibraryProjector`, where these were private):

```kotlin
package dev.androidjtools.sync.samplelib

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun parseJsonObject(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
```

In `LibraryProjection.kt`: delete the private `parseObject`, `string`, `long`, `double` members of `LibraryProjector` and the imports that become unused (`SerializationException`, `Json`, `JsonPrimitive`, `doubleOrNull`, `longOrNull`); replace the two `parseObject(` calls with `parseJsonObject(`.

Create `ServerAudio.kt` with exactly the code from the Interfaces block:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.ServerCapabilities

const val ASSET_FILE_ENTITY = "asset_file"

const val NO_SERVER_AUDIO_NOTICE = "This track has no audio file on your Sample Lib server yet."
const val DECK_NEEDS_DOWNLOAD_NOTICE = "Download this track to load it on a deck."

/** One audio file the server can serve. [sha256] is lowercase; [etag] is the server's quoted validator, verbatim. */
data class AssetFileDescriptor(
    val id: String,
    val assetId: String,
    val role: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val etag: String,
)

/** [stream] is what the player streams (the small preview when there is one); [download] is what a pin stores. */
data class ServerAudio(val stream: AssetFileDescriptor, val download: AssetFileDescriptor)

data class ServerAudioSupport(val advertised: Boolean, val range: Boolean)

fun serverAudioSupport(capabilities: ServerCapabilities?): ServerAudioSupport {
    if (capabilities == null) return ServerAudioSupport(advertised = false, range = false)
    val advertised = ASSET_FILE_ENTITY in capabilities.pull &&
        "resource_uri" in capabilities.media &&
        "content_hash" in capabilities.media
    return ServerAudioSupport(advertised, range = advertised && "range" in capabilities.media)
}

object AssetFiles {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val roles = setOf("original", "preview")

    /** Returns null for anything that does not match the documented descriptor exactly. */
    fun parse(entity: CachedEntity): AssetFileDescriptor? {
        val value = parseJsonObject(entity.valueJson) ?: return null
        val id = value.string("id")?.takeIf { it.isNotBlank() && it == entity.entityId } ?: return null
        val assetId = value.string("asset_id")?.takeIf { it.isNotBlank() } ?: return null
        val role = value.string("file_role")?.takeIf { it in roles } ?: return null
        if (value.string("resource_uri") != "$role://$id") return null
        val digest = value.string("sha256")?.takeIf { sha256.matches(it) } ?: return null
        val size = value.long("size_bytes")?.takeIf { it > 0L } ?: return null
        val mime = value.string("mime_type")?.takeIf { it.isNotBlank() } ?: return null
        val etag = value.string("etag")?.takeIf { it.isNotBlank() } ?: return null
        return AssetFileDescriptor(id, assetId, role, digest.lowercase(), size, mime, etag)
    }

    fun audioByAsset(entities: List<CachedEntity>): Map<String, ServerAudio> =
        entities.mapNotNull(::parse)
            .groupBy { it.assetId }
            .mapValues { (_, files) ->
                val sorted = files.sortedBy { it.id }
                val preview = sorted.firstOrNull { it.role == "preview" }
                val original = sorted.firstOrNull { it.role == "original" }
                // Both roles are non-empty here: parse() only yields original or preview.
                ServerAudio(stream = preview ?: original!!, download = original ?: preview!!)
            }
}
```

`LibraryProjection.kt`: replace the data class and `project` head/tail.

```kotlin
data class LibraryProjection(
    val tracks: List<Track>,
    val cues: Map<String, List<Cue>>,
    val loops: Map<String, List<Loop>>,
    /** Audio files by track (asset) id, whether or not the server currently advertises streaming. */
    val audio: Map<String, ServerAudio> = emptyMap(),
    /** True when the server advertises audio, so [audio] may be streamed. A downloaded copy never needs it. */
    val audioAdvertised: Boolean = false,
) {
```
(rest of the class body unchanged)

```kotlin
    fun project(
        assets: List<CachedEntity>,
        intervals: List<CachedEntity>,
        assetFiles: List<CachedEntity> = emptyList(),
        audioAdvertised: Boolean = false,
        downloaded: Map<String, String> = emptyMap(),
    ): LibraryProjection {
        val parsedIntervals = intervals.mapNotNull(::parseInterval)
        val byAsset = parsedIntervals.groupBy { it.assetId }
        val audio = AssetFiles.audioByAsset(assetFiles)

        val tracks = assets.mapNotNull { entity ->
            val value = parseJsonObject(entity.valueJson) ?: return@mapNotNull null
            val duration = value.long("duration_ms") ?: 0L
            val fullSpan = byAsset[entity.entityId].orEmpty()
                .firstOrNull { duration > 0 && it.startMs == 0L && it.endMs >= duration }
            val files = audio[entity.entityId]
            val offline = files != null && downloaded[files.download.id] == files.download.sha256
            val playable = files != null && (audioAdvertised || offline)
            Track(
                id = entity.entityId,
                title = value.string("title") ?: value.string("original_filename") ?: entity.entityId,
                artist = value.string("artist") ?: "Unknown artist",
                album = value.string("collection"),
                durationMs = duration,
                bpm = fullSpan?.bpm,
                key = fullSpan?.key,
                rating = value.long("rating")?.toInt(),
                offlineAvailable = offline,
                playable = playable,
                unplayableReason = when {
                    playable -> null
                    audioAdvertised && files == null -> NO_SERVER_AUDIO_NOTICE
                    else -> SERVER_AUDIO_UNAVAILABLE_NOTICE
                },
                deckBlockedReason = if (playable && !offline) DECK_NEEDS_DOWNLOAD_NOTICE else null,
            )
        }
        val trackIds = tracks.mapTo(HashSet()) { it.id }
        return LibraryProjection(
            tracks = tracks,
            cues = parsedIntervals.flatMap { it.cues }.groupBy { it.trackId },
            loops = parsedIntervals.flatMap { it.loops }.groupBy { it.trackId },
            audio = audio.filterKeys { it in trackIds },
            audioAdvertised = audioAdvertised,
        )
    }
```

`SampleLibProviders.kt`: replace `SampleLibProjectionState`:

```kotlin
class SampleLibProjectionState(
    cache: LibraryCache,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    capabilities: StateFlow<ServerCapabilities?> = MutableStateFlow(null),
    /** asset_file id -> lowercase sha256 of a verified local copy. A cold flow so the offline store loads off the main thread. */
    downloaded: Flow<Map<String, String>> = flowOf(emptyMap()),
) {
    val projection: StateFlow<LibraryProjection> = combine(cache.changes, capabilities, downloaded) { _, caps, files -> caps to files }
        .map { (caps, files) ->
            try {
                val snap = cache.snapshot("asset", "interval", ASSET_FILE_ENTITY)
                val assetFiles = snap[ASSET_FILE_ENTITY].orEmpty()
                // Until the first hello of this process the capabilities are unknown (null): cached file rows
                // are then the evidence, so a cold start does not lock a synced library out of playback.
                val advertised = serverAudioSupport(caps).advertised || (caps == null && assetFiles.isNotEmpty())
                LibraryProjector.project(snap["asset"].orEmpty(), snap["interval"].orEmpty(), assetFiles, advertised, files)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null // keep previous value
            }
        }
        .filterNotNull()
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.Eagerly, LibraryProjection.EMPTY)
    // tracks: unchanged
```
Add imports `dev.androidjtools.remote.samplelib.ServerCapabilities`, `kotlinx.coroutines.flow.Flow` and `kotlinx.coroutines.flow.flowOf`. `SERVER_AUDIO_UNAVAILABLE_NOTICE` stays where it is.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*ServerAudioTest' --tests '*LibraryProjectionTest' --tests '*SampleLibProvidersTest' 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL. Then the whole unit suite (`./gradlew --offline :app:testDebugUnitTest`) must still be green: older tests that built `Track(...)` or asserted `playable == false` for server tracks keep passing because the projector defaults preserve S1 behaviour. If a Compose/androidTest source constructs `Track` positionally, `./gradlew --offline :app:compileDebugAndroidTestKotlin` must still compile (the new field is last and defaulted).

- [ ] **Step 6: Mutation check, then commit**

Flip `serverAudioSupport`'s `"content_hash" in` check to `true`; `audioIsAdvertisedOnlyWithTheFamilyAndTheMediaFeatures` must go RED. Flip `downloaded[...] == files.download.sha256` to `files.download.id in downloaded`; `aLocalCopyOfAnOlderRevisionDoesNotCountAsDownloaded` must go RED. Restore both.

```bash
git add app
git commit -m "feat(samplelib): project asset_file rows into playable server tracks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Media credential, authenticating HTTP client, and manager hook

**Files:**
- Create: `app/src/main/java/dev/androidjtools/remote/samplelib/MediaAuth.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSessionManager.kt`
- Create: `app/src/test/java/dev/androidjtools/testing/LoopbackHttpServer.kt`
- Test: `app/src/test/java/dev/androidjtools/remote/samplelib/MediaAuthTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionManagerTest.kt` (append)

**Interfaces:**
- Consumes: `MobileCredential.bearerValue()` (internal, same module), `StoredSessionResult.Loaded`.
- Produces:
  - `class MediaAuth` with `val base: HttpUrl`, `fun accepts(url: HttpUrl): Boolean`, `internal fun authorizationHeader(): String`, `fun mediaUrl(fileId: String): HttpUrl`, companion `create(baseUrl: String, credential: MobileCredential): MediaAuth?`.
  - `fun interface MediaAuthProvider { fun mediaAuth(): MediaAuth? }`.
  - `fun mediaUrl(baseUrl: String, fileId: String): HttpUrl?`.
  - `class MediaAuthInterceptor(provider: MediaAuthProvider) : Interceptor`.
  - `fun newMediaHttpClient(provider: MediaAuthProvider, base: OkHttpClient = OkHttpClient()): OkHttpClient`.
  - `SampleLibSessionManager : MediaAuthProvider` (blocking, call off the main thread).
  - Test helper `dev.androidjtools.testing.LoopbackHttpServer(handler)`, `RecordedRequest`, `StubResponse`.

- [ ] **Step 1: Write the loopback test server**

`app/src/test/java/dev/androidjtools/testing/LoopbackHttpServer.kt` (the JDK-internal `com.sun.net.httpserver` is not on the Android unit-test classpath, so this follows the `ServerSocket` pattern of `SampleLibHttpTransportPathTest`, one thread per connection):

```kotlin
package dev.androidjtools.testing

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Header names are lower-cased. */
data class RecordedRequest(val method: String, val path: String, val headers: Map<String, String>)

class StubResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/** Minimal loopback HTTP/1.1 server for unit tests. Every response closes the connection. */
class LoopbackHttpServer(private val handler: (RecordedRequest) -> StubResponse) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    val port: Int get() = server.localPort
    val requests: List<RecordedRequest> get() = seen.toList()

    /** [prefix] is a reverse-proxy style path prefix such as "/samplelab" (or empty). */
    fun baseUrl(prefix: String = "") = "http://127.0.0.1:$port$prefix"

    init {
        thread(isDaemon = true, name = "loopback-http-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                thread(isDaemon = true, name = "loopback-http-conn") { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
            }
        }
        val requestLine = line() ?: return
        val parts = requestLine.split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line() ?: break
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val request = RecordedRequest(parts[0], parts[1], headers)
        seen += request
        val response = handler(request)
        val head = StringBuilder("HTTP/1.1 ${response.status} X\r\n")
        response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (response.headers.keys.none { it.equals("Content-Length", true) }) head.append("Content-Length: ${response.body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        socket.getOutputStream().apply {
            write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method != "HEAD") write(response.body)
            flush()
        }
    }

    override fun close() = server.close()
}
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/dev/androidjtools/remote/samplelib/MediaAuthTest.kt`:

```kotlin
package dev.androidjtools.remote.samplelib

import dev.androidjtools.testing.LoopbackHttpServer
import dev.androidjtools.testing.StubResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAuthTest {
    private val servers = mutableListOf<LoopbackHttpServer>()

    private fun server(handler: (dev.androidjtools.testing.RecordedRequest) -> StubResponse = { StubResponse(200, body = "ok".toByteArray()) }) =
        LoopbackHttpServer(handler).also { servers += it }

    @After
    fun stop() = servers.forEach { it.close() }

    private fun auth(baseUrl: String, token: String = "tok-secret") =
        MediaAuth.create(baseUrl, MobileCredential.issued(token))!!

    private fun client(auth: MediaAuth?) = newMediaHttpClient(MediaAuthProvider { auth })

    private fun get(client: OkHttpClient, url: String, vararg headers: Pair<String, String>): Int =
        client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.code }

    @Test
    fun theBearerIsSentOnlyToThePairedOriginsMediaPath() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        assertEquals(200, get(client, "${paired.baseUrl()}/v1/sync/media/file-1"))
        assertEquals("Bearer tok-secret", paired.requests.single().headers["authorization"])
    }

    @Test
    fun nothingElseOnThePairedHostGetsTheBearer() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${paired.baseUrl()}/v1/sync/pull")
        get(client, "${paired.baseUrl()}/v1/sync/media")
        get(client, "${paired.baseUrl()}/other/v1/sync/media/file-1")
        // Dot segments are normalised by HttpUrl before matching, so traversal cannot widen the match.
        get(client, "${paired.baseUrl()}/v1/sync/media/%2e%2e/pull")
        get(client, "${paired.baseUrl()}/v1/sync/media/../pull")

        assertEquals(5, paired.requests.size)
        assertTrue(paired.requests.all { "authorization" !in it.headers })
    }

    @Test
    fun aDifferentHostOrPortNeverGetsTheBearer() {
        val paired = server()
        val other = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${other.baseUrl()}/v1/sync/media/file-1") // different port
        get(client, "http://localhost:${paired.port}/v1/sync/media/file-1") // different host name

        assertTrue(other.requests.all { "authorization" !in it.headers })
        assertEquals(1, paired.requests.size)
        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun aReverseProxyPrefixIsPartOfTheMatch() {
        val paired = server()
        val client = client(auth(paired.baseUrl("/samplelab")))

        get(client, "${paired.baseUrl("/samplelab")}/v1/sync/media/file-1")
        get(client, "${paired.baseUrl()}/v1/sync/media/file-1")

        assertEquals("Bearer tok-secret", paired.requests[0].headers["authorization"])
        assertNull(paired.requests[1].headers["authorization"])
    }

    @Test
    fun redirectsAreNeverFollowedSoTheBearerCannotLeakThroughOne() {
        val target = server()
        val paired = server {
            StubResponse(302, headers = mapOf("Location" to "http://127.0.0.1:${target.port}/v1/sync/media/file-1"))
        }
        val client = client(auth(paired.baseUrl()))

        assertEquals(302, get(client, "${paired.baseUrl()}/v1/sync/media/file-1"))
        assertTrue(target.requests.isEmpty())
    }

    @Test
    fun aCallerSuppliedAuthorizationHeaderIsRemovedWhenTheUrlIsNotTheMediaRoute() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${paired.baseUrl()}/elsewhere", "Authorization" to "Bearer injected")

        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun withNoCredentialNothingIsSent() {
        val paired = server()
        val client = client(null)

        get(client, "${paired.baseUrl()}/v1/sync/media/file-1")

        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun theCredentialNeverAppearsInTheStringForm() {
        val rendered = auth("http://127.0.0.1:8765").toString()
        assertFalse(rendered.contains("tok-secret"))
    }

    @Test
    fun aRemoteHostWithoutTlsCannotHoldACredential() {
        assertNull(MediaAuth.create("http://example.com", MobileCredential.issued("t")))
        assertNull(MediaAuth.create("not a url", MobileCredential.issued("t")))
        assertNotNull(MediaAuth.create("https://example.com/samplelab", MobileCredential.issued("t")))
        assertNotNull(MediaAuth.create("http://localhost:8765", MobileCredential.issued("t")))
    }

    @Test
    fun mediaUrlsKeepTheProxyPrefixAndEncodeTheId() {
        assertEquals(
            "http://127.0.0.1:1/samplelab/v1/sync/media/file-1",
            mediaUrl("http://127.0.0.1:1/samplelab", "file-1").toString(),
        )
        assertEquals("http://127.0.0.1:1/v1/sync/media/file-1", mediaUrl("http://127.0.0.1:1", "file-1").toString())
        assertEquals("http://127.0.0.1:1/v1/sync/media/a%2Fb", mediaUrl("http://127.0.0.1:1/", "a/b").toString())
        assertNull(mediaUrl("nope", "file-1"))
    }
}
```

Append to `SampleLibSessionManagerTest` (it has `store`, `manager`, `seedStoredSession()`, `transport`, `page()`, `assetChange()`):

```kotlin
    @Test
    fun theMediaCredentialIsAvailableAfterPairingAndGoneAfterForget() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.pair("https://samplelab.example", "246810")

        val auth = manager.mediaAuth()!!
        assertEquals("samplelab.example", auth.base.host)

        manager.forget()
        assertNull(manager.mediaAuth())
    }

    @Test
    fun aColdManagerLoadsTheMediaCredentialFromTheStoreOnDemand() = runBlocking {
        seedStoredSession()
        assertEquals("samplelab.example", manager.mediaAuth()!!.base.host)
    }

    @Test
    fun aRejectedCredentialWithdrawsTheMediaCredentialUntilTheNextAcceptedSession() = runBlocking {
        seedStoredSession()
        transport.helloFailure = SampleLibFailure.AuthRequired()

        manager.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, manager.state.value)
        assertNull(manager.mediaAuth())

        transport.helloFailure = null
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.refresh()
        assertEquals("samplelab.example", manager.mediaAuth()!!.base.host)
    }

    @Test
    fun withNothingStoredThereIsNoMediaCredential() = runBlocking {
        manager.refresh()
        assertNull(manager.mediaAuth())
    }
```

If `SampleLibFailure.AuthRequired` has constructor arguments, mirror how the existing test at the `AUTH_REQUIRED` case in this file constructs it.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*MediaAuthTest' --tests '*SampleLibSessionManagerTest' 2>&1 | tail -15
```
Expected: compilation FAILS (`MediaAuth`, `newMediaHttpClient`, `manager.mediaAuth` unresolved).

- [ ] **Step 4: Implement `MediaAuth.kt`**

```kotlin
package dev.androidjtools.remote.samplelib

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

private const val MEDIA_SEGMENTS = "v1/sync/media"

/**
 * The paired server's media origin plus its bearer. It can only be created for an https server or a loopback
 * address, mirrors [SampleLibHttpTransport]'s own rule, and never renders the secret.
 */
class MediaAuth private constructor(val base: HttpUrl, private val credential: MobileCredential) {
    private val mediaPrefix: String = base.encodedPath.trimEnd('/') + "/$MEDIA_SEGMENTS/"

    /** True only for the same scheme, host and port, under `<base path>/v1/sync/media/`. */
    fun accepts(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.encodedPath.startsWith(mediaPrefix) && url.encodedPath.length > mediaPrefix.length

    internal fun authorizationHeader(): String = "Bearer ${credential.bearerValue()}"

    fun mediaUrl(fileId: String): HttpUrl = buildMediaUrl(base, fileId)

    override fun toString(): String = "MediaAuth(${base.host}, <redacted>)"

    companion object {
        fun create(baseUrl: String, credential: MobileCredential): MediaAuth? {
            val parsed = parseBase(baseUrl) ?: return null
            return MediaAuth(parsed, credential)
        }
    }
}

fun interface MediaAuthProvider {
    /** Blocking (may read the Keystore-backed store): call off the main thread. Null when no credential is usable. */
    fun mediaAuth(): MediaAuth?
}

/** The streaming/download URL of an asset_file under [baseUrl], or null when [baseUrl] is not acceptable. */
fun mediaUrl(baseUrl: String, fileId: String): HttpUrl? = parseBase(baseUrl)?.let { buildMediaUrl(it, fileId) }

private fun parseBase(baseUrl: String): HttpUrl? {
    val parsed = (if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/").toHttpUrlOrNull() ?: return null
    val loopback = parsed.host == "127.0.0.1" || parsed.host == "localhost"
    return parsed.takeIf { loopback || it.isHttps }
}

private fun buildMediaUrl(base: HttpUrl, fileId: String): HttpUrl =
    base.newBuilder().addPathSegments(MEDIA_SEGMENTS).addPathSegment(fileId).build()

/** Adds the bearer only for [MediaAuth.accepts] URLs and strips any caller-supplied Authorization otherwise. */
class MediaAuthInterceptor(private val provider: MediaAuthProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Cheap pre-check so unrelated requests never trigger a credential load.
        val mayBeMedia = request.url.encodedPath.contains("/$MEDIA_SEGMENTS/")
        val auth = if (mayBeMedia) provider.mediaAuth()?.takeIf { it.accepts(request.url) } else null
        val outgoing = request.newBuilder().removeHeader("Authorization").apply {
            if (auth != null) header("Authorization", auth.authorizationHeader())
        }.build()
        return chain.proceed(outgoing)
    }
}

/** One client for Media3 streaming and for offline downloads: never follows redirects. */
fun newMediaHttpClient(provider: MediaAuthProvider, base: OkHttpClient = OkHttpClient()): OkHttpClient =
    base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(MediaAuthInterceptor(provider))
        .build()
```

Note `addPathSegments("v1/sync/media")` after a base ending in `/` (path segment list `[…, ""]`) replaces the trailing empty segment, so the prefix is kept and no `//` appears; `mediaUrlsKeepTheProxyPrefixAndEncodeTheId` pins that.

- [ ] **Step 5: Implement the manager hook**

In `SampleLibSessionManager.kt` change the class header and add the state (after the `mutableProfile` line):

```kotlin
class SampleLibSessionManager(
    ...
) : MediaAuthProvider {
```

```kotlin
    // The media bearer is cached in memory so playback does not touch the Keystore per request. The generation
    // counter stops a slow store load from re-caching a credential that was forgotten or rejected meanwhile.
    private val authLock = Any()
    private var cachedAuth: MediaAuth? = null
    private var authUsable = true
    private var authGeneration = 0L

    override fun mediaAuth(): MediaAuth? {
        val generation: Long
        synchronized(authLock) {
            if (!authUsable) return null
            cachedAuth?.let { return it }
            generation = authGeneration
        }
        val session = (store.load() as? StoredSessionResult.Loaded)?.session ?: return null
        val auth = MediaAuth.create(session.profile.baseUrl, session.credential) ?: return null
        synchronized(authLock) {
            if (!authUsable || authGeneration != generation) return null
            cachedAuth = auth
        }
        return auth
    }

    private fun revokeMediaAuth() = synchronized(authLock) {
        authGeneration++
        authUsable = false
        cachedAuth = null
    }

    private fun acceptMediaAuth(baseUrl: String, session: SampleLibSession) = synchronized(authLock) {
        authGeneration++
        authUsable = true
        cachedAuth = MediaAuth.create(baseUrl, session.credential)
    }
```

Add imports `dev.androidjtools.remote.samplelib.MediaAuth` and `MediaAuthProvider`. Wire the calls:

- `refresh()`: in the `StoredSessionResult.Absent` branch call `revokeMediaAuth()` first; in `Unreadable` call `revokeMediaAuth()` first.
- `applyRefreshFailure`: in the `SampleLibFailure.AuthRequired` branch call `revokeMediaAuth()` before `fail(...)`.
- `forget()`: call `revokeMediaAuth()` as the first statement inside `lock.withLock`.
- `syncSession(client, session)`: first statement `mutableProfile.value?.let { acceptMediaAuth(it.baseUrl, session) }` (a session exists only after the server accepted the credential; in `pair`, `mutableProfile.value = saved` is set right before `syncSession`).

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*MediaAuthTest' --tests '*SampleLibSessionManagerTest' 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Mutation check, then commit**

In `accepts`, drop the `url.port == base.port` term: `aDifferentHostOrPortNeverGetsTheBearer` must go RED. Change `followRedirects(false)` to `true`: `redirectsAreNeverFollowed…` must go RED. In `forget()`, remove `revokeMediaAuth()`: `…GoneAfterForget` must go RED. Restore all.

```bash
git add app
git commit -m "feat(samplelib): media credential, authenticating client and manager hook

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Authenticated `RangeTransport` for the offline engine, and runtime wiring

The offline engine (`OfflineDownloadEngine`) already resumes with `Range`/`If-Range`, enforces quota and verifies SHA-256. It is unwired in the shipped app and its default transport sends no credential and follows redirects. This task gives it a transport that maps `original://<id>` / `preview://<id>` to the paired server's media URL, reuses the authenticating client from Task 3, and moves the cache out of `filesDir`.

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRangeTransport.kt`
- Modify: `app/src/main/java/dev/androidjtools/offline/download/WorkManagerOfflineDownloads.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibRangeTransportTest.kt` (create)

**Interfaces:**
- Consumes: `MediaAuthProvider`, `MediaAuth.mediaUrl(fileId)`, `newMediaHttpClient`, `LoopbackHttpServer` (Task 3); `RangeTransport`, `RangeRequest`, `RangeResponse`, `OkHttpRangeTransport(client)`, `OfflineDownloadEngine(store, storage, quota, transport)`, `OfflineCacheStorage`, `OfflineCacheQuotaManager`, `OfflineDownloadStore`.
- Produces:
  - `class SampleLibRangeTransport(auth: MediaAuthProvider, client: OkHttpClient) : RangeTransport`.
  - `OfflineDownloadRuntime.create(context: Context, transport: RangeTransport): OfflineDownloadRuntime` (public); the data class gains `val root: File`. The `get(context)` / `coordinator(context)` singleton accessors are **removed** (their only caller is the worker, which now goes through `SampleLibRuntime`).
  - `SampleLibRuntime.mediaHttpClient: OkHttpClient`, `.rangeTransport: RangeTransport`, `.offline: OfflineDownloadRuntime` (lazy).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibRangeTransportTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.DownloadAttemptResult
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheQuotaManager
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadEngine
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.remote.samplelib.MediaAuth
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.newMediaHttpClient
import dev.androidjtools.testing.LoopbackHttpServer
import dev.androidjtools.testing.RecordedRequest
import dev.androidjtools.testing.StubResponse
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibRangeTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(5000) { (it * 31 % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val servers = mutableListOf<LoopbackHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun media(respond: (RecordedRequest) -> StubResponse): LoopbackHttpServer =
        LoopbackHttpServer(respond).also { servers += it }

    /** Mirrors the real route for original://file-1: strong etag, Range N-, If-Range. */
    private fun fileServer(body: ByteArray = content) = media { request ->
        val etag = "\"$digest\""
        val range = request.headers["range"]
        val ifRange = request.headers["if-range"]
        if (!request.path.endsWith("/v1/sync/media/file-1")) {
            StubResponse(404)
        } else if (range != null && (ifRange == null || ifRange == etag)) {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            StubResponse(
                206,
                mapOf("ETag" to etag, "Content-Range" to "bytes $start-${body.size - 1}/${body.size}"),
                body.copyOfRange(start, body.size),
            )
        } else {
            StubResponse(200, mapOf("ETag" to etag), body)
        }
    }

    private fun transport(server: LoopbackHttpServer, prefix: String = "", token: String? = "tok-secret"): SampleLibRangeTransport {
        val auth = token?.let { MediaAuth.create(server.baseUrl(prefix), MobileCredential.issued(it)) }
        val provider = MediaAuthProvider { auth }
        return SampleLibRangeTransport(provider, newMediaHttpClient(provider))
    }

    private fun asset(uri: String = "original://file-1", sha: String = digest, length: Long? = content.size.toLong()) =
        OfflineAsset("file-1", uri, sha, length, advertisedEtag = "\"$sha\"")

    private fun engine(transport: SampleLibRangeTransport, root: File = tmp.newFolder()): Triple<OfflineDownloadEngine, OfflineDownloadStore, OfflineCacheStorage> {
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        return Triple(OfflineDownloadEngine(store, storage, OfflineCacheQuotaManager(store, storage, 1L shl 20), transport), store, storage)
    }

    @Test
    fun aRoleUriIsMappedToTheAuthenticatedMediaUrlUnderTheProxyPrefix() = runBlocking {
        val server = fileServer()
        val response = transport(server, "/samplelab").open(RangeRequest(asset()))

        response.use { assertArrayEquals(content, it.body.readBytes()) }
        val seen = server.requests.single()
        assertEquals("/samplelab/v1/sync/media/file-1", seen.path)
        assertEquals("Bearer tok-secret", seen.headers["authorization"])
    }

    @Test
    fun rangeAndIfRangeArePassedThrough() = runBlocking {
        val server = fileServer()
        val response = transport(server).open(RangeRequest(asset(), startByte = 100, ifRange = "\"$digest\""))

        response.use {
            assertEquals(206, it.statusCode)
            assertEquals(100L, it.contentRangeStart)
            assertEquals("\"$digest\"", it.etag)
            assertArrayEquals(content.copyOfRange(100, content.size), it.body.readBytes())
        }
        assertEquals("bytes=100-", server.requests.single().headers["range"])
    }

    @Test
    fun onlyRoleUrisOfThePairedServerAreAccepted() = runBlocking {
        val server = fileServer()
        val subject = transport(server)
        for (uri in listOf("https://evil.example/x", "file:///etc/passwd", "original://a/b", "original://", "stem://file-1", "file-1", "original://file-1?x=1")) {
            try {
                subject.open(RangeRequest(asset(uri)))
                fail("accepted $uri")
            } catch (expected: IOException) {
                assertFalse(expected.message.orEmpty().contains("tok-secret"))
            }
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun withoutAPairedCredentialNoRequestIsMade() = runBlocking {
        val server = fileServer()
        try {
            transport(server, token = null).open(RangeRequest(asset()))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theEngineDownloadsVerifiesAndPublishesTheFile() = runBlocking {
        val server = fileServer()
        val (engine, store, storage) = engine(transport(server))

        assertTrue(engine.download(asset()) is DownloadAttemptResult.Success)

        assertEquals(OfflineDownloadPhase.AVAILABLE, store.get("file-1")?.phase)
        assertArrayEquals(content, storage.finalFile("file-1").readBytes())
        assertEquals("Bearer tok-secret", server.requests.single().headers["authorization"])
    }

    @Test
    fun theEngineResumesAPartialDownloadWithTheStoredValidator() = runBlocking {
        val server = fileServer()
        val (engine, store, storage) = engine(transport(server))
        store.ensure(asset())
        storage.partialFile("file-1").writeBytes(content.copyOfRange(0, 1200))
        store.update("file-1") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = 1200, currentEtag = "\"$digest\"") }

        assertTrue(engine.download(asset()) is DownloadAttemptResult.Success)

        assertEquals("bytes=1200-", server.requests.single().headers["range"])
        assertEquals("\"$digest\"", server.requests.single().headers["if-range"])
        assertArrayEquals(content, storage.finalFile("file-1").readBytes())
    }

    @Test
    fun aTruncatedBodyNeverBecomesAvailable() = runBlocking {
        val server = media { StubResponse(200, mapOf("Content-Length" to "${content.size}"), content.copyOfRange(0, 1000)) }
        val (engine, store, storage) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Retry)
        assertFalse(storage.finalFile("file-1").exists())
        assertEquals(OfflineDownloadPhase.QUEUED, store.get("file-1")?.phase)
        assertNull(OfflineMediaResolver(store, storage).resolve("file-1"))
    }

    @Test
    fun aBodyWithTheWrongHashIsDiscardedAndReportedAsIntegrity() = runBlocking {
        val corrupted = content.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val server = fileServer(corrupted)
        val (engine, store, storage) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Retry)
        assertEquals(DownloadFailureKind.INTEGRITY, (result as DownloadAttemptResult.Retry).error.kind)
        assertFalse(storage.finalFile("file-1").exists())
        assertFalse(storage.partialFile("file-1").exists())
    }

    @Test
    fun aRevokedCredentialIsAHardFailureNotARetryLoop() = runBlocking {
        val server = media { StubResponse(401) }
        val (engine, store, _) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Failed)
        assertEquals(OfflineDownloadPhase.FAILED, store.get("file-1")?.phase)
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*SampleLibRangeTransportTest' 2>&1 | tail -15
```
Expected: compilation FAILS (`SampleLibRangeTransport` unresolved).

- [ ] **Step 3: Implement the transport**

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRangeTransport.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OkHttpRangeTransport
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.offline.download.RangeResponse
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Downloads `original://<asset_file id>` / `preview://<asset_file id>` from the paired server. The persisted
 * descriptor keeps only the role reference; the URL is rebuilt from the current pairing for every request, so
 * no host or credential is ever stored with the download.
 */
class SampleLibRangeTransport(
    private val auth: MediaAuthProvider,
    client: OkHttpClient,
) : RangeTransport {
    private val delegate = OkHttpRangeTransport(client)

    override suspend fun open(request: RangeRequest): RangeResponse {
        val fileId = fileIdOf(request.asset.resourceUri) ?: throw IOException("Unsupported media reference.")
        // May read the Keystore-backed store the first time after process start.
        val credential = withContext(Dispatchers.IO) { auth.mediaAuth() } ?: throw IOException("No paired Sample Lib server.")
        val url = credential.mediaUrl(fileId).toString()
        return delegate.open(request.copy(asset = request.asset.copy(resourceUri = url)))
    }

    private companion object {
        private val reference = Regex("^(?:original|preview)://([^/?#\\s]+)$")

        fun fileIdOf(resourceUri: String): String? = reference.matchEntire(resourceUri)?.groupValues?.get(1)
    }
}
```

- [ ] **Step 4: Rewire the offline runtime**

`WorkManagerOfflineDownloads.kt`: the worker and the runtime object become

```kotlin
class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val asset = inputData.toOfflineAsset() ?: return Result.failure()
        val runtime = SampleLibRuntime.get(applicationContext).offline
        // ... the `when (val result = runtime.engine.download(asset))` block is unchanged
    }
    // companion unchanged
}

data class OfflineDownloadRuntime(
    val root: File,
    val store: OfflineDownloadStore,
    val storage: OfflineCacheStorage,
    val resolver: OfflineMediaResolver,
    val engine: OfflineDownloadEngine,
) {
    companion object {
        private const val DEFAULT_QUOTA_BYTES = 4L * 1024L * 1024L * 1024L

        /** The cache lives in noBackupFilesDir: excluded from device backups even if the manifest policy changes. */
        fun create(context: Context, transport: RangeTransport): OfflineDownloadRuntime {
            val root = File(context.noBackupFilesDir, "offline-downloads")
            val store = OfflineDownloadStore(File(root, "state"))
            val storage = OfflineCacheStorage(File(root, "media"))
            val resolver = OfflineMediaResolver(store, storage)
            val quota = OfflineCacheQuotaManager(store, storage, DEFAULT_QUOTA_BYTES)
            return OfflineDownloadRuntime(
                root = root,
                store = store,
                storage = storage,
                resolver = resolver,
                engine = OfflineDownloadEngine(store, storage, quota, transport),
            )
        }
    }
}
```
Delete the `instance`, `get` and `coordinator` members, the `okhttp3.OkHttpClient` import, and add `import dev.androidjtools.sync.samplelib.SampleLibRuntime`. `WorkManagerDownloadScheduler` is unchanged. The work `Data` still carries only `asset_id`, `resource_uri` (`original://…`), hash, length, range flag, etag: no host, no token.

`SampleLibRuntime.kt`: add after `val controller`:

```kotlin
    /** One authenticating client for Media3 streaming and downloads: bearer only for the paired media path, no redirects. */
    val mediaHttpClient: OkHttpClient = newMediaHttpClient(manager)
    val rangeTransport: RangeTransport = SampleLibRangeTransport(manager, mediaHttpClient)

    /** Lazy: reading the offline state is disk I/O that the service and the sync worker never need. */
    val offline: OfflineDownloadRuntime by lazy { OfflineDownloadRuntime.create(appContext, rangeTransport) }
    val downloadScheduler: DownloadScheduler by lazy { WorkManagerDownloadScheduler(appContext) }
```
Keep these three declarations **above** `projection` (Task 6 reads `offline` from the projection's flow).
with imports for `OkHttpClient`, `RangeTransport`, `OfflineDownloadRuntime`, `DownloadScheduler`, `WorkManagerDownloadScheduler`, `newMediaHttpClient`.

- [ ] **Step 5: Run the tests and the compile gates**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*SampleLibRangeTransportTest' 2>&1 | tail -15
./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin 2>&1 | tail -5
```
Expected: tests PASS (9); both compile gates BUILD SUCCESSFUL.

- [ ] **Step 6: Mutation check, then commit**

Replace the `reference` regex with `^(?:original|preview)://(.+)$`; `onlyRoleUrisOfThePairedServerAreAccepted` must go RED (`original://a/b`). In the engine integration, none of the hash logic is ours, so only confirm the tests fail if the transport returns the wrong host: change `credential.mediaUrl(fileId)` to `credential.mediaUrl("x$fileId")` and see `theEngineDownloadsVerifiesAndPublishesTheFile` go RED. Restore.

```bash
git add app
git commit -m "feat(offline): authenticated media range transport and no-backup offline cache

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: One resolver from track id to local file or stream URL

`OfflineMediaResolver.resolve()` re-hashes the whole file on every call, which would block the main thread if `play()` used it. This task adds a cheap check and the single resolver that the player, the decks and the UI share.

**Files:**
- Modify: `app/src/main/java/dev/androidjtools/offline/download/OfflineCache.kt` (`OfflineMediaResolver`)
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibMediaResolver.kt`
- Test: `app/src/test/java/dev/androidjtools/offline/download/OfflineMediaResolverQuickTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibMediaResolverTest.kt` (create)

**Interfaces:**
- Consumes: `OfflineDownloadStore`, `OfflineCacheStorage`, `LibraryProjection.audio/audioAdvertised`, `mediaUrl(baseUrl, fileId)`.
- Produces:
  - `OfflineMediaResolver.resolveQuick(assetId: String, expectedSha256: String? = null): OfflineCachedMedia?`
  - `class SampleLibMediaResolver(projection: () -> LibraryProjection, baseUrl: () -> String?, offline: () -> OfflineMediaResolver)` (the offline resolver is a provider so the lazily built offline runtime is first touched off the main thread) with `localUri(trackId)`, `streamUri(trackId)`, `playbackUri(trackId)` (local first, else stream), `deckUri(trackId)` (local only). All return `String?`, never block on disk beyond one `File.length()`, never read credentials.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/offline/download/OfflineMediaResolverQuickTest.kt`:

```kotlin
package dev.androidjtools.offline.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaResolverQuickTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = sha256Hex(bytes)
    private val asset = OfflineAsset("f1", "original://f1", sha, bytes.size.toLong())

    private fun setup(phase: OfflineDownloadPhase = OfflineDownloadPhase.AVAILABLE, write: ByteArray? = bytes):
        Triple<OfflineDownloadStore, OfflineCacheStorage, OfflineMediaResolver> {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        store.ensure(asset)
        store.update("f1") { it.copy(phase = phase) }
        write?.let { storage.finalFile("f1").writeBytes(it) }
        return Triple(store, storage, OfflineMediaResolver(store, storage))
    }

    @Test
    fun anAvailableFileOfTheRightSizeResolvesWithoutHashing() {
        val (_, storage, resolver) = setup()
        val media = resolver.resolveQuick("f1", sha)!!
        assertEquals(storage.finalFile("f1").toURI().toString(), media.uri)
        assertEquals(10L, media.sizeBytes)
    }

    @Test
    fun quickResolutionDoesNotRehashSoItCannotBlockTheMainThread() {
        // Same length, different content: resolve() would catch it by hashing, resolveQuick() deliberately does not.
        val (_, _, resolver) = setup(write = "ABCDEFGHIJ".toByteArray())
        assertNotNull(resolver.resolveQuick("f1", sha))
        assertNull(resolver.resolve("f1"))
    }

    @Test
    fun aWrongSizeOrMissingFileIsNotAvailable() {
        assertNull(setup(write = "short".toByteArray()).third.resolveQuick("f1", sha))
        assertNull(setup(write = null).third.resolveQuick("f1", sha))
    }

    @Test
    fun onlyTheAvailablePhaseResolves() {
        for (phase in OfflineDownloadPhase.values().filter { it != OfflineDownloadPhase.AVAILABLE }) {
            assertNull(phase.name, setup(phase).third.resolveQuick("f1", sha))
        }
    }

    @Test
    fun aDescriptorForADifferentRevisionDoesNotResolve() {
        val (_, _, resolver) = setup()
        assertNull(resolver.resolveQuick("f1", "ee".repeat(32)))
        assertNotNull(resolver.resolveQuick("f1", sha.uppercase()))
        assertNotNull(resolver.resolveQuick("f1"))
    }

    @Test
    fun anUnknownIdResolvesToNothing() {
        assertNull(setup().third.resolveQuick("nope", sha))
    }
}
```

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibMediaResolverTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibMediaResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun descriptor(id: String, role: String, sha: String = this.sha) =
        AssetFileDescriptor(id, "a1", role, sha, 10, "audio/wav", "\"$sha\"")

    private val audio = ServerAudio(stream = descriptor("f-prev", "preview"), download = descriptor("f-orig", "original"))

    private class Fixture(val offline: OfflineMediaResolver, val store: OfflineDownloadStore, val storage: OfflineCacheStorage)

    private fun fixture(): Fixture {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        return Fixture(OfflineMediaResolver(store, storage), store, storage)
    }

    private fun Fixture.download(id: String = "f-orig", sha: String = this@SampleLibMediaResolverTest.sha) {
        store.ensure(OfflineAsset(id, "original://$id", sha, 10))
        store.update(id) { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        storage.finalFile(id).writeBytes(bytes)
    }

    private fun projection(advertised: Boolean = true, withAudio: Boolean = true) = LibraryProjection(
        tracks = emptyList(), cues = emptyMap(), loops = emptyMap(),
        audio = if (withAudio) mapOf("a1" to audio) else emptyMap(), audioAdvertised = advertised,
    )

    private fun resolver(f: Fixture, projection: LibraryProjection = projection(), base: String? = "https://lab.example/samplelab") =
        SampleLibMediaResolver({ projection }, { base }, { f.offline })

    @Test
    fun withoutALocalCopyTheStreamUrlIsThePreviewUnderTheProxyPrefixAndCarriesNoCredential() {
        val r = resolver(fixture())
        assertEquals("https://lab.example/samplelab/v1/sync/media/f-prev", r.playbackUri("a1"))
        assertEquals("https://lab.example/samplelab/v1/sync/media/f-prev", r.streamUri("a1"))
        assertNull(r.localUri("a1"))
    }

    @Test
    fun aVerifiedLocalCopyWinsOverStreaming() {
        val f = fixture().also { it.download() }
        val r = resolver(f)
        val local = r.localUri("a1")!!
        assertTrue(local.startsWith("file:"))
        assertEquals(local, r.playbackUri("a1"))
    }

    @Test
    fun decksOnlyEverGetTheLocalCopy() {
        val none = resolver(fixture())
        assertNull(none.deckUri("a1"))
        val f = fixture().also { it.download() }
        assertEquals(resolver(f).localUri("a1"), resolver(f).deckUri("a1"))
    }

    @Test
    fun aLocalCopyOfAnOlderRevisionIsIgnored() {
        val f = fixture().also { it.download(sha = "ee".repeat(32)) }
        assertNull(resolver(f).localUri("a1"))
    }

    @Test
    fun streamingNeedsAdvertisedAudioAndAPairedBase() {
        assertNull(resolver(fixture(), projection(advertised = false)).streamUri("a1"))
        assertNull(resolver(fixture(), base = null).streamUri("a1"))
        assertNull(resolver(fixture(), base = "http://example.com").streamUri("a1"))
        // ...but a local copy still plays when the server is not advertising audio or no base is known.
        val f = fixture().also { it.download() }
        assertTrue(resolver(f, projection(advertised = false), base = null).playbackUri("a1")!!.startsWith("file:"))
    }

    @Test
    fun unknownTracksResolveToNothing() {
        val r = resolver(fixture(), projection(withAudio = false))
        assertNull(r.playbackUri("a1"))
        assertNull(r.deckUri("a1"))
        assertNull(resolver(fixture()).playbackUri("device-media:7"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*OfflineMediaResolverQuickTest' --tests '*SampleLibMediaResolverTest' 2>&1 | tail -10
```
Expected: compilation FAILS (`resolveQuick`, `SampleLibMediaResolver` unresolved).

- [ ] **Step 3: Implement**

`OfflineCache.kt`, inside `OfflineMediaResolver` (after `resolve`):

```kotlin
    /**
     * Cheap availability check for call sites that must not block (the player's play(), deck loading, state
     * flows): the verified-complete phase, the right revision and the expected length. The SHA-256 was verified
     * when the download completed; [resolve] re-hashes and is for explicit revalidation off the main thread.
     */
    fun resolveQuick(assetId: String, expectedSha256: String? = null): OfflineCachedMedia? {
        val record = store.get(assetId) ?: return null
        if (record.phase != OfflineDownloadPhase.AVAILABLE) return null
        if (expectedSha256 != null && !record.asset.normalizedSha256.equals(expectedSha256, ignoreCase = true)) return null
        val file = storage.finalFile(assetId)
        if (!file.isFile) return null
        val expected = record.asset.contentLength ?: record.totalBytes
        if (expected != null && file.length() != expected) return null
        return OfflineCachedMedia(assetId, file.toURI().toString(), record.asset.normalizedSha256, file.length())
    }
```

`SampleLibMediaResolver.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OfflineMediaResolver
import dev.androidjtools.remote.samplelib.mediaUrl

/**
 * The only place that decides where a server track's audio comes from. A verified local copy always wins;
 * streaming needs the server to advertise audio; Live decks get local copies only. Every method is safe on the
 * main thread: no hashing, no credentials, no network.
 */
class SampleLibMediaResolver(
    private val projection: () -> LibraryProjection,
    private val baseUrl: () -> String?,
    private val offline: () -> OfflineMediaResolver,
) {
    fun localUri(trackId: String): String? {
        val download = projection().audio[trackId]?.download ?: return null
        return offline().resolveQuick(download.id, download.sha256)?.uri
    }

    fun streamUri(trackId: String): String? {
        val current = projection()
        if (!current.audioAdvertised) return null
        val stream = current.audio[trackId]?.stream ?: return null
        return mediaUrl(baseUrl() ?: return null, stream.id)?.toString()
    }

    fun playbackUri(trackId: String): String? = localUri(trackId) ?: streamUri(trackId)

    fun deckUri(trackId: String): String? = localUri(trackId)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*OfflineMediaResolverQuickTest' --tests '*SampleLibMediaResolverTest' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL (6 + 6 tests).

- [ ] **Step 5: Mutation check, then commit**

Make `deckUri` return `playbackUri(trackId)`; `decksOnlyEverGetTheLocalCopy` must go RED. Remove the `expectedSha256` comparison from `resolveQuick`; `aDescriptorForADifferentRevisionDoesNotResolve` and `aLocalCopyOfAnOlderRevisionIsIgnored` must go RED. Restore.

```bash
git add app
git commit -m "feat(samplelib): single media resolver with a non-blocking local-file check

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Download provider for server tracks (pin, state, summary, prune)

Replaces `ServerAwareDownloads` (which reported every server track as not downloaded and ignored pins) with a provider over `OfflineDownloadCoordinator`. Device tracks still delegate.

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibDownloadProvider.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt` (delete `ServerAwareDownloads`)
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibDownloadProviderTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt` (remove the two `ServerAwareDownloads` tests, `serverTracksReportNotDownloadedAndNeverReachDelegateForPinRetryCancel` and `deviceTracksDelegate`, and the `FakeDownloads` helper they used; the new tests supersede them)

**Interfaces:**
- Consumes: `OfflineDownloadCoordinator`, `OfflineDownloadStore.allRecords/get/update/all/evictIfEligible`, `OfflineCacheStorage.evict/usedBytes`, `LibraryProjection.audio/hasTrack`, `DownloadScheduler`.
- Produces:
  - `fun downloadedFiles(records: Flow<Map<String, OfflineDownloadRecord>>): Flow<Map<String, String>>` (asset_file id to sha256 for `AVAILABLE` records).
  - `class SampleLibDownloadProvider(delegate, projection: StateFlow<LibraryProjection>, rangeSupported: () -> Boolean, coordinator, store, storage, scope, maxBytes, freeSpaceBytes: () -> Long, dispatcher: CoroutineDispatcher, reserveBytes: Long = 256 MiB) : DownloadProvider`.
  - Pin owner for tracks is the track id (`OfflineDownloadCoordinator.pinTrack(asset, trackId)` → owner `track:<trackId>`), cache key is the asset_file id.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibDownloadProviderTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.DownloadScheduler
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibDownloadProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val original = AssetFileDescriptor("f-orig", "a1", "original", sha, 10, "audio/wav", "\"$sha\"")
    private val preview = AssetFileDescriptor("f-prev", "a1", "preview", "cd".repeat(32), 4, "audio/mpeg", "\"x\"")
    private val audio = ServerAudio(stream = preview, download = original)

    private class RecordingScheduler : DownloadScheduler {
        val enqueued = mutableListOf<OfflineAsset>()
        val cancelled = mutableListOf<String>()
        override fun enqueue(asset: OfflineAsset, replace: Boolean) {
            enqueued += asset
        }
        override fun cancel(assetId: String) {
            cancelled += assetId
        }
    }

    private class FakeDevice : DownloadProvider {
        val calls = mutableListOf<String>()
        override val cacheSummary: StateFlow<OfflineCacheSummary> = MutableStateFlow(OfflineCacheSummary())
        override fun state(trackId: String): StateFlow<TrackDownloadState> =
            MutableStateFlow(TrackDownloadState(trackId, DownloadStatus.AVAILABLE, 1.0))
        override fun pinTrack(trackId: String) { calls += "pin:$trackId" }
        override fun unpinTrack(trackId: String) { calls += "unpin:$trackId" }
        override fun pinPlaylist(playlistId: String) = Unit
        override fun unpinPlaylist(playlistId: String) = Unit
        override fun retry(trackId: String) { calls += "retry:$trackId" }
        override fun cancel(trackId: String) { calls += "cancel:$trackId" }
        override fun pruneUnpinned() = Unit
    }

    private inner class Fixture(
        val scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        free: Long = 10L shl 30,
        range: Boolean = true,
        projectionAudio: Map<String, ServerAudio> = mapOf("a1" to audio),
    ) {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val scheduler = RecordingScheduler()
        val device = FakeDevice()
        val projection = MutableStateFlow(
            LibraryProjection(
                tracks = listOf(dev.androidjtools.core.model.Track("a1", "A", "B", durationMs = 1)),
                cues = emptyMap(), loops = emptyMap(), audio = projectionAudio, audioAdvertised = true,
            ),
        )
        val provider = SampleLibDownloadProvider(
            delegate = device, projection = projection, rangeSupported = { range },
            coordinator = OfflineDownloadCoordinator(store, scheduler, OfflineMediaResolver(store, storage)),
            store = store, storage = storage, scope = scope, maxBytes = 1L shl 30,
            freeSpaceBytes = { free }, dispatcher = dispatcher, reserveBytes = 1000,
        )
    }

    @Test
    fun pinningQueuesTheOriginalWithItsHashLengthEtagAndRoleReference() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        f.provider.pinTrack("a1")

        val asset = f.scheduler.enqueued.single()
        assertEquals("f-orig", asset.assetId)
        assertEquals("original://f-orig", asset.resourceUri)
        assertEquals(sha, asset.contentSha256)
        assertEquals(10L, asset.contentLength)
        assertEquals("\"$sha\"", asset.advertisedEtag)
        assertTrue(asset.supportsRange)
        assertEquals(setOf("track:a1"), f.store.get("f-orig")!!.pinOwners)
        assertEquals(DownloadStatus.QUEUED, f.provider.state("a1").value.status)
    }

    @Test
    fun aServerWithoutRangeSupportIsReflectedInTheQueuedAsset() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), range = false)
        f.provider.pinTrack("a1")
        assertFalse(f.scheduler.enqueued.single().supportsRange)
    }

    @Test
    fun stateFollowsTheEngineRecordAndReportsProgress() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val state = f.provider.state("a1")
        assertEquals(DownloadStatus.NOT_DOWNLOADED, state.value.status)

        f.provider.pinTrack("a1")
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = 5) }
        assertEquals(DownloadStatus.DOWNLOADING, state.value.status)
        assertEquals(0.5, state.value.progress, 0.0001)
        assertEquals(10L, state.value.totalBytes)

        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = 10) }
        assertEquals(DownloadStatus.AVAILABLE, state.value.status)

        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.CORRUPT) }
        assertEquals(DownloadStatus.CORRUPT, state.value.status)
    }

    @Test
    fun aRecordOfAnotherRevisionIsNotShownAsDownloaded() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.store.ensure(OfflineAsset("f-orig", "original://f-orig", "ee".repeat(32), 10))
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
    }

    @Test
    fun aFullDiskFailsVisiblyBeforeAnythingIsQueued() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), free = 500)

        f.provider.pinTrack("a1")

        assertTrue(f.scheduler.enqueued.isEmpty())
        val state = f.provider.state("a1").value
        assertEquals(DownloadStatus.FAILED, state.status)
        assertTrue(state.error.orEmpty().contains("space"))
        assertEquals(DownloadFailureKind.QUOTA, f.store.get("f-orig")!!.error!!.kind)
    }

    @Test
    fun retryAndCancelActOnTheDownloadFile() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")

        f.provider.cancel("a1")
        assertEquals(listOf("f-orig"), f.scheduler.cancelled)
        assertEquals(DownloadStatus.CANCELLED, f.provider.state("a1").value.status)

        f.provider.retry("a1")
        assertEquals(2, f.scheduler.enqueued.size)
        assertEquals(DownloadStatus.QUEUED, f.provider.state("a1").value.status)
    }

    @Test
    fun unpinningACompletedDownloadRemovesTheFileAndTheBytes() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE, bytesDownloaded = 10) }

        f.provider.unpinTrack("a1")

        assertFalse(f.storage.finalFile("f-orig").exists())
        assertEquals(OfflineDownloadPhase.NOT_CACHED, f.store.get("f-orig")!!.phase)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
    }

    @Test
    fun unpinningAnActiveDownloadCancelsItInsteadOfDeletingUnderTheWorker() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")

        f.provider.unpinTrack("a1")

        assertEquals(listOf("f-orig"), f.scheduler.cancelled)
        assertEquals(emptySet<String>(), f.store.get("f-orig")!!.pinOwners)
    }

    @Test
    fun pruneRemovesEveryUnpinnedCopyButKeepsPinnedOnes() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        val strayAsset = OfflineAsset("stray", "original://stray", "ab".repeat(32), 3)
        f.store.ensure(strayAsset)
        f.store.update("stray") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        f.storage.finalFile("stray").writeBytes("abc".toByteArray())

        f.provider.pruneUnpinned()

        assertTrue(f.storage.finalFile("f-orig").exists())
        assertFalse(f.storage.finalFile("stray").exists())
    }

    @Test
    fun theSummaryCountsUsedBytesAndPinnedTracks() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.provider.pinTrack("a1")
        f.storage.finalFile("f-orig").writeBytes(bytes)
        f.store.update("f-orig") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }

        val summary = f.provider.cacheSummary.first { it.usedBytes > 0 }

        assertEquals(10L, summary.usedBytes)
        assertEquals(1L shl 30, summary.maxBytes)
        assertEquals(setOf("a1"), summary.pinnedTrackIds)
        assertEquals(0L, summary.evictableBytes)
    }

    @Test
    fun deviceTracksAndTracksWithoutAudioAreLeftAlone() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler), projectionAudio = emptyMap())

        f.provider.pinTrack("device-media:5")
        f.provider.pinTrack("a1") // a server track with no audio file: nothing to download
        f.provider.retry("device-media:5")

        assertEquals(listOf("pin:device-media:5", "retry:device-media:5"), f.device.calls)
        assertTrue(f.scheduler.enqueued.isEmpty())
        assertEquals(DownloadStatus.NOT_DOWNLOADED, f.provider.state("a1").value.status)
        assertEquals(DownloadStatus.AVAILABLE, f.provider.state("device-media:5").value.status)
    }

    @Test
    fun downloadedFilesListsOnlyCompleteRecordsWithTheirHash() = runTest {
        val f = Fixture(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        f.store.ensure(OfflineAsset("done", "original://done", "ab".repeat(32), 1))
        f.store.update("done") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        f.store.ensure(OfflineAsset("busy", "original://busy", "cd".repeat(32), 1))
        f.store.update("busy") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING) }

        assertEquals(mapOf("done" to "ab".repeat(32)), downloadedFiles(f.store.allRecords).first())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*SampleLibDownloadProviderTest' 2>&1 | tail -10
```
Expected: compilation FAILS (`SampleLibDownloadProvider`, `downloadedFiles` unresolved).

- [ ] **Step 3: Implement**

`SampleLibDownloadProvider.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadError
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadRecord
import dev.androidjtools.offline.download.OfflineDownloadStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TRACK_OWNER_PREFIX = "track:"

/** asset_file id to the lowercase sha256 of every complete local copy. */
fun downloadedFiles(records: Flow<Map<String, OfflineDownloadRecord>>): Flow<Map<String, String>> =
    records
        .map { all ->
            all.values.filter { it.phase == OfflineDownloadPhase.AVAILABLE }
                .associate { it.asset.assetId to it.asset.normalizedSha256 }
        }
        .distinctUntilChanged()

/**
 * Pins, state, retry/cancel and the cache summary for server tracks, on top of the resumable engine. Device
 * tracks and anything without a server audio file go to [delegate]. Every mutation runs on [dispatcher] (a
 * single background thread in production) because the store fsyncs its state files.
 */
class SampleLibDownloadProvider(
    private val delegate: DownloadProvider,
    private val projection: StateFlow<LibraryProjection>,
    private val rangeSupported: () -> Boolean,
    private val coordinator: OfflineDownloadCoordinator,
    private val store: OfflineDownloadStore,
    private val storage: OfflineCacheStorage,
    private val scope: CoroutineScope,
    private val maxBytes: Long,
    private val freeSpaceBytes: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val reserveBytes: Long = 256L * 1024L * 1024L,
) : DownloadProvider {
    private val states = ConcurrentHashMap<String, StateFlow<TrackDownloadState>>()

    override val cacheSummary: StateFlow<OfflineCacheSummary> = store.allRecords
        .map(::summarize)
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.Eagerly, OfflineCacheSummary(maxBytes = maxBytes))

    private fun isServerTrack(trackId: String) = projection.value.hasTrack(trackId)
    private fun downloadOf(trackId: String): AssetFileDescriptor? = projection.value.audio[trackId]?.download

    override fun state(trackId: String): StateFlow<TrackDownloadState> {
        if (!isServerTrack(trackId)) return delegate.state(trackId)
        return states.getOrPut(trackId) {
            val fileId = projection.map { it.audio[trackId]?.download }.distinctUntilChanged()
            combine(fileId, store.allRecords) { descriptor, records -> toState(trackId, descriptor, records) }
                .stateIn(scope, SharingStarted.Eagerly, toState(trackId, downloadOf(trackId), store.allRecords.value))
        }
    }

    override fun pinTrack(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.pinTrack(trackId)
        val descriptor = downloadOf(trackId) ?: return
        val asset = offlineAsset(descriptor)
        scope.launch(dispatcher) {
            val complete = store.get(asset.assetId)?.let {
                it.phase == OfflineDownloadPhase.AVAILABLE && it.asset.normalizedSha256 == asset.normalizedSha256
            } == true
            if (!complete && freeSpaceBytes() < descriptor.sizeBytes + reserveBytes) {
                store.ensure(asset)
                store.update(asset.assetId) {
                    it.copy(
                        phase = OfflineDownloadPhase.FAILED,
                        error = OfflineDownloadError(DownloadFailureKind.QUOTA, "Not enough free space on this device.", retryable = true),
                    )
                }
                return@launch
            }
            coordinator.pinTrack(asset, trackId)
        }
    }

    override fun unpinTrack(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.unpinTrack(trackId)
        val descriptor = downloadOf(trackId) ?: return
        scope.launch(dispatcher) {
            coordinator.unpinTrack(descriptor.id, trackId)
            val record = store.get(descriptor.id) ?: return@launch
            when {
                record.pinned -> Unit // another owner still needs it
                record.phase == OfflineDownloadPhase.QUEUED || record.phase == OfflineDownloadPhase.DOWNLOADING ->
                    // Never delete underneath a running worker: stop it and let prune remove the partial later.
                    coordinator.cancel(descriptor.id)
                else -> store.evictIfEligible(descriptor.id) { storage.evict(descriptor.id) }
            }
        }
    }

    // Server playlists are not synced yet (S3); nothing to pin.
    override fun pinPlaylist(playlistId: String) = delegate.pinPlaylist(playlistId)
    override fun unpinPlaylist(playlistId: String) = delegate.unpinPlaylist(playlistId)

    override fun retry(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.retry(trackId)
        val descriptor = downloadOf(trackId) ?: return
        scope.launch(dispatcher) { coordinator.retry(offlineAsset(descriptor)) }
    }

    override fun cancel(trackId: String) {
        if (!isServerTrack(trackId)) return delegate.cancel(trackId)
        val descriptor = downloadOf(trackId) ?: return
        scope.launch(dispatcher) { coordinator.cancel(descriptor.id) }
    }

    override fun pruneUnpinned() {
        delegate.pruneUnpinned()
        scope.launch(dispatcher) {
            store.all().filter { !it.pinned }.forEach { record ->
                store.evictIfEligible(record.asset.assetId) { storage.evict(record.asset.assetId) }
            }
        }
    }

    private fun offlineAsset(d: AssetFileDescriptor) = OfflineAsset(
        assetId = d.id,
        resourceUri = "${d.role}://${d.id}",
        contentSha256 = d.sha256,
        contentLength = d.sizeBytes,
        supportsRange = rangeSupported(),
        advertisedEtag = d.etag,
    )

    private fun toState(trackId: String, descriptor: AssetFileDescriptor?, records: Map<String, OfflineDownloadRecord>): TrackDownloadState {
        val record = descriptor?.let { records[it.id] }
        // A record made for another revision of the file says nothing about the current one.
        if (record == null || record.asset.normalizedSha256 != descriptor.sha256) {
            return TrackDownloadState(trackId, DownloadStatus.NOT_DOWNLOADED)
        }
        val status = when (record.phase) {
            OfflineDownloadPhase.NOT_CACHED -> DownloadStatus.NOT_DOWNLOADED
            OfflineDownloadPhase.QUEUED -> DownloadStatus.QUEUED
            OfflineDownloadPhase.DOWNLOADING -> DownloadStatus.DOWNLOADING
            OfflineDownloadPhase.AVAILABLE -> DownloadStatus.AVAILABLE
            OfflineDownloadPhase.FAILED -> DownloadStatus.FAILED
            OfflineDownloadPhase.CANCELLED -> DownloadStatus.CANCELLED
            OfflineDownloadPhase.CORRUPT -> DownloadStatus.CORRUPT
        }
        return TrackDownloadState(trackId, status, record.progress, record.bytesDownloaded, record.totalBytes, record.error?.message)
    }

    private fun summarize(records: Map<String, OfflineDownloadRecord>): OfflineCacheSummary {
        val all = records.values
        return OfflineCacheSummary(
            usedBytes = storage.usedBytes(),
            maxBytes = maxBytes,
            evictableBytes = all.filter { !it.pinned && it.phase == OfflineDownloadPhase.AVAILABLE }.sumOf { it.bytesDownloaded },
            pinnedTrackIds = all.flatMap { it.pinOwners }
                .filter { it.startsWith(TRACK_OWNER_PREFIX) }
                .mapTo(HashSet()) { it.removePrefix(TRACK_OWNER_PREFIX) },
        )
    }
}
```

Note `store.evictIfEligible` is `internal` to the module, and `storage.evict` returns `Long?`. `OfflineDownloadError` and `DownloadFailureKind` are in `OfflineDownloadModels.kt`.

`SampleLibProviders.kt`: delete `ServerAwareDownloads`. `SampleLibRuntime.compose()`: replace the downloads line, build the resolver and feed the projection with `downloaded`:

```kotlin
    private val projection = SampleLibProjectionState(
        cache = cache,
        scope = scope,
        capabilities = manager.capabilities,
        downloaded = flow { emitAll(downloadedFiles(offline.store.allRecords)) },
    )
```
(`flow { }` is cold, so `offline` is first touched on the projection's IO thread. Declare `offline` (Task 4) **above** `projection`, because the eagerly started projection coroutine may run before the constructor finishes.) Declare the following after `projection`:

```kotlin
    private val ioDispatcher = Executors.newSingleThreadExecutor { Thread(it, "samplelib-offline").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    val mediaResolver = SampleLibMediaResolver(
        projection = { projection.projection.value },
        baseUrl = { manager.profile.value?.baseUrl },
        offline = { offline.resolver },
    )
```
In `compose()`:

```kotlin
            downloads = SampleLibDownloadProvider(
                delegate = device.downloads,
                projection = projection.projection,
                rangeSupported = { serverAudioSupport(manager.capabilities.value).range },
                coordinator = OfflineDownloadCoordinator(offline.store, downloadScheduler, offline.resolver),
                store = offline.store,
                storage = offline.storage,
                scope = wrapperScope,
                maxBytes = OFFLINE_QUOTA_BYTES,
                freeSpaceBytes = { offline.root.usableSpace },
                dispatcher = ioDispatcher,
            ),
```
with `OFFLINE_QUOTA_BYTES` made `internal const` in `WorkManagerOfflineDownloads.kt` (rename `DEFAULT_QUOTA_BYTES` and reuse it). `isServer` is still used by `ServerAwarePlayback` until Task 7.

- [ ] **Step 4: Run the tests and compile gates**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*SampleLibDownloadProviderTest' --tests '*SampleLibProvidersTest' --tests '*SampleLibMediaResolverTest' 2>&1 | tail -12
./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin 2>&1 | tail -5
```
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Mutation check, then commit**

In `pinTrack`, delete the free-space block: `aFullDiskFailsVisiblyBeforeAnythingIsQueued` must go RED. In `unpinTrack`, replace the `QUEUED/DOWNLOADING` branch with the `else` eviction: `unpinningAnActiveDownloadCancels…` must go RED. Restore.

```bash
git add app
git commit -m "feat(samplelib): pin, state, retry and prune server downloads through the offline engine

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Stream server tracks through Media3 with the authenticating client

Media3's `ExoPlayer` exists only inside `Media3PlaybackService`. It currently uses the default HTTP stack, which would send no credential and follows redirects. This task points it at the Task 3 client, makes the main player ask the single resolver, and stops the player's own error text from leaking an address.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/dev/androidjtools/playback/Media3PlaybackService.kt`
- Create: `app/src/main/java/dev/androidjtools/playback/PlaybackErrorText.kt`
- Modify: `app/src/main/java/dev/androidjtools/playback/Media3PlaybackProvider.kt` (`emit()`)
- Modify: `app/src/main/java/dev/androidjtools/device/DeviceMediaProviders.kt` (`DeviceMediaProviderBundle.create`)
- Modify: `app/src/main/java/dev/androidjtools/shell/AndroidDjToolsActivity.kt`
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt` (`ServerAwarePlayback`, `playbackRefusal`)
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt` (`compose`)
- Test: `app/src/test/java/dev/androidjtools/playback/PlaybackErrorTextTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt` (replace `serverTracksAreNotPlayedAndSayWhy`)

**Interfaces:**
- Consumes: `SampleLibRuntime.mediaHttpClient`, `SampleLibRuntime.mediaResolver.playbackUri(trackId)` (Tasks 4, 5, 6), `LibraryProjection.tracks`.
- Produces:
  - `internal fun scrubUrls(text: String?): String` (replaces `http(s)://`, `file://`, `content://` runs with `<address>`; blank or null becomes `"Playback failed."`).
  - `fun playbackRefusal(projection: LibraryProjection, trackId: String): String?`.
  - `ServerAwarePlayback(delegate: PlaybackProvider, refusalFor: (String) -> String?)`.
  - `DeviceMediaProviderBundle.create(context, mediaUriResolver: (String) -> String? = ::mediaUriForDeviceTrack)`.

- [ ] **Step 1: Add the dependency (one online resolve)**

`app/build.gradle.kts`, next to the other Media3 lines:

```kotlin
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
```

This artifact is not in the offline Gradle cache. Resolving it fetches one AndroidX artifact from Maven Google/Central, which is a network fetch outside the machine's normal cached state: **ask the user for a go-ahead before this step** if the session has not already been cleared for network access. Then, once:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -n "datasource-okhttp"
```
Expected: a line showing `androidx.media3:media3-datasource-okhttp:1.3.1`. Afterwards `--offline` works again. If the fetch is blocked, stop and report; do not vendor or hand-copy the artifact.

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/dev/androidjtools/playback/PlaybackErrorTextTest.kt`:

```kotlin
package dev.androidjtools.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorTextTest {
    @Test
    fun serverAddressesAreRemovedFromPlayerMessages() {
        val cleaned = scrubUrls("Unable to connect to http://192.168.1.5:8765/samplelab/v1/sync/media/f-prev")
        assertEquals("Unable to connect to <address>", cleaned)
        assertFalse(scrubUrls("https://lab.example/v1/sync/media/x failed").contains("lab.example"))
    }

    @Test
    fun localUrisAreScrubbedToo() {
        assertEquals("Open failed: <address>", scrubUrls("Open failed: file:///data/user/0/app/x.media"))
        assertEquals("Open failed: <address> now", scrubUrls("Open failed: content://media/external/audio/media/7 now"))
    }

    @Test
    fun plainMessagesPassThroughAndEmptyOnesGetADefault() {
        assertEquals("Response code: 401", scrubUrls("Response code: 401"))
        assertEquals("Playback failed.", scrubUrls(null))
        assertEquals("Playback failed.", scrubUrls("   "))
    }
}
```

In `SampleLibProvidersTest`, replace `serverTracksAreNotPlayedAndSayWhy` with:

```kotlin
    @Test
    fun aRefusedTrackIsNotPlayedAndSaysWhy() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { if (it.startsWith("srv-")) "No audio on the server." else null }

        playback.play("srv-1")
        assertTrue(device.played.isEmpty())
        assertEquals("No audio on the server.", playback.playbackNotice.value)

        playback.play("dev-1")
        assertEquals(listOf("dev-1"), device.played)
        assertNull(playback.playbackNotice.value)
    }

    @Test
    fun aPlayableServerTrackReachesTheDelegate() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { null }
        playback.play("srv-ok")
        assertEquals(listOf("srv-ok"), device.played)
    }

    @Test
    fun playbackRefusalFollowsTheProjection() {
        val playable = track("srv-ok")
        val blocked = track("srv-no").copy(playable = false, unplayableReason = "No audio on the server.")
        val bare = track("srv-bare").copy(playable = false, unplayableReason = null)
        val projection = LibraryProjection(listOf(playable, blocked, bare), emptyMap(), emptyMap())

        assertNull(playbackRefusal(projection, "srv-ok"))
        assertEquals("No audio on the server.", playbackRefusal(projection, "srv-no"))
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, playbackRefusal(projection, "srv-bare"))
        // Not a server track (device media, or an id from before a Forget): the player decides.
        assertNull(playbackRefusal(projection, "device-media:7"))
        assertNull(playbackRefusal(LibraryProjection.EMPTY, "srv-ok"))
    }
```
(`track(id)` is the existing helper in this test class.)

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*PlaybackErrorTextTest' --tests '*SampleLibProvidersTest' 2>&1 | tail -10
```
Expected: compilation FAILS (`scrubUrls`, `playbackRefusal`, new `ServerAwarePlayback` signature).

- [ ] **Step 4: Implement**

`playback/PlaybackErrorText.kt`:

```kotlin
package dev.androidjtools.playback

private val address = Regex("""(?i)\b(?:https?|file|content)://\S+""")

/** Player error text can embed the media URL; never show a server address (or a device path) to the person. */
internal fun scrubUrls(text: String?): String =
    text?.replace(address, "<address>")?.trim().orEmpty().ifEmpty { "Playback failed." }
```

`Media3PlaybackProvider.emit()`:

```kotlin
            current?.playerError != null -> PlaybackIssue(
                PlaybackIssueKind.PLAYBACK_ERROR,
                scrubUrls(current.playerError?.message),
            )
```

`Media3PlaybackService.kt`:

```kotlin
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.androidjtools.sync.samplelib.SampleLibRuntime
```
```kotlin
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Server tracks stream through the same authenticating client as downloads: the bearer is added by an
        // interceptor, only for the paired server's media route, and redirects are never followed. file: and
        // content: URIs keep their default handling. No credential ever travels on a MediaItem.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(SampleLibRuntime.get(this).mediaHttpClient))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .build()
            .also {
                it.setAudioAttributes(audioAttributes, true)
                it.setHandleAudioBecomingNoisy(true)
            }
        session = MediaSession.Builder(this, requireNotNull(player)).build()
    }
```

`DeviceMediaProviderBundle.create`:

```kotlin
        fun create(
            context: Context,
            mediaUriResolver: (String) -> String? = ::mediaUriForDeviceTrack,
        ): DeviceMediaProviderBundle {
            ...
            val playback = Media3PlaybackProvider.create(
                context = context.applicationContext,
                mediaUriResolver = mediaUriResolver,
            )
```

`SampleLibProviders.kt`: replace `ServerAwarePlayback` and add `playbackRefusal`:

```kotlin
/** The reason a server track cannot be played, or null (device media and unknown ids are the player's business). */
fun playbackRefusal(projection: LibraryProjection, trackId: String): String? {
    val track = projection.tracks.firstOrNull { it.id == trackId } ?: return null
    return if (track.playable) null else track.unplayableReason ?: SERVER_AUDIO_UNAVAILABLE_NOTICE
}

/** Plays through [delegate]; refuses tracks [refusalFor] gives a reason for and says why instead of failing silently. */
class ServerAwarePlayback(
    private val delegate: PlaybackProvider,
    private val refusalFor: (String) -> String?,
) : PlaybackProvider by delegate {
    private val notice = MutableStateFlow<String?>(null)
    override val playbackNotice: StateFlow<String?> = notice.asStateFlow()

    override fun play(trackId: String) {
        val refusal = refusalFor(trackId)
        if (refusal != null) {
            notice.value = refusal
            return
        }
        notice.value = null
        delegate.play(trackId)
    }
}
```

`SampleLibRuntime.compose()`: delete the `isServer` local and use

```kotlin
            playback = ServerAwarePlayback(device.playback) { id -> playbackRefusal(projection.projection.value, id) },
```

`AndroidDjToolsActivity.onCreate`: build the runtime first and give the bundle a resolver that tries device media, then the server resolver:

```kotlin
        super.onCreate(savedInstanceState)
        val runtime = SampleLibRuntime.get(this)
        deviceProviders = DeviceMediaProviderBundle.create(
            this,
            mediaUriResolver = { id -> mediaUriForDeviceTrack(id) ?: runtime.mediaResolver.playbackUri(id) },
        )
        val providers = runtime.compose(deviceProviders.providers, lifecycleScope)
```
and delete the later duplicate `val runtime = SampleLibRuntime.get(this)`.

- [ ] **Step 5: Run the tests and the gates**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*PlaybackErrorTextTest' --tests '*SampleLibProvidersTest' 2>&1 | tail -10
./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug 2>&1 | tail -8
```
Expected: PASS; compile gates and lint BUILD SUCCESSFUL (`UnstableApi` is covered by the `@OptIn`). `lintDebug` must report 0 errors.

Evidence honesty for this task: Media3 session playback, `OkHttpDataSource` streaming, notification/lock-screen behaviour and the real Keystore load in the service process are **compile-only / not run on a device**. Say so in the commit body.

- [ ] **Step 6: Mutation check, then commit**

In `scrubUrls` remove `file|content`: `localUrisAreScrubbedToo` must go RED. In `playbackRefusal` return `null` always: `playbackRefusalFollowsTheProjection` must go RED. Restore.

```bash
git add app
git commit -m "feat(playback): stream server tracks through Media3 with the authenticating client

Media3 session playback and OkHttpDataSource streaming are compile-only (no device).

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Live decks accept only local audio

A deck must never be handed a network source. The projection already sets `Track.deckBlockedReason` for server tracks that are not downloaded (Task 2) and `SampleLibMediaResolver.deckUri` returns local files only (Task 5). This task enforces both at the controller, the buttons and the engine resolver.

**Files:**
- Create: `app/src/main/java/dev/androidjtools/live/DeckLoadAvailability.kt`
- Modify: `app/src/main/java/dev/androidjtools/live/LivePerformance.kt` (`loadDeck`)
- Modify: `app/src/main/java/dev/androidjtools/ui/live/LivePerformanceScreen.kt` (`TrackLoader`)
- Modify: `app/src/main/java/dev/androidjtools/shell/AndroidDjToolsActivity.kt` (engine resolvers)
- Test: `app/src/test/java/dev/androidjtools/live/DeckLoadAvailabilityTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/live/LivePerformanceControllerTest.kt` (append)

**Interfaces:**
- Consumes: `Track.playable`, `Track.unplayableReason`, `Track.deckBlockedReason`.
- Produces: `data class DeckLoadAvailability(val enabled: Boolean, val reason: String?)` and `fun deckLoadAvailability(track: Track): DeckLoadAvailability`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/live/DeckLoadAvailabilityTest.kt`:

```kotlin
package dev.androidjtools.live

import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLoadAvailabilityTest {
    private val base = Track(id = "t", title = "T", artist = "A", durationMs = 1000)

    @Test
    fun aDeviceOrDownloadedTrackCanBeLoaded() {
        val availability = deckLoadAvailability(base)
        assertTrue(availability.enabled)
        assertNull(availability.reason)
    }

    @Test
    fun aStreamOnlyServerTrackNeedsADownloadFirst() {
        val availability = deckLoadAvailability(base.copy(deckBlockedReason = "Download this track to load it on a deck."))
        assertFalse(availability.enabled)
        assertEquals("Download this track to load it on a deck.", availability.reason)
    }

    @Test
    fun anUnplayableTrackExplainsWhyItsOwnReasonFirst() {
        val availability = deckLoadAvailability(base.copy(playable = false, unplayableReason = "No audio.", deckBlockedReason = "Download"))
        assertFalse(availability.enabled)
        assertEquals("No audio.", availability.reason)
    }

    @Test
    fun anUnplayableTrackWithoutAReasonStillGetsOne() {
        val availability = deckLoadAvailability(base.copy(playable = false))
        assertFalse(availability.enabled)
        assertTrue(availability.reason.orEmpty().isNotBlank())
    }
}
```

Append inside `LivePerformanceControllerTest` (next to `anUnplayableTrackIsRefusedByLoadDeck`; `RecordingEngine` and `grid()` are that file's helpers):

```kotlin
    @Test
    fun aStreamOnlyServerTrackIsRefusedByLoadDeckUntilItIsDownloaded() {
        val engine = RecordingEngine()
        val controller = LivePerformanceController(engine)
        val streamOnly = Track(
            id = "srv-1", title = "S", artist = "A", durationMs = 120_000L, bpm = 128.0,
            playable = true, deckBlockedReason = "Download this track to load it on a deck.",
        )

        controller.loadDeck(DeckId.A, streamOnly, grid("srv-1", 128.0))

        assertTrue(engine.loadedTracks.isEmpty())
        assertEquals(null, controller.state.value.deckA.trackId)

        controller.loadDeck(DeckId.A, streamOnly.copy(deckBlockedReason = null, offlineAvailable = true), grid("srv-1", 128.0))
        assertEquals("srv-1", engine.loadedTracks[DeckId.A])
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*DeckLoadAvailabilityTest' --tests '*LivePerformanceControllerTest' 2>&1 | tail -10
```
Expected: compilation FAILS (`deckLoadAvailability`).

- [ ] **Step 3: Implement**

`live/DeckLoadAvailability.kt`:

```kotlin
package dev.androidjtools.live

import dev.androidjtools.core.model.Track

data class DeckLoadAvailability(val enabled: Boolean, val reason: String?)

/** A deck takes device files and downloaded copies only; anything that would stream stays off the decks. */
fun deckLoadAvailability(track: Track): DeckLoadAvailability = when {
    !track.playable -> DeckLoadAvailability(false, track.unplayableReason ?: "This track cannot be played.")
    track.deckBlockedReason != null -> DeckLoadAvailability(false, track.deckBlockedReason)
    else -> DeckLoadAvailability(true, null)
}
```

`LivePerformance.kt` `loadDeck`: replace `if (!track.playable) return` with

```kotlin
        if (!deckLoadAvailability(track).enabled) return
```

`LivePerformanceScreen.kt` `TrackLoader`: inside `items(...) { track ->` compute `val availability = deckLoadAvailability(track)`; replace the `track.unplayableReason?.let { reason -> Text(...) }` block with

```kotlin
                                availability.reason?.let { reason ->
                                    Text(
                                        reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2,
                                        modifier = Modifier.testTag("deck-blocked-reason"),
                                    )
                                }
```
and both buttons `enabled = availability.enabled`. Add imports `dev.androidjtools.live.deckLoadAvailability` and `androidx.compose.ui.platform.testTag` if missing.

`AndroidDjToolsActivity`: both engines' resolvers (keep `mediaUri` for the player from Task 7):

```kotlin
        // Decks get device files and verified local copies only; deckUri never returns a network address.
        val deckUri: (String) -> String? = { id -> mediaUriForDeviceTrack(id) ?: runtime.mediaResolver.deckUri(id) }
        val liveEngine = NativeAaudioLiveAudioEngine.createOrNull(
            context = this,
            mediaUriResolver = deckUri,
            onPosition = onLivePosition,
        ) ?: Media3LiveAudioEngine(
            context = this,
            mediaUriResolver = deckUri,
            onPosition = onLivePosition,
        )
```

- [ ] **Step 4: Run the tests and gates**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*DeckLoadAvailabilityTest' --tests '*LivePerformanceControllerTest' 2>&1 | tail -10
./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin 2>&1 | tail -5
```
Expected: PASS and BUILD SUCCESSFUL. Deck audio output and the Compose buttons are compile-only (no device).

- [ ] **Step 5: Mutation check, then commit**

In `deckLoadAvailability` delete the `deckBlockedReason` branch: both the availability test and the controller test must go RED. Restore.

```bash
git add app
git commit -m "feat(live): decks load device files and downloaded copies only

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Forget (and a server switch) removes downloaded audio

After Forget the rows that give a downloaded file meaning are gone and the credential that fetched it is destroyed. Orphaned multi-hundred-MB files that no screen can manage are a defect. This task also makes in-flight downloads stop and stay dead.

**Files:**
- Create: `app/src/main/java/dev/androidjtools/offline/download/OfflineDownloadCleanup.kt`
- Modify: `app/src/main/java/dev/androidjtools/offline/download/OfflineDownloadStore.kt` (`clear()`)
- Modify: `app/src/main/java/dev/androidjtools/offline/download/OfflineCache.kt` (`OfflineCacheStorage.deleteAll()`)
- Modify: `app/src/main/java/dev/androidjtools/offline/download/OfflineDownloadEngine.kt` (state disappears means cancelled)
- Modify: `app/src/main/java/dev/androidjtools/offline/download/OfflineDownloadCoordinator.kt` (`DownloadScheduler.cancelAll`)
- Modify: `app/src/main/java/dev/androidjtools/offline/download/WorkManagerOfflineDownloads.kt` (scheduler + worker guard)
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSessionManager.kt` (`onLibraryDiscarded`)
- Modify: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt`
- Modify: `app/src/main/java/dev/androidjtools/ui/sync/SyncScreen.kt` (confirmation)
- Test: `app/src/test/java/dev/androidjtools/offline/download/OfflineDownloadCleanupTest.kt` (create)
- Test: `app/src/test/java/dev/androidjtools/offline/download/OfflineDownloadEngineTest.kt` (append; update its `FakeScheduler`)
- Test: `app/src/test/java/dev/androidjtools/offline/download/OfflinePlaybackIntegrationTest.kt` (update its `RejectingScheduler`)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibDownloadProviderTest.kt` (update its `RecordingScheduler`)
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionManagerTest.kt` (append)
- Test: `app/src/androidTest/java/dev/androidjtools/ui/sync/SyncScreenServerTest.kt` (compile-only; append)

**Interfaces:**
- Consumes: `OfflineDownloadStore`, `OfflineCacheStorage`, `DownloadScheduler`, `WorkManagerDownloadScheduler`.
- Produces:
  - `OfflineDownloadStore.clear(): Boolean`, `OfflineCacheStorage.deleteAll(): Boolean`.
  - `DownloadScheduler.cancelAll()` (abstract; every implementer is updated in this task).
  - `fun clearOfflineDownloads(store, storage, scheduler): Boolean`.
  - `SampleLibSessionManager(..., onLibraryDiscarded: suspend () -> Boolean = { true })`: called by `forget()` (its result counts toward success) and by `pair()` when the new server's identity differs from the cached one (a `false` aborts that pairing before anything is saved).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/offline/download/OfflineDownloadCleanupTest.kt`:

```kotlin
package dev.androidjtools.offline.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDownloadCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Scheduler : DownloadScheduler {
        var allCancelled = 0
        override fun enqueue(asset: OfflineAsset, replace: Boolean) = Unit
        override fun cancel(assetId: String) = Unit
        override fun cancelAll() { allCancelled++ }
    }

    private fun asset(id: String) = OfflineAsset(id, "original://$id", "ab".repeat(32), 3)

    private fun fixture(): Triple<OfflineDownloadStore, OfflineCacheStorage, File> {
        val root = tmp.newFolder()
        return Triple(OfflineDownloadStore(File(root, "state")), OfflineCacheStorage(File(root, "media")), root)
    }

    @Test
    fun clearRemovesEveryRecordFromMemoryObserversAndDisk() {
        val (store, _, root) = fixture()
        store.ensure(asset("a"))
        store.ensure(asset("b"))
        val observed = store.observe("a")

        assertTrue(store.clear())

        assertTrue(store.all().isEmpty())
        assertNull(observed.value)
        assertTrue(store.allRecords.value.isEmpty())
        assertTrue(OfflineDownloadStore(File(root, "state")).all().isEmpty())
    }

    @Test
    fun deleteAllRemovesPartialAndFinalFilesOfEveryAsset() {
        val (_, storage, _) = fixture()
        storage.partialFile("a").writeBytes(byteArrayOf(1, 2))
        storage.finalFile("b").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(storage.deleteAll())

        assertEquals(0L, storage.usedBytes())
    }

    @Test
    fun clearOfflineDownloadsStopsWorkFlagsRunningDownloadsAndWipesEverything() {
        val (store, storage, _) = fixture()
        val scheduler = Scheduler()
        store.ensure(asset("a"))
        store.update("a") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, pinOwners = setOf("track:t1")) }
        storage.partialFile("a").writeBytes(byteArrayOf(1))
        store.ensure(asset("b"))
        store.update("b") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        storage.finalFile("b").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(clearOfflineDownloads(store, storage, scheduler))

        assertEquals(1, scheduler.allCancelled)
        assertTrue(store.all().isEmpty())
        assertEquals(0L, storage.usedBytes())
    }

    @Test
    fun clearOfflineDownloadsReportsFailureWhenSomethingSurvives() {
        val (store, storage, root) = fixture()
        storage.finalFile("stuck").writeBytes(byteArrayOf(1, 2, 3))
        val media = File(root, "media")
        media.setWritable(false) // a read-only directory refuses deletions
        try {
            // Root ignores directory permissions; skip rather than assert something the OS will not enforce.
            org.junit.Assume.assumeTrue(!media.canWrite())
            assertFalse(clearOfflineDownloads(store, storage, Scheduler()))
        } finally {
            media.setWritable(true)
        }
    }
}
```

Update the three existing `DownloadScheduler` fakes (`OfflineDownloadEngineTest.FakeScheduler`, `OfflinePlaybackIntegrationTest.RejectingScheduler`, and `SampleLibDownloadProviderTest.RecordingScheduler`) with `override fun cancelAll() = Unit` (the rejecting one: `error("unexpected cancelAll")`).

Append to `OfflineDownloadEngineTest` (look at how `engine(...)`, `asset(...)`, `tempRoot()` are used in the file's existing tests and reuse them):

```kotlin
    @Test
    fun aDownloadWhoseStateWasClearedStopsAndLeavesNothingBehind() = runTest {
        val root = tempRoot()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        val content = ByteArray(300) { it.toByte() }
        val asset = asset("asset-cleared", content)
        val body = object : java.io.InputStream() {
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (served >= content.size) return -1
                if (served == 0) store.clear() // Forget happens while the first chunk is in flight
                val count = minOf(100, content.size - served, length)
                content.copyInto(buffer, offset, served, served + count)
                served += count
                return count
            }
        }
        val transport = object : RangeTransport {
            override suspend fun open(request: RangeRequest) =
                RangeResponse(200, body, content.size.toLong(), null, null, null)
        }

        val result = engine(store, storage, transport, quotaBytes = 4096).download(asset)

        assertTrue(result is DownloadAttemptResult.Cancelled)
        assertFalse(storage.finalFile(asset.assetId).exists())
        assertTrue(store.all().isEmpty())
    }
```

Append to `SampleLibSessionManagerTest` (the `broken`-style helpers from the existing tests show how to build a second manager; here we use the class-level `store`, `cache`, `transport`, `client`):

```kotlin
    private fun managerWith(hook: suspend () -> Boolean) =
        SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" }, hook)

    @Test
    fun forgetAlsoDiscardsTheDownloadsAndNeedsThemGone() = runBlocking {
        var calls = 0
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(1, calls)
        assertEquals(SyncState.UNCONFIGURED, subject.state.value)
    }

    @Test
    fun aFailedDownloadCleanupKeepsTheConnectionSoForgetCanBeRetried() = runBlocking {
        val subject = managerWith { false }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
        assertTrue(subject.profile.value != null)
    }

    @Test
    fun aThrowingCleanupIsReportedNotPropagated() = runBlocking {
        val subject = managerWith { error("disk gone") }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        subject.pair("https://samplelab.example", "246810")

        subject.forget()

        assertEquals(SyncState.DEGRADED, subject.state.value)
    }

    @Test
    fun pairingADifferentServerDiscardsTheOldServersDownloadsFirst() = runBlocking {
        var calls = 0
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        subject.pair("https://samplelab.example", "246810")

        assertEquals(1, calls)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun pairingTheSameServerKeepsItsDownloads() = runBlocking {
        var calls = 0
        cache.applyPage(listOf(assetChange("a1")), emptyList(), CacheMeta("c0", "gen-1", "server-1"))
        val subject = managerWith { calls++; true }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        subject.pair("https://samplelab.example", "246810")

        assertEquals(0, calls)
    }

    @Test
    fun ifTheOldDownloadsCannotBeRemovedThePairingIsAbortedBeforeAnythingIsSaved() = runBlocking {
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        val subject = managerWith { false }

        subject.pair("https://samplelab.example", "246810")

        assertEquals(StoredSessionResult.Absent, store.load())
        assertEquals(listOf("old"), cache.entities("asset").map { it.entityId })
        assertTrue(subject.error.value.orEmpty().contains("downloads"))
        assertEquals(SyncState.UNCONFIGURED, subject.state.value)
    }
```

Append to `SyncScreenServerTest` (androidTest, compile-only): change `FakeController` to `val savedUrl = MutableStateFlow<String?>(null)`, `override val serverUrl: StateFlow<String?> = savedUrl`, `var forgets = 0`, `override fun forget() { forgets++ }`, then

```kotlin
    @Test
    fun forgetAsksForConfirmationAndMentionsDownloads() {
        val controller = FakeController().also { it.savedUrl.value = "https://samplelab.example" }
        show(controller)

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("forget-button"))
        compose.onNodeWithTag("forget-button").performClick()
        assertEquals(0, controller.forgets)
        compose.onNodeWithTag("forget-dialog-text").assertTextContains("downloaded", substring = true)

        compose.onNodeWithTag("forget-cancel").performClick()
        assertEquals(0, controller.forgets)

        compose.onNodeWithTag("forget-button").performClick()
        compose.onNodeWithTag("forget-confirm").performClick()
        assertEquals(1, controller.forgets)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*OfflineDownloadCleanupTest' --tests '*OfflineDownloadEngineTest' --tests '*SampleLibSessionManagerTest' 2>&1 | tail -12
```
Expected: compilation FAILS (`clear`, `deleteAll`, `cancelAll`, `clearOfflineDownloads`, `onLibraryDiscarded` parameter).

- [ ] **Step 3: Implement the offline side**

`OfflineDownloadStore`, public:

```kotlin
    /** Removes every record (memory, observers and files). False if a state file could not be deleted. */
    fun clear(): Boolean = synchronized(lock) {
        var clean = true
        directory.listFiles().orEmpty().filter(File::isFile).forEach { if (!it.delete()) clean = false }
        records.clear()
        observers.values.forEach { it.value = null }
        mutableRecords.value = emptyMap()
        clean
    }
```

`OfflineCacheStorage`:

```kotlin
    /** Deletes every partial and completed file. False if anything is left. */
    fun deleteAll(): Boolean {
        directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return directory.listFiles().orEmpty().isEmpty()
    }
```

`OfflineDownloadCoordinator.kt`: add `fun cancelAll()` to `DownloadScheduler`. `WorkManagerDownloadScheduler`:

```kotlin
    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }
```

`OfflineDownloadCleanup.kt`:

```kotlin
package dev.androidjtools.offline.download

/**
 * Stops queued and running download work, flags running downloads so their loops end, then removes every record
 * and cached byte. Returns true only when nothing is left; callers must treat false as "not forgotten".
 */
fun clearOfflineDownloads(store: OfflineDownloadStore, storage: OfflineCacheStorage, scheduler: DownloadScheduler): Boolean {
    scheduler.cancelAll()
    store.all().forEach { record -> store.update(record.asset.assetId) { it.copy(cancelRequested = true) } }
    val filesGone = storage.deleteAll()
    val stateGone = store.clear()
    // A worker that was mid-write may have recreated a file between the two deletions: look once more.
    return filesGone && stateGone && storage.usedBytes() == 0L && store.all().isEmpty()
}
```

`OfflineDownloadEngine`: in the download loop, replace

```kotlin
            record = store.get(asset.assetId) ?: return protocolFailure(asset.assetId, "download state disappeared")
            if (record.cancelRequested) return cancelled(asset.assetId)
```
with

```kotlin
            // A cleared store (Forget) means the whole cache is being removed: stop, do not report a failure.
            record = store.get(asset.assetId) ?: return cancelled(asset.assetId)
            if (record.cancelRequested) return cancelled(asset.assetId)
```
and in the copy loop replace `if (store.get(asset.assetId)?.cancelRequested == true) return cancelled(asset.assetId)` with

```kotlin
                        if (store.get(asset.assetId)?.cancelRequested != false) return cancelled(asset.assetId)
```
(`null` and `true` both stop; only an existing, un-flagged record continues).

`OfflineDownloadWorker.doWork`: after parsing the asset, guard against a stale queued job after Forget or a revoked credential:

```kotlin
        val samplelib = SampleLibRuntime.get(applicationContext)
        val runtime = samplelib.offline
        if (samplelib.manager.mediaAuth() == null) {
            // Forgotten or rejected: never recreate state for a server we no longer have. update() only touches
            // an existing record, so after Forget this is a no-op.
            runtime.store.update(asset.assetId) {
                it.copy(
                    phase = OfflineDownloadPhase.FAILED,
                    error = OfflineDownloadError(DownloadFailureKind.HTTP, "The server connection is no longer valid. Pair again.", retryable = true),
                )
            }
            return Result.failure()
        }
```

- [ ] **Step 4: Implement the manager hook, runtime wiring and dialog**

`SampleLibSessionManager` constructor gains the last parameter:

```kotlin
    private val onLibraryDiscarded: suspend () -> Boolean = { true },
```
`forget()`:

```kotlin
    suspend fun forget() = lock.withLock {
        revokeMediaAuth()
        val downloadsCleared = attempt { onLibraryDiscarded() }
        val storeCleared = attempt { store.clear() }
        val cacheReset = attempt {
            cache.reset()
            true
        }
        if (downloadsCleared && storeCleared && cacheReset) {
            ...unchanged
```
`pair()`: inside the `guarded` block replace the cache-reset line with

```kotlin
            val cachedServer = cache.meta().serverId
            if (cachedServer != null && cachedServer != session.identity.serverId) {
                // The old server's files are meaningless (and unmanageable) under a new server.
                if (!attempt { onLibraryDiscarded() }) {
                    mutableState.value = previousState
                    mutableError.value = "Could not remove the previous server's downloads. Try pairing again."
                    return@guarded
                }
                cache.reset()
            }
```

`SampleLibRuntime` constructor call:

```kotlin
    val manager = SampleLibSessionManager(
        SampleLibProfileStore(directory, AesGcmKeystoreCipher()),
        cache,
        onLibraryDiscarded = { withContext(Dispatchers.IO) { clearOfflineDownloads(offline.store, offline.storage, downloadScheduler) } },
    )
```
(`offline` and `downloadScheduler` are lazy properties declared later; the lambda only runs at forget time.) Import `kotlinx.coroutines.withContext` and `clearOfflineDownloads`.

`SyncScreen.kt`, in `ServerSyncContent` add `var confirmForget by remember { mutableStateOf(false) }`, change the Forget button's `onClick` to `{ confirmForget = true }`, and after the `Row` of buttons:

```kotlin
                if (confirmForget) {
                    AlertDialog(
                        onDismissRequest = { confirmForget = false },
                        title = { Text("Forget this server?") },
                        text = {
                            Text(
                                "This removes the server connection, its synced library and any audio downloaded from it from this device.",
                                modifier = Modifier.testTag("forget-dialog-text"),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmForget = false
                                    server.forget()
                                },
                                modifier = Modifier.testTag("forget-confirm"),
                            ) { Text("Forget") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmForget = false }, modifier = Modifier.testTag("forget-cancel")) { Text("Cancel") }
                        },
                    )
                }
```
with `import androidx.compose.material3.AlertDialog`.

- [ ] **Step 5: Run the tests and the gates**

```bash
./gradlew --offline :app:testDebugUnitTest --tests '*OfflineDownloadCleanupTest' --tests '*OfflineDownloadEngineTest' --tests '*OfflinePlaybackIntegrationTest' --tests '*SampleLibSessionManagerTest' --tests '*SampleLibDownloadProviderTest' 2>&1 | tail -12
./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug 2>&1 | tail -6
```
Expected: PASS; BUILD SUCCESSFUL; lint 0 errors. The worker guard, the dialog and WorkManager cancellation are compile-only (no device).

- [ ] **Step 6: Mutation check, then commit**

In `forget()`, drop `downloadsCleared` from the success condition: `aFailedDownloadCleanupKeepsTheConnectionSoForgetCanBeRetried` must go RED. Revert the engine's copy-loop check to `== true`: `aDownloadWhoseStateWasClearedStopsAndLeavesNothingBehind` must go RED. In `clearOfflineDownloads` remove the `usedBytes() == 0L` term: `clearOfflineDownloadsReportsFailureWhenSomethingSurvives` must go RED. Restore.

```bash
git add app
git commit -m "feat(samplelib): forget and server switch remove downloaded audio and stop in-flight work

Worker guard, WorkManager cancellation and the confirmation dialog are compile-only (no device).

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Real-server proof, documentation, and final verification

The Python fake proves the Android side against our reading of the contract. This task makes the live contract test run the real client, transport and engine against the real server, so the reading is checked against reality, and finishes the docs.

**Files:**
- Modify: `scripts/seed_live_samplelib.py`
- Modify: `app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibLiveContractTest.kt`
- Modify: `CHANGELOG.md`
- Modify: `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (lines mentioning "until S2" and "scheduled with S2")
- Modify: `docs/superpowers/plans/2026-10-12-samplelab-s1-connect-and-read.md` (deviations 2 and 4, the "deferred to S2" table row)
- Modify: `docs/protocol-negotiation/sample-lib.md`, `docs/protocol-negotiation/android-client.md` (banner only)
- Modify: `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (status line: S2 plan reference)

**Interfaces:**
- Consumes: everything above; the real server's `asset_files` table (`id, asset_id, file_role, relpath, mime_type, content_hash_sha256, duration_ms, ...`) and `library_rel_to_path` (a relpath must resolve inside the data root).
- Produces: seeded real rows `file-original` (role `original`), `file-preview` (role `preview`) and a hidden `file-stem` (role `stem`, must never be exposed); a live test `serverAudioIsAdvertisedProjectedAndDownloadsThroughTheRealMediaRoute`.

- [ ] **Step 1: Seed real audio and descriptors**

`scripts/seed_live_samplelib.py`: add imports `hashlib`, `math`, `struct`, `wave` and these helpers above `main`:

```python
def write_wav(path: Path, seconds: float, hz: float) -> str:
    """Write a small mono 16-bit WAV and return its SHA-256 (the server needs a real hash for a strong ETag)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rate = 8000
    frames = b"".join(
        struct.pack("<h", int(12000 * math.sin(2 * math.pi * hz * i / rate))) for i in range(int(rate * seconds))
    )
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(frames)
    return hashlib.sha256(path.read_bytes()).hexdigest()
```

In `main`, after the `markers` insert and before `conn.commit()`:

```python
        files = [
            ("file-original", "original", "originals/a.wav", 0.25, 220.0),
            ("file-preview", "preview", "previews/a-preview.wav", 0.05, 440.0),
            ("file-stem", "stem", "stems/a-drums.wav", 0.05, 110.0),  # never exposed to mobile clients
        ]
        for file_id, role, relpath, seconds, hz in files:
            digest = write_wav(data_dir / relpath, seconds, hz)
            conn.execute(
                "INSERT INTO asset_files (id, asset_id, file_role, relpath, mime_type, content_hash_sha256, duration_ms) "
                "VALUES (?, 'asset-1', ?, ?, 'audio/wav', ?, ?)",
                (file_id, role, relpath, digest, int(seconds * 1000)),
            )
```

- [ ] **Step 2: Write the failing live test**

Append to `SampleLibLiveContractTest` (add imports as needed: `dev.androidjtools.offline.download.*`, `dev.androidjtools.sync.samplelib.AssetFiles`, `SampleLibRangeTransport`, `serverAudioSupport`, `okhttp3.Request`, `java.io.File`, `java.security.MessageDigest`, `org.junit.Assert.assertArrayEquals`, `assertNotNull`, `assertNull`):

```kotlin
    private class RecordingTransport(private val inner: RangeTransport) : RangeTransport {
        val statuses = mutableListOf<Int>()
        override suspend fun open(request: RangeRequest): RangeResponse = inner.open(request).also { statuses += it.statusCode }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun serverAudioIsAdvertisedProjectedAndDownloadsThroughTheRealMediaRoute() = runBlocking {
        requireLive()
        val dir = Files.createTempDirectory("samplelib-live-audio").toFile()
        val db = JdbcSqlDatabase()
        try {
            val cache = SqlLibraryCache(db)
            val manager = SampleLibSessionManager(SampleLibProfileStore(File(dir, "profile"), FakeCipher()), cache)
            manager.pair(baseUrl, pairingCode)
            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)

            val support = serverAudioSupport(manager.capabilities.value)
            assertTrue("server must advertise asset_file with resource_uri + content_hash", support.advertised)
            assertTrue("server must advertise range", support.range)

            // Only original and preview are exposed; the seeded stem row must not leak.
            val rows = cache.entities("asset_file")
            assertEquals(setOf("file-original", "file-preview"), rows.map { it.entityId }.toSet())
            rows.forEach { assertNotNull("descriptor parses: ${it.entityId}", AssetFiles.parse(it)) }

            val projection = LibraryProjector.project(
                cache.entities("asset"), cache.entities("interval"), rows, audioAdvertised = true,
            )
            val track = projection.tracks.single { it.id == "asset-1" }
            assertTrue(track.playable)
            assertNotNull("streamed tracks are not deck-ready", track.deckBlockedReason)
            val audio = projection.audio.getValue("asset-1")
            assertEquals("file-preview", audio.stream.id)
            assertEquals("file-original", audio.download.id)

            // The media route: 401 without a credential, 404 for the hidden stem and for unknown ids, 416 for an
            // oversize suffix, and never a redirect.
            val authed = newMediaHttpClient(manager)
            val plain = okhttp3.OkHttpClient.Builder().followRedirects(false).build()
            val originalUrl = mediaUrl(baseUrl, "file-original")!!
            plain.newCall(Request.Builder().url(originalUrl).build()).execute().use { assertEquals(401, it.code) }
            authed.newCall(Request.Builder().url(mediaUrl(baseUrl, "file-stem")!!).build()).execute().use { assertEquals(404, it.code) }
            authed.newCall(Request.Builder().url(mediaUrl(baseUrl, "nope")!!).build()).execute().use { assertEquals(404, it.code) }
            authed.newCall(Request.Builder().url(originalUrl).header("Range", "bytes=-99999999").build()).execute()
                .use { assertEquals(416, it.code) }

            // Download the original with the real transport and engine; the hash is re-checked independently here.
            val recording = RecordingTransport(SampleLibRangeTransport(manager, authed))
            val store = OfflineDownloadStore(File(dir, "state"))
            val storage = OfflineCacheStorage(File(dir, "media"))
            val engine = OfflineDownloadEngine(store, storage, OfflineCacheQuotaManager(store, storage, 1L shl 24), recording)
            val d = audio.download
            val asset = OfflineAsset(d.id, "original://${d.id}", d.sha256, d.sizeBytes, advertisedEtag = d.etag)

            assertTrue(engine.download(asset) is DownloadAttemptResult.Success)
            val downloaded = storage.finalFile(d.id).readBytes()
            assertEquals(d.sizeBytes, downloaded.size.toLong())
            assertEquals(d.sha256, sha256(downloaded))
            assertEquals(OfflineDownloadPhase.AVAILABLE, store.get(d.id)?.phase)

            // Resume: a half copy continues with Range + If-Range (the quoted etag round-trips) and verifies.
            store.clear()
            storage.deleteAll()
            val half = downloaded.size / 2
            store.ensure(asset)
            storage.partialFile(d.id).writeBytes(downloaded.copyOfRange(0, half))
            store.update(d.id) { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = half.toLong(), currentEtag = d.etag) }
            recording.statuses.clear()
            assertTrue(engine.download(asset) is DownloadAttemptResult.Success)
            assertEquals(listOf(206), recording.statuses)
            assertArrayEquals(downloaded, storage.finalFile(d.id).readBytes())

            // With the copy verified, the track is offline and deck-ready.
            val offline = LibraryProjector.project(
                cache.entities("asset"), cache.entities("interval"), rows, audioAdvertised = true, downloaded = mapOf(d.id to d.sha256),
            ).tracks.single { it.id == "asset-1" }
            assertTrue(offline.offlineAvailable)
            assertNull(offline.deckBlockedReason)

            // After Forget the credential is gone: the transport refuses before any request.
            manager.forget()
            assertNull(manager.mediaAuth())
            try {
                recording.open(RangeRequest(asset))
                org.junit.Assert.fail("expected IOException after forget")
            } catch (_: java.io.IOException) {
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
```

- [ ] **Step 3: Run the live suite to verify it fails first, then passes**

First confirm the seed change alone is not enough (the test should fail before the Kotlin pieces exist only if earlier tasks are missing; with Tasks 1-9 merged it must pass). Run:

```bash
GRADLE_ARGS=--offline scripts/run_live_samplelib_contract.sh
```
Expected: `live contract: 7 executed, 0 failed` (the 6 existing live tests plus this one). The script exports a clean `git archive HEAD` of `../sample-lab/third_party/sample-lib` (merged HEAD with the backend half), so it exercises exactly what is committed there. If it reports `descriptor parses` or `404/416` mismatches, the Python fake and the real server disagree: **fix the fake and the contract text in Task 1's files to match the real server**, not the Android code, unless the real server contradicts `third_party/sample-lib/docs/contracts/android-sync-v1.md`.

Also run the unskipped-count guard the script prints: `0 executed` is a failure.

- [ ] **Step 4: Documentation**

`CHANGELOG.md`, under `## Unreleased`, add a new section **above** "Release engineering" and adjust two S1 lines:

```markdown
### Sample Lab integration (S2: server audio)

- Tracks on a Sample Lib server that advertises `asset_file` can now be played: they stream through Media3 over an authenticated, redirect-free HTTP client (the preview file when the server has one). The bearer is added only for the paired server's `/v1/sync/media/` route and is never placed on a media item, in work data, or in logs; player error text is scrubbed of server addresses.
- Server tracks can be pinned for offline use from the Offline screen: downloads are resumable (Range with the server's quoted validator), SHA-256 verified before they count as available, stored outside backups, and refused when the device is nearly out of space. Unpinning removes the local copy.
- Live decks accept device files and downloaded copies only; a server track that is only streamable shows "Download this track to load it on a deck."
- Forget server (now with a confirmation) and switching to a different server also remove downloaded audio and stop running downloads.
- Not in this release: matching server tracks to identical device files by content hash, and a cache that stays readable while a new snapshot loads (both follow in separate changes).
```
In "Sample Lab integration (S1...)" replace "Server tracks cannot be played yet (server audio arrives in S2); device tracks stay visible and playable." with "Without server audio (older servers, or before S2), server tracks cannot be played; device tracks stay visible and playable." Replace the known-limitation "Server tracks cannot be played until server audio ships." with "Server tracks can only be played when the server advertises audio (`asset_file`)." Add known limitations:

```markdown
- Right after launch, before the first successful connection of the session, the server's audio support is inferred from the cached library; a server that withdrew audio since the last sync is noticed at the next connection.
- Cancelling an in-flight download leaves its partial file until the track is unpinned again or cache pruning runs.
- Media3 session streaming, `OkHttpDataSource`, download scheduling through WorkManager, deck audio output and the new Forget confirmation have been built and unit-tested where possible but NOT run on a device.
```

`docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md`: status line becomes "S1 implementation plan: `…s1…`. S2 (Android side) plan: `docs/superpowers/plans/2026-10-13-samplelab-s2-server-audio-android.md`. S3 to S5 not yet planned."; line 70 "…until S2." becomes "…until content-hash matching ships (a follow-up to S2)."; line 123 "(S1 clears the cache first; a staged swap is scheduled with S2 - see the plan's deviation 4)" becomes "(S1 clears the cache first; a staged swap is a separate follow-up - see the S1 plan's deviation 4)".

`docs/superpowers/plans/2026-10-12-samplelab-s1-connect-and-read.md`: in deviation 2 replace "hash matching moves to S2 with server audio" with "hash matching moves to a follow-up after S2 (server audio does not depend on it)"; in deviation 4 replace "Scheduled with S2." with "A separate follow-up after S2."; in the status table replace "**deferred to S2** (deviation 2)" with "**deferred after S2** (deviation 2)"; and the comment in `CompositeLibraryProvider` ("No de-duplication until S2 brings content-hash matching.") in `SampleLibProviders.kt` becomes "No de-duplication yet; content-hash matching is a follow-up.".

`docs/protocol-negotiation/sample-lib.md` and `android-client.md`: insert directly under the title one line: `> Historical negotiation record. The shipped contract is `contracts/sync/v1` (asset_file pull family and `GET /v1/sync/media/{asset_file_id}`); resource URIs here such as `asset://…` were superseded by `<role>://<asset_file id>`.` Do not rewrite the record's body or edit the dated parity evidence in `docs/parity/` (the "39 backend/protocol tests" sentences describe past runs).

- [ ] **Step 5: Final verification (this exact list, nothing skipped)**

```bash
cd <worktree>
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/home/user/code/androidjtools/.gradle-local
./gradlew --offline :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug 2>&1 | tail -15
python3 -I -m unittest discover -s scripts -p 'test_*.py' 2>&1 | tail -4
PYTHONDONTWRITEBYTECODE=1 uv run --quiet --with-requirements test-server/requirements-test.txt python -I -m unittest discover -s test-server -p 'test_*.py' 2>&1 | tail -4
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=test-server/samplelib uv run --quiet --with-requirements test-server/requirements-test.txt python -m unittest discover -s test-server/samplelib -p 'test_*.py' 2>&1 | tail -4
python3 e2e/parity/run_backend_contracts.py 2>&1 | tail -6
GRADLE_ARGS=--offline scripts/run_live_samplelib_contract.sh 2>&1 | tail -4
```
Expected: unit tests 0 failures (the S1 baseline was 301 tests / 6 skipped; this plan adds roughly 90, report the real total), lint 0 errors, androidTest sources compile, assembleDebug succeeds, scripts tests OK, both fake suites OK, `run_backend_contracts.py` OK, live contract `N executed, 0 failed` with N = 7. Report each number as measured.

Also run once and paste in the report: `git diff --stat main...HEAD | tail -3` and `git log --oneline main..HEAD`.

- [ ] **Step 6: Commit**

```bash
git add scripts app CHANGELOG.md docs
git commit -m "test(samplelib): prove server audio against the real server; document S2

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Out of scope (separate plans)

1. **Content-hash de-duplication** of server tracks against identical device files (S1 deviation 2). Needs a device-file hashing strategy and a decision on which copy wins.
2. **Staged snapshot swap** (S1 deviation 4): keep the cache readable until a fresh snapshot commits.
3. **S3-S5** from the spec: editor mutations, playlists, analysis ingestion.
4. **Device qualification**: every item marked "compile-only" above (Media3 session and `OkHttpDataSource` streaming, WorkManager download scheduling, Keystore on a cold service start, deck audio output, Compose dialogs, notification and lock-screen controls) needs one pass on a real device before a store release.
5. Registering with Play or F-Droid, and CI secrets: unchanged; owned by the release-signing work.
