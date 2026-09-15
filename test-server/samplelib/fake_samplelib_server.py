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
            self._json(404, _error("not_found", "Unknown Sample Lib fake endpoint."))
        except ProtocolHttpError as exc:
            self._json(exc.status, exc.payload)

    def do_HEAD(self) -> None:  # noqa: N802
        try:
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
