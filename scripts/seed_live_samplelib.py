#!/usr/bin/env python3
"""Seed a disposable Sample Lib database with the sync fixture (asset-1 / interval-1 / marker-a, asset-2,
and the folder "Sets" holding the playlist "Warmup"), plus five analysed tracks for the analysis-suggestion tests.

Each analysed track (asset-analysis, asset-accept-a, asset-accept-b, asset-replace, asset-cues) has a full-length
interval (interval-<name>) and three COMPLETED persisted analysis runs written in the shapes the server reads them
(see ANALYSIS_RUNS): an Essentia run that yields the key only (the later runs win the other kinds), a tempo-candidates
run that yields tempo and beat grid, and a chop-workbench run that yields cue candidates. Tests that mutate
suggestions or intervals use a track of their own so the order of tests does not matter.

Usage: seed_live_samplelib.py SAMPLE_LIB_SRC_ROOT DATA_DIR
"""

from __future__ import annotations

import hashlib
import json
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


ANALYSED_TRACKS = ("analysis", "accept-a", "accept-b", "replace", "cues")

# (suffix, tool_name, tool_version, operation, completed_at, output_json). Timestamps are fixed and in the past so a run
# posted during a test is always the newest. Expected suggestions per track, as the tests assert them:
#   tempo      <name>-tempo:tempo:asset-<name>        bpm 128, alternatives [64, 256], confidence 0.7
#   beat_grid  <name>-tempo:beat_grid:asset-<name>    bpm 128, first_beat_ms 100, 4 beats
#   key        <name>-essentia:key:asset-<name>       "A minor" (A / minor), NO confidence (the run recorded none)
#   cue_candidates <name>-chop:cue_candidates:asset-<name>   [1000, 2500, 4000], no confidence
ANALYSIS_RUNS = (
    (
        "essentia", "essentia", "2.1", "mir.essentia_lowlevel", "2026-10-01 10:00:00",
        {
            "normalized_result": {
                "status": "ok",
                "features": {"bpm": 90, "confidence": 0.8, "key": "A", "scale": "minor", "onsets": [0.5, 1.0, 1.5]},
                "rhythm": {"beats": [0.5, 1.0, 1.5]},
            }
        },
    ),
    (
        "tempo", "tempo-candidates", "1.0", "mir.tempo_candidates", "2026-10-01 10:05:00",
        {"candidates": [{"bpm": 128, "confidence": 0.7, "beat_times_ms": [100, 569, 1038, 1507]}]},
    ),
    (
        "chop", "sample-intelligence", "1.0", "sample-intelligence.mir_chop_workbench", "2026-10-01 10:10:00",
        {"chop_points_ms": [1000, 2500, 4000]},
    ),
)


def seed_analysed_tracks(conn) -> None:
    for name in ANALYSED_TRACKS:
        asset_id, interval_id = f"asset-{name}", f"interval-{name}"
        conn.execute(
            "INSERT INTO assets (id, content_hash_sha256, storage_relpath, duration_ms, title, artist, rating) "
            "VALUES (?, ?, ?, 8000, ?, 'Artist', 1)",
            (asset_id, f"hash-{name}", f"originals/{name}.wav", f"Analysed {name}"),
        )
        conn.execute(
            "INSERT INTO intervals (id, asset_id, label, start_ms, end_ms) VALUES (?, ?, 'Full track', 0, 8000)",
            (interval_id, asset_id),
        )
        for suffix, tool, version, operation, completed_at, output in ANALYSIS_RUNS:
            conn.execute(
                "INSERT INTO analysis_runs (id, asset_id, tool_name, tool_version, operation, output_json, status, completed_at) "
                "VALUES (?, ?, ?, ?, ?, ?, 'completed', ?)",
                (f"{name}-{suffix}", asset_id, tool, version, operation, json.dumps(output), completed_at),
            )


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
        seed_analysed_tracks(conn)
        conn.commit()
    finally:
        conn.close()
    print(f"seeded {db}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
