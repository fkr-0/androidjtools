@projmgrauth
Set repo: androidjtools.

# Sync protocol negotiation — Sample Lib authority advocate
Canonical OCP task: `<task>`.

Only write `docs/protocol-negotiation/sample-lib.md` plus checkpoint. Inspect androidjtools and the actual `/home/user/code/sample-lab/third_party/sample-lib` implementation/contracts/tests read-only; also inspect Sample Lab integration boundaries where needed. Preserve all dirty work.

Negotiate from Sample Lib's authority/integrity perspective: stable asset/interval/marker/tag/playlist identity, revision-checked mutations, transactions, idempotency receipts, tombstones, bounded retention, pagination/cursors, authorization, media/resource URLs, DJXML interoperability, provenance, conflict semantics, schema evolution and compatibility. Determine which existing API semantics should be reused versus which narrowly versioned sync endpoints/events are justified.

Treat analysis services as producers of advisory suggestions, not alternate metadata authorities. Specify how Sample Intelligence/Demucs/ASR outputs are versioned/cached/exposed and how an Android acceptance mutation references suggestion provenance without coupling Sample Lib to one model stack.

Produce concrete endpoint/event/DTO proposals, constraints Sample Lib must enforce, requests it can reasonably accept from Android, and migration/testing requirements. Falsify optimistic assumptions against current code. No production edits; checkpoint when defensible; no legacy prolong.