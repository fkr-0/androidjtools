#!/usr/bin/env python3
"""Deterministic executable test double for the synthesized Sample Lib sync v1."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import socket
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_PATH = ROOT / "contracts" / "sync" / "v1" / "protocol.json"
PROTOCOL = json.loads(PROTOCOL_PATH.read_text(encoding="utf-8"))
SUPPORTED_VERSIONS = tuple(PROTOCOL["protocol_versions"])
SYNC_STATES = tuple(PROTOCOL["sync_states"].keys())
SUPPORTED_MUTATIONS = tuple(PROTOCOL["capabilities"]["mutations"])
OPERATION_CONTRACTS = PROTOCOL["mutation"]["operation_contracts"]


class TransportOffline(ConnectionError):
    pass


@dataclass(frozen=True)
class ProtocolHttpError(Exception):
    status: int
    payload: dict[str, Any]


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def _fingerprint(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


def _error(code: str, message: str) -> dict[str, Any]:
    return {"error": {"code": code, "message": message}}


class FakeSyncServer:
    server_id = "fake-sample-lib-v1"
    authority_generation = "generation:fixture-v1"
    principal_id = "principal:fixture"
    installation_id = "installation:fixture"

    def __init__(self, scenario: str = "compatible") -> None:
        self.server_change_revision = 7
        self.scenario = "compatible"
        self.entities: dict[tuple[str, str], dict[str, Any]] = {
            ("asset", "asset-1"): {
                "revision": "rev:asset-1:7",
                "schema": "sample-lib.asset.sync/v1",
                "value": {"title": "Fixture One", "rating": 3},
            },
            ("analysis_suggestion", "candidate-bpm-1"): self._analysis_candidate(
                "candidate-bpm-1", "bpm", 92.48, 0.94, "essentia-rhythm"
            ),
            ("analysis_suggestion", "candidate-key-1"): self._analysis_candidate(
                "candidate-key-1", "key", "8A", 0.87, "essentia-tonal"
            ),
            ("analysis_suggestion", "candidate-related-1"): self._analysis_candidate(
                "candidate-related-1",
                "related_track",
                {"track_id": "asset-2", "dimensions": ["bpm", "key", "energy"]},
                0.79,
                "sample-intelligence-related",
            ),
        }
        # Minimal revision history lets the fake emit bounded remote changed-field maps for
        # safe-fieldwise conflicts instead of pretending the entire canonical object changed.
        self.entity_history: dict[tuple[str, str], dict[str, dict[str, Any]]] = {
            ("asset", "asset-1"): {
                "rev:asset-1:6": {"title": "Fixture One", "rating": 2},
                "rev:asset-1:7": {"title": "Fixture One", "rating": 3},
            },
        }
        self.tombstones = [
            {
                "entity_type": "marker",
                "entity_id": "marker-deleted-1",
                "revision": "rev:marker-deleted-1:6",
                "deleted_at": "2026-09-15T11:00:00Z",
            }
        ]
        self.receipts: dict[str, tuple[str, dict[str, Any]]] = {}
        self.set_scenario(scenario)

    @staticmethod
    def _analysis_candidate(
        candidate_id: str, kind: str, value: Any, confidence: float, model: str
    ) -> dict[str, Any]:
        return {
            "revision": f"rev:analysis_suggestion:{candidate_id}:1",
            "schema": "androidjtools.analysis-candidate/v1",
            "value": {
                "candidate_id": candidate_id,
                "analysis_run_id": "analysis-run-fixture-1",
                "asset_id": "asset-1",
                "interval_id": None,
                "kind": kind,
                "value": value,
                "confidence": confidence,
                "source": {
                    "service": "sample-lib",
                    "model": model,
                    "version": "fixture-1",
                    "pipeline_version": "analysis-v1",
                },
                "generated_at": "2026-09-15T12:00:00Z",
                "input_identity": {
                    "entity_type": "asset",
                    "entity_id": "asset-1",
                    "revision": "rev:asset-1:7",
                    "content_sha256": "a" * 64,
                },
                "freshness": "fresh",
                "decision": "proposed",
            },
        }

    @property
    def capabilities(self) -> dict[str, Any]:
        return copy.deepcopy(PROTOCOL["capabilities"])

    @property
    def limits(self) -> dict[str, int]:
        return copy.deepcopy(PROTOCOL["limits_defaults"])

    def set_scenario(self, scenario: str) -> None:
        if scenario not in SYNC_STATES:
            raise ValueError(f"unknown sync scenario: {scenario}")
        self.scenario = scenario

    def _guard_transport(self) -> None:
        if self.scenario == "offline":
            raise TransportOffline("deterministic offline scenario")
        if self.scenario == "auth_required":
            raise ProtocolHttpError(401, _error("auth_required", "Pairing or authentication is required."))
        if self.scenario == "unavailable":
            raise ProtocolHttpError(503, _error("unavailable", "The fake Sample Lib service is unavailable."))

    def _incompatible(self, requested_versions: list[str]) -> ProtocolHttpError:
        return ProtocolHttpError(426, {
            "type": "hello_incompatible",
            "error": {"code": "incompatible_protocol", "message": "Client and server have no mutually supported sync protocol version."},
            "requested_versions": requested_versions,
            "supported_versions": list(SUPPORTED_VERSIONS),
        })

    def _require_protocol_v1(self, request: dict[str, Any]) -> None:
        requested = request.get("protocol_version")
        if requested not in SUPPORTED_VERSIONS:
            raise self._incompatible([str(requested)] if requested is not None else [])

    def _next_revision(self, entity_type: str, entity_id: str) -> str:
        self.server_change_revision += 1
        return f"rev:{entity_type}:{entity_id}:{self.server_change_revision}"

    @staticmethod
    def _entity_change(key: tuple[str, str], authoritative: dict[str, Any]) -> dict[str, Any]:
        entity_type, entity_id = key
        return {
            "entity_type": entity_type,
            "entity_id": entity_id,
            "revision": authoritative["revision"],
            "schema": authoritative["schema"],
            "value": copy.deepcopy(authoritative["value"]),
        }

    def _snapshot_changes(self, scopes: list[str]) -> list[dict[str, Any]]:
        requested = set(scopes)

        def included(entity_type: str) -> bool:
            return "library" in requested or entity_type in requested or f"{entity_type}s" in requested

        return [
            self._entity_change(key, authoritative)
            for key, authoritative in sorted(self.entities.items())
            if included(key[0])
        ]

    def _rejected_receipt(self, mutation_id: str, code: str, message: str) -> dict[str, Any]:
        return {
            "mutation_id": mutation_id,
            "outcome": "rejected",
            "server_change_revision": self.server_change_revision,
            "error": {"code": code, "message": message},
        }

    def _mutation_contract_error(self, mutation: dict[str, Any]) -> tuple[str, str] | None:
        operation = mutation.get("operation")
        contract = OPERATION_CONTRACTS.get(operation)
        if contract is None:
            return ("unsupported_operation", "Operation was not negotiated as an offline-safe mutation family.")
        if mutation.get("entity_type") not in contract["entity_types"]:
            return ("operation_entity_mismatch", "Operation is not valid for the supplied entity_type.")
        policy = contract["base_revision_policy"]
        base_revision = mutation.get("base_revision")
        if policy == "required_existing" and base_revision is None:
            return ("base_revision_required", "This operation requires an opaque base_revision for an existing entity.")
        if policy == "must_be_null_create_or_ensure" and base_revision is not None:
            return ("base_revision_must_be_null", "This create-or-ensure operation requires base_revision=null.")
        return None

    def hello(self, request: dict[str, Any]) -> dict[str, Any]:
        self._guard_transport()
        requested = [str(version) for version in request.get("protocol_versions", [])]
        if self.scenario == "incompatible":
            raise self._incompatible(requested)
        common = [version for version in requested if version in SUPPORTED_VERSIONS]
        if not common:
            raise self._incompatible(requested)
        selected = max(common, key=lambda version: tuple(int(part) for part in version.split(".")))
        return {
            "type": "hello_accepted",
            "protocol_version": selected,
            "server_id": self.server_id,
            "authority_generation": self.authority_generation,
            "server_change_revision": self.server_change_revision,
            "capabilities": self.capabilities,
            "limits": self.limits,
        }

    def pull(self, request: dict[str, Any]) -> dict[str, Any]:
        self._guard_transport()
        self._require_protocol_v1(request)
        mode = "snapshot" if request.get("cursor") is None else "incremental"
        if self.scenario == "cursor_expired" and mode == "incremental":
            raise ProtocolHttpError(409, {
                "type": "pull_cursor_expired",
                "protocol_version": "1",
                "authority_generation": self.authority_generation,
                "error": {"code": "cursor_expired", "message": "Cursor predates retained change/tombstone history."},
            })
        changes: list[dict[str, Any]] = []
        tombstones: list[dict[str, Any]] = []
        if mode == "snapshot":
            changes = self._snapshot_changes(list(request.get("scopes", [])))
        elif self.scenario == "changes_available":
            fixture = self.entities[("asset", "asset-1")]
            changes.append(self._entity_change(("asset", "asset-1"), fixture))
            tombstones = copy.deepcopy(self.tombstones)
        return {
            "type": "pull_response",
            "protocol_version": "1",
            "mode": mode,
            "next_cursor": f"opaque:{self.authority_generation}:{self.server_change_revision}",
            "has_more": False,
            "changes": changes,
            "tombstones": tombstones,
            "authority_generation": self.authority_generation,
            "server_change_revision": self.server_change_revision,
        }

    def _conflict_receipt(self, mutation: dict[str, Any], code: str = "stale_base_revision") -> dict[str, Any]:
        key = (mutation["entity_type"], mutation["entity_id"])
        authoritative = self.entities.get(key)
        operation = mutation.get("operation")
        contract = OPERATION_CONTRACTS.get(operation, {})
        declared_safe_fields = set(contract.get("merge_safe_fields", []))
        local_value = copy.deepcopy(mutation["payload"])
        base_value = self.entity_history.get(key, {}).get(mutation.get("base_revision"))
        authoritative_value = copy.deepcopy(authoritative["value"]) if authoritative else None
        remote_changed = None
        if authoritative_value is not None and base_value is not None:
            remote_changed = {
                field: authoritative_value.get(field)
                for field in set(base_value) | set(authoritative_value)
                if base_value.get(field) != authoritative_value.get(field)
            }
        safe_fieldwise = bool(
            authoritative is not None
            and declared_safe_fields
            and isinstance(local_value, dict)
            and remote_changed is not None
            and set(local_value).issubset(declared_safe_fields)
            and set(remote_changed).issubset(declared_safe_fields)
        )
        merge_class = "delete_vs_edit" if authoritative is None else ("safe_fieldwise" if safe_fieldwise else "editor_aggregate")
        conflict = {
            "mutation_id": mutation["mutation_id"],
            "entity_id": mutation["entity_id"],
            "base_revision": mutation.get("base_revision"),
            "authoritative_revision": authoritative["revision"] if authoritative else None,
            "local_value": local_value,
            "remote_value": remote_changed if safe_fieldwise else authoritative_value,
            "merge_class": merge_class,
            "code": code,
        }
        if safe_fieldwise:
            conflict["merge_safe_fields"] = sorted(declared_safe_fields)
        return {
            "mutation_id": mutation["mutation_id"],
            "outcome": "conflict",
            "server_change_revision": self.server_change_revision,
            "conflict": conflict,
        }

    def _forced_receipt(self, mutation: dict[str, Any]) -> dict[str, Any] | None:
        key = (mutation["entity_type"], mutation["entity_id"])
        authoritative = self.entities.get(key)
        entity_revision = authoritative["revision"] if authoritative else "rev:missing:0"
        if self.scenario == "applied":
            revision = self._next_revision(*key)
            self.entities[key] = {"revision": revision, "schema": "sample-lib.asset.sync/v1", "value": copy.deepcopy(mutation["payload"])}
            return {"mutation_id": mutation["mutation_id"], "outcome": "applied", "server_change_revision": self.server_change_revision, "entity_revision": revision, "canonical": copy.deepcopy(mutation["payload"])}
        if self.scenario == "no_op":
            return {"mutation_id": mutation["mutation_id"], "outcome": "no_op", "server_change_revision": self.server_change_revision, "entity_revision": entity_revision}
        if self.scenario == "rejected":
            return {"mutation_id": mutation["mutation_id"], "outcome": "rejected", "server_change_revision": self.server_change_revision, "error": {"code": "fixture_rejected", "message": "Rejected by deterministic fake-server scenario."}}
        if self.scenario == "conflict":
            return self._conflict_receipt(mutation)
        return None

    def _apply_mutation(self, mutation: dict[str, Any]) -> dict[str, Any]:
        mutation_id = mutation["mutation_id"]
        body_fingerprint = _fingerprint(mutation)
        cached = self.receipts.get(mutation_id)
        if cached:
            original_fingerprint, original_receipt = cached
            if original_fingerprint == body_fingerprint:
                return copy.deepcopy(original_receipt)
            return {"mutation_id": mutation_id, "outcome": "rejected", "server_change_revision": self.server_change_revision, "error": {"code": "idempotency_key_reused", "message": "mutation_id was already used with a different mutation body."}}

        contract_error = self._mutation_contract_error(mutation)
        if contract_error is not None:
            receipt = self._rejected_receipt(mutation_id, *contract_error)
            self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(receipt))
            return receipt

        forced = self._forced_receipt(mutation)
        if forced is not None:
            self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(forced))
            return forced

        key = (mutation["entity_type"], mutation["entity_id"])
        authoritative = self.entities.get(key)
        authoritative_revision = authoritative["revision"] if authoritative else None
        operation = mutation["operation"]
        policy = OPERATION_CONTRACTS[operation]["base_revision_policy"]
        if policy == "must_be_null_create_or_ensure":
            if authoritative is not None:
                receipt = {"mutation_id": mutation_id, "outcome": "no_op", "server_change_revision": self.server_change_revision, "entity_revision": authoritative_revision}
                self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(receipt))
                return receipt
        elif mutation.get("base_revision") != authoritative_revision:
            receipt = self._conflict_receipt(mutation)
            self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(receipt))
            return receipt

        payload = copy.deepcopy(mutation["payload"])
        if mutation["operation"] == "asset.metadata.patch" and authoritative is not None:
            next_value = copy.deepcopy(authoritative["value"])
            next_value.update(payload)
        elif mutation["operation"] == "analysis.suggestion.accept" and authoritative is not None:
            next_value = copy.deepcopy(authoritative["value"])
            kind = payload.get("kind")
            proposed = payload.get("value")
            if kind == "bpm" and isinstance(proposed, (int, float)) and not isinstance(proposed, bool):
                next_value["bpm"] = float(proposed)
            elif kind == "key" and isinstance(proposed, str) and proposed.strip():
                next_value["key"] = proposed.strip()
            else:
                receipt = self._rejected_receipt(
                    mutation_id,
                    "unsupported_analysis_candidate",
                    "The fake supports canonical acceptance for BPM and key candidates only.",
                )
                self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(receipt))
                return receipt
        else:
            next_value = payload
        if authoritative is not None and authoritative["value"] == next_value:
            receipt = {"mutation_id": mutation_id, "outcome": "no_op", "server_change_revision": self.server_change_revision, "entity_revision": authoritative_revision}
        else:
            revision = self._next_revision(*key)
            schema = authoritative["schema"] if authoritative else f"sample-lib.{mutation['entity_type']}.sync/v1"
            if authoritative is not None:
                self.entity_history.setdefault(key, {})[authoritative_revision] = copy.deepcopy(authoritative["value"])
            self.entity_history.setdefault(key, {})[revision] = copy.deepcopy(next_value)
            self.entities[key] = {"revision": revision, "schema": schema, "value": next_value}
            receipt = {"mutation_id": mutation_id, "outcome": "applied", "server_change_revision": self.server_change_revision, "entity_revision": revision, "canonical": copy.deepcopy(next_value)}
        self.receipts[mutation_id] = (body_fingerprint, copy.deepcopy(receipt))
        return receipt

    def push(self, request: dict[str, Any]) -> dict[str, Any]:
        self._guard_transport()
        self._require_protocol_v1(request)
        receipts = [self._apply_mutation(mutation) for mutation in request.get("mutations", [])]
        return {"type": "push_response", "protocol_version": "1", "receipts": receipts, "server_change_revision": self.server_change_revision}

    def receipt(self, mutation_id: str) -> dict[str, Any]:
        self._guard_transport()
        cached = self.receipts.get(mutation_id)
        if cached is None:
            raise ProtocolHttpError(404, _error("receipt_not_found", "No receipt exists for this authenticated installation and mutation_id."))
        return {"type": "receipt_lookup_response", "protocol_version": "1", "receipt": copy.deepcopy(cached[1])}

    def exercise_state(self, state: str) -> dict[str, Any]:
        self.set_scenario(state)
        hello_request = {"type": "hello_request", "protocol_versions": ["1"], "client_id": "state-probe", "installation_id": self.installation_id, "capabilities": []}
        pull_request = {"type": "pull_request", "protocol_version": "1", "cursor": "opaque:prior", "scopes": ["library"], "limit": 100}
        mutation = {"mutation_id": f"state-{state}", "entity_type": "asset", "entity_id": "asset-1", "base_revision": "rev:asset-1:7", "operation": "asset.metadata.patch", "payload": {"rating": 4}, "created_at": "2026-09-15T12:00:00Z", "provenance": {"source": "fake-state-probe"}}
        push_request = {"type": "push_request", "protocol_version": "1", "client_id": "state-probe", "mutations": [mutation]}
        try:
            if state in {"compatible", "incompatible", "auth_required", "unavailable", "offline"}:
                body = self.hello(hello_request)
            elif state in {"up_to_date", "changes_available", "cursor_expired"}:
                body = self.pull(pull_request)
            else:
                body = self.push(push_request)
            return {"state": state, "kind": "response", "status": 200, "body": body}
        except ProtocolHttpError as exc:
            return {"state": state, "kind": "http_error", "status": exc.status, "body": exc.payload}
        except TransportOffline as exc:
            return {"state": state, "kind": "offline", "status": None, "body": {"message": str(exc)}}


class FakeSyncHttpServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], engine: FakeSyncServer | None = None) -> None:
        self.engine = engine or FakeSyncServer()
        super().__init__(server_address, FakeSyncHttpHandler)


class FakeSyncHttpHandler(BaseHTTPRequestHandler):
    server: FakeSyncHttpServer

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        return

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("request body must be a JSON object")
        return value

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _drop_connection(self) -> None:
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def do_GET(self) -> None:  # noqa: N802
        try:
            prefix = "/v1/sync/receipts/"
            if not self.path.startswith(prefix):
                self._send_json(404, _error("not_found", "Unknown fake-server endpoint."))
                return
            mutation_id = unquote(self.path[len(prefix):])
            self._send_json(200, self.server.engine.receipt(mutation_id))
        except TransportOffline:
            self._drop_connection()
        except ProtocolHttpError as exc:
            self._send_json(exc.status, exc.payload)

    def do_POST(self) -> None:  # noqa: N802
        try:
            body = self._read_json()
            if self.path == "/__scenario__":
                self.server.engine.set_scenario(str(body.get("state", "")))
                self._send_json(200, {"state": self.server.engine.scenario})
                return
            if self.path == "/v1/sync/hello":
                response = self.server.engine.hello(body)
            elif self.path == "/v1/sync/pull":
                response = self.server.engine.pull(body)
            elif self.path == "/v1/sync/push":
                response = self.server.engine.push(body)
            else:
                self._send_json(404, _error("not_found", "Unknown fake-server endpoint."))
                return
            self._send_json(200, response)
        except TransportOffline:
            self._drop_connection()
        except ProtocolHttpError as exc:
            self._send_json(exc.status, exc.payload)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            self._send_json(400, _error("invalid_request", str(exc)))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--scenario", choices=SYNC_STATES, default="compatible")
    args = parser.parse_args()
    server = FakeSyncHttpServer((args.host, args.port), FakeSyncServer(args.scenario))
    print(f"fake sync server listening on http://{args.host}:{args.port} scenario={args.scenario}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
