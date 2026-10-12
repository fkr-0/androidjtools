#!/usr/bin/env python3
"""Seed a disposable Sample Lib database with the sync fixture (asset-1 / interval-1 / marker-a).

Usage: seed_live_samplelib.py SAMPLE_LIB_SRC_ROOT DATA_DIR
"""

from __future__ import annotations

import sys
from pathlib import Path


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
        conn.commit()
    finally:
        conn.close()
    print(f"seeded {db}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
