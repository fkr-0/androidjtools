#!/usr/bin/env python3
"""Seed a disposable Sample Lib database with the sync fixture (asset-1 / interval-1 / marker-a, asset-2,
and the folder "Sets" holding the playlist "Warmup").

Usage: seed_live_samplelib.py SAMPLE_LIB_SRC_ROOT DATA_DIR
"""

from __future__ import annotations

import hashlib
import math
import struct
import sys
import wave
from pathlib import Path


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


def main(argv: list[str]) -> int:
    root, data_dir = Path(argv[1]), Path(argv[2])
    sys.path.insert(0, str(root / "src"))
    from sample_lib.db import connect, initialize_database

    data_dir.mkdir(parents=True, exist_ok=True)
    db = data_dir / "sample-lib.sqlite3"
    initialize_database(db)
    conn = connect(db)
    try:
        conn.execute(
            "INSERT INTO assets (id, content_hash_sha256, storage_relpath, duration_ms, title, artist, rating) "
            "VALUES ('asset-1', 'hash-1', 'originals/a.wav', 10000, 'A', 'Artist', 3)"
        )
        conn.execute(
            "INSERT INTO intervals (id, asset_id, label, start_ms, end_ms) "
            "VALUES ('interval-1', 'asset-1', 'Loop', 1000, 4000)"
        )
        conn.execute(
            "INSERT INTO markers (id, interval_id, position_ms, label, marker_type, color) "
            "VALUES ('marker-a', 'interval-1', 1500, 'A', 'slice', '#112233')"
        )
        conn.execute(
            "INSERT INTO assets (id, content_hash_sha256, storage_relpath, duration_ms, title, artist, rating) "
            "VALUES ('asset-2', 'hash-2', 'originals/b.wav', 8000, 'B', 'Artist', 2)"
        )
        conn.execute("INSERT INTO playlists (id, name, kind) VALUES ('folder-sets', 'Sets', 'folder')")
        conn.execute(
            "INSERT INTO playlists (id, name, kind, parent_id) VALUES ('playlist-warmup', 'Warmup', 'playlist', 'folder-sets')"
        )
        # Position 1 is inserted before position 0 so only the explicit position yields the right order.
        conn.execute(
            "INSERT INTO playlist_items (id, playlist_id, asset_id, position) VALUES ('item-second', 'playlist-warmup', 'asset-1', 1)"
        )
        conn.execute(
            "INSERT INTO playlist_items (id, playlist_id, asset_id, position) VALUES ('item-first', 'playlist-warmup', 'asset-2', 0)"
        )
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
        conn.commit()
    finally:
        conn.close()
    print(f"seeded {db}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
