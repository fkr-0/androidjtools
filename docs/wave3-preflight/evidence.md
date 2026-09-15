# Wave Three preflight evidence snapshot

Canonical OCP task: `ANDROIDJTOOLS-WAVE3-PARALLEL-OBSERVER-20260915`

Snapshot time: initial 2026-09-15 14:29Z; refreshed 14:35Z (16:35 Europe/Berlin) after a concurrent Sample Lib proposal/checkpoint became durable.

This file records read-only evidence used by `README.md`. It is intentionally explicit about evidence authority and absence.

## Control-plane evidence

| Item | Identity / state |
| --- | --- |
| workspace | `ws-29fe5ce01a58c90621e8f5ca53e95ef8` |
| observer OCP | `ANDROIDJTOOLS-WAVE3-PARALLEL-OBSERVER-20260915`, `running`, dispatch `a6a59ffe0b1b4a3487a822db1e180961`, ABC target `4FC6995258D3DD0261296C37689095D1`, route `fleet/chatgpt/1@9` |
| observer packet | `ba0c180d-cdce-4ccd-8b0b-64980a372bbc`, active |
| observer lease | `37f38816-e036-46d5-bccb-d20c82780826` |
| observer claim | `a251a165-0f25-4944-b7ac-c9d4be2ee792`, `docs/wave3-preflight` |
| EPIC-10 OCP | `ANDROIDJTOOLS-WAVE0-PROTOCOL-20260915`, `running` |
| EPIC-10 packet | `e4de7c8a-bc75-4236-b18a-b93253698cab`, **`recovery_required`** as of 2026-09-15T14:15:52.242Z |
| EPIC-10 latest checkpoint | `b3249e96-6f21-423f-9ed4-aa8850287dac`; digest `682490b46791941f7366fa6009398d29b677d5f93f6bf23f8f733107385bfb53`; historical lease `2612560a-7a59-4323-bc93-33935842717c`; focused tests 13/13 passed |
| EPIC-10 current claim | none returned by live active-claim listing |
| EPIC-03 OCP | `ANDROIDJTOOLS-UX-PLAYER-QUEUE-20260915`, `queued`; no current workflow packet/claim |
| Android protocol advocate | `ANDROIDJTOOLS-PROTOCOL-ANDROID-CLIENT-20260915`, OCP `queued`; durable checkpoint/artifact exists |
| Sample Lib advocate | `ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260915`, OCP still `terminal / blocked_retriable`; `docs/protocol-negotiation/sample-lib.md` now exists, sha256 `3dbfb05a43eb263651fbc06aaec1a743c02c09e3433fc6b42e2555b21b99279b`; checkpoint sha256 `542d4ece7ed0c59a8401a24b52db0afa9a04ec80b53e7d98e06f49fe693dfb62` says `status: complete`; its earlier write claim is no longer in the active-claim listing |
| Sample Lab/Overtone advocate | `ANDROIDJTOOLS-PROTOCOL-SAMPLELAB-OVERTONE-20260915`, terminal complete |
| synthesis | `ANDROIDJTOOLS-PROTOCOL-SYNTHESIS-20260915`, queued; all three proposal files now exist; `decision.md` remains absent at refresh |
| Wave Three implementation packets | none for EPIC-09/11/12/13 |

The OCP workflow discrepancy projection reports observer packet `ba0c180d-cdce-4ccd-8b0b-64980a372bbc` and EPIC-10 packet `e4de7c8a-bc75-4236-b18a-b93253698cab` as runtime packets without canonical bindings. The orchestrator note explicitly directs the observer not to create duplicate bindings/tasks/packets.

## Repository evidence and hashes

| Path | SHA-256 at inspection | Relevant fact |
| --- | --- | --- |
| `docs/agent-workflows.yml` | `e88c4849ae1386b74dc0ec4b73796951076bfabe44a314d129a6e363d878b232` | Wave Three contains EPIC-09/11/12/13 and has common EPIC-10 dependency; independent review + transactional mutation policy. |
| `docs/epics.yml` | `379a6d85cf1222ef29e59f4d7acbbc1b57067bea685cee1586b2ce6c7db98b70` | Exact per-epic dependencies, priorities, scopes and acceptance. |
| `docs/user-stories.yml` | `31ae93e55d1a3f6bcf3f852ca91260e6a658f13e39b2c56a797e6a516945bf0a` | US-090..094, US-110..114, US-120..123, US-130..132 executable-story expectations. |
| `docs/architecture.yml` | `9cf62e22909c497a992ff21db4bb87f221f2600237c79290ca4328ea481d86e2` | Room/WorkManager/Media3/OkHttp design; provider target responsibilities; DJXML exchange-format-not-authority rule. |
| `app/src/main/java/dev/androidjtools/core/provider/Providers.kt` | `e2aaf411b5c0542c7953b4a9212377e3dbeb3729e8aa3d0899313b01532468b2` | Current provider interfaces are projection-heavy and lack multiple Wave Three commands. |
| `app/src/main/java/dev/androidjtools/core/model/Domain.kt` | `ef506516e584c567efa5ef3a27821041d332bd53f7dd83edb5b3f1cdb68ba8de` | Current Android mutation/receipt revision fields are `Long`-shaped; sync states include auth/incompatible/migration/conflict. |
| `app/src/main/java/dev/androidjtools/fixture/FixtureProviders.kt` | `9f40e48c481c09e3f95b1926ccdf18f6bf7bf062c4687e8c1f5ffde9a196ad21` | Static download/journal/sync examples only; not durable implementation evidence. |
| `app/src/debug/java/dev/androidjtools/debug/DebugHarnessProviders.kt` | `392298576751d3f8a91b402c46954af58def8f8ebba40782d9c4c1d61576a4f5` | Deterministic failure/state injection exists; does not prove backend/process durability. |
| `contracts/sync/v1/sync-contract.yml` | `1b167ed423a44d9b08dc28e54657a0e55096869c0121c9f0be56f1171a3e6b65` | Draft authority/idempotency/receipt/cursor/conflict/security invariants. |
| `contracts/sync/v1/protocol.json` | `aafb7c50e9649255369cb0d8b3708d79c05606ebb742f806a8b2efe765e3e866` | Version 1, four receipts, 11 fake states, replay/collision semantics. |
| `contracts/sync/v1/sync.schema.json` | `6b80dd41e1b29667762b539ba51d338d1b3ba218899497ba888838948e8d3e8c` | Current entity/base revision type is integer; generic CRUD mutation envelope. |
| `docs/protocol-negotiation/android-client.md` | `561f06371ef489490b7e92a147e3a4ee76ae3020704664f668eaa06e18fac285` | Proposal requests opaque revisions, per-family write capabilities, atomic page+cursor, reset semantics, journal migration, pairing/auth and media integrity/resume. |
| `docs/protocol-negotiation/samplelab-overtone.md` | `14571a6e9a08ccf79d023efa578a05b3f5bd81ede74635e1f7001af7eb5d0474` | Authority split, provider-neutral resources/candidates, Sample Lib receipt as canonical commit boundary. |
| `docs/protocol-negotiation/sample-lib.md` | `3dbfb05a43eb263651fbc06aaec1a743c02c09e3433fc6b42e2555b21b99279b` | Direct Sample Lib inspection: opaque revisions, narrow authenticated sync facade, change/tombstone/receipt ledger, path-free resources, and canonical playlist migration before playlist writes. |
| `.ws-bridge/agent-checkpoints/ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260915.json` | `542d4ece7ed0c59a8401a24b52db0afa9a04ec80b53e7d98e06f49fe693dfb62` | Tool-managed checkpoint reports the authority negotiation `complete`, creating a lifecycle/artifact mismatch with the still-`blocked_retriable` OCP task. |

## EPIC-10 checkpoint evidence versus lifecycle state

Latest checkpoint payload states `implementation_complete_verification_green` with dirty paths confined to `contracts/sync/v1/**` and `test-server/**`. Verification command:

    PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS='error::ResourceWarning' python3 -m unittest discover -s test-server -p 'test_*.py' -v

reported 13/13 passed at the checkpoint.

The current evidence projection marks that checkpoint as `historical_checkpoint` / provisional and the workflow packet `recovery_required` status as the current authoritative status. Therefore this preflight does **not** mark EPIC-10 complete or ready.

## Working-tree preservation evidence

Read-only `git status --short` at inspection showed a shared dirty checkout with existing changes/untracked trees including `.gitignore`, `bridge.yml`, `.ws-bridge/`, `app/`, build/Gradle files, `contracts/`, `docs/`, and `test-server/`. Before this observer transaction, `docs/wave3-preflight/` contained no files. This observer does not reset, clean, stash, stage, commit, or modify any path outside its claim.

## Static checks required for this preflight

After transaction commit, run only documentation/static checks:

    git diff --check -- docs/wave3-preflight
    test -s docs/wave3-preflight/README.md
    test -s docs/wave3-preflight/evidence.md
    grep -q 'EPIC-11-MUTATION-JOURNAL-CONFLICTS' docs/wave3-preflight/README.md
    grep -q 'EPIC-12-SAMPLELIB-INTEGRATION' docs/wave3-preflight/README.md
    grep -q 'EPIC-09-OFFLINE-DOWNLOADS' docs/wave3-preflight/README.md
    grep -q 'EPIC-13-DJXML-INTEROP' docs/wave3-preflight/README.md

No Android build, device test, hosted CI, or real Sample Lib run is claimed by this observer lane.

## Refresh note: protocol proposal convergence

During verification, the Sample Lib authority proposal and checkpoint landed concurrently. This changes the preflight from “two proposals plus one missing” to “all three proposals durable, synthesis still queued.” It does **not** clear EPIC-10 or any Wave Three gate. The Sample Lib proposal independently confirms several Android-proposal concerns—especially opaque entity revisions, operation-family capabilities, tombstones/cursor expiry, mobile auth, and resource integrity—and adds a concrete acceptance risk: current Sample Lib has no canonical ordered playlist model; DJXML crate paths currently map to tags and cannot serve as stable playlist authority.
