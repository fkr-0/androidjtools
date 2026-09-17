from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_DIR = ROOT / "contracts" / "sync" / "v1"
INTELLIGENCE_PROTOCOL = ROOT / "contracts" / "intelligence" / "v1" / "protocol.json"


def mutation(mutation_id: str = "mutation-0001") -> dict:
    return {
        "mutation_id": mutation_id,
        "entity_type": "asset",
        "entity_id": "asset-1",
        "base_revision": "rev:asset-1:7",
        "operation": "asset.metadata.patch",
        "payload": {"rating": 4},
        "created_at": "2026-09-15T12:00:00Z",
        "provenance": {"source": "contract-test"},
    }


class SyncContractSchemaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads((CONTRACT_DIR / "sync.schema.json").read_text(encoding="utf-8"))
        cls.protocol = json.loads((CONTRACT_DIR / "protocol.json").read_text(encoding="utf-8"))
        cls.intelligence = json.loads(INTELLIGENCE_PROTOCOL.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(cls.schema, format_checker=FormatChecker())

    def validate(self, message: dict) -> None:
        self.validator.validate(message)

    def hello_accepted(self) -> dict:
        return {
            "type": "hello_accepted",
            "protocol_version": "1",
            "server_id": "sample-lib",
            "authority_generation": "generation:1",
            "server_change_revision": 7,
            "capabilities": copy.deepcopy(self.protocol["capabilities"]),
            "limits": copy.deepcopy(self.protocol["limits_defaults"]),
        }

    def test_protocol_repaired_in_place_uses_opaque_revisions_and_namespaced_endpoints(self) -> None:
        self.assertEqual(1, self.protocol["version"])
        self.assertEqual(["1"], self.protocol["protocol_versions"])
        self.assertEqual("opaque_string", self.protocol["authority"]["entity_revision_kind"])
        self.assertEqual("diagnostic_monotonic_integer", self.protocol["authority"]["server_change_revision_kind"])
        self.assertFalse(self.protocol["mutation"]["generic_crud_allowed"])
        self.assertTrue(self.protocol["http"]["hello"].startswith("/v1/sync/"))
        self.assertFalse(self.protocol["negotiation"]["silent_downgrade_allowed"])

    def test_hello_pull_push_cursor_expired_and_receipt_examples_validate(self) -> None:
        messages = [
            {"type": "hello_request", "protocol_versions": ["1"], "client_id": "android-client", "installation_id": "device-installation", "capabilities": ["pull.cursor.v1"]},
            self.hello_accepted(),
            {"type": "hello_incompatible", "error": {"code": "incompatible_protocol", "message": "No mutually supported protocol version."}, "requested_versions": ["2"], "supported_versions": ["1"]},
            {"type": "pull_request", "protocol_version": "1", "cursor": None, "scopes": ["library"], "limit": 100},
            {"type": "pull_response", "protocol_version": "1", "mode": "snapshot", "next_cursor": "opaque:cursor:7", "has_more": False, "changes": [{"entity_type": "asset", "entity_id": "asset-1", "revision": "rev:asset-1:7", "schema": "sample-lib.asset.sync/v1", "value": {"rating": 3}}], "tombstones": [{"entity_type": "marker", "entity_id": "marker-1", "revision": "rev:marker-1:6", "deleted_at": "2026-09-15T11:00:00Z"}], "authority_generation": "generation:1", "server_change_revision": 7},
            {"type": "pull_cursor_expired", "protocol_version": "1", "authority_generation": "generation:1", "error": {"code": "cursor_expired", "message": "Cursor expired."}},
            {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [mutation()]},
            {"type": "push_response", "protocol_version": "1", "receipts": [{"mutation_id": "mutation-0001", "outcome": "applied", "server_change_revision": 8, "entity_revision": "rev:asset-1:8", "canonical": {"rating": 4}}], "server_change_revision": 8},
            {"type": "receipt_lookup_response", "protocol_version": "1", "receipt": {"mutation_id": "mutation-0001", "outcome": "no_op", "server_change_revision": 8, "entity_revision": "rev:asset-1:8"}},
        ]
        for message in messages:
            with self.subTest(message_type=message["type"]):
                self.validate(message)

    def test_integer_entity_revisions_are_rejected(self) -> None:
        bad = self.hello_accepted()
        self.validate(bad)
        pull = {"type": "pull_response", "protocol_version": "1", "mode": "incremental", "next_cursor": "opaque", "has_more": False, "changes": [{"entity_type": "asset", "entity_id": "asset-1", "revision": 7, "schema": "sample-lib.asset.sync/v1", "value": {}}], "tombstones": [], "authority_generation": "generation:1", "server_change_revision": 7}
        with self.assertRaises(ValidationError):
            self.validate(pull)

    def test_generic_crud_operation_is_rejected_by_schema(self) -> None:
        request = {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [mutation()]}
        request["mutations"][0]["operation"] = "update"
        with self.assertRaises(ValidationError):
            self.validate(request)

    def test_operation_family_binds_entity_type_and_base_revision_policy(self) -> None:
        null_asset = mutation("null-asset-0001")
        null_asset["base_revision"] = None
        wrong_family = mutation("wrong-family-01")
        wrong_family["operation"] = "interval.editor_state.replace"
        ensure_attach = {
            "mutation_id": "ensure-tag-0001",
            "entity_type": "tag_attachment",
            "entity_id": "attachment:fixture",
            "base_revision": None,
            "operation": "tag.ensure_attach",
            "payload": {"namespace": "crate", "value": "Warmup", "target": {"type": "asset", "id": "asset-1"}},
            "created_at": "2026-09-15T12:00:00Z",
            "provenance": {"source": "contract-test"},
        }
        for invalid in (null_asset, wrong_family):
            with self.subTest(mutation_id=invalid["mutation_id"]):
                with self.assertRaises(ValidationError):
                    self.validate({"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [invalid]})
        self.validate({"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [ensure_attach]})

        contracts = self.protocol["mutation"]["operation_contracts"]
        self.assertEqual(["asset"], contracts["asset.metadata.patch"]["entity_types"])
        self.assertEqual("required_existing", contracts["asset.metadata.patch"]["base_revision_policy"])
        self.assertIn("rating", contracts["asset.metadata.patch"]["merge_safe_fields"])
        self.assertNotIn("merge_safe_fields", contracts["interval.editor_state.replace"])
        self.assertEqual("must_be_null_create_or_ensure", contracts["tag.ensure_attach"]["base_revision_policy"])

    def test_mutation_requires_idempotency_identity_and_base_revision(self) -> None:
        request = {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [mutation()]}
        for field in ("mutation_id", "base_revision"):
            invalid = copy.deepcopy(request)
            del invalid["mutations"][0][field]
            with self.subTest(field=field):
                with self.assertRaises(ValidationError):
                    self.validate(invalid)

    def test_every_receipt_outcome_has_distinct_valid_shape(self) -> None:
        receipts = [
            {"mutation_id": "mutation-0001", "outcome": "applied", "server_change_revision": 8, "entity_revision": "rev:8"},
            {"mutation_id": "mutation-0002", "outcome": "no_op", "server_change_revision": 8, "entity_revision": "rev:8"},
            {"mutation_id": "mutation-0003", "outcome": "rejected", "server_change_revision": 8, "error": {"code": "invalid_mutation", "message": "Rejected."}},
            {"mutation_id": "mutation-0004", "outcome": "conflict", "server_change_revision": 8, "conflict": {"mutation_id": "mutation-0004", "entity_id": "asset-1", "base_revision": "rev:7", "authoritative_revision": "rev:8", "local_value": {"title": "Local"}, "remote_value": {"rating": 5}, "merge_class": "safe_fieldwise", "merge_safe_fields": ["title", "rating"], "code": "stale_base_revision"}},
        ]
        self.assertEqual(set(self.protocol["receipt_outcomes"]), {r["outcome"] for r in receipts})
        self.validate({"type": "push_response", "protocol_version": "1", "receipts": receipts, "server_change_revision": 8})

    def test_capability_negotiation_withholds_playlist_writes(self) -> None:
        caps = self.protocol["capabilities"]
        self.assertEqual("unsupported_until_canonical_playlist_migration", caps["playlists"])
        self.assertNotIn("playlist.reorder", caps["mutations"])
        self.assertIn("interval.editor_state.replace", caps["mutations"])

    def test_intelligence_machine_contract_requires_receipt_before_applied_reply(self) -> None:
        mediated = self.intelligence["mediated_action"]
        self.assertTrue(mediated["request_id_idempotent"])
        self.assertFalse(mediated["conflict_auto_rebase"])
        self.assertTrue(mediated["canonical_commit_required_before_applied_reply"])
        self.assertIn("needs_reconfirmation", mediated["states"])
        self.assertIn("conflict", mediated["terminal_replies"])
        self.assertEqual("sample_lib", self.intelligence["authority"]["canonical_metadata"])


if __name__ == "__main__":
    unittest.main()
