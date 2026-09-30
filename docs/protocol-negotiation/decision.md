# Android DJ Tools sync protocol — synthesis decision

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-CONVERGENCE-20260927`

Lineage: revalidates and compatibly supersedes the independently reviewed `ANDROIDJTOOLS-PROTOCOL-SYNTHESIS-20260915` decision; the earlier review remains historical evidence for the unchanged v1 invariants.

Status: **decision revalidated on 2026-09-28 against all three substantive proposals and current Android/Sample Lib/Sample Lab/Oversampler code; protocol v1 is already implemented in the production Sample Lib adapter and must now evolve compatibly**.

This decision reconciles the Android/offline-first, Sample Lib authority, and Sample Lab/Intelligence/Overtone proposals against the current repository contracts. It does not average the proposals. Where a proposal is stale relative to implemented code, current code wins; where current Sample Lib lacks the required durable semantics, the contract remains an explicit implementation requirement rather than a fabricated capability.

The 2026-09-27 falsification changed one material wire detail and two migration conclusions. Android already implements opaque revisions, durable mutation replay, conflict classes, pairing/TLS guards, receipt recovery, and range/hash media handling; its production transport already calls `POST /v1/sync/pair` while the machine schema/fake omitted that endpoint. Sample Lib also already ships a narrow `android_sync` facade with pairing, signed mobile credentials, authority generation, signed opaque cursors, principal-scoped receipts, canonical playlist/playlist-item reads, and `interval.editor_state.replace`. This synthesis therefore makes pairing machine-checkable, records the observed server baseline, and replaces the obsolete "build the facade" successor with a convergence tranche for the remaining scale/capability gaps.

### 2026-09-28 falsification delta

All three fresh proposal artifacts are substantive and were independently checked against the current repositories rather than treated as votes. The recheck found no evidence supporting a v2 fork or an incompatible v1 rewrite. It did bound four live Sample Lib conformance gaps that the protocol must not disguise as capabilities:

1. production hello still advertises only `interval.editor_state.replace` for sync writes and advertises no analysis sync capability;
2. signed pull cursors currently carry entity-revision state maps, so cursor size/signing/JSON work grows O(N) with synced entity cardinality;
3. hello advertises a 31,536,000-second mutation replay horizon, while current receipt lookup/storage does not age-enforce that horizon;
4. pairing credentials are cryptographically bound to server/client/installation/scopes, but the current token claims contain no expiry and the protocol has no implemented rotation/revocation lifecycle.

These are implementation/conformance gaps, not permission to weaken durable pull, receipt authority, explicit conflict handling, or capability honesty. The machine contract records them as observed facts so future tests cannot accidentally promote aspirational operation families into the live baseline.

### 2026-09-28 execution evidence

The synthesis lane directly revalidated the current Android client/journal, Sample Lib sync facade, and Oversampler Sample Lab adapter. With third-party pytest plugin autoload disabled to avoid an unrelated host `libtmux`/pytest incompatibility, the executable sync schema/fake suite passes **32 tests plus 28 subtests**. The production Sample Lib Android-sync API suite passes **8 tests** (one Starlette/AnyIO deprecation warning only). Android's focused remote-Sample-Lib, sync-journal, and conflict JVM tests pass under JDK 17, and Oversampler's Sample Lab adapter/convergence suites pass **16 tests / 99 assertions**. Android's current client capability-gates mutation families, preserves mutation identity across ambiguous-delivery recovery, pins server identity/generation, and verifies media integrity; the durable journal stores opaque revisions, immutable canonical request bodies, and authoritative receipts. Oversampler's current adapter marks ambiguous mutations `outcome=:unknown`, performs readback reconciliation, and explicitly does not retry the mutation itself, which is evidence against treating generic Sample Lab mutation routes as implicitly idempotent.

The Android JVM gate emits only the existing Android Gradle Plugin warning that AGP 8.5.2 was tested through compileSdk 34 while this project uses compileSdk 35; it still completes successfully. The initial Sample Lib pytest invocation also required the repository package path (`PYTHONPATH=third_party/sample-lib/src`). Neither environment detail changes the wire decision, and no additional protocol change follows from this revalidation.

## 1. Authority and protocol boundary

Sample Lib is the sole canonical authority for sync-visible library metadata. Android owns a durable local projection/cache plus a mutation journal. Analysis services produce advisory evidence. Overtone owns performance/runtime state. A mediator may correlate requests and confirmations but is not a metadata authority.

The cloudless v1 facade is deliberately narrow:

```text
POST /v1/sync/pair                  # explicit operator bootstrap; unauthenticated only for pairing
POST /v1/sync/hello
POST /v1/sync/pull
POST /v1/sync/push
GET  /v1/sync/receipts/{mutation_id}
WS   /v1/sync/events                  # optional wake hint only
GET/HEAD <authenticated resource>     # media/resource bytes
```

Existing generic Sample Lib REST endpoints are not retroactively declared offline-safe. The sync facade is where durable cursor, revision, receipt, auth and replay semantics live.

## 2. Versioning decision

Keep protocol version `1` for the already-implemented compatible wire shapes. The 2026-09-27 inspection found production `sample_lib.android_sync` and Android transport code already speaking v1, so the older rationale that v1 was wholly unshipped is superseded. From this point, v1 changes must be additive or capability-gated; an incompatible envelope/revision/cursor semantic change requires a new negotiated version rather than silent in-place breakage. Adding the missing pairing schema is alignment with an existing v1 endpoint, not a new incompatible behavior.

Hello selects the highest mutually supported version. No overlap returns HTTP 426 with `hello_incompatible`; silent downgrade is forbidden. Optional operation families are advertised by hello and clients must obey the live subset rather than assuming every operation described by the protocol vocabulary is implemented.

An accepted hello identifies both `server_id` and an opaque `authority_generation`. A generation change means the authority database/projection generation changed enough that old cursors cannot be trusted. Android must bootstrap rather than interpret the mismatch as an empty delta.

## 3. Revisions, cursors and sequencing

### Decision

Entity revisions and mutation `base_revision` are **opaque non-empty strings**. Clients may compare them for equality only. They may not parse, increment, sort or construct them.

Sample Lib already proves this shape with the interval editor's SHA-256 revision fingerprint. That production-grounded concurrency token wins over the fixture's former `integer >= 0` schema.

A separate `server_change_revision` may be a monotonic integer for diagnostics and internal feed construction. It is not an entity revision and is never a write precondition.

Pull cursors are opaque server-owned strings. Android commits page changes, tombstones and `next_cursor` in one local transaction. Cursor expiration is explicit: `pull_cursor_expired` requires a new `cursor=null` snapshot. The server never silently resumes “from now.”

### Snapshot semantics

`cursor=null` begins a snapshot. A complete implementation must bind snapshot traversal and the later incremental high-water so concurrent writes cannot disappear between the two. Duplicate projection delivery is acceptable because stable entity ID + revision application is idempotent; omission is not. A final snapshot page is therefore a completeness claim: every authoritative entity in the requested scopes must have been represented across the snapshot pages before `has_more=false` permits Android to prune unseen local rows.

Android only prunes entities absent from a reset snapshot after the final snapshot page commits successfully. The executable fake has the same safety rule: `cursor=null` enumerates its authoritative in-scope fixture entities, including during recovery from the `cursor_expired` scenario, rather than returning an empty terminal snapshot.

## 4. Capability negotiation and mutation envelope

A generic `write=true` or generic `create/update/delete` capability is rejected. Current Sample Lib semantics are not uniformly idempotent across every REST route.

Hello advertises read and mutation capability by entity/operation family. The v1 contract currently names these mutation families as the intended narrow target:

- `interval.editor_state.replace` — strongest current transaction/idempotency precedent;
- `asset.metadata.patch` — only after Sample Lib revision/receipt wrapping exists;
- `tag.ensure_attach` and `tag.detach` — only after journal/tombstone emission exists;
- `analysis.suggestion.accept` — normal canonical mutation with bounded suggestion provenance.

Playlist reads are `canonical_v1`: current Sample Lib exposes stable canonical playlist and playlist-item identities. That does **not** imply write support. Android must not advertise offline playlist reorder/edit until Sample Lib exposes an explicit revision-checked ordered mutation family.

Each mutation contains stable `mutation_id`, target entity, opaque `base_revision` (or null only where an operation explicitly defines create semantics), operation family, bounded payload, diagnostic `created_at`, and provenance. Operation identity is not decorative: v1 binds each advertised operation to allowed entity families and a base-revision policy, and both the wire schema and runtime reject mismatches before any write:

| Operation | Allowed `entity_type` | `base_revision` policy |
| --- | --- | --- |
| `interval.editor_state.replace` | `interval` | required opaque revision of an existing interval |
| `asset.metadata.patch` | `asset` | required opaque revision of an existing asset; never implicit asset creation |
| `tag.ensure_attach` | `tag_attachment` | explicit `null` create-or-ensure semantic |
| `tag.detach` | `tag_attachment` | required opaque revision of the existing relation |
| `analysis.suggestion.accept` | `asset` or `interval` | required opaque current revision |

Same mutation ID + same canonical body returns the original receipt without reapplying. Same ID + different body is rejected as `idempotency_key_reused`. Push batches are processed in request order and return one authoritative receipt per mutation; v1 does not imply cross-entity all-or-nothing atomicity.

## 5. Receipts and ambiguous delivery

Receipts remain authoritative and have four outcomes:

| Outcome | Meaning | Android journal action |
| --- | --- | --- |
| `applied` | canonical state changed | terminal success; update projection from receipt/pull |
| `no_op` | valid intent already satisfied | terminal success |
| `rejected` | invalid, unauthorized, unsupported or key misuse | terminal until user/edit creates new intent |
| `conflict` | authority changed or structural resolution required | stop replay; require explicit resolution/reconfirmation |

`GET /v1/sync/receipts/{mutation_id}` is a recovery/debug endpoint scoped to the authenticated principal/installation. Exact push replay remains safe even without lookup, but lookup lets a client reconcile a lost response without repeating expensive work.

A numeric `server_change_revision` may appear on receipts for diagnostics; `entity_revision` is opaque.

## 6. Conflict matrix

Wall-clock last-write-wins is rejected.

| Merge class | Example | v1 behavior |
| --- | --- | --- |
| `safe_fieldwise` | explicitly independent metadata fields | server returns bounded changed-field maps plus `merge_safe_fields` derived only from the operation contract; Android may auto-merge only when local/remote keys are disjoint and every changed key is declared safe |
| `editor_aggregate` | cue/loop/marker editor aggregate changed | return current aggregate/revision; rebase requires new user/client intent |
| `ordered_collection` | concurrent playlist reorder | never timestamp-LWW; current ordered IDs required after playlist model exists |
| `delete_vs_edit` | queued edit targets tombstoned entity | do not recreate silently; explicit conflict |
| `identity_conflict` | child ID belongs to another aggregate | reject |
| `incompatible_schema` | queued payload no longer understood | reject/migrate/review |

Conflict DTOs carry opaque base/current revisions, stable code, merge class, and bounded local/remote changed-field values where safe. `safe_fieldwise` additionally requires authoritative `merge_safe_fields`; callers cannot manufacture this authority. All other conflict classes, overlapping keys, missing authority, and undeclared fields remain explicit user resolution rather than generic auto-rebase.

## 7. Change journal, tombstones and retention

Sample Lib still needs a durable change/outbox journal; its production sync adapter already persists authoritative mutation receipts in the existing operation-event ledger. Canonical write + change record + receipt must remain crash-atomic as mutation coverage broadens, and canonical delete + tombstone must commit crash-atomically. All sync-visible write origins (local UI, imports, batch operations, Android) must emit through the same convergence mechanism.

The convergence feed may be compacted, but every non-expired cursor must still converge. Hello advertises effective horizons. Initial policy defaults in the contract are 90 days for cursor/tombstone retention and 365 days for mutation receipt replay; these are negotiated policy values, not protocol constants.

When retention or a configured cap invalidates a cursor, the server returns `cursor_expired`. It never silently forgets a still-advertised replay guarantee.

Current-production conformance note (revalidated 2026-09-28): `sample_lib.android_sync` advertises `mutation_replay_horizon_seconds = 31_536_000`, but its receipt storage/lookup path does not currently age-enforce that horizon. This is an implementation gap under the existing v1 guarantee, not a reason to change the wire contract. The server must either enforce the advertised replay horizon (including deterministic expiry semantics) or advertise the effective horizon it actually guarantees; retaining receipts longer is harmless, but silently retaining or expiring them on semantics inconsistent with hello is not.

## 8. Pairing, authentication and trust

Phone access requires an authentication boundary before the real adapter is enabled. The current loopback/local API assumptions are not a mobile security contract.

Minimum v1 requirements:

1. pairing is an explicit operator action;
2. the client supplies a stable installation ID during explicit pairing; the server derives the principal from the paired client/installation identity and cryptographically binds both identities into the credential, so later request JSON cannot impersonate another installation;
3. non-loopback access uses authenticated TLS or an equivalently authenticated private transport;
4. authorization scopes include at least `sync.read`, `sync.write`, `media.read`, `analysis.read`, with `analysis.request` separate when job launch is allowed;
5. every mutation target is re-authorized server-side;
6. receipts, cursors and resource resolution are principal-scoped;
7. credentials never appear in cursors, resource identity, mutation provenance, logs, WebSocket messages or diagnostic exports;
8. revocation blocks future send/read but does not silently delete the Android mutation journal.

Current production pairing already derives a principal from client/installation identity, signs a server-bound scoped bearer credential, and checks those identities on hello. The current credential claims have no expiry field, and no rotation/revocation protocol was found in the inspected sync façade. Exact credential expiry, rotation/revocation storage and local TLS bootstrap therefore remain implementation choices; see bounded ambiguity below.

## 9. Media and offline cache

Durable media identity is a Sample Lib `resource_uri`, not a filesystem path or temporary signed URL. Sync/resource projections expose content hash, size and MIME when known. Fetch URLs may be refreshed independently of identity.

Android marks an offline download complete only after final content-hash verification. Byte-range resume is used only when advertised and validators still match; otherwise restart. Raw `storage_relpath`, worker output directories, command paths and provider URLs never cross the mobile boundary.

Pinned user content survives ordinary cache eviction; unpinned media is cacheable/evictable without changing Sample Lib authority.

## 10. Intelligence decision

Analysis is advisory until Sample Lib confirms a normal mutation. Android branches on semantic capability IDs, not provider/model names or ports.

The machine contract now records semantic capabilities, job states, candidate freshness/decisions, sanitized media identity and mediated action lifecycle. The YAML contract defines portable DTO families:

- capability — semantic operation/availability, provider only as bounded provenance;
- analysis job — correlated durable/mediator execution state;
- candidate — value, confidence, input identity, provenance and freshness;
- safety finding — independently visible warning/blocking evidence;
- media ref — registered resource identity and content hash, never host path;
- transcript marker — proposal vs accepted durability.

A candidate with changed input revision/content becomes stale. Stale candidates are never auto-applied. `decision: accepted` is meaningful only after the associated canonical mutation receives `applied` or `no_op` authority evidence.

No verified canonical ASR backend was found in the inspected checkout. `analysis.speech_transcript` is a semantic capability slot, not a claim that ComfyUI, Whisper or another stack is currently authoritative/available.

## 11. Overtone / requester confirmation flow

Overtone does not write Sample Lib through a bypass. It emits a mediated request with idempotent requester-owned `request_id`, end-to-end `correlation_id`, target identity, expected opaque revision, bounded proposal and intent (`cue.confirm_set`, `loop.confirm_set`, or `marker.confirm_set`).

State flow:

```text
received -> validated -> awaiting_user
                         | accept
                         v
                   mutation_queued -> mutation_submitted
                                      | applied/no_op
                                      v
                                   committed -> requester_replied
                                      |
                                      + conflict -> needs_reconfirmation -> requester_replied

awaiting_user -> rejected/expired -> requester_replied
validation/submission terminal failure -> failed -> requester_replied
```

Rules:

- same request ID + same material request returns current request state;
- same request ID + materially different request is a protocol conflict;
- mediator validates target/revision before prompting the user;
- user acceptance creates a **fresh normal sync mutation**, carrying request/correlation/candidate references in provenance;
- offline acceptance may be journaled, but Overtone is never told “applied” before authoritative receipt;
- conflict is never auto-rebased: canonical state is re-read and the proposal needs confirmation again;
- every terminal path has a queryable terminal requester reply;
- applying the committed result to Overtone runtime is a separate performance operation. `performance_apply_failed` does not roll back or repeat the Sample Lib commit.

## 12. Fake-server qualification added by this synthesis

The executable fake now models the decisions rather than the older assumptions:

- opaque entity/base revisions distinct from numeric `server_change_revision`;
- structured operation-family capabilities and explicit playlist write withholding;
- namespaced `/v1/sync/*` endpoints;
- authority generation and negotiated retention limits;
- snapshot/incremental pull mode, with terminal bootstrap snapshots proven complete for the fake's authoritative in-scope fixture state;
- explicit `cursor_expired` state with successful `cursor=null` reset recovery;
- change page with tombstone evidence;
- idempotent replay and same-ID/different-body rejection;
- unsupported generic CRUD rejection even if schema validation is bypassed;
- operation/entity/base-revision invariant rejection in both schema and runtime, preventing implicit asset creation or cross-family writes;
- durable receipt lookup;
- all protocol responses validated against the JSON Schema;
- machine assertions for mediated request idempotency, no conflict auto-rebase, and receipt-before-applied requester reply.

This fake remains a deterministic contract test double. It does not prove the remaining Sample Lib change-journal scaling, retention enforcement, broader mutation-family, advisory-sync, or optional wake-stream semantics; current production pairing/auth and receipt persistence are verified separately from Sample Lib code.

## 13. Migration path

### A. Current Android contract/fake — done in this synthesis

- repair entity revisions from integer to opaque string;
- separate diagnostic server change sequence from entity revision;
- namespace endpoints under `/v1/sync/*`;
- replace generic CRUD with operation-family capabilities;
- add generation/cursor-expiry/tombstone/receipt recovery semantics;
- make intelligence/mediation lifecycle machine-checkable.

### B. Android client convergence — already implemented and reverified

The current Android code has moved beyond the older fixture-era assumptions. `OpaqueRevision` is a non-empty string value type; `DurableMutationJournal` persists immutable canonical mutation bodies before delivery, recovers `APPLYING` to replayable `PENDING` after process death, and records authoritative receipts durably; `ProtocolOperationPolicy` enforces operation/entity/base-revision rules; `ConflictResolver` only auto-merges explicitly authorized disjoint safe fields; `SampleLibHttpTransport` implements `/v1/sync/pair`, bearer-authenticated hello/pull/push/receipt/media calls, rejects insecure non-loopback HTTP, constrains media fetches to the paired origin, and supports Range/If-Range resume. These are evidence, not future requirements.

The remaining Android work is ordinary hardening/integration only: preserve the durable journal and projection across future storage migrations, keep background replay bounded by platform scheduling constraints, and do not weaken the protocol invariants while UI/provider work evolves.

### C. Sample Lib sync-v1 convergence — canonical successor tranche

The production `sample_lib.android_sync` adapter already implements pairing/device principals, authority generation, signed opaque cursors, principal-scoped durable receipts, canonical playlist reads, media capability advertisement, and `interval.editor_state.replace`. The remaining server tranche is therefore convergence, not greenfield facade work:

- replace the current O(N) revision-map-in-cursor strategy with a scalable durable change/tombstone journal or equivalent cursor state while preserving opaque cursor semantics and coherent snapshots;
- keep the existing receipt ledger/idempotency behavior authoritative and crash-safe as mutation families broaden, and make the advertised mutation replay horizon true in storage/lookup semantics (or advertise the effective guaranteed horizon instead of an unenforced policy value);
- wrap only evidence-backed additional mutation families (`asset.metadata.patch`, tag attach/detach, then advisory acceptance) and advertise each only when actually implemented;
- project advisory analysis candidates into sync only after a durable sanitized Sample Lib representation exists; current production hello correctly advertises no analysis capability;
- retain canonical playlist and playlist-item reads, but do not advertise offline reorder/write until a revision-checked ordered mutation family exists;
- implement optional WebSocket wake hints only as an optimization after durable pull remains sufficient for convergence.

### D. Narrow additional writes

Revision/receipt-wrap asset metadata and tag attachment operations. Add sanitized suggestion projection and acceptance provenance. Do not broaden to arbitrary REST calls.

### E. Canonical playlists

Add stable playlist and playlist-item identities, aggregate revision and transactional ordering semantics; migrate crate tags as compatibility input where possible; update DJXML projection. Only then advertise playlist write capabilities.

## 14. Rejected alternatives

- **Reintroduce numeric entity revisions or silently reinterpret v1 revisions:** rejected; deployed Android/Sample Lib v1 already uses opaque revision tokens, and changing that meaning in place would break an implemented compatibility boundary.
- **Generic CRUD mutation envelope:** rejected; current Sample Lib routes do not share uniform idempotency/concurrency semantics.
- **WebSocket replication as authority:** rejected; Android cannot guarantee continuous socket consumption. WS is only a wake hint followed by durable pull.
- **Timestamp last-write-wins:** rejected; device clocks are not concurrency authority and would lose structural edits/order.
- **Full snapshot every sync:** rejected as normal path due large-library/battery/database churn. Snapshot remains bootstrap/reset recovery.
- **CRDTs everywhere:** rejected for v1; unnecessary complexity around an existing canonical transaction/revision model. A future ordered-playlist design may independently choose an operation/CRDT model if justified.
- **Use search `LIMIT/OFFSET` as sync cursor:** rejected; mutable search ordering cannot be durable change history.
- **Crate/tag path as canonical playlist identity:** rejected; it cannot provide stable ordered playlist/item identity.
- **Raw provider output on Android:** rejected; paths, worker endpoints and model-specific shapes remain server-internal. Android gets sanitized semantic DTOs.
- **Overtone direct durable writes:** rejected; performance intent must pass through user confirmation and ordinary Sample Lib mutation/receipt semantics.

## 15. Bounded remaining ambiguity

The protocol shape is now bounded enough for implementation. These details remain intentionally implementation-owned rather than guessed here:

1. exact device credential format, issuance/revocation storage, and local TLS/public-key bootstrap;
2. exact SQLite schema/compaction strategy for Sample Lib change and receipt ledgers, provided crash-atomic invariants hold;
3. exact opaque cursor encoding/signing and coherent snapshot implementation;
4. exact signed/authorized resource-fetch URL mechanism and Range/validator implementation;
5. canonical playlist ordering/write operation model and DJXML interoperability rules, which gate playlist writes but not canonical reads;
6. exact future Room/local-journal migration mechanics when the durable Android sync store evolves; the current Kotlin opaque-revision wrapper already exists and is not an open protocol question;
7. future ASR provider implementation; semantic contract exists, provider availability is not asserted;
8. whether receipt lookup remains mandatory in production — exact push replay is correctness-sufficient, but lookup is retained as recommended recovery/support surface.

None of these ambiguities justifies returning to integer revisions, generic CRUD, unauthenticated phone access, WebSocket authority, or model-specific Android coupling.

## 16. Proposal dispositions and bounded successor

The three proposals are not treated as votes:

- **Android/offline-first proposal:** accepted where it demands durable journal-before-send, idempotent replay, opaque cursors, process-death recovery, bounded WorkManager-friendly batches, explicit conflicts, resumable hash-verified media, and semantic advisory intelligence. Claims that these Android primitives are still only hypothetical are superseded by current implementation evidence.
- **Sample Lib authority proposal:** accepted on canonical ownership, operation-family writes, opaque revisions, receipt authority, principal-scoped auth and canonical playlists. Falsification found the production `android_sync` facade already implements much of this boundary; the remaining issue is convergence/scaling and capability breadth, not facade existence. Its current signed cursor embeds revision maps and its hello advertises only `interval.editor_state.replace`, canonical playlist reads, no analysis capability, and no implemented WebSocket event stream. The proposal's later revalidation also correctly identified that the advertised 365-day mutation replay horizon is not age-enforced by current receipt storage/lookup; that gap is folded into the same Sample Lib convergence tranche rather than creating a second protocol successor.
- **Sample Lab/Overtone proposal:** accepted on provider-neutral advisory DTOs and mediated request/confirmation/reply semantics. Overtone runtime application remains separate from canonical commit; no direct mobile/browser performance-control channel becomes durable library authority.

Exactly one concrete successor is warranted: **converge the existing Sample Lib sync-v1 production adapter** by replacing O(N) cursor state with scalable durable change/tombstone tracking, making advertised retention/replay horizons match enforceable storage semantics, and then adding only negotiated, revision-checked operation/advisory capabilities that have real persistence semantics. No parallel Android protocol rewrite, replacement facade, or legacy prolong task is justified.

## 17. Acceptance decision

The synthesis decision is ready for independent protocol review when:

- JSON Schema validates;
- the fake drives every declared state;
- pairing, opaque revisions, cursor expiry, tombstones, idempotent receipts, receipt lookup, operation-family capability and canonical-read/write-withholding playlist tests pass;
- intelligence mediation assertions pass;
- scoped diff contains only the synthesis packet's allowed contracts, decision and fake-server paths.

Wave Three may treat EPIC-10's **old** 13/13 integer-revision checkpoint as superseded evidence. The gate should clear only after this repaired synthesis contract is independently reviewed and the existing EPIC-10 lifecycle is reconciled against it; the remaining real backend tranche is Sample Lib cursor/change-journal scalability plus capability-by-capability mutation/advisory convergence, not greenfield auth/facade work.
