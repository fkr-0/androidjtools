# Wave Three guarded implementation preflight

Canonical OCP task: `ANDROIDJTOOLS-WAVE3-PARALLEL-OBSERVER-20260915`

Observed control-plane snapshot: initial 2026-09-15 14:29Z, refreshed at 14:35Z (16:35 Europe/Berlin) after the Sample Lib authority proposal landed.

This document is an implementation preflight, not an implementation claim. It separates current facts from blockers, assumptions and proposed work. Nothing here promotes a checkpoint, passing test, file on disk, fixture behavior, or architectural intention into lifecycle completion or real-backend evidence.

## 1. Executive gate decision

**Wave Three is not legally executable yet.** The common hard dependency `EPIC-10-SYNC-CONTRACT` is currently represented by workflow packet `e4de7c8a-bc75-4236-b18a-b93253698cab` in `recovery_required`. Its latest historical checkpoint reports the focused protocol suite green at 13/13, but the current workflow packet state supersedes that checkpoint for readiness. No active EPIC-10 write claim exists at this snapshot.

`EPIC-09-OFFLINE-DOWNLOADS` is additionally blocked by `EPIC-03-PLAYBACK-QUEUE`; canonical OCP task `ANDROIDJTOOLS-UX-PLAYER-QUEUE-20260915` is still `queued`, has no workflow packet in the current packet inventory, and has no active playback claim.

`EPIC-13-DJXML-INTEROP` is additionally and intentionally downstream of `EPIC-12-SAMPLELIB-INTEGRATION`, so it cannot start in parallel with EPIC-12 even after EPIC-10 clears.

No separately authorized EPIC-09/11/12/13 implementation packet, lease, or claim was found. The only Wave Three packet currently active is this observer packet, and its claim is restricted to `docs/wave3-preflight/**`.

## 2. Current prerequisite and authority matrix

| Concern | Canonical OCP task | OCP state | Workflow packet | Packet state | Current claim / lease identity | Consequence |
| --- | --- | --- | --- | --- | --- | --- |
| Wave Three observer | `ANDROIDJTOOLS-WAVE3-PARALLEL-OBSERVER-20260915` | `running` | `ba0c180d-cdce-4ccd-8b0b-64980a372bbc` | `active` | claim `a251a165-0f25-4944-b7ac-c9d4be2ee792`; lease `37f38816-e036-46d5-bccb-d20c82780826`; scope `docs/wave3-preflight` | Docs-only work is authorized. No implementation claim may be minted from this packet. |
| EPIC-10 sync contract | `ANDROIDJTOOLS-WAVE0-PROTOCOL-20260915` | `running` | `e4de7c8a-bc75-4236-b18a-b93253698cab` | **`recovery_required`** | **no current active claim**; historical lease `2612560a-7a59-4323-bc93-33935842717c`; checkpoint `wsbridge:checkpoint:ws-29fe5ce01a58c90621e8f5ca53e95ef8:e4de7c8a-bc75-4236-b18a-b93253698cab:sha256:682490b46791941f7366fa6009398d29b677d5f93f6bf23f8f733107385bfb53` | Common Wave Three hard gate is closed. 13/13 focused tests are useful evidence, not lifecycle completion or independent approval. |
| EPIC-03 playback/queue | `ANDROIDJTOOLS-UX-PLAYER-QUEUE-20260915` | **`queued`** | none in current workflow packet inventory | n/a | no current active playback claim | EPIC-09 hard gate is closed independently of EPIC-10. |
| Android protocol advocate | `ANDROIDJTOOLS-PROTOCOL-ANDROID-CLIENT-20260915` | `queued` | no bound packet in current workflow inventory | n/a | no active document claim; complete artifact/checkpoint exists | Lifecycle and artifact disagree. Proposal is usable as evidence but must not be treated as approved synthesis. |
| Sample Lib protocol advocate | `ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260915` | `terminal / blocked_retriable` | none bound | n/a | proposal `docs/protocol-negotiation/sample-lib.md` and checkpoint are now durable; its earlier direct claim has expired/released from the live claim listing | Artifact status says complete, but canonical OCP lifecycle still says `blocked_retriable`; reconcile the existing task rather than inventing a replacement. |
| Sample Lab / Overtone advocate | `ANDROIDJTOOLS-PROTOCOL-SAMPLELAB-OVERTONE-20260915` | `terminal / complete` | no relevant live implementation packet | n/a | no active claim | Durable proposal exists and is valid input evidence. |
| Protocol synthesis | `ANDROIDJTOOLS-PROTOCOL-SYNTHESIS-20260915` | `queued` | none bound | n/a | no active claim | All three substantive proposal files now exist; the file-level start condition is satisfied, but the existing synthesis task still needs native lifecycle admission/dispatch and no `decision.md` is present yet. |
| EPIC-11 implementation | none admitted | none | none | none | none | Do not implement yet. |
| EPIC-12 implementation | none admitted | none | none | none | none | Do not implement yet. |
| EPIC-09 implementation | none admitted | none | none | none | none | Do not implement yet. |
| EPIC-13 implementation | none admitted | none | none | none | none | Do not implement yet. |

Control-plane correlation caveat: the OCP task and this observer packet are currently reported as unbound by the workflow discrepancy projection. The orchestrator note explicitly says not to mint a duplicate task or packet; the active packet/lease/claim above is therefore the execution authority for this docs-only lane, while the canonical OCP identity remains the durable work identity.

## 3. Required Wave Three table

| Epic | Hard dependencies | Current gate | Earliest legal start | Proposed non-overlapping implementation write scope | Executable acceptance tests |
| --- | --- | --- | --- | --- | --- |
| `EPIC-11-MUTATION-JOURNAL-CONFLICTS` | `EPIC-10-SYNC-CONTRACT` | EPIC-10 packet is `recovery_required`; protocol revision/capability semantics are also unsettled | After EPIC-10 has been recovered, independently reviewed, and reaches an accepted lifecycle state | `app/src/main/java/dev/androidjtools/sync/journal/**`; `app/src/main/java/dev/androidjtools/sync/conflict/**`; packet should explicitly add mirrored unit/instrumentation test paths, but not `remote/samplelib/**` or `offline/download/**` | US-110 durability-before-success; US-111 exact idempotent replay including process death while `APPLYING`; US-112 receipt-gated compaction; US-113 deterministic safe-merge matrix; US-114 side-by-side unsafe conflict UI; migration-preserves-unresolved-intent tests |
| `EPIC-12-SAMPLELIB-INTEGRATION` | `EPIC-10-SYNC-CONTRACT` | EPIC-10 lifecycle gate closed; all three protocol proposals now exist but synthesis is still queued and no accepted decision has repaired the draft contract | After EPIC-10 reaches accepted lifecycle state; implement against the final approved protocol, not the present draft by assumption | `app/src/main/java/dev/androidjtools/remote/samplelib/**`; `test-server/samplelib/**`; packet should explicitly add mirrored remote-client tests while avoiding `sync/journal/**` ownership | US-120 pairing/health E2E; US-121 opaque-cursor delta pull; US-122 push/authoritative-receipt E2E; US-123 disconnect/reconnect with pending edits; auth-required/version-mismatch tests; fake-vs-real Sample Lib qualification |
| `EPIC-09-OFFLINE-DOWNLOADS` | `EPIC-03-PLAYBACK-QUEUE`, `EPIC-10-SYNC-CONTRACT` | EPIC-10 `recovery_required` **and** player/queue OCP still `queued` with no implementation packet/claim | Only after both EPIC-10 and EPIC-03 are accepted lifecycle-complete dependencies | `app/src/main/java/dev/androidjtools/offline/download/**`; packet should explicitly add mirrored tests. Playback packages are read/consume-only; any core-provider contract extension requires a separate narrow claim/coordination | US-090 track/playlist pin; US-091 progress/error state; US-092 WorkManager process-death resume; US-093 pin-safe quota/eviction; US-094 airplane-mode cached playback + prep; hash/validator/range integrity tests |
| `EPIC-13-DJXML-INTEROP` | `EPIC-12-SAMPLELIB-INTEGRATION` plus Wave Three's common EPIC-10 gate transitively | EPIC-12 not admitted or implemented; real Sample Lib round-trip path therefore absent | After EPIC-12 reaches accepted lifecycle state and real Sample Lib sync qualification exists | `app/src/main/java/dev/androidjtools/interop/**`; `e2e/djxml/**`; do not mutate `remote/samplelib/**`, sync journal, or Sample Lib canonical state directly | US-130 Android -> receipt -> Sample Lib -> DJXML export round trip; US-131 DJXML import -> Sample Lib commit -> cursor pull -> Android; US-132 crate/playlist identity/order round trip with deterministic fixtures |

The table deliberately distinguishes **legal dependency readiness** from **implementation risk readiness**. Protocol synthesis is not encoded as a separate epic dependency, but the current Android proposal identifies production-significant disagreements with the current EPIC-10 draft. Those must be resolved before implementation hard-codes draft assumptions.

## 4. Facts about current seams

### 4.1 Architecture intent versus implemented provider surface

The architecture document intends Room persistence, WorkManager for sync retry/download resume/cache prune, Media3 playback, and backend-neutral providers including `WorkManagerDownloadProvider` and `SampleLibSyncProvider`. Those names are architectural targets, not evidence that their concrete implementations exist.

Current Kotlin seams are significantly narrower:

| Current seam | What exists now | Wave Three implication |
| --- | --- | --- |
| `PlaybackProvider` | current track, playing state, position; `play`, `toggle`, `seek` | No queue API or Media3 implementation is present in this seam yet. EPIC-09 must consume the eventual EPIC-03 provider rather than invent a second player. |
| `DownloadProvider` | `state(trackId)` only | EPIC-09 requires enqueue/pin/pause/resume/cancel/cache-policy operations that are not currently exposed. A minimal backend-neutral provider seam change needs separately authorized ownership; it must not be smuggled into `offline/download/**`. |
| `MutationJournalProvider` | read-only `pending` and `recentReceipts` flows | EPIC-11 needs enqueue/durable-commit/replay/receipt/conflict resolution behavior. The implementation can live under `sync/journal/**`, but wiring new operations into the app provider graph may require a separately coordinated core/provider change. |
| `SyncProvider` | sync state + pending mutation count | EPIC-12 needs pairing, health, pull, push, receipts and reconnect behavior. Network DTOs must remain below the provider boundary; UI composables must not receive Sample Lib-specific types. |
| `PreparationProvider` | read-only cue/loop/beatgrid flows | Offline edit producers are not yet represented here. Wave Two or a separately coordinated provider extension must provide mutation-producing operations before Wave Three can prove the full edit path. |
| `PlaylistProvider` | read-only playlist projection | EPIC-13's crate/playlist round-trip depends on stable canonical playlist semantics even though EPIC-08 is not encoded as a hard dependency. Treat this as an integration assumption/risk, not a fabricated dependency. |

The debug harness currently injects deterministic download failure, pending mutations, all receipt outcomes, auth/version/migration states, and backend health. It is useful fixture evidence only; it does not prove Room durability, WorkManager resume, pairing, network behavior, or real Sample Lib interoperability.

### 4.2 Current sync contract facts

The current dirty EPIC-10 implementation establishes useful draft invariants: version 1 negotiation, explicit HTTP 426 incompatibility, opaque pull cursor field, mutations with `mutation_id` and required `base_revision`, exact same-ID/body replay, same-ID/different-body collision rejection, four receipt outcomes, tombstones, bounded pull/push arrays, and deterministic fake states including auth/offline/unavailable/conflict.

However, the current machine schema encodes entity/base revisions as non-negative integers and generic mutation operations as `create/update/delete`. The Android advocate proposal argues that real entity revisions should be opaque tokens and that offline mutation capability must be advertised per mutation family. The newly durable Sample Lib authority proposal independently reaches the same core conclusions: opaque string revisions, operation-family capabilities, explicit authority generation/cursor expiry, authenticated mobile sync, durable change/tombstone/receipt persistence, path-free resource projection, and no playlist write capability until Sample Lib has a real canonical playlist aggregate. These points are strong convergence evidence, but synthesis is still queued and EPIC-10 is still `recovery_required`; they are therefore **unapproved migration requirements**, not decisions this observer may silently bake into implementation.

## 5. EPIC-11 implementation-ready design

### 5.1 Durability and replay state machine

A user edit may be reported as **queued locally** only after the complete canonical mutation envelope is durably committed. It must not be reported as Sample Lib-confirmed until an authoritative receipt or equivalent subsequent canonical pull confirms it.

Recommended durable states are `PENDING`, `APPLYING`, `PENDING_RETRY`, `CONFLICT`, `REJECTED`, and terminal `APPLIED/NO_OP`. Persist the stable `mutation_id`, paired profile/server identity, entity identity, operation, base revision token, canonical payload bytes or deterministic canonical representation, payload digest, provenance, attempt metadata, and receipt/conflict evidence.

Once an attempt may have reached the server, `(mutation_id, canonical body)` is immutable. Process death while persisted as `APPLYING` means delivery is unknown; restart must replay the exact same mutation identity/body, never mint a replacement ID. A new mutation ID is legal only for a deliberate user reapply against newly read canonical state.

### 5.2 Receipt-gated compaction

No unresolved mutation may be evicted because of age, cache pressure, application upgrade, or retry count. Compact only after a durable authoritative `applied`/`no_op` receipt is recorded. Rejected and conflict records remain queryable until a bounded user/repair policy explicitly resolves them. Journal schema migration must be non-destructive; an un-migratable unresolved envelope moves the client to `LOCAL_MIGRATION_REQUIRED` rather than being rewritten under the same mutation ID.

### 5.3 Merge matrix

| Conflict class | Automatic action | Required evidence/UI |
| --- | --- | --- |
| identical canonical value / server `no_op` | accept authoritative no-op receipt | retain receipt, compact normally |
| disjoint scalar fields and an **approved protocol rule** marks the family field-wise merge-safe | deterministic merge may be automatic | record original local/remote revisions, merged mutation is a new explicit envelope if server round-trip is required |
| same scalar field changed differently | **no auto-merge** | local and remote side by side; use server or intentional reapply |
| `ordered_collection` | **no generic auto-merge** | show local/remote ordering; domain-specific reorder only |
| `range_overlap` | **no generic auto-merge** | show both cue/loop/range values and canonical timing context |
| `delete_vs_edit` | **never resurrect automatically** | accept deletion or intentional recreation/reapply where domain permits |
| `incompatible_schema` | block replay | preserve bytes and enter migration/compatibility recovery |

### 5.4 Mandatory process-death tests

At minimum: death before journal transaction commit must not show queued success; death after durable insert but before send must replay once; death after `APPLYING` persisted but before bytes leave must safely replay; death after server commit but before receipt persistence must replay same ID/body and receive original receipt; death during receipt persistence must recover without duplicate logical effect; app upgrade with unresolved records must preserve identities/bodies; conflict resolution followed by death must not silently convert the old conflicted envelope into a new body.

## 6. EPIC-12 implementation-ready design

### 6.1 Pairing, server identity and health

Pairing must be explicit user action and bind a profile to a stable Sample Lib server identity. Non-loopback mobile access must not inherit loopback trust. Credentials must be per-installation/revocable and stored using Android secure storage; credentials, auth headers and raw secrets must not enter mutation provenance, cursors, logs or exported diagnostics. `401 auth_required`, incompatible protocol, unavailable backend, and ordinary offline transport must remain distinguishable states.

Health is diagnostic readiness, not canonical synchronization truth. A healthy endpoint does not imply the cursor is current or pending mutations are receipted.

### 6.2 Pull

Use the approved opaque cursor contract. Apply each bounded page's changes/tombstones and its `next_cursor` in one local transaction. Process death before commit reuses the previous cursor; process death after commit resumes from the committed next cursor. Never infer deletion from omission in an incremental page. If synthesis adopts cursor expiry/reset, interrupted snapshot recovery must never prune entities merely because later snapshot pages were not yet seen.

### 6.3 Push, receipts and reconnect

Push only journal envelopes whose mutation family is approved for idempotent receipt semantics. A response lost after possible commit is resolved by exact same-ID/body replay. Persist each authoritative receipt before compaction. Reconnect wakes durable pull/push; it must not rebuild intent from transient UI memory.

### 6.4 Fake versus real qualification

The deterministic fake server should prove protocol parsing, state transitions, receipt classes, retries, auth/version failures, cursor handling and process/network fault logic. It **cannot** prove real Sample Lib endpoint availability, durable transaction semantics, server identity/pairing, actual media/resource behavior, or DJXML bridge interoperability. US-120 through US-123 require a separately identified real Sample Lib E2E target and evidence; absent that evidence, report the real-backend portion as unverified rather than promoting fake-server success.

## 7. EPIC-09 implementation-ready design

WorkManager is a scheduler, not the source of truth. Durable transfer state must survive worker cancellation, app process death and reboot. Persist resource identity, expected content hash and size, validator/ETag where available, partial-file identity and completed byte count/ranges before a worker claims resumability.

Downloads should write to profile-scoped partial files and promote to the content-addressed available cache only after final size/hash verification. When byte-range resume is advertised, resume with a strong validator (`If-Range` or approved equivalent); a changed validator restarts safely. Range-not-satisfiable, hash mismatch, or changing content under an immutable identity is an integrity failure, not completion.

Pinning is a durable reference/policy, not merely a current download status. Playlist pinning must retain the resolved membership policy explicitly enough that cache pruning cannot evict required media accidentally. Unpinned verified cache entries may be quota-evicted; pinned media and unresolved mutation journal rows are outside ordinary cache eviction.

EPIC-09 consumes EPIC-03's eventual Media3/player URI contract. It must not create a second playback stack or infer offline availability from a static Track boolean alone. Airplane-mode acceptance requires actual cached-media playback through the real playback provider plus preparation access, not fixture state.

## 8. EPIC-13 implementation-ready design

DJXML is a bidirectional exchange format behind Sample Lib; it is never Android's canonical database or a bypass around mutation/receipt semantics.

### Authority and identity boundary

Android-authored preparation follows: local durable mutation -> Sample Lib authoritative receipt -> canonical Sample Lib state -> DJXML v2 export. Imported DJXML follows: DJXML parser/adapter -> validated Sample Lib canonical mutation/import transaction -> Sample Lib revision/change feed -> Android cursor pull. Android must never import XML directly into its canonical-looking projection without Sample Lib authority.

Maintain explicit mappings between Sample Lib stable asset/interval/cue/loop/tag/playlist identities and DJXML identifiers. Mapping must survive export/import without substituting Android row IDs, host paths, transient filenames, or positional indexes for canonical identity. Where DJXML cannot represent a Sample Lib semantic exactly, qualification must define a deterministic lossy/deferred rule instead of fabricating round-trip equality.

### Playlist/crate boundary

Round-trip tests must cover playlist identity, ordering, nested crate hierarchy where supported, duplicate track membership semantics, deleted/missing assets, and renamed crates/playlists. Smart-playlist rules require an explicit interchange policy; do not silently serialize a computed membership snapshot as if it preserved the rule.

### E2E fixtures

Fixtures should include stable assets with cues, loops, regions, namespaced tags, playlists/crates, ordering changes, unicode metadata, missing/unknown references, and at least one deliberate interoperability limitation. Run Android -> Sample Lib -> DJXML -> Sample Lib -> Android and the reverse import direction, comparing canonical semantic projections rather than byte-identical XML.

## 9. Migration and seam risks

| Risk | Current evidence | Preflight rule |
| --- | --- | --- |
| Revision type mismatch | Kotlin `PendingMutation.baseRevision`/receipt revisions and current JSON schema are numeric; Android proposal requests opaque tokens matching existing Sample Lib fingerprint precedent | Do not persist a Wave Three Room schema that assumes `Long` until synthesis/EPIC-10 approval settles the wire type. |
| Generic CRUD envelope may over-promise idempotency | Current v1 has generic `create/update/delete`; Android proposal says existing server routes are not uniformly replay-safe | Gate offline write UI per approved mutation family/capability. |
| Cursor expiry/reset unspecified in current v1 | cursor exists, but no expiry/snapshot reset contract | EPIC-12 must not invent silent reset semantics; synthesis/EPIC-10 must decide. |
| Pairing/auth underspecified | current contract says explicit pairing/HTTPS preferred and fake has `auth_required`, but no credential/server-identity flow | Keep credentials/profile identity out of DTOs until approved security boundary exists. |
| Download resource contract missing | current sync DTOs do not provide complete immutable media/hash/range semantics | EPIC-09 may build local cache machinery only against an approved resource contract; otherwise restart-only behavior must be explicit. |
| Provider interfaces are projection-only | current download/journal/sync/preparation/playlist interfaces omit many operations required by Wave Three | Any core/provider edits need an explicit narrow claim/owner coordination; Wave Three implementations must not expand scope implicitly. |
| EPIC-03 not implemented | OCP queued, no packet/claim | EPIC-09 cannot prove real offline playback. |
| Canonical playlist authority is absent in current Sample Lib | Sample Lib authority inspection found no canonical playlist table or ordered playlist-item model; current DJXML crate paths map to `namespace="crate"` tags, whose identity/order semantics are insufficient | This is not a new canonical epic dependency, but it is a real acceptance blocker for EPIC-13/US-132 and offline playlist writes. Add stable playlist + playlist-item identity/revisions before claiming playlist/crate round-trip correctness. |
| Fake state mistaken for backend proof | fixture/debug surfaces cover many statuses | Keep fake qualification and real Sample Lib qualification separately reported. |

## 10. Assumptions that are not hard dependencies

- Wave Two preparation/playlist producers will eventually emit backend-neutral mutation intents. The current canonical dependency graph does not make Wave Two a hard Wave Three prerequisite, so this preflight does not add one; it flags missing producer seams as integration risk.
- The architecture's `sample-lib DJXML v2 adapter` reference is now corroborated by the Sample Lib authority inspection: current export uses Sample Lib asset identity hints and current crate interoperability is tag/path based. This observer still has no fresh real-backend execution evidence; EPIC-13 must prove the adapter after EPIC-12 establishes a real Sample Lib path, and US-132 additionally needs canonical playlist identity rather than crate-tag identity.
- Room/WorkManager/Media3/OkHttp are architectural technology choices already documented. Their mere presence in architecture or Gradle is not implementation evidence for the Wave Three semantics above.

## 11. Recommended canonical dispatch order after gates clear

1. **Reconcile the completed advocate artifacts, then execute the already-canonical protocol synthesis task.** All three proposal files now exist. Reconcile the Android-client and Sample-Lib task lifecycle mismatches using their existing checkpoints/artifacts, then dispatch `ANDROIDJTOOLS-PROTOCOL-SYNTHESIS-20260915` through native admission. The synthesis should explicitly decide opaque revisions, operation-family write capabilities, authority generation/cursor reset, tombstones/retention, mobile auth, media integrity/resume, and playlist capability gating.
2. **Use the synthesis decision to recover, repair and independently approve EPIC-10.** Resolve packet `e4de7c8a-bc75-4236-b18a-b93253698cab` through the workflow recovery path; apply only approved contract corrections, rerun the focused contract suite, and obtain the required independent review. Do not manually flip readiness because the historical checkpoint passed 13/13 tests.
3. **When EPIC-10 is accepted, admit EPIC-11 and EPIC-12 in parallel** with separate exact claims. Their canonical scopes do not overlap: local journal/conflict engine versus remote Sample Lib transport/test double. Priority order remains EPIC-11 (99) then EPIC-12 (97) if capacity is constrained.
4. **Admit EPIC-09 only when EPIC-03 is also accepted.** It may then run in parallel with EPIC-11/12 because `offline/download/**` is disjoint, consuming playback and protocol contracts read-only.
5. **Admit EPIC-13 only after EPIC-12 is accepted.** Keep it in `interop/**` + `e2e/djxml/**`; qualification must traverse the actual Sample Lib sync path. EPIC-13 may legally start then, but full US-132 acceptance additionally requires a canonical Sample Lib playlist/playlist-item model rather than current crate-tag compatibility semantics.

## 12. Explicit blockers and exact next lifecycle actions

| Blocker | Exact next lifecycle action |
| --- | --- |
| EPIC-10 packet is `recovery_required` | Workflow authority must inspect its dirty recovery state/transaction evidence and use the canonical recovery mechanism to restore/requeue or explicitly block it; then rerun verification and submit it to an independent reviewer. No manual readiness override. |
| Android advocate OCP is still `queued` despite a complete artifact/checkpoint | Authenticated OCP authority should reconcile the canonical task using the existing artifact/checkpoint; do not create a replacement task or `next.md`. |
| Sample Lib advocate OCP remains `blocked_retriable` even though `sample-lib.md` and a status=`complete` checkpoint are now durable | Reconcile `ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260915` against its existing artifact/checkpoint using authenticated OCP authority. Do not create a replacement task or replay the already-completed document work. |
| Protocol synthesis is still `queued`; all three substantive proposal files now exist but no decision is durable | Execute the existing `ANDROIDJTOOLS-PROTOCOL-SYNTHESIS-20260915` through native admission after lifecycle reconciliation of the advocate inputs. Falsify all three proposals against current code, settle revisions/capabilities/cursor reset/auth/media/playlist semantics, and feed approved contract changes back through EPIC-10 recovery/review. |
| EPIC-03 OCP is `queued`, no packet/claim | Admit/execute the existing canonical `ANDROIDJTOOLS-UX-PLAYER-QUEUE-20260915` under native admission; require Media3 queue/recreation acceptance before EPIC-09 starts. |
| No Wave Three implementation packets exist | After each dependency gate is durably satisfied, admit fresh canonical implementation opportunities for EPIC-11 and EPIC-12, then EPIC-09 when EPIC-03 clears, then EPIC-13 after EPIC-12. Do not derive implementation claims from this observer packet. |

## 13. Ready-state definition for handoff

This observer should recommend implementation dispatch only when the authoritative workflow/OCP view shows the relevant dependency packet(s) in accepted lifecycle state, required independent reviews are recorded, and exact non-overlapping implementation claims can be admitted. Files/tests/checkpoints may support that decision but never substitute for it.
