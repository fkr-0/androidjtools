# Sync protocol negotiation — Android / offline-first advocate

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-ANDROID-CLIENT-20260927`

Status: **2026-09-28 repository-grounded Android/offline-first proposal; not unilateral protocol victory**

## 2026-09-28 revalidation delta

This proposal was rechecked against the current Android client, the checked-in sync/intelligence contracts, Sample Lib's live production adapter under `third_party/sample-lib/src/sample_lib/android_sync.py`, the canonical playlist migration, and Oversampler's current Sample Lab adapter.

### Resolved since the earlier negotiation

- The checked-in sync contract now uses **opaque string entity/base revisions** and separates them from numeric `server_change_revision`.
- The canonical wire endpoints are now `POST /v1/sync/hello`, `POST /v1/sync/pull`, `POST /v1/sync/push`, and `GET /v1/sync/receipts/{mutation_id}`.
- Sample Lib now has a real mobile sync façade with explicit pairing, scoped bearer credentials, stable `server_id`, `authority_generation`, integrity-protected opaque cursors, snapshot/incremental pull, principal-scoped receipt lookup, and replay-safe mutation receipts.
- Sample Lib migration `0013_canonical_playlists.sql` establishes canonical playlist and playlist-item identities; the current Android sync server exposes them in pull and advertises `playlists: canonical_v1`.
- Android's remote Sample Lib client already pins server identity, detects authority-generation reset, bounds pages/batches from hello, recovers ambiguous mutation delivery through receipt lookup, and verifies hash-addressed media with optional range resume.
- Android's offline download layer already persists process-death-safe metadata keyed by canonical asset identity and keeps canonical `resourceUri` / SHA-256 separate from local cache paths.

### Remaining acceptance blockers / negotiation demands

1. **The real Android journal exists, but the thin presentation/domain projection still carries fixture-era numeric revisions.** `sync/journal/MutationJournal.kt` persists opaque-string `baseRevision`, immutable canonical request bodies, APPLYING/PENDING recovery and authoritative receipts with a versioned v1→v2 migration. `core/model/Domain.kt` still exposes `Long?` revision fields for thin UI/fixture models. Those presentation types MUST NOT leak back into the durable wire/journal path; the remaining hardening question is replacing the current atomic file snapshot store with the architecture-target Room transaction boundary when page projection/cursor persistence lands.
2. **Server capability truth is narrower than the checked-in synthesis contract.** The production Sample Lib façade currently advertises only `interval.editor_state.replace` as an offline-capable mutation and no analysis sync capability. Android MUST branch on hello capabilities, not on contract aspirations or fixture providers.
3. **Cached intelligence is client-readable, but production Sample Lib sync does not yet expose `analysis_suggestion` in its pull set.** Offline review/acceptance therefore requires either a server pull projection for advisory candidates or a deliberately separate cached intelligence feed; acceptance still MUST become a fresh normal revision-checked sync mutation and only becomes canonical after receipt.
4. **Canonical playlists are now readable, but playlist writes/reorder are not negotiated mutations.** Android can cache/browse/pin canonical playlists offline today; offline playlist edits remain disabled until a receipt-backed ordered-collection mutation family exists.
5. **The server's opaque cursor embeds the full entity→revision state map; a paged incremental cursor can embed both baseline and target maps.** This is fail-closed and deterministic, but cursor size and signing/JSON cost are O(N) in synced entity cardinality. Scale qualification must measure token bytes, request/response limits, CPU and 100k-entity convergence; a compact server-side change-log cursor may be required before large-library release.
6. **Pairing is explicit but credential lifecycle remains minimal.** The current signed token has scopes and server binding, but protocol-level revocation/rotation/expiry semantics and authenticated non-loopback transport policy still need an operational decision.
7. **WorkManager scheduling is still not a correctness boundary.** Mutation intent already has crash-safe atomic file persistence and APPLYING→PENDING recovery, while media downloads use unique WorkManager jobs plus durable partial state. The missing convergence boundary is a transactional canonical projection + pull cursor store: page changes/tombstones and `next_cursor` must commit together, independent of worker timing.
8. **Advertised mutation replay retention is stronger than the current Sample Lib implementation.** The v1 contract/hello uses a 31,536,000-second mutation receipt replay horizon, but current receipt storage/lookup does not age-enforce that boundary. Sample Lib MUST either enforce the advertised horizon or advertise the actually guaranteed horizon. Android MUST persist the effective horizon with pairing/hello state and must not assume a mutation remains safely replayable forever; once an unresolved attempted mutation is older than the last server-guaranteed replay horizon, automatic resend stops and the client requires receipt/readback reconciliation or explicit user recovery rather than risking a duplicate apply.
9. **Current tombstones are cursor-derived observations, not a durable deletion history.** Sample Lib's current v1 facade compares the prior cursor revision map with present canonical rows and emits a tombstone when a previously seen identity disappears. That is sufficient for a client whose cursor already contained the entity, but it does not prove historical tombstone retention for clients that were offline before the deletion or whose cursor state was lost/expired. Before Android relies on an advertised tombstone-retention horizon, Sample Lib MUST back sync-visible deletes with a durable change/tombstone feed or explicitly scope the guarantee to cursor-carried prior identity and force snapshot reset outside it. Android MUST treat `cursor_expired` as a bootstrap boundary rather than assuming a missing historical delete can be reconstructed incrementally.

### Current smallest robust decision

Keep the v1 core as:

```text
explicit pair + versioned hello
  + opaque cursor pull with bounded pages + tombstones/reset
  + durable Android mutation journal + Sample Lib mutation-id receipts
  + canonical resource identity + hash-verified/resumable media
  + optional WebSocket wake hints only (contracted, not currently implemented by Sample Lib sync)
```

Do **not** broaden v1 by pretending unsupported mutation families are offline-safe. Ship capability-gated correctness first; add metadata/tag/playlist/intelligence acceptance families only when Sample Lib can persist the same idempotency and revision invariants already proven by interval editor state.

## Executive decision

Android should converge on the smallest protocol that remains correct under mobile process death and ambiguous delivery:

```text
versioned REST hello
  + opaque cursor pull with bounded pages + tombstones
  + durable client mutation journal + server mutation-id receipts
  + content-addressed/resumable media fetch
  + optional WebSocket wake hints
```

The durable truth remains Sample Lib. Android owns a **projection, cache, and intent journal**, never a second canonical library database. WebSocket delivery, Android WorkManager, wall-clock timestamps, provider callbacks, and intelligence suggestions are all advisory scheduling/evidence mechanisms; none is a correctness boundary.

The existing `contracts/sync/v1/*` direction is now substantially implemented rather than merely aspirational. The remaining Android demands are narrower: capability truth must match the live Sample Lib façade; pull-page state and the next cursor must commit atomically in a durable Android store; unresolved mutation intent must survive process death and schema migration; large-library cursor/reset behavior must be load-tested; credential revocation/rotation and non-loopback transport policy must be operationally defined; and new offline write families must be added only when they gain the same durable receipt/revision semantics as the current interval editor mutation.

These are negotiation demands, not production code edits in this lane.

---

## 1. Repository-grounded starting point

### Android DJ Tools today

The current executable contract already establishes the important base invariants:

- `contracts/sync/v1/sync-contract.yml`
  - Sample Lib is canonical authority;
  - mutations are idempotent;
  - receipts are authoritative;
  - cursors are opaque;
  - conflicts and incompatibility are explicit;
  - optional WebSockets are wakeup hints only;
  - intelligence acceptance uses ordinary mutations.
- `contracts/sync/v1/protocol.json`
  - same `mutation_id` + same canonical body returns the original receipt;
  - same `mutation_id` + different body is rejected;
  - outcomes are `applied`, `no_op`, `rejected`, `conflict`.
- `contracts/sync/v1/sync.schema.json`
  - already bounds pull pages and push batches;
  - carries explicit changes and tombstones;
  - now represents entity/base revisions as opaque strings, matching the production Sample Lib mobile façade. The remaining revision mismatch is limited to thin Android UI/domain fixture types, not the wire contract.
- `app/src/main/java/dev/androidjtools/core/model/Domain.kt`
  - already separates pending mutations, receipts, download state, sync state, suggestions, and canonical-looking library projections;
  - currently models `PendingMutation.baseRevision` as `Long?`, which should be treated as fixture-era shape rather than a settled cross-repository wire type.
- `app/src/main/java/dev/androidjtools/core/provider/Providers.kt`
  - gives the Android app the right abstraction seams: library/preparation/playlist/download/journal/sync/analysis providers.
- `app/src/main/java/dev/androidjtools/fixture/FixtureProviders.kt`
  - already demonstrates pending ordinary edits and an accepted-intelligence mutation in one journal projection;
  - its hard-coded provider availability is fixture data, not evidence that those analysis backends are installed in a deployment.

### Sample Lib / Sample Lab precedents

The strongest server-side precedent is the existing atomic interval editor mutation:

`PATCH /v1/intervals/{interval_id}/editor-state`

`third_party/sample-lib/src/sample_lib/editor_state.py` already proves the semantics Android needs:

- caller-provided `mutation_id`;
- opaque SHA-256 `base_revision`;
- `BEGIN IMMEDIATE` writer serialization before reading the revision;
- a persisted mutation receipt keyed by mutation ID;
- same ID + same request returns the persisted result without reapplying;
- same ID + different interval/base/body returns a deterministic conflict;
- stale base revision returns an explicit conflict with current revision/state;
- interval bounds and the complete marker set are committed atomically.

The browser offline journal in `docs/implementation/desktop/offline-editor-journal-phase2.md` provides an equally useful client precedent:

- journal before claiming offline durability;
- unresolved records retained indefinitely;
- attempted payload becomes immutable after possible server delivery;
- persisted `applying` is replayable after crash;
- finite retry bursts plus lifecycle/connectivity wakeups;
- exact replay after ambiguous timeout/408/429/5xx;
- conflict stops automatic replay;
- `Use server state` or `Reapply local draft` creates a new mutation ID against current revision;
- journal schema migrations are explicit and non-destructive.

But this strength is **not universal across current Sample Lib APIs**. Existing Sample Lab/Oversampler integration documentation and adapters intentionally treat many generic stateful POST/PATCH/DELETE calls as non-idempotent: after an ambiguous result they re-read instead of blindly retrying. Therefore the mobile protocol needs an explicit capability boundary rather than retroactively declaring every existing mutation route safe.

### Overtone boundary

The Overtone repository reinforces the authority split:

- Sample Lib / Sample Lab own assets, intervals, tags, analysis, editgraphs, renders, resource identity and provenance.
- Overtone owns playback, sequencing, synthesis, effect semantics and runtime performance state.
- Overtone's Sample Lab HTTP adapter retries bounded idempotent GETs, but `mutation-json!` marks ambiguous mutation transport as `outcome=:unknown`; `mutation-with-reread!` reconciles by reading current state rather than repeating the mutation.
- portable Overtone persistence keeps stable Sample Lab IDs/resource URIs/content hashes and strips host-private cache paths.

The parallel Sample Lab/Overtone negotiation reaches the same product rule this proposal depends on:

> Evidence/performance systems may propose; Sample Lib commits; Android confirms/reviews; Overtone performs.

---

## 2. Android constraints that must shape the wire contract

Android cannot rely on a long-lived foreground process or a continuously connected socket. Correctness has to survive all of the following without special server cleanup:

```text
radio loss / Wi-Fi -> mobile handover
DNS/TLS reconnect
request body delivered, response lost
process kill between local state transitions
OS reboot
WorkManager delay or cancellation
battery/data saver
multiple devices editing the same canonical entity
app upgrade while unresolved intents exist
large libraries where a full snapshot is expensive
partial media files after process death
WebSocket disconnect, duplicate hint, or missed hint
```

The protocol therefore cannot derive correctness from:

- precise WorkManager timing;
- a WebSocket sequence observed continuously;
- client timestamps or last-write-wins;
- "retry POST and hope";
- a volatile in-memory queue;
- the presence of a cached suggestion/provider result;
- an Android filesystem path.

It must derive correctness from durable server/client identities and transactional state transitions.

---

## 3. Authority and local state classes

Android proposes three deliberately different durability classes:

| Store | Durability | May rebuild? | May quota-evict? | Authority |
| --- | --- | --- | --- | --- |
| Read projection | durable convenience | yes | yes, by policy | Sample Lib |
| Media/waveform cache | content-addressed cache; pins supported | yes from server | unpinned yes | Sample Lib resource identity |
| Mutation journal | durable user intent + receipts | **no destructive reset** | **never while unresolved** | Sample Lib decides outcome |

An app upgrade may drop/rebuild a stale search projection. It may delete an unpinned cached waveform. It MUST NOT drop a queued cue edit because the journal schema changed.

The canonical Android state is therefore not "whatever is in Room". Room holds a projection plus unresolved intent. UI must distinguish:

- canonical confirmed state;
- local queued draft/overlay;
- explicit conflict;
- advisory intelligence candidate;
- media availability.

---

## 4. Smallest robust wire protocol

Keep the existing v1 conceptual surface unless synthesis finds a naming collision:

```text
POST /v1/sync/pair
POST /v1/sync/hello
POST /v1/sync/pull
POST /v1/sync/push
GET  /v1/sync/receipts/{mutation_id}

GET/HEAD <advertised Sample Lib resource endpoint>   # media, with optional Range
WS       /v1/sync/events                              # optional wake hint only
```

The receipt-query endpoint is now implemented as:

```text
GET /v1/sync/receipts/{mutation_id}
```

It is principal-scoped in Sample Lib and is used by the current Android client to reconcile ambiguous delivery before leaving the exact mutation pending. Exact same-ID/body replay remains the underlying idempotency invariant.

### 4.1 Hello / capability negotiation

Use the currently implemented v1 envelope rather than introducing a parallel capability vocabulary:

```json
{
  "type": "hello_request",
  "protocol_versions": ["1"],
  "client_id": "android-dj-tools",
  "installation_id": "opaque-device-installation-id",
  "capabilities": []
}
```

The production Sample Lib shape is already concrete:

```json
{
  "type": "hello_accepted",
  "protocol_version": "1",
  "server_id": "stable-server-installation-id",
  "authority_generation": "opaque-generation",
  "server_change_revision": 481220,
  "capabilities": {
    "pull": ["asset", "interval", "marker", "tag", "tag_attachment", "playlist", "playlist_item"],
    "mutations": ["interval.editor_state.replace"],
    "playlists": "canonical_v1",
    "analysis": [],
    "media": ["resource_uri", "content_hash", "range"]
  },
  "limits": {
    "pull_page_max": 500,
    "push_batch_max": 100,
    "tombstone_retention_seconds": 7776000,
    "mutation_replay_horizon_seconds": 31536000
  }
}
```

Capability truth is authoritative at runtime: **Android only offers offline mutation UI for operation names present in `capabilities.mutations` and only enables advisory feeds/media features actually advertised by hello.** The checked-in vocabulary may describe future operation families; it is not permission to queue them against a narrower production server.

No-overlap remains explicit HTTP 426 + `hello_incompatible`. Silent guessing/downgrade is forbidden.

### 4.2 Revision vocabulary — resolved in the wire contract, still lagging in thin UI/domain types

The production wire now uses the required separation:

```yaml
server_change_revision: integer-or-opaque-diagnostic-high-water-mark
entity_revision: opaque non-empty token
base_revision: opaque non-empty token, or explicit create sentinel
cursor: opaque string owned entirely by server
```

`entity_revision` / `base_revision` are no longer constrained to an integer in the checked-in v1 wire contract or Sample Lib mobile façade. The remaining mismatch is Android's thin `core/model/Domain.kt` projection, where pending/receipt revision fields are still `Long?`; those types MUST NOT define the durable journal or wire representation.

The client MUST NOT parse, increment, compare, sort, or construct an entity revision. Equality is the only portable operation.

A global `server_change_revision` MAY be monotonic for diagnostics/feed construction, but clients still MUST use the opaque cursor for continuation.

### 4.3 Pull request and page

Keep the existing shape, with optional reset metadata during snapshot recovery:

```json
{
  "type": "pull_request",
  "protocol_version": "1",
  "cursor": "opaque-or-null",
  "scopes": ["library"],
  "limit": 500
}
```

Normal response:

```json
{
  "type": "pull_response",
  "protocol_version": "1",
  "next_cursor": "opaque-next",
  "has_more": true,
  "changes": [
    {
      "entity_type": "interval",
      "entity_id": "interval_123",
      "revision": "f8c9...opaque...",
      "value": {"...": "bounded canonical projection"}
    }
  ],
  "tombstones": [
    {
      "entity_type": "playlist",
      "entity_id": "playlist_9",
      "revision": "opaque-delete-revision"
    }
  ],
  "server_change_revision": 481220
}
```

#### Pull invariants

1. A page is self-contained and bounded; `limit` is a client hint the server may clamp.
2. For a given cursor and stable server generation, replaying a page MUST be semantically safe. Duplicate change delivery is allowed; omission is not.
3. Android applies all changes/tombstones and stores `next_cursor` in **one local transaction**.
4. Android never persists `next_cursor` before page data.
5. Crash before commit => same cursor is requested again.
6. Crash after commit => `next_cursor` is already present; page is not required again.
7. Entity upsert/delete application is idempotent by stable ID + revision.
8. Tombstones have the same authority as ordinary changes. A missing entity in an incremental page is not deletion evidence.
9. Server retains changes/tombstones for a documented cursor horizon. If the cursor falls behind that horizon, it MUST fail explicitly as `cursor_expired`; it MUST NOT silently start "from now".

### 4.4 Cursor expiry and full snapshot reset

This is a required v1 recovery contract for large libraries.

Suggested error:

```json
{
  "type": "pull_cursor_expired",
  "error": {
    "code": "cursor_expired",
    "message": "cursor predates retained change history"
  },
  "reset_required": true,
  "authority_generation": "opaque-generation"
}
```

Recovery now uses `cursor = null` on the same pull endpoint. Sample Lib binds every signed cursor to `authority_generation`, pull scopes and a fixed entity-revision state; a generation/scope/integrity mismatch or mid-page authority drift fails explicitly as `cursor_expired` rather than silently rebasing.

Android MUST NOT delete all locally missing entities page by page. It stages/marks the snapshot generation and only prunes entities absent from the snapshot **after the final page commits**. A crash halfway through resumes/restarts the snapshot without turning the first half into false deletions.

If server implementation cannot provide a coherent paged snapshot yet, `cursor_expired` is a hard sync error rather than permission to guess.

### 4.5 Push request and receipts

The current batch model is acceptable if every mutation receives one authoritative receipt and batches are not falsely described as globally atomic:

```json
{
  "type": "push_request",
  "protocol_version": "1",
  "client_id": "android-dj-tools",
  "mutations": [
    {
      "mutation_id": "01K...stable-id",
      "entity_type": "interval",
      "entity_id": "interval_123",
      "base_revision": "f8c9...opaque...",
      "operation": "replace_editor_state",
      "payload": {"start_ms": 1000, "end_ms": 8000, "markers": []},
      "created_at": "2026-09-15T14:00:00Z",
      "provenance": {"source": "android_manual"}
    }
  ]
}
```

Receipt:

```json
{
  "mutation_id": "01K...stable-id",
  "outcome": "applied",
  "server_change_revision": 481221,
  "entity_revision": "c41a...opaque...",
  "canonical": {"optional": "bounded authoritative projection"}
}
```

#### Push invariants

- `mutation_id` is generated **before** durable journal insert and persists across process death.
- `(mutation_id, canonical mutation body)` becomes immutable once a server attempt may have occurred.
- Same ID + same canonical body returns/reconstructs the original receipt and never reapplies.
- Same ID + different canonical body is a deterministic `rejected`/conflict-class receipt such as `idempotency_key_reused`.
- `base_revision` is required for revisioned entities; create semantics need an explicit create sentinel/absence rule rather than overloading a normal revision value accidentally.
- `created_at` is diagnostic only and never conflict precedence.
- A response lost after commit is resolved by resending the **same** mutation envelope.
- Push batch replay is safe because individual IDs are authoritative. Server may return already-known receipts mixed with newly applied receipts.
- One mutation's conflict/rejection MUST NOT make another mutation appear applied without a receipt.

---

## 5. Android mutation journal state machine

The browser journal is the correct baseline, adapted for process death and WorkManager:

```text
local edit
   |
   | atomic local DB write succeeds
   v
PENDING (attempt_count=0)
   |
   | worker/UI sync claims record, persists attempt metadata
   v
APPLYING
   |
   +-- applied/no_op receipt --------------------> APPLIED (terminal)
   |
   +-- explicit validation rejection -----------> REJECTED (terminal/user-actionable)
   |
   +-- base/current conflict --------------------> CONFLICT (non-terminal until user resolves)
   |
   +-- timeout/socket reset/408/429/5xx --------> PENDING_RETRY
                                                     |
                                                     | exact same envelope
                                                     +----> APPLYING
```

### Required persisted fields

Android's current `PendingMutation` model is only an early UI/domain projection. The durable store needs at least:

```yaml
journal_id: local stable record id
mutation_id: server idempotency identity
profile_id: paired Sample Lib server profile
entity_type: protocol family
entity_id: canonical Sample Lib id
operation: bounded operation name
base_revision: opaque token
payload: complete canonical mutation payload
payload_digest: canonical-body hash
provenance: bounded structured provenance
state: pending | applying | pending_retry | conflict | rejected | applied | cancelled
created_at: diagnostic
updated_at: diagnostic
attempt_count: integer
last_attempt_at: diagnostic
last_error_class: bounded diagnostic
receipt: authoritative terminal/success receipt when known
conflict: bounded conflict DTO when present
supersedes_journal_id: optional intentional reapply lineage
```

### Coalescing rule

Before the first possible server attempt (`attempt_count == 0`), Android MAY replace/coalesce a local pending draft only when the mutation family explicitly permits it and the base revision is unchanged. After any possible server delivery, payload/body is frozen.

This makes rapid offline knob/metadata edits practical without weakening ambiguous-delivery safety.

### Process death

Persisted `applying` is replayable. Android may die after:

1. changing local state to `applying`;
2. sending the bytes;
3. server commit;
4. before receiving or persisting the receipt.

On restart, the only safe assumption is "delivery may have happened." Replay the exact same ID/body. Do not generate a replacement mutation.

---

## 6. WorkManager and wake scheduling

WorkManager is a scheduler, not a transaction manager.

### MUST

- every sync unit reads durable state at execution time;
- correctness must be identical if a worker starts late, is stopped, or never runs until the next app launch;
- use unique work per server profile to reduce duplicate local workers, but never depend on that uniqueness for server correctness;
- persist all progress before returning success from a worker;
- keep requests/pages bounded so an individual worker can stop between units.

### SHOULD

Wake sync from multiple cheap signals:

- app startup/resume;
- connectivity regained;
- explicit user refresh;
- successful pairing;
- optional WebSocket dirty hint;
- periodic WorkManager maintenance window;
- pending journal entry creation.

Use finite retry/backoff and then leave the durable journal pending for a later wake. Do not run a permanent polling foreground service solely to maintain sync.

Large user-initiated media downloads may use Android's foreground-transfer mechanisms when platform policy requires; ordinary metadata convergence should not.

---

## 7. WebSocket: wake hint, never replication log

Optional realtime message:

```json
{
  "type": "sync_hint",
  "profile_id": "server-profile",
  "scopes": ["intervals", "playlists"],
  "server_change_revision": 481222
}
```

Android reaction: coalesce hints and schedule a normal pull from its durable cursor.

Correctness requirements:

- missed hint: eventual pull still converges;
- duplicate hint: harmless;
- out-of-order hint: harmless;
- socket reconnect: no event replay needed for correctness;
- no mutation bodies or canonical state are trusted from the hint channel.

This keeps realtime UX without turning Android lifecycle management into a distributed-log consumer problem.

---

## 8. Tombstones and deletion

Every sync-visible deletion needs a tombstone containing at least stable entity type/id and an authoritative deletion revision/change position.

Server obligations:

- retain tombstones for at least the advertised cursor history horizon;
- never reuse canonical entity identity for a semantically different object;
- if a client cursor predates tombstone retention, return `cursor_expired`.

Android obligations:

- apply tombstones transactionally with the page cursor;
- retain local unresolved journal records even if the target receives a tombstone; convert the intent to `delete_vs_edit` conflict instead of silently dropping it;
- deleting an Android cached media file is local cache eviction, **not** a server deletion mutation.

---

## 9. Conflict classes and user actions

Retain the existing classes, but define behavior tightly:

| Class | Automatic behavior | User baseline |
| --- | --- | --- |
| `safe_fieldwise` | MAY merge only with a negotiated deterministic rule proving disjoint fields | inspect merged result |
| `ordered_collection` | no generic auto-merge | use server / intentionally reapply or domain-specific reorder tool |
| `range_overlap` | no generic auto-merge | compare cue/loop/range and choose/re-edit |
| `delete_vs_edit` | never resurrect automatically | accept deletion or intentionally create/reapply where domain allows |
| `incompatible_schema` | block mutation replay | upgrade/migrate before resolution |

Baseline conflict UI always offers the two proven operations where meaningful:

1. **Use server state** — cancel local conflict and load canonical state.
2. **Reapply local draft** — fetch current state/revision, create a **new mutation ID** against it, preserve lineage, and submit intentionally.

No timestamp last-write-wins path exists.

For playlist ordering, cues/loops/ranges, and destructive metadata changes, Android requests explicit domain semantics before any automatic merge is enabled.

---

## 10. Offline cues, loops, metadata, and playlists

"Offline-first" does not mean every current REST endpoint is silently journalable.

### Phase rule

An edit family becomes offline-capable only when Sample Lib advertises all of:

```yaml
stable_entity_identity: true
opaque_revision_precondition: true
idempotent_mutation_id: true
authoritative_receipt: true
explicit_conflict: true
bounded_canonical_readback: true
```

The existing interval editor-state mutation already satisfies the core pattern and should be the first real family.

Metadata fields can follow once their server mutation is revision-conditioned and idempotent. Canonical playlist and playlist-item identities now exist and are pull-visible, but membership/order writes should still wait for an explicit revisioned ordering mutation model; readable canonical identity does not by itself solve concurrent ordered-list mutation.

Android concedes that unsupported mutation families remain online-only even when the rest of the app works offline.

---

## 11. Advisory intelligence offline acceptance

Cached intelligence is **advisory evidence**, not canonical metadata. Android may cache and review candidate DTOs offline, including source/model/version/confidence/input revision.

Suggested flow:

```text
candidate cached locally
     |
     | user accepts offline
     v
ordinary mutation is durably journaled
  provenance.suggestion_id = candidate id
  provenance.source = bounded provider/model identity
  base_revision = candidate/input/current cached canonical revision
     |
     | UI: "Queued acceptance — not yet confirmed by Sample Lib"
     v
reconnect -> normal /v1/push
     |
     +-- applied/no_op -> canonical state updates; candidate decision may show accepted
     +-- conflict/stale -> require review/re-confirmation
     +-- rejected -> show server reason
```

Important invariants:

- Android MUST NOT mutate canonical-looking Track/Cue/Loop fields merely because a candidate was accepted locally.
- The queued mutation retains enough bounded provenance to survive eviction of the suggestion cache itself.
- A stale candidate is never auto-applied.
- If canonical input revision changed while offline, conflict/revalidation beats user-time timestamp precedence.
- Candidate rejection remains local feedback unless feedback synchronization is an explicit separate capability.
- Safety findings remain visible according to their own policy and are not hidden by accepting a musical candidate.

This is consistent with `contracts/intelligence/v1/suggestion-contract.yml` and the Sample Lab/Overtone negotiation.

---

## 12. Media cache and resume

Metadata sync and binary transfer should remain separate.

A sync entity carries a portable resource reference, e.g.:

```json
{
  "resource_uri": "asset://asset_123/original",
  "content_hash_sha256": "...",
  "size_bytes": 104857600,
  "media_type": "audio/flac",
  "range_supported": true
}
```

### Download algorithm

1. Resolve/fetch through an allow-listed Sample Lib resource endpoint; never persist a host filesystem path as identity.
2. Write to a profile-scoped temporary `.part` object.
3. Persist resume metadata: resource identity, expected hash/size, strong ETag/version if supplied, completed byte range.
4. On restart, if range support is advertised, request `Range: bytes=N-` with `If-Range`/equivalent validator.
5. If the validator changed, discard the partial and restart against the new immutable resource identity/version.
6. If range is unsupported, restart from zero rather than concatenating guesses.
7. Before promotion to `AVAILABLE`, verify total size where known and final SHA-256.
8. Atomic rename/promote the verified file into content-addressed cache.

`416 Range Not Satisfiable`, hash mismatch, or a resource changing under the same supposedly immutable identity are protocol/integrity events, not success.

Pinned playlist/download content survives ordinary cache eviction. Unpinned media may be evicted. Neither action changes Sample Lib ownership.

For bulk downloads Android SHOULD allow unmetered/charging constraints and user override rather than forcing all media through background sync.

---

## 13. Pairing, authentication, and server identity

Current integration code is appropriately conservative about remote origins: Overtone defaults to loopback and requires explicit opt-in for non-loopback Sample Lab URLs. Sample Lib's current development API must not simply be exposed to a LAN phone as if loopback trust transferred automatically.

### Proposed minimum pairing flow

```text
operator enables mobile pairing on Sample Lib/Sample Lab
        |
        +-- displays short-lived QR/code containing server identity + pairing nonce
        |
Android verifies user-visible server identity/fingerprint
        |
Android sends installation_id + pairing proof over authenticated transport
        |
server issues revocable per-installation credential
        |
Android stores credential in Android Keystore-backed storage
```

### MUST

- explicit user pairing; no unauthenticated LAN mutation surface;
- stable server identity, so a profile does not silently switch canonical authorities;
- per-installation/client revocation;
- credentials excluded from logs, cursors, mutation provenance, resource identity, screenshots/diagnostic exports;
- `401 auth_required` is distinct from offline/unavailable;
- revocation never deletes the local mutation journal; it blocks sending until re-paired or user chooses to discard/export intent.

### SHOULD

- HTTPS for any non-loopback/network deployment;
- pairing establishes/verifies the TLS/public-key identity (or another explicit authenticated server identity), especially where local self-signed deployment is supported;
- credentials are scoped narrowly to the paired Sample Lib profile.

Exact credential technology—mTLS device cert vs bearer device token—is an open negotiation point. The wire protocol should not bake secrets into DTO bodies merely to choose one early.

---

## 14. Local schema migration

Android already has `LOCAL_MIGRATION_REQUIRED` in its sync state; the protocol design should make that meaningful.

### Read projection

- version schema;
- migrate where cheap;
- otherwise drop/rebuild from cursor reset/full snapshot.

### Media cache

- content blobs are hash-addressed;
- index/schema may rebuild from manifests/blobs where feasible;
- partial transfer metadata may be discarded and redownloaded if incompatible.

### Mutation journal

MUST use explicit ordered migrations with validation and crash-safe transactionality.

If an unresolved journal record cannot be migrated without changing its server-observable canonical body:

1. stop mutation replay;
2. preserve the original bytes/record;
3. enter `LOCAL_MIGRATION_REQUIRED`;
4. offer bounded diagnostics/export/recovery tooling;
5. never silently drop, rewrite under the same `mutation_id`, or mark applied.

This is stricter than read-cache migration by design.

---

## 15. Large-library scaling

The protocol must scale by bounded work, not by assuming a giant initial JSON response.

### MUST

- bounded pull page and push batch sizes;
- stable IDs and deterministic page continuation;
- per-page transaction/cursor commit;
- media omitted from metadata pages except portable refs/hash/size;
- server-side filtering/scopes where they reduce unnecessary projections without changing authority;
- full resync paginated.

### SHOULD

- Android maintains local indexes for offline browse/search over cached projection fields;
- server remains canonical for fields not present locally and for complete search semantics;
- WorkManager yields between pages/batches so the OS can stop/reschedule work;
- diagnostics expose counts/lag rather than dumping full payloads.

A test library of at least 100k metadata entities should be used for bounded-memory/page-convergence qualification; it need not contain 100k binary audio files.

---

## 16. Failure and ambiguous-delivery matrix

| Event | Android behavior | Required server behavior |
| --- | --- | --- |
| offline before request | keep durable pending; no attempt | none |
| connection fails before known response | conservatively treat attempted mutation as possibly delivered once bytes may have left process | same-ID replay safe |
| server commits, response lost | retry same ID/base/body while within the advertised replay guarantee | return original receipt, no duplicate apply |
| unresolved attempted mutation ages beyond advertised replay horizon | stop automatic replay; query receipt/read back authoritative state and require explicit recovery if outcome remains unknown | never advertise a replay horizon longer than durable receipt/idempotency evidence actually supports |
| same ID, changed body | stop/rejected | deterministic idempotency collision |
| stale base | conflict | authoritative current revision + bounded conflict/readback data |
| HTTP 401 | `AUTH_REQUIRED`; preserve journal | no mutation applied under invalid auth |
| HTTP 426 | `SERVER_INCOMPATIBLE` | supported versions explicit |
| 408/429/5xx after mutation attempt | freeze body, finite backoff/retry exact envelope | receipt/idempotency boundary makes replay safe |
| process killed with row `APPLYING` | replay exact envelope on next wake | same-ID safe |
| process killed while applying pull page | local transaction rolls back or commits page+cursor together | cursor/page replay safe |
| duplicate pull page | idempotent upsert/tombstone application | stable identity/revision |
| cursor older than tombstone history | stop incremental sync, request reset | explicit `cursor_expired` |
| snapshot reset interrupted | resume/restart without pruning unseen entities | coherent snapshot generation |
| journal migration fails | `LOCAL_MIGRATION_REQUIRED`, preserve bytes | no server assumption |
| cached media partial + process death | resume by validator/range or restart | immutable identity + optional Range |
| final media hash mismatch | fail integrity, delete/quarantine partial | hash is authoritative |
| WebSocket missed | next normal wake/pull converges | hints not correctness channel |
| suggestion accepted offline then source revision changes | explicit conflict/stale review | revision-conditioned mutation |
| target deleted remotely while local edit queued | `delete_vs_edit` conflict | tombstone/current conflict data |
| two devices reorder same playlist | explicit ordered-collection conflict until domain semantics exist | no timestamp LWW |

---

## 17. Debuggability and support evidence

Offline correctness is hard to support unless state transitions are inspectable without leaking secrets.

Android SHOULD expose a debug-safe sync snapshot with:

```yaml
profile_id: non-secret local profile id
server_id_fingerprint: bounded fingerprint
protocol_version: "1"
sync_state: enum
last_successful_sync_at: diagnostic timestamp
last_pull_cursor_fingerprint: hash/prefix, not raw bearer-like token
server_change_revision: diagnostic
pending_count: integer
applying_count: integer
conflict_count: integer
rejected_count: integer
oldest_pending_age_ms: integer
last_error_class: bounded enum
last_error_http_status: optional
last_sync_run_id: random correlation id
media_partial_count: integer
media_integrity_failures: integer
```

Per-mutation diagnostics may expose mutation ID, entity type/ID, payload digest, attempt count, state, receipt outcome and error class. Do not dump credentials, auth headers, provider secrets, arbitrary server bodies, host filesystem paths, or complete user media metadata when unnecessary.

Every pull/push request SHOULD carry a non-authoritative trace/correlation ID for logs. It is never an idempotency identity.

---

## 18. Alternatives considered

### A. WebSocket-first replicated event stream

**Rejected as correctness foundation.** Android cannot guarantee continuous socket consumption. Durable catch-up would recreate cursors/log retention/compaction anyway. Keep WS only as wake optimization.

### B. Full snapshots on every sync

**Rejected as normal algorithm.** Simple but wasteful for large libraries, battery, radio and SQLite churn; deletion detection is fragile during interrupted snapshots. Retain full snapshot only for first sync/cursor-expiry recovery.

### C. Timestamp last-write-wins REST

**Rejected.** Device clocks are not a concurrency protocol; it silently destroys edits and is especially wrong for ordered playlists and cue/loop ranges.

### D. CRDTs everywhere

**Rejected for v1.** They add substantial domain and migration complexity while Sample Lib already has authoritative revisions/transactions. Specific future playlist/order semantics may adopt a proven CRDT/operation model, but generic metadata/cue/loop sync does not need it.

### E. Generic database replication / Couch-style sync

**Rejected.** It would leak storage topology into the product contract, duplicate Sample Lib domain validation, and make provider/render/resource boundaries harder to preserve. Android needs a bounded domain projection, not SQLite replication.

### F. Directly queue arbitrary existing Sample Lib REST calls

**Rejected.** Current APIs do not uniformly provide idempotency/revision receipts. Ambiguous mobile delivery would create duplicate/unknown writes. The sync mutation family is an explicit safer boundary.

### G. REST + cursor + journal

**Selected.** It composes already-proven pieces:

- Sample Lib atomic revision/idempotency transaction precedent;
- browser durable journal precedent;
- existing Android sync schema/provider seams;
- Overtone's authority and portable-resource boundaries;
- optional realtime without lifecycle dependence.

It requires the least new machinery while solving the actual failure modes.

---

## 19. Normative requirements

### MUST — server / Sample Lib

- remain canonical authority for synced library entities;
- negotiate protocol version explicitly and fail incompatibility explicitly;
- provide opaque cursor pull with bounded pages and tombstones;
- provide explicit cursor-expiry/reset behavior;
- provide stable entity IDs and opaque per-entity revision tokens;
- persist mutation-ID receipts atomically with each offline-capable mutation;
- advertise mutation receipt/idempotency replay retention truthfully and enforce at least the advertised horizon;
- return original receipt for exact replay and reject same-ID/different-body reuse within the advertised replay guarantee;
- advertise which mutation families/operations actually satisfy this contract;
- return explicit conflicts rather than timestamp LWW;
- retain tombstones/change history for a documented cursor horizon;
- expose portable media resource identity plus content hash; advertise byte-range support if real;
- require explicit network pairing/auth for mobile access;
- never require Android to know a Sample Lib host filesystem path.

### MUST — Android

- durably journal before claiming an offline edit is queued;
- never claim a queued mutation is canonical before receipt/pull confirmation;
- keep attempted mutation body immutable and reuse its mutation ID after ambiguity/process death;
- apply each pull page and next cursor atomically;
- treat cursors and entity revisions as opaque;
- preserve unresolved journal records through cache eviction and app upgrades;
- stop automatic replay on conflict/incompatible schema/auth barriers;
- use server state or a new mutation ID/current revision for intentional reapply;
- treat WebSockets and WorkManager as wake/scheduling mechanisms only;
- verify media hash before marking a download available;
- keep intelligence suggestions advisory until an ordinary mutation receives canonical confirmation.

### SHOULD

- provide receipt lookup for support/debug reconciliation;
- use stable snapshot generation IDs for full-reset paging;
- use finite exponential-ish retry bursts plus lifecycle/connectivity wakes;
- expose sanitized sync diagnostics and correlation IDs;
- support Range + validator resume for large media;
- support per-device credential revocation;
- keep binary transfer out of metadata pull pages;
- test at large metadata cardinality and under deterministic process/network fault injection.

### MAY

- coalesce never-attempted mutations when the family permits it and base revision is unchanged;
- add deterministic field-level merge for explicitly negotiated safe domains;
- add WebSocket wake hints;
- use foreground transfer UX for user-requested large downloads where Android policy requires it;
- use the implemented principal-scoped receipt-query endpoint for diagnostics/recovery while preserving exact same-ID/body replay as the correctness path.

---

## 20. Android demands on Sample Lib

1. **Generalize, don't pretend:** either implement a generic sync mutation ledger or explicitly expose only the idempotent families already backed by durable receipts.
2. **Opaque revisions:** accept that existing SHA-256 editor fingerprints are valid revisions; do not force integer entity revisions merely to match Android's current fixture type.
3. **Durable change feed:** supply cursor paging + tombstones + defined retention/expiry.
4. **Snapshot reset:** make first/full resync coherent and interruptible without false deletions.
5. **Per-mutation atomicity:** domain validation + canonical mutation + durable receipt in one server transaction.
6. **Canonical conflict evidence:** current revision and bounded current state/fetch reference sufficient for user reconciliation.
7. **Portable media:** stable resource identity, hash, size and real resume capability advertisement.
8. **Mobile trust boundary:** explicit pairing, authenticated requests and revocation suitable for LAN/VPN deployment.
9. **Stable playlist semantics before offline reorder:** stable identities and a conflict-safe membership/order mutation contract.
10. **Capability truthfulness:** provider/intelligence availability must be discovered, not inferred from Android fixture entries.

---

## 21. Android concessions

Android can keep v1 small by accepting all of the following:

- no cloud service requirement;
- no generic offline write support for server mutation families that lack receipts/revisions;
- no exactly-once transport claim—**at-least-once delivery + idempotent mutation identity** is enough;
- no requirement to interpret cursor or revision contents;
- server may clamp page/batch sizes;
- WebSocket may be absent or lossy;
- full resync is acceptable after explicit cursor expiry;
- no automatic playlist/range merge until domain rules are proven;
- existing Sample Lib resource routes may carry media if they add the required hash/resume semantics—no duplicate media API is demanded;
- pairing credential format can remain implementation-specific behind a stable auth failure/identity contract;
- provider implementation names need not leak into the core sync protocol.

---

## 22. Required test vectors before protocol acceptance

The synthesis/implementation lane should turn these into executable fake-server + Android client contract tests.

1. hello selects highest mutually supported version.
2. hello with no overlap returns 426/incompatible; client does not guess.
3. server advertises only editor-state offline family; Android does not queue unsupported playlist write.
4. revision token is a SHA-256 string and round-trips without parsing.
5. pull page commits changes + tombstones + cursor atomically.
6. process death before pull transaction commit causes same cursor replay and no state loss.
7. process death after pull transaction commit resumes from stored next cursor.
8. duplicate pull page is harmless.
9. tombstone removes cached projection but converts queued target edit to `delete_vs_edit` conflict.
10. cursor expiry returns explicit reset requirement.
11. paged snapshot interruption does not delete entities absent from incomplete pages.
12. 100k-entity fixture converges with bounded page memory and no giant response.
13. offline edit is not shown as server-confirmed before journal insert/receipt.
14. process death with mutation `APPLYING` replays same ID/base/body.
15. server commits mutation, drops response, retry returns original receipt and creates exactly one logical edit.
16. same mutation ID with different body is rejected deterministically.
17. stale base returns conflict with current revision/readback data.
18. `Use server` cancels local conflict without server rewrite.
19. `Reapply local` generates new mutation ID against fetched current revision.
20. 408/429/5xx after possible attempt freezes body and retries exact envelope only.
21. auth revocation preserves journal and enters `AUTH_REQUIRED`.
22. re-pair resumes exact unresolved mutation identity rather than replacing it.
23. app upgrade migrates unresolved journal transactionally.
24. impossible journal migration enters `LOCAL_MIGRATION_REQUIRED` without deleting intent.
25. read-cache schema reset cannot delete journal rows.
26. cache pressure evicts unpinned media but not unresolved mutations.
27. partial media resumes with valid Range/validator and final hash passes.
28. changed validator restarts partial download rather than concatenating versions.
29. final media hash mismatch never promotes file to available.
30. missed WebSocket hint still converges on periodic/app-resume pull.
31. duplicate/out-of-order hints coalesce safely.
32. offline cached suggestion acceptance creates a normal queued mutation with candidate provenance.
33. canonical revision changes before suggestion acceptance reaches server -> explicit conflict/stale review.
34. candidate cache eviction does not make already-journaled acceptance unreplayable.
35. concurrent devices editing same cue produce one apply and one explicit stale-base conflict, not timestamp LWW.
36. concurrent playlist reorder remains ordered-collection conflict unless a future negotiated merge operation exists.
37. push response can contain a mix of replayed original receipts and newly applied receipts.
38. one rejected mutation in a batch does not cause sibling mutations to lack individual receipts/status.
39. diagnostics expose cursor/server fingerprints and mutation IDs/digests without credentials or raw secrets.
40. fixture-only provider availability does not cause protocol hello to advertise an actually unavailable backend.
41. live hello advertising only `interval.editor_state.replace` disables offline asset/tag/playlist/intelligence writes even if checked-in contracts describe future families.
42. canonical playlist and playlist-item pull survives process death/pagination and remains locally browsable/pinnable while edit/reorder controls remain capability-disabled.
43. signed cursor tampering returns explicit `cursor_expired`; the client never treats an invalid cursor as an empty delta.
44. a receipt created for one paired principal is not recoverable by a differently paired principal that knows the mutation ID.
45. measure encoded cursor bytes at 1k/10k/100k entities, including paged incremental `state` + `target_state`; fail qualification before transport/server limits are approached.
46. mutate canonical state between snapshot pages and between incremental pages; both paths fail closed and restart without pruning unseen local rows.
47. absence of `/v1/sync/events` on the live Sample Lib server leaves correctness unchanged: lifecycle/connectivity/manual wakes still converge through durable pull.
48. mutation replay retention boundary: a receipt remains recoverable and exact same-ID/body replay is deduplicated through the final instant of the server-advertised horizon; once an unresolved attempted mutation is older than that guaranteed horizon, Android does not auto-resend and instead enters explicit outcome-reconciliation/recovery. A server advertising 31,536,000 seconds while failing to retain/deduplicate for that duration fails conformance.
49. deletion-retention boundary: create entity A, let client C1 observe A, delete A, and verify C1 receives a tombstone; then repeat with client C2 whose retained cursor predates/omits A or whose cursor expires before observing the deletion. The server must either deliver the historical tombstone from a durable change feed or return explicit `cursor_expired`/snapshot-reset semantics. It must never return a successful empty incremental page that leaves stale A locally.

---

## 23. Open negotiation points

These are the items synthesis should resolve explicitly rather than bury in implementation:

1. **Revision schema — resolved at wire level:** keep opaque string/token revisions. Remaining work is to prevent fixture-era `Long?` UI/domain fields from leaking into the durable journal.
2. **Server change-feed owner — resolved for v1 implementation:** Sample Lib now owns the mobile sync façade directly; keep Sample Lab/Overtone as consumers/orchestrators rather than new sync authorities.
3. **Cursor representation and scale:** the current signed cursor contains the complete entity→revision state map, and paged incremental cursors can contain both baseline `state` and `target_state`. This is correct but O(N) in library cardinality and can make cursor bytes, signing/JSON work and request headers/bodies very large. Decide whether v1 needs a server-side durable change log / compact cursor before large-library release.
4. **Snapshot reset encoding — resolved:** `POST /v1/sync/pull` with `cursor:null` begins a snapshot; authority drift during paging fails closed as `cursor_expired` and restarts the snapshot.
5. **Create base revision:** explicit `null`/sentinel vs a special token. Avoid conflating a valid revision value with "does not exist" when create-capable mutation families are added.
6. **Mutation family expansion:** retain bounded domain operation names and add each family only with durable receipt/revision semantics; the production server currently negotiates only `interval.editor_state.replace`.
7. **Receipt lookup — resolved surface, open retention conformance:** `/v1/sync/receipts/{mutation_id}` exists and is principal-scoped; retain it as recovery/debugging support, not as permission to mutate a frozen envelope. Current Sample Lib advertises a 31,536,000-second mutation replay horizon without age-enforcing receipt storage/lookup, so implementation must either enforce that guarantee or advertise the shorter truth before Android can rely on it operationally.
8. **Push ordering:** whether mutations in one batch are processed sequentially in request order or only individually; dependencies between mutations should be explicit rather than inferred.
9. **Playlist mutation model:** canonical playlist/item identities now exist and are pull-visible; negotiate revisioned membership/order mutation semantics before enabling offline playlist edits or reorder.
10. **Media resume endpoint:** extend existing resource routes with Range/ETag/hash semantics vs add a dedicated download surface.
11. **Pairing credential:** per-device bearer token vs mTLS/device certificate; both must bind to stable server identity and support revocation.
12. **Background limits:** negotiated server backoff hints (`Retry-After`) and Android batching defaults; correctness must remain independent of exact timing.
13. **Suggestion feedback:** keep rejection local in v1 unless a separate feedback mutation family is intentionally specified.
14. **Deletion history representation:** current Sample Lib tombstones are inferred from cursor-carried prior identity. Decide whether production v1 introduces a durable change/tombstone journal before broad offline use, or explicitly narrows retention guarantees and requires snapshot reset when historical deletion evidence cannot be reconstructed.

---

## 24. Proposed synthesis decision

Adopt **REST + opaque cursor + durable mutation journal + authoritative idempotency receipts** as the v1 correctness core, with optional WebSocket wake hints and separate hash-verified/resumable media transfer.

Before calling the current v1 implementation production-ready for offline Android dogfooding at scale, close the following acceptance blockers:

```yaml
blocking:
  - Android canonical projection/cursor store transactionally commits page data + next_cursor using opaque wire revisions; the existing mutation journal remains the durable intent path
  - existing durable mutation journal is wired into the production sync provider and its migration/replay invariants remain covered by process-death tests
  - Android gates offline UI strictly from live hello mutation capabilities
  - Sample Lib exposes advisory intelligence through a durable pull/cache contract before offline candidate review is called end-to-end
  - playlist offline writes remain disabled until a revisioned receipt-backed ordered-collection mutation family exists
  - 100k-entity cursor/snapshot/incremental reset behavior is load-qualified
  - credential revocation/rotation/expiry and authenticated non-loopback transport policy are operationally defined
```

This protocol is intentionally conservative. It does not require CRDTs, a cloud service, permanent sockets, database replication, or exactly-once transport. It does require the server and phone to preserve the few durable identities that make ambiguous mobile execution recoverable: **server identity, cursor, canonical entity identity/revision, mutation identity/body, authoritative receipt, and media content hash**.
