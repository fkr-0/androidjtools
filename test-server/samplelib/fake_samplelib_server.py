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
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

PARENT = Path(__file__).resolve().parents[1]
if str(PARENT) not in sys.path:
    sys.path.insert(0, str(PARENT))

from fake_sync_server import FakeSyncServer, ProtocolHttpError, TransportOffline, _error  # noqa: E402

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
            if self.path == "/v1/sync/hello":
                self._auth("sync.read")
                result = self.server.engine.sync.hello(body)
            elif self.path == "/v1/sync/pull":
                self._auth("sync.read")
                result = self.server.engine.sync.pull(body)
            elif self.path == "/v1/sync/push":
                self._auth("sync.write")
                result = self.server.engine.sync.push(body)
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
