#!/usr/bin/env python3
"""Authenticated deterministic Sample Lib facade for EPIC-12 client qualification.

This wraps the accepted protocol fake rather than changing its wire contract. Pairing,
mobile authorization, revocation and authenticated resource delivery are implementation-
owned details intentionally left open by the v1 synthesis decision.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import copy
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

PARENT = Path(__file__).resolve().parents[1]
if str(PARENT) not in sys.path:
    sys.path.insert(0, str(PARENT))

from fake_sync_server import FakeSyncServer, ProtocolHttpError, TransportOffline, _error, _fingerprint  # noqa: E402

_fingerprint_of = _fingerprint

PAIRING_CODE = "482913"
ACCESS_TOKEN = "fixture-mobile-token-never-log"
PRINCIPAL_ID = "principal:android-fixture"
MEDIA_BYTES = (b"sample-lib-authenticated-media-fixture-v1\n" * 64)
MEDIA_SHA256 = hashlib.sha256(MEDIA_BYTES).hexdigest()
MEDIA_ETAG = '"fixture-media-v1"'
PREVIEW_BYTES = MEDIA_BYTES[:512]
ASSET_FILE_SCHEMA = "sample-lib.asset-file/v1"
PLAYLIST_SCHEMA = "sample-lib.playlist/v1"
PLAYLIST_ITEM_SCHEMA = "sample-lib.playlist-item/v1"
INTERVAL_SCHEMA = "sample-lib.interval/v1"
ANALYSIS_SUGGESTION_SCHEMA = "sample-lib.analysis-suggestion/v1"
ACCEPT_OPERATION = "analysis_suggestion.accept"
ANALYSIS_KINDS = ("tempo", "key", "beat_grid", "cue_candidates")


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


class AuthenticatedSampleLibFake:
    def __init__(self) -> None:
        self.sync = FakeSyncServer()
        self.revoked = False
        self.drop_after_commit = False
        asset = self.sync.entities[("asset", "asset-1")]["value"]
        asset["resources"] = [
            {
                "resource_uri": "asset://asset-1/audio",
                "fetch_path": "/v1/resources/audio-fixture",
                "sha256": MEDIA_SHA256,
                "size_bytes": len(MEDIA_BYTES),
                "mime_type": "audio/wav",
                "etag": MEDIA_ETAG,
            }
        ]
        self.media_scope_revoked = False
        self.analysis_scope_revoked = False
        self._init_analysis()
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

        # Canonical playlists as the real server emits them: a folder, a playlist inside it, and items whose
        # explicit `position` is the opposite of their pull (entity id) order.
        for playlist_id, name, kind, parent in (
            ("folder-sets", "Sets", "folder", None),
            ("playlist-warmup", "Warmup", "playlist", "folder-sets"),
        ):
            self.sync.entities[("playlist", playlist_id)] = {
                "revision": "1",
                "schema": PLAYLIST_SCHEMA,
                "value": {"id": playlist_id, "name": name, "kind": kind, "parent_id": parent},
            }
        for item_id, position in (("item-first", 1), ("item-second", 0)):
            self.sync.entities[("playlist_item", item_id)] = {
                "revision": f"rev:playlist_item:{item_id}:1",
                "schema": PLAYLIST_ITEM_SCHEMA,
                "value": {"id": item_id, "playlist_id": "playlist-warmup", "asset_id": "asset-1", "position": position},
            }


    # ------------------------------------------------------------------ S5 analysis suggestions
    #
    # Mirrors docs/superpowers/plans/2026-10-23-samplelab-s5-analysis-suggestions.md. The fake never computes
    # anything: suggestions are fixtures, the "worker" is a deterministic switch (/__samplelib__/analysis/complete),
    # and hello/pull never start a job.

    def _init_analysis(self) -> None:
        # The synthesized candidate fixtures of the base fake are not what any server sends: drop them.
        for key in [k for k in self.sync.entities if k[0] == "analysis_suggestion"]:
            del self.sync.entities[key]
        self.sync.entities[("asset", "asset-1")]["value"]["duration_ms"] = 180000
        self.sync.entities[("interval", "interval-full-1")] = {
            "revision": "rev:interval:interval-full-1:1",
            "schema": INTERVAL_SCHEMA,
            "value": {
                "interval": {
                    "id": "interval-full-1",
                    "asset_id": "asset-1",
                    "label": "Full track",
                    "start_ms": 0,
                    "end_ms": 180000,
                    "bpm": 88.0,
                    "key_label": "7B",
                    "loopable": 0,
                },
                "markers": [],
            },
        }
        # A second track nothing has been analysed for yet: the target of an explicit request.
        self.sync.entities[("asset", "asset-2")] = {
            "revision": "rev:asset-2:1",
            "schema": "sample-lib.asset.sync/v1",
            "value": {"title": "Fixture Two", "rating": 2, "duration_ms": 120000},
        }
        self.analysis_enabled = True
        self.analysis_request_mode = "enqueue_v1"
        self.analysis_jobs: list[dict[str, Any]] = []
        self.provider_calls = 0  # incremented only by the explicit request route and the worker switch
        self.known_suggestion_ids: set[str] = set()
        self.removed_suggestions: set[str] = set()
        self.change_log: list[dict[str, Any]] = []  # entities/tombstones created after the fixtures, by revision
        self.bpm_hypotheses: list[dict[str, Any]] = []
        self._run_counter = 0
        self._seed_suggestions("run-1", "asset-1", ("tempo", "key", "beat_grid", "cue_candidates"))

    def _suggestion_value(self, run_id: str, kind: str, asset_id: str, interval_id: str | None = None) -> dict[str, Any]:
        scope_id = interval_id or asset_id
        data: dict[str, Any]
        confidence: float | None = 0.82
        if kind == "tempo":
            data = {"bpm": 92.48, "alternatives": [46.24, 184.96]}
        elif kind == "key":
            data = {"key_label": "A minor", "root": "A", "mode": "minor"}
            confidence = None  # a run that recorded no confidence: the app must show the suggestion all the same
        elif kind == "beat_grid":
            data = {"bpm": 92.48, "first_beat_ms": 250, "beat_times_ms": [250, 900, 1549, 2198]}
        elif kind == "cue_candidates":
            data = {"points_ms": [8000, 16000, 32000]}
        else:
            raise ValueError(kind)
        return {
            "id": f"{run_id}:{kind}:{scope_id}",
            "kind": kind,
            "asset_id": asset_id,
            "interval_id": interval_id,
            "analysis_run_id": run_id,
            "tool": "mir.essentia_lowlevel" if kind != "cue_candidates" else "sample-intelligence.mir_chop_workbench",
            "tool_version": "2.1",
            "created_at": "2026-10-01T10:00:00Z",
            "confidence": confidence,
            "data": data,
        }

    def _seed_suggestions(self, run_id: str, asset_id: str, kinds: tuple[str, ...], *, logged: bool = False) -> list[str]:
        ids = []
        for kind in kinds:
            value = self._suggestion_value(run_id, kind, asset_id)
            revision = self._suggestion_revision(value["id"], logged)
            self.sync.entities[("analysis_suggestion", value["id"])] = {
                "revision": revision,
                "schema": ANALYSIS_SUGGESTION_SCHEMA,
                "value": value,
            }
            self.known_suggestion_ids.add(value["id"])
            ids.append(value["id"])
            if logged:
                self.change_log.append({"kind": "change", "key": ("analysis_suggestion", value["id"]), "rev": self.sync.server_change_revision})
        return ids

    def _suggestion_revision(self, suggestion_id: str, logged: bool) -> str:
        if logged:
            return self.sync._next_revision("analysis_suggestion", suggestion_id)
        return f"rev:analysis_suggestion:{suggestion_id}:1"

    def configure_analysis(self, enabled: bool, request_mode: str) -> None:
        self.analysis_enabled = enabled
        self.analysis_request_mode = request_mode

    def hello(self, request: dict[str, Any]) -> dict[str, Any]:
        result = self.sync.hello(request)
        caps = result["capabilities"]
        caps["pull"] = [family for family in caps["pull"] if family != "analysis_suggestion"]
        caps["mutations"] = [op for op in caps["mutations"] if op != "analysis.suggestion.accept"]
        if self.analysis_enabled:
            caps["analysis"] = list(ANALYSIS_KINDS)
            caps["analysis_request"] = self.analysis_request_mode
            caps["mutations"].append(ACCEPT_OPERATION)
        else:
            # An older server: an empty list and no analysis_request key at all.
            caps["analysis"] = []
        return result

    @staticmethod
    def _revision_number(revision: str) -> int:
        tail = revision.rsplit(":", 1)[-1]
        return int(tail) if tail.isdigit() else 0

    def pull(self, request: dict[str, Any]) -> dict[str, Any]:
        scopes = set(request.get("scopes", []))
        if "analysis" in scopes and not self.analysis_enabled:
            raise ProtocolHttpError(400, _error("invalid_request", "Unknown pull scope: analysis."))
        library_scopes = sorted(scopes - {"analysis"}) or ["__none__"]
        result = self.sync.pull({**request, "scopes": library_scopes})
        # The library scope never carries suggestions; only the analysis scope does.
        result["changes"] = [c for c in result["changes"] if c["entity_type"] != "analysis_suggestion"]
        cursor = request.get("cursor")
        since = self._cursor_revision(cursor)
        if since is None:
            if "analysis" in scopes:
                result["changes"] += [
                    self.sync._entity_change(key, entity)
                    for key, entity in sorted(self.sync.entities.items())
                    if key[0] == "analysis_suggestion"
                ]
        else:
            seen = {(c["entity_type"], c["entity_id"]) for c in result["changes"]}
            for entry in self.change_log:
                if entry["rev"] <= since:
                    continue
                key = entry["key"]
                if entry["kind"] == "change":
                    wanted = key[0] == "analysis_suggestion" and "analysis" in scopes
                    wanted = wanted or (key[0] != "analysis_suggestion" and library_scopes != ["__none__"])
                    entity = self.sync.entities.get(key)
                    if wanted and entity is not None and key not in seen:
                        result["changes"].append(self.sync._entity_change(key, entity))
                        seen.add(key)
                elif entry["kind"] == "tombstone" and (key[0] != "analysis_suggestion" or "analysis" in scopes):
                    result["tombstones"].append(
                        {"entity_type": key[0], "entity_id": key[1], "revision": entry["revision"], "deleted_at": "2026-10-02T00:00:00Z"}
                    )
        return result

    @staticmethod
    def _cursor_revision(cursor: Any) -> int | None:
        if not isinstance(cursor, str):
            return None
        tail = cursor.rsplit(":", 1)[-1]
        return int(tail) if tail.isdigit() else None

    # --- explicit request (never implicit)

    def request_analysis(self, body: dict[str, Any]) -> dict[str, Any]:
        asset_id = body.get("asset_id")
        if not isinstance(asset_id, str) or not asset_id:
            raise ProtocolHttpError(400, _error("invalid_request", "asset_id is required."))
        kinds = body.get("kinds")
        if kinds is not None and (not isinstance(kinds, list) or not all(isinstance(k, str) for k in kinds)):
            raise ProtocolHttpError(400, _error("invalid_request", "kinds must be a list of strings."))
        force = body.get("force", False)
        if not isinstance(force, bool):
            raise ProtocolHttpError(400, _error("invalid_request", "force must be a boolean."))
        if ("asset", asset_id) not in self.sync.entities:
            raise ProtocolHttpError(404, {"detail": _error("entity_not_found", "No such asset.")})
        wanted = list(kinds) if kinds is not None else list(ANALYSIS_KINDS)
        if self.analysis_request_mode != "enqueue_v1":
            return {
                "asset_id": asset_id,
                "status": "unavailable",
                "kinds": {kind: "unavailable" for kind in wanted},
                "job_id": None,
                "message": "No analysis worker is configured on this server.",
            }
        in_flight = next((job for job in self.analysis_jobs if job["asset_id"] == asset_id and job["state"] in {"queued", "running"}), None)
        statuses: dict[str, str] = {}
        needed: list[str] = []
        for kind in wanted:
            if kind not in ANALYSIS_KINDS:
                statuses[kind] = "unavailable"
            elif not force and self._has_suggestion(asset_id, kind):
                statuses[kind] = "cached"
            elif in_flight is not None:
                statuses[kind] = in_flight["state"]
            else:
                statuses[kind] = "queued"
                needed.append(kind)
        job_id = in_flight["id"] if in_flight is not None else None
        if needed and in_flight is None:
            self.provider_calls += 1
            job_id = f"job-{len(self.analysis_jobs) + 1}"
            self.analysis_jobs.append({"id": job_id, "asset_id": asset_id, "kinds": needed, "state": "queued", "force": force})
        values = set(statuses.values())
        if values <= {"cached"}:
            overall = "cached"
        elif values <= {"unavailable"}:
            overall = "unavailable"
        elif "running" in values:
            overall = "running"
        else:
            overall = "queued"
        return {
            "asset_id": asset_id,
            "status": overall,
            "kinds": statuses,
            "job_id": job_id if overall in {"queued", "running"} else None,
            "message": "Nothing can produce the requested kinds." if overall == "unavailable" else None,
        }

    def _has_suggestion(self, asset_id: str, kind: str) -> bool:
        return any(
            key[0] == "analysis_suggestion" and entity["value"]["asset_id"] == asset_id and entity["value"]["kind"] == kind
            for key, entity in self.sync.entities.items()
        )

    def complete_analysis(self, body: dict[str, Any]) -> dict[str, Any]:
        """Test switch standing in for a worker: finish every queued job, replacing earlier results of the kinds it ran."""
        completed = []
        for job in self.analysis_jobs:
            if job["state"] not in {"queued", "running"}:
                continue
            self.provider_calls += 1
            self._run_counter += 1
            run_id = f"run-{self._run_counter + 1}"
            for kind in job["kinds"]:
                for key, entity in list(self.sync.entities.items()):
                    if key[0] == "analysis_suggestion" and entity["value"]["asset_id"] == job["asset_id"] and entity["value"]["kind"] == kind:
                        self._remove_suggestion(key)
            self._seed_suggestions(run_id, job["asset_id"], tuple(job["kinds"]), logged=True)
            job["state"] = "completed"
            completed.append(job["id"])
        return {"completed": completed}

    def _remove_suggestion(self, key: tuple[str, str]) -> None:
        del self.sync.entities[key]
        self.removed_suggestions.add(key[1])
        revision = self.sync._next_revision(*key)
        self.change_log.append({"kind": "tombstone", "key": key, "rev": self.sync.server_change_revision, "revision": revision})

    def start_running(self) -> dict[str, Any]:
        for job in self.analysis_jobs:
            if job["state"] == "queued":
                job["state"] = "running"
        return {"running": [job["id"] for job in self.analysis_jobs if job["state"] == "running"]}

    def replace_suggestion(self, body: dict[str, Any]) -> dict[str, Any]:
        """Re-run one kind synchronously: tombstone the old id and publish a new one (a conflict source for accepts)."""
        asset_id, kind = str(body["asset_id"]), str(body["kind"])
        for key, entity in list(self.sync.entities.items()):
            if key[0] == "analysis_suggestion" and entity["value"]["asset_id"] == asset_id and entity["value"]["kind"] == kind:
                self._remove_suggestion(key)
        self._run_counter += 1
        ids = self._seed_suggestions(f"run-{self._run_counter + 1}", asset_id, (kind,), logged=True)
        return {"ids": ids}

    # --- accept

    def push(self, request: dict[str, Any]) -> dict[str, Any]:
        self.sync._guard_transport()
        self.sync._require_protocol_v1(request)
        receipts = [self.apply_mutation(mutation) for mutation in request.get("mutations", [])]
        return {
            "type": "push_response",
            "protocol_version": "1",
            "receipts": receipts,
            "server_change_revision": self.sync.server_change_revision,
        }

    def apply_mutation(self, mutation: dict[str, Any]) -> dict[str, Any]:
        if mutation.get("operation") != ACCEPT_OPERATION:
            return self.sync._apply_mutation(mutation)
        mutation_id = mutation["mutation_id"]
        fingerprint = _fingerprint_of(mutation)
        cached = self.sync.receipts.get(mutation_id)
        if cached:
            if cached[0] == fingerprint:
                return copy.deepcopy(cached[1])
            return self.sync._rejected_receipt(mutation_id, "idempotency_key_reused", "mutation_id was already used with a different mutation body.")
        receipt = self._accept(mutation)
        self.sync.receipts[mutation_id] = (fingerprint, copy.deepcopy(receipt))
        return receipt

    def _reject(self, mutation: dict[str, Any], code: str, message: str) -> dict[str, Any]:
        return self.sync._rejected_receipt(mutation["mutation_id"], code, message)

    def _accept(self, mutation: dict[str, Any]) -> dict[str, Any]:
        if not self.analysis_enabled:
            return self._reject(mutation, "unsupported_operation", "Operation was not negotiated as an offline-safe mutation family.")
        if mutation.get("entity_type") != "analysis_suggestion":
            return self._reject(mutation, "entity_type_mismatch", "Operation is not valid for the supplied entity_type.")
        if mutation.get("base_revision") is None:
            return self._reject(mutation, "base_revision_required", "This operation requires the suggestion's pulled revision.")
        suggestion_id = mutation["entity_id"]
        entity = self.sync.entities.get(("analysis_suggestion", suggestion_id))
        if entity is None:
            if suggestion_id in self.removed_suggestions:
                return self._suggestion_changed(mutation, None)
            return self._reject(mutation, "entity_not_found", "No such analysis suggestion.")
        if mutation["base_revision"] != entity["revision"]:
            return self._suggestion_changed(mutation, entity)
        suggestion = entity["value"]
        payload = mutation.get("payload") or {}
        if suggestion["kind"] == "cue_candidates":
            return self._reject(mutation, "use_editor_state_replace", "Cue candidates are applied with interval.editor_state.replace.")
        target = payload.get("target_interval_id")
        if suggestion["interval_id"] is None:
            if not target:
                return self._reject(mutation, "target_interval_required", "An asset-level suggestion needs target_interval_id.")
            interval = self.sync.entities.get(("interval", str(target)))
            if interval is None:
                return self._reject(mutation, "entity_not_found", "The target interval does not exist.")
            if interval["value"]["interval"]["asset_id"] != suggestion["asset_id"]:
                return self._reject(mutation, "target_interval_mismatch", "The interval does not belong to the suggestion's asset.")
        else:
            if target and target != suggestion["interval_id"]:
                return self._reject(mutation, "target_interval_mismatch", "The suggestion is scoped to a different interval.")
            target = suggestion["interval_id"]
            interval = self.sync.entities.get(("interval", target))
            if interval is None:
                return self._reject(mutation, "entity_not_found", "The suggestion's interval no longer exists.")
        changes: dict[str, Any]
        data = suggestion["data"]
        if "bpm" in payload and suggestion["kind"] != "tempo":
            return self._reject(mutation, "invalid_payload", "bpm is only valid for tempo suggestions.")
        if suggestion["kind"] in {"tempo", "beat_grid"}:
            allowed = [data["bpm"]] + (data.get("alternatives", []) if suggestion["kind"] == "tempo" else [])
            bpm = payload.get("bpm", data["bpm"])
            if isinstance(bpm, bool) or not isinstance(bpm, (int, float)) or not any(abs(bpm - a) < 1e-9 for a in allowed):
                return self._reject(mutation, "invalid_payload", "bpm must equal the suggestion's bpm or one of its alternatives.")
            changes = {"bpm": float(bpm)}
            if suggestion["confidence"] is not None:
                changes["bpm_confidence"] = suggestion["confidence"]
        else:
            changes = {"key_label": data["key_label"], "root": data.get("root"), "mode": data.get("mode")}
        current = interval["value"]["interval"]
        if all(current.get(field) == value for field, value in changes.items()):
            return {
                "mutation_id": mutation["mutation_id"],
                "outcome": "no_op",
                "server_change_revision": self.sync.server_change_revision,
                "entity_revision": entity["revision"],
                "canonical": self._interval_canonical(mutation, interval["value"], interval["revision"]),
            }
        key = ("interval", str(target))
        new_value = copy.deepcopy(interval["value"])
        new_value["interval"].update(changes)
        revision = self.sync._next_revision(*key)
        self.sync.entities[key] = {"revision": revision, "schema": interval["schema"], "value": new_value}
        self.change_log.append({"kind": "change", "key": key, "rev": self.sync.server_change_revision})
        if "bpm" in changes:
            self.bpm_hypotheses.append(
                {"interval_id": target, "bpm": changes["bpm"], "source_kind": "android_accept",
                 "analysis_run_id": suggestion["analysis_run_id"], "requires_human_review": 0}
            )
        return {
            "mutation_id": mutation["mutation_id"],
            "outcome": "applied",
            "server_change_revision": self.sync.server_change_revision,
            # The suggestion's revision; the interval's new one is canonical.revision.
            "entity_revision": entity["revision"],
            "canonical": self._interval_canonical(mutation, new_value, revision),
        }

    @staticmethod
    def _interval_canonical(mutation: dict[str, Any], value: dict[str, Any], revision: str) -> dict[str, Any]:
        return {**copy.deepcopy(value), "revision": revision, "mutation_id": mutation["mutation_id"]}

    def _suggestion_changed(self, mutation: dict[str, Any], current: dict[str, Any] | None) -> dict[str, Any]:
        return {
            "mutation_id": mutation["mutation_id"],
            "outcome": "conflict",
            "server_change_revision": self.sync.server_change_revision,
            "entity_revision": current["revision"] if current else None,
            "canonical": copy.deepcopy(current["value"]) if current else None,
            "conflict": {
                "mutation_id": mutation["mutation_id"],
                "entity_id": mutation["entity_id"],
                "base_revision": mutation.get("base_revision"),
                "authoritative_revision": current["revision"] if current else None,
                "local_value": copy.deepcopy(mutation.get("payload")),
                "remote_value": copy.deepcopy(current["value"]) if current else None,
                "merge_class": "analysis_suggestion",
                "code": "suggestion_changed",
            },
        }

    def pair(self, request: dict[str, Any]) -> dict[str, Any]:
        if request.get("pairing_code") != PAIRING_CODE:
            raise ProtocolHttpError(401, _error("pairing_rejected", "Pairing code was not accepted."))
        installation_id = str(request.get("installation_id") or "")
        client_id = str(request.get("client_id") or "")
        if not installation_id or not client_id:
            raise ProtocolHttpError(400, _error("invalid_pairing_request", "client_id and installation_id are required."))
        self.revoked = False
        return {
            "type": "pairing_accepted",
            "server_id": self.sync.server_id,
            "principal_id": PRINCIPAL_ID,
            "installation_id": installation_id,
            "access_token": ACCESS_TOKEN,
            "scopes": ["sync.read", "sync.write", "media.read", "analysis.read"],
        }

    def authorize(self, authorization: str | None, scope: str) -> None:
        if self.revoked or authorization != f"Bearer {ACCESS_TOKEN}":
            raise ProtocolHttpError(401, _error("auth_required", "Mobile authorization is missing, invalid, or revoked."))
        if scope == "media.read" and self.media_scope_revoked:
            raise ProtocolHttpError(403, _error("scope_forbidden", "Authorization does not grant the required scope."))
        if scope == "analysis.read" and self.analysis_scope_revoked:
            raise ProtocolHttpError(403, _error("scope_forbidden", "Authorization does not grant the required scope."))
        allowed = {"sync.read", "sync.write", "media.read", "analysis.read"}
        if scope not in allowed:
            raise ProtocolHttpError(403, _error("scope_forbidden", "Authorization does not grant the required scope."))


class SampleLibFakeHttpServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], engine: AuthenticatedSampleLibFake | None = None) -> None:
        self.engine = engine or AuthenticatedSampleLibFake()
        super().__init__(address, SampleLibFakeHandler)


class SampleLibFakeHandler(BaseHTTPRequestHandler):
    server: SampleLibFakeHttpServer

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        # Deliberately suppress HTTP access logs so Authorization can never leak.
        return

    def _read_json(self) -> dict[str, Any]:
        size = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(size) if size else b"{}"
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("request body must be an object")
        return value

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _drop(self) -> None:
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def _auth(self, scope: str) -> None:
        self.server.engine.authorize(self.headers.get("Authorization"), scope)

    def do_POST(self) -> None:  # noqa: N802
        try:
            body = self._read_json()
            if self.path == "/v1/sync/pair":
                self._json(200, self.server.engine.pair(body))
                return
            if self.path == "/__samplelib__/revoke":
                self.server.engine.revoked = True
                self._json(200, {"revoked": True})
                return
            if self.path == "/__samplelib__/drop-after-commit":
                self.server.engine.drop_after_commit = True
                self._json(200, {"drop_after_commit": True})
                return
            if self.path == "/__samplelib__/deny-media":
                self.server.engine.media_scope_revoked = True
                self._json(200, {"media_scope_revoked": True})
                return
            if self.path == "/__scenario__":
                self.server.engine.sync.set_scenario(str(body.get("state", "")))
                self._json(200, {"state": self.server.engine.sync.scenario})
                return
            if self.path == "/__samplelib__/deny-analysis":
                self.server.engine.analysis_scope_revoked = True
                self._json(200, {"analysis_scope_revoked": True})
                return
            if self.path == "/__samplelib__/analysis/configure":
                self.server.engine.configure_analysis(bool(body.get("enabled", True)), str(body.get("request", "enqueue_v1")))
                self._json(200, {"enabled": self.server.engine.analysis_enabled, "request": self.server.engine.analysis_request_mode})
                return
            if self.path == "/__samplelib__/analysis/running":
                self._json(200, self.server.engine.start_running())
                return
            if self.path == "/__samplelib__/analysis/complete":
                self._json(200, self.server.engine.complete_analysis(body))
                return
            if self.path == "/__samplelib__/analysis/replace":
                self._json(200, self.server.engine.replace_suggestion(body))
                return
            if self.path == "/v1/sync/hello":
                self._auth("sync.read")
                result = self.server.engine.hello(body)
            elif self.path == "/v1/sync/pull":
                self._auth("sync.read")
                if "analysis" in body.get("scopes", []):
                    self._auth("analysis.read")
                result = self.server.engine.pull(body)
            elif self.path == "/v1/sync/analysis/request":
                self._auth("sync.write")
                result = self.server.engine.request_analysis(body)
            elif self.path == "/v1/sync/push":
                self._auth("sync.write")
                result = self.server.engine.push(body)
                if self.server.engine.drop_after_commit:
                    self.server.engine.drop_after_commit = False
                    self._drop()
                    return
            else:
                self._json(404, _error("not_found", "Unknown Sample Lib fake endpoint."))
                return
            self._json(200, result)
        except TransportOffline:
            self._drop()
        except ProtocolHttpError as exc:
            self._json(exc.status, exc.payload)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            self._json(400, _error("invalid_request", str(exc)))

    def do_GET(self) -> None:  # noqa: N802
        try:
            if self.path == "/healthz":
                self._json(200, {"status": "ok", "service": "samplelib-fake"})
                return
            if self.path.startswith("/v1/sync/receipts/"):
                self._auth("sync.read")
                mutation_id = unquote(self.path.removeprefix("/v1/sync/receipts/"))
                self._json(200, self.server.engine.sync.receipt(mutation_id))
                return
            if self.path == "/v1/resources/audio-fixture":
                self._auth("media.read")
                self._send_media(include_body=True)
                return
            if self.path.startswith("/v1/sync/media/"):
                self._serve_asset_file(include_body=True)
                return
            self._json(404, _error("not_found", "Unknown Sample Lib fake endpoint."))
        except ProtocolHttpError as exc:
            self._json(exc.status, exc.payload)

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

    def _send_media(self, *, include_body: bool) -> None:
        start = 0
        range_header = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        # A stale validator invalidates byte offsets. RFC-style behavior is to ignore
        # Range and return the complete current representation so the client restarts.
        range_allowed = not if_range or if_range == MEDIA_ETAG
        if range_header and range_allowed:
            if not range_header.startswith("bytes=") or not range_header.endswith("-"):
                self.send_error(416)
                return
            start = int(range_header.removeprefix("bytes=").removesuffix("-"))
            if start < 0 or start >= len(MEDIA_BYTES):
                self.send_error(416)
                return
        payload = MEDIA_BYTES[start:]
        status = 206 if start else 200
        self.send_response(status)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", MEDIA_ETAG)
        self.send_header("X-Content-SHA256", MEDIA_SHA256)
        self.send_header("Content-Length", str(len(payload)))
        if start:
            self.send_header("Content-Range", f"bytes {start}-{len(MEDIA_BYTES) - 1}/{len(MEDIA_BYTES)}")
        self.end_headers()
        if include_body:
            self.wfile.write(payload)

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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    args = parser.parse_args()
    server = SampleLibFakeHttpServer((args.host, args.port))
    host, port = server.server_address
    print(f"samplelib fake listening on http://{host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
