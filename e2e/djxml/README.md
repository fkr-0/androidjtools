# EPIC-13 DJXML interoperability qualification

DJXML is an exchange format behind Sample Lib, never Android authority. Android canonical identity is the Sample Lib asset identity; Sample Lib's DJXML v2 adapter exports it as `Grouping="sample-lib:<asset-id>"` and refuses to manufacture assets for unmatched XML tracks.

`qualify_samplelib.py` executes selected **real Sample Lib adapter tests** and inspects the production FastAPI route table. Its output intentionally distinguishes adapter support from Android end-to-end availability. A normal run returns structured JSON even when real Android round-trip is blocked; `--require-real-sync` exits 3 until the production Sample Lib `/v1/sync/hello`, `/v1/sync/pull`, and `/v1/sync/push` facade exists.

Current expected boundary: cue, loop, ranged-region and namespaced-tag semantics are supported by the real DJXML adapter; crate paths round-trip as `crate` tags. Crate compatibility is not ordered playlist authority. US-132 remains blocked until Sample Lib exposes canonical playlist identity plus ordered item identity/revisions, and the Android real-system round trip remains blocked until the EPIC-12 sync facade exists in production Sample Lib.
