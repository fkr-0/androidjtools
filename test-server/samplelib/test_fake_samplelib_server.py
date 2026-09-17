from __future__ import annotations

import hashlib
import http.client
import json
import threading
import unittest
import urllib.error
import urllib.request

from fake_samplelib_server import (
    ACCESS_TOKEN,
    MEDIA_BYTES,
    MEDIA_SHA256,
    PAIRING_CODE,
    SampleLibFakeHttpServer,
)
from qualify_target import probe


def mutation(mutation_id: str) -> dict:
    return {
        "mutation_id": mutation_id,
        "entity_type": "asset",
        "entity_id": "asset-1",
        "base_revision": "rev:asset-1:7",
        "operation": "asset.metadata.patch",
        "payload": {"rating": 4},
        "created_at": "2026-09-15T12:00:00Z",
        "provenance": {"source": "epic-12-fake"},
    }


class AuthenticatedSampleLibFakeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = SampleLibFakeHttpServer(("127.0.0.1", 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.host = host
        self.port = port
        self.base = f"http://{host}:{port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def post(self, path: str, body: dict, *, auth: bool = False) -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def get(self, path: str, *, auth: bool = False, headers: dict | None = None) -> tuple[int, bytes, dict]:
        request_headers = dict(headers or {})
        if auth:
            request_headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
        request = urllib.request.Request(self.base + path, headers=request_headers, method="GET")
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.status, response.read(), dict(response.headers.items())

    def pair(self) -> dict:
        status, body = self.post(
            "/v1/sync/pair",
            {
                "type": "pairing_request",
                "client_id": "android-client",
                "installation_id": "installation-a",
                "pairing_code": PAIRING_CODE,
            },
        )
        self.assertEqual(200, status)
        return body

    def test_pairing_establishes_stable_identity_and_revocable_mobile_auth(self) -> None:
        paired = self.pair()
        self.assertEqual("fake-sample-lib-v1", paired["server_id"])
        self.assertEqual("installation-a", paired["installation_id"])
        self.assertIn("sync.read", paired["scopes"])

        hello_request = {
            "type": "hello_request",
            "protocol_versions": ["1"],
            "client_id": "android-client",
            "installation_id": "installation-a",
            "capabilities": [],
        }
        _, hello = self.post("/v1/sync/hello", hello_request, auth=True)
        self.assertEqual(paired["server_id"], hello["server_id"])
        self.assertEqual("unsupported_until_canonical_playlist_migration", hello["capabilities"]["playlists"])

        self.post("/__samplelib__/revoke", {})
        request = urllib.request.Request(
            self.base + "/v1/sync/hello",
            data=json.dumps(hello_request).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {ACCESS_TOKEN}"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()

    def test_auth_is_required_and_bad_pairing_code_is_explicit(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.post(
                "/v1/sync/pair",
                {"client_id": "android", "installation_id": "install", "pairing_code": "wrong"},
            )
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()

        self.pair()
        request = urllib.request.Request(
            self.base + "/v1/sync/pull",
            data=json.dumps({"type": "pull_request", "protocol_version": "1", "cursor": None, "scopes": ["library"], "limit": 10}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as missing:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(401, missing.exception.code)
        missing.exception.close()

    def test_cursor_expiry_requires_bounded_snapshot_reset_with_canonical_entity(self) -> None:
        self.pair()
        self.post("/__scenario__", {"state": "cursor_expired"})
        incremental = {"type": "pull_request", "protocol_version": "1", "cursor": "opaque:stale", "scopes": ["library"], "limit": 100}
        request = urllib.request.Request(
            self.base + "/v1/sync/pull",
            data=json.dumps(incremental).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {ACCESS_TOKEN}"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as expired:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(409, expired.exception.code)
        detail = json.loads(expired.exception.read().decode())
        self.assertEqual("cursor_expired", detail["error"]["code"])
        expired.exception.close()

        incremental["cursor"] = None
        _, reset = self.post("/v1/sync/pull", incremental, auth=True)
        self.assertEqual("snapshot", reset["mode"])
        # Bootstrap snapshots may contain additional authoritative entity families (for
        # example analysis suggestions). Ordering is not a sync-contract guarantee, so
        # locate the canonical asset by identity instead of treating changes[0] as asset-1.
        asset_change = next(
            change
            for change in reset["changes"]
            if change["entity_type"] == "asset" and change["entity_id"] == "asset-1"
        )
        resource = asset_change["value"]["resources"][0]
        self.assertEqual(MEDIA_SHA256, resource["sha256"])

    def test_incremental_pull_carries_tombstone_and_version_mismatch_is_explicit(self) -> None:
        self.pair()
        self.post("/__scenario__", {"state": "changes_available"})
        _, page = self.post(
            "/v1/sync/pull",
            {"type": "pull_request", "protocol_version": "1", "cursor": "opaque:prior", "scopes": ["library"], "limit": 100},
            auth=True,
        )
        self.assertEqual("incremental", page["mode"])
        self.assertEqual("marker-deleted-1", page["tombstones"][0]["entity_id"])

        request = urllib.request.Request(
            self.base + "/v1/sync/hello",
            data=json.dumps(
                {
                    "type": "hello_request",
                    "protocol_versions": ["2"],
                    "client_id": "android-client",
                    "installation_id": "installation-a",
                    "capabilities": [],
                }
            ).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {ACCESS_TOKEN}"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as incompatible:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(426, incompatible.exception.code)
        body = json.loads(incompatible.exception.read().decode())
        self.assertEqual("hello_incompatible", body["type"])
        incompatible.exception.close()

    def test_disconnect_after_commit_recovers_authoritative_receipt_without_duplicate_effect(self) -> None:
        self.pair()
        self.post("/__samplelib__/drop-after-commit", {})
        body = {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [mutation("ambiguous-1")]}
        connection = http.client.HTTPConnection(self.host, self.port, timeout=2)
        connection.request(
            "POST",
            "/v1/sync/push",
            body=json.dumps(body),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {ACCESS_TOKEN}"},
        )
        with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError)):
            connection.getresponse()
        connection.close()

        status, raw, _ = self.get("/v1/sync/receipts/ambiguous-1", auth=True)
        self.assertEqual(200, status)
        receipt = json.loads(raw.decode())["receipt"]
        self.assertEqual("applied", receipt["outcome"])
        revision_after_commit = receipt["server_change_revision"]

        _, replay = self.post("/v1/sync/push", body, auth=True)
        self.assertEqual(receipt, replay["receipts"][0])
        self.assertEqual(revision_after_commit, replay["server_change_revision"])

    def test_authenticated_range_media_is_resumable_and_integrity_checkable(self) -> None:
        self.pair()
        prefix = MEDIA_BYTES[:73]
        status, suffix, headers = self.get(
            "/v1/resources/audio-fixture",
            auth=True,
            headers={"Range": f"bytes={len(prefix)}-", "If-Range": '"fixture-media-v1"'},
        )
        self.assertEqual(206, status)
        self.assertEqual(f"bytes {len(prefix)}-{len(MEDIA_BYTES) - 1}/{len(MEDIA_BYTES)}", headers["Content-Range"])
        rebuilt = prefix + suffix
        self.assertEqual(MEDIA_BYTES, rebuilt)
        self.assertEqual(MEDIA_SHA256, hashlib.sha256(rebuilt).hexdigest())

        # Validator mismatch makes byte offsets unsafe: restart with a complete 200 body.
        stale_status, complete, stale_headers = self.get(
            "/v1/resources/audio-fixture",
            auth=True,
            headers={"Range": "bytes=73-", "If-Range": '"stale-etag"'},
        )
        self.assertEqual(200, stale_status)
        self.assertNotIn("Content-Range", stale_headers)
        self.assertEqual(MEDIA_BYTES, complete)

    def test_secret_safe_qualification_probe_distinguishes_sync_ready_fake(self) -> None:
        result = probe(self.base, ACCESS_TOKEN)
        self.assertEqual(200, result["healthz"])
        self.assertEqual("hello_accepted", result["sync"]["type"])
        self.assertEqual("fake-sample-lib-v1", result["sync"]["server_id"])
        self.assertNotIn(ACCESS_TOKEN, json.dumps(result))


if __name__ == "__main__":
    unittest.main()
