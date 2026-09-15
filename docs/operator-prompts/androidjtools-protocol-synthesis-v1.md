@projmgrauth
Set repo: androidjtools.

# Sync protocol negotiation — synthesis and decision lane
Canonical OCP task: `<task>`.

Wait until the three proposal files exist and are substantive: `docs/protocol-negotiation/android-client.md`, `sample-lib.md`, and `samplelab-overtone.md`. Then independently inspect the same current repositories and falsify all three proposals against actual contracts/code. Preserve concurrent work and claim only `contracts/sync/**`, `contracts/intelligence/**`, `docs/protocol-negotiation/decision.md`, protocol tests/fixtures and `test-server/**` if implementation is warranted.

Resolve disagreements into the smallest robust cloudless protocol. Sample Lib remains canonical. Android is offline-first with a durable mutation journal. Delivery must be idempotent and receipts authoritative. Pull cursors are opaque. WebSocket is optional wakeup/realtime hint, never durable state authority. Conflicts are explicit. Intelligence outputs are advisory candidates with capability/provenance/freshness; accepting one is a normal mutation. Overtone/Sample Lab requests are correlated mediated tasks, not direct browser/mobile control of Overtone.

Deliver: versioned endpoint/DTO/state-machine contract, compatibility negotiation, pairing/auth boundary, conflict matrix, mutation/receipt examples, analysis suggestion/job contract, requester-confirmation flow, fake-server scenarios, migration path from existing APIs, and an explicit rejected-alternatives record. Update draft contracts only where evidence supports the decision and add machine-checkable fixtures/tests when practical.

Do not merely average the proposals; state decisions and rationale. Continue through review/test until remaining protocol ambiguity is bounded and named. Durable checkpoint, at most one canonical successor for a concrete implementation tranche, no legacy prolong.