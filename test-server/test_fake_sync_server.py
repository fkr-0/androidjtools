from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from jsonschema import Draft202012Validator

from fake_sync_server import PROTOCOL, SYNC_STATES, FakeSyncHttpServer, FakeSyncServer, ProtocolHttpError

ROOT = Path(__file__).resolve().parents[1]
WIRE_SCHEMA = json.loads((ROOT / "contracts" / "sync" / "v1" / "sync.schema.json").read_text(encoding="utf-8"))


def mutation(
    mutation_id: str = "mutation-0001",
    *,
    base_revision: str | None = "rev:asset-1:7",
    payload: dict | None = None,
    operation: str = "asset.metadata.patch",
    entity_type: str = "asset",
    entity_id: str = "asset-1",
) -> dict:
    return {"mutation_id": mutation_id, "entity_type": entity_type, "entity_id": entity_id, "base_revision": base_revision, "operation": operation, "payload": payload or {"rating": 4}, "created_at": "2026-09-15T12:00:00Z", "provenance": {"source": "fake-server-test"}}


def push_request(item: dict) -> dict:
    return {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [item]}


class FakeSyncServerTests(unittest.TestCase):
    def test_hello_advertises_generation_structured_capabilities_and_limits(self) -> None:
        body = FakeSyncServer().hello({"type": "hello_request", "protocol_versions": ["1"], "client_id": "android", "installation_id": "device", "capabilities": []})
        self.assertEqual("generation:fixture-v1", body["authority_generation"])
        self.assertEqual("unsupported_until_canonical_playlist_migration", body["capabilities"]["playlists"])
        self.assertIn("interval.editor_state.replace", body["capabilities"]["mutations"])
        self.assertGreater(body["limits"]["tombstone_retention_seconds"], 0)

    def test_idempotent_replay_returns_original_receipt_without_reapplying(self) -> None:
        server = FakeSyncServer()
        request = push_request(mutation())
        first = server.push(request)
        change_after_first = server.server_change_revision
        second = server.push(request)
        self.assertEqual("applied", first["receipts"][0]["outcome"])
        self.assertEqual(first, second)
        self.assertEqual(change_after_first, server.server_change_revision)

    def test_reusing_mutation_id_with_different_body_is_rejected(self) -> None:
        server = FakeSyncServer()
        server.push(push_request(mutation("stable-id")))
        changed = mutation("stable-id", payload={"rating": 5})
        receipt = server.push(push_request(changed))["receipts"][0]
        self.assertEqual("rejected", receipt["outcome"])
        self.assertEqual("idempotency_key_reused", receipt["error"]["code"])

    def test_stale_opaque_base_revision_produces_explicit_conflict(self) -> None:
        server = FakeSyncServer()
        receipt = server.push(push_request(mutation("stale-0001", base_revision="rev:asset-1:6")))["receipts"][0]
        self.assertEqual("conflict", receipt["outcome"])
        self.assertEqual("rev:asset-1:6", receipt["conflict"]["base_revision"])
        self.assertEqual("rev:asset-1:7", receipt["conflict"]["authoritative_revision"])
        self.assertEqual({"title": "Fixture One", "rating": 3}, receipt["conflict"]["remote_value"])

    def test_equal_authoritative_patch_produces_no_op(self) -> None:
        server = FakeSyncServer()
        receipt = server.push(push_request(mutation("same-value", payload={"rating": 3})))["receipts"][0]
        self.assertEqual("no_op", receipt["outcome"])
        self.assertEqual(7, server.server_change_revision)

    def test_unknown_generic_operation_is_rejected_even_if_schema_validation_is_bypassed(self) -> None:
        server = FakeSyncServer()
        receipt = server.push(push_request(mutation("generic-update", operation="update")))["receipts"][0]
        self.assertEqual("rejected", receipt["outcome"])
        self.assertEqual("unsupported_operation", receipt["error"]["code"])

    def test_receipt_recovery_returns_durable_original_receipt(self) -> None:
        server = FakeSyncServer()
        original = server.push(push_request(mutation("recover-me")))["receipts"][0]
        lookup = server.receipt("recover-me")
        self.assertEqual("receipt_lookup_response", lookup["type"])
        self.assertEqual(original, lookup["receipt"])

    def test_changes_available_contains_opaque_revision_and_tombstone(self) -> None:
        server = FakeSyncServer("changes_available")
        body = server.pull({"type": "pull_request", "protocol_version": "1", "cursor": "opaque:prior", "scopes": ["library"], "limit": 100})
        self.assertEqual("incremental", body["mode"])
        self.assertTrue(body["changes"][0]["revision"].startswith("rev:"))
        self.assertEqual("marker-deleted-1", body["tombstones"][0]["entity_id"])

    def test_bootstrap_snapshot_contains_every_authoritative_in_scope_entity(self) -> None:
        server = FakeSyncServer()
        expected = set(server.entities)
        body = server.pull({"type": "pull_request", "protocol_version": "1", "cursor": None, "scopes": ["library"], "limit": 100})
        observed = {(change["entity_type"], change["entity_id"]) for change in body["changes"]}
        self.assertEqual("snapshot", body["mode"])
        self.assertFalse(body["has_more"])
        self.assertEqual(expected, observed)

    def test_cursor_expiry_is_explicit_and_requires_bootstrap(self) -> None:
        server = FakeSyncServer("cursor_expired")
        with self.assertRaises(ProtocolHttpError) as caught:
            server.pull({"type": "pull_request", "protocol_version": "1", "cursor": "old", "scopes": ["library"], "limit": 100})
        self.assertEqual(409, caught.exception.status)
        self.assertEqual("pull_cursor_expired", caught.exception.payload["type"])
        self.assertEqual("cursor_expired", caught.exception.payload["error"]["code"])
        reset = server.pull({"type": "pull_request", "protocol_version": "1", "cursor": None, "scopes": ["library"], "limit": 100})
        self.assertEqual("snapshot", reset["mode"])
        self.assertEqual(set(server.entities), {(change["entity_type"], change["entity_id"]) for change in reset["changes"]})

    def test_required_existing_operation_rejects_null_base_without_creating_entity(self) -> None:
        server = FakeSyncServer()
        item = mutation("null-create-0001", base_revision=None, entity_id="asset-new")
        receipt = server.push(push_request(item))["receipts"][0]
        self.assertEqual("rejected", receipt["outcome"])
        self.assertEqual("base_revision_required", receipt["error"]["code"])
        self.assertNotIn(("asset", "asset-new"), server.entities)

    def test_operation_entity_mismatch_is_rejected_without_mutation(self) -> None:
        server = FakeSyncServer()
        before = json.loads(json.dumps(server.entities["asset", "asset-1"]))
        item = mutation("family-mismatch-01", operation="interval.editor_state.replace")
        receipt = server.push(push_request(item))["receipts"][0]
        self.assertEqual("rejected", receipt["outcome"])
        self.assertEqual("operation_entity_mismatch", receipt["error"]["code"])
        self.assertEqual(before, server.entities["asset", "asset-1"])
        self.assertEqual(7, server.server_change_revision)

    def test_unsupported_protocol_version_fails_explicitly(self) -> None:
        server = FakeSyncServer()
        with self.assertRaises(ProtocolHttpError) as caught:
            server.pull({"type": "pull_request", "protocol_version": "2", "cursor": None, "scopes": ["library"], "limit": 100})
        self.assertEqual(426, caught.exception.status)
        self.assertEqual("hello_incompatible", caught.exception.payload["type"])

    def test_fake_server_exercises_every_declared_sync_state(self) -> None:
        self.assertEqual(tuple(PROTOCOL["sync_states"].keys()), SYNC_STATES)
        observations = {state: FakeSyncServer().exercise_state(state) for state in SYNC_STATES}
        self.assertEqual(set(SYNC_STATES), set(observations))
        self.assertEqual("hello_accepted", observations["compatible"]["body"]["type"])
        self.assertEqual([], observations["up_to_date"]["body"]["changes"])
        self.assertEqual(1, len(observations["changes_available"]["body"]["changes"]))
        self.assertEqual(409, observations["cursor_expired"]["status"])
        for outcome in ("applied", "no_op", "rejected", "conflict"):
            with self.subTest(outcome=outcome):
                self.assertEqual(outcome, observations[outcome]["body"]["receipts"][0]["outcome"])
        self.assertEqual(401, observations["auth_required"]["status"])
        self.assertEqual(503, observations["unavailable"]["status"])
        self.assertEqual("offline", observations["offline"]["kind"])
        self.assertEqual(426, observations["incompatible"]["status"])

    def test_protocol_messages_conform_to_wire_schema(self) -> None:
        validator = Draft202012Validator(WIRE_SCHEMA)
        message_states = {"compatible", "up_to_date", "changes_available", "cursor_expired", "applied", "no_op", "rejected", "conflict", "incompatible"}
        for state in message_states:
            with self.subTest(state=state):
                validator.validate(FakeSyncServer().exercise_state(state)["body"])


class FakeSyncHttpServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.httpd = FakeSyncHttpServer(("127.0.0.1", 0), FakeSyncServer())
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.httpd.server_address
        self.base_url = f"http://{host}:{port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def post(self, path: str, body: dict) -> tuple[int, dict]:
        request = urllib.request.Request(self.base_url + path, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def get(self, path: str) -> tuple[int, dict]:
        with urllib.request.urlopen(self.base_url + path, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def test_namespaced_http_hello_push_and_receipt_lookup(self) -> None:
        status, hello = self.post("/v1/sync/hello", {"type": "hello_request", "protocol_versions": ["1"], "client_id": "android-client", "installation_id": "fixture-installation", "capabilities": []})
        self.assertEqual(200, status)
        self.assertEqual("hello_accepted", hello["type"])
        status, pushed = self.post("/v1/sync/push", push_request(mutation("http-receipt")))
        self.assertEqual(200, status)
        self.assertEqual("applied", pushed["receipts"][0]["outcome"])
        status, lookup = self.get("/v1/sync/receipts/http-receipt")
        self.assertEqual(200, status)
        self.assertEqual(pushed["receipts"][0], lookup["receipt"])

    def test_old_unnamespaced_endpoint_is_not_supported(self) -> None:
        request = urllib.request.Request(self.base_url + "/v1/hello", data=b"{}", headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=2)
        caught.exception.close()
        self.assertEqual(404, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
