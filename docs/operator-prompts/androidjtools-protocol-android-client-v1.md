@projmgrauth
Set repo: androidjtools.

# Sync protocol negotiation — Android/offline-first advocate
Canonical OCP task: `<task>`.

This is a read-heavy architecture negotiation lane. Preserve all work; only write `docs/protocol-negotiation/android-client.md` plus your checkpoint. Inspect current androidjtools architecture/contracts/code and read relevant Sample Lib/Sample Lab/Oversampler contracts read-only through projmgrauth.

Argue from the Android client's real constraints: unreliable/mobile connectivity, process death, offline edits for cues/loops/metadata/playlists, deterministic mutation journal, idempotency, opaque pull cursors, pagination, tombstones, asset/media cache and resume, background WorkManager limits, pairing/auth, schema migration, conflict classes, large library scale, WebSocket wakeups vs durable pull, and debuggability. Include how cached advisory intelligence suggestions can be reviewed/accepted offline while Sample Lib remains canonical.

Propose concrete wire DTOs/endpoints/state transitions and invariants, but do not declare victory by preference. Explicitly list demands on Sample Lib and concessions Android can make. Identify failure/ambiguous-delivery cases and test vectors. Compare REST+cursor+journal against credible alternatives and justify the smallest robust protocol.

Deliver a decision-oriented proposal with MUST/SHOULD/MAY requirements and open negotiation points. No production code edits. Continue until repository-grounded and testable, then checkpoint; no legacy prolong.