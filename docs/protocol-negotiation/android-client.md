# Sync protocol negotiation — Android / offline-first advocate

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-ANDROID-CLIENT-20260915`

Status: **decision proposal, not unilateral protocol victory**

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

The existing `contracts/sync/v1/*` direction is fundamentally right, but Android requests several changes before calling the wire contract production-ready:

1. **Entity revisions MUST be opaque tokens, not integers.** Current Android schema/code model revisions as integers/`Long`; the real Sample Lib atomic editor contract already uses a SHA-256 revision fingerprint. A global monotonic server change sequence may be numeric, but it is distinct from an entity revision token.
2. **Offline mutation capability MUST be advertised per entity/operation family.** Sample Lib currently has strong durable idempotency for the atomic interval editor route, but many existing POST/PATCH/DELETE routes and the Overtone bridge deliberately do not blindly retry ambiguous stateful calls. Android MUST NOT assume the generic mutation envelope makes an unsupported server route idempotent.
3. **Pull cursor application MUST be atomic locally:** apply a page's changes/tombstones and store its `next_cursor` in the same local database transaction.
4. **Cursor expiry/reset semantics MUST be explicit.** Tombstone retention is necessarily finite; clients older than that horizon need a safe, paged snapshot reset rather than silent data resurrection.
5. **Media references MUST support immutable identity, final hash verification, and byte-range resume when advertised.** Durable identity is a Sample Lib resource ID/URI plus content hash, not a host path or temporary URL.
6. **The Android mutation journal requires its own non-destructive schema migration contract.** Disposable read/media caches may be rebuilt; unresolved user intent may not be dropped because an app upgrade changed local schema.
7. **Pairing/authentication MUST be part of the network deployment contract.** Current Sample Lib and Overtone integration defaults are intentionally loopback-oriented; exposing that trust model unchanged to a phone on a LAN is not acceptable.

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
  - currently defines entity/base revisions as integers. This is the principal wire mismatch found in this review.
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
POST /v1/hello
POST /v1/pull
POST /v1/push

GET/HEAD <advertised Sample Lib resource endpoint>   # media, with optional Range
WS       <optional advertised wake endpoint>         # hint only
```

A separate receipt-query endpoint is useful for diagnostics but not required for correctness if exact push replay is guaranteed. If provided, prefer:

```text
GET /v1/receipts/{mutation_id}
```

and make it authorization-scoped to the paired installation/client.

### 4.1 Hello / capability negotiation

Request, extending the current shape:

```json
{
  "type": "hello_request",
  "protocol_versions": ["1"],
  "client_id": "android-dj-tools",
  "installation_id": "opaque-device-installation-id",
  "capabilities": [
    "pull.cursor.v1",
    "push.receipts.v1",
    "media.range.v1",
    "wake.websocket.v1"
  ]
}
```

Accepted response should distinguish server-wide transport capabilities from operation families:

```json
{
  "type": "hello_accepted",
  "protocol_version": "1",
  "server_id": "stable-server-installation-id",
  "server_change_revision": 481220,
  "capabilities": ["pull.cursor.v1", "push.receipts.v1"],
  "mutation_families": {
    "interval.editor_state": {
      "operations": ["replace"],
      "idempotent_receipts": true,
      "revision_kind": "opaque"
    },
    "playlist.membership": {
      "operations": [],
      "idempotent_receipts": false
    }
  },
  "limits": {
    "pull_page_max": 1000,
    "push_batch_max": 100
  }
}
```

The exact encoding of capabilities remains negotiable. The invariant does not: **Android only offers offline mutation UI for families the server explicitly declares receipt/idempotency support for.** Unsupported writes may remain online-only or unavailable.

No-overlap remains explicit HTTP 426 + `hello_incompatible`. Silent guessing/downgrade is forbidden.

### 4.2 Revision vocabulary — requested correction

Use separate concepts:

```yaml
server_change_revision: integer-or-opaque-diagnostic-high-water-mark
entity_revision: opaque non-empty token
base_revision: opaque non-empty token, or explicit create sentinel
cursor: opaque string owned entirely by server
```

`entity_revision` / `base_revision` MUST NOT be constrained to an integer. The existing Sample Lib editor revision is a 64-hex fingerprint and is already a valid, production-grounded optimistic-concurrency token.

The client MUST NOT parse, increment, compare, sort, or construct an entity revision. Equality is the only portable operation.

A global `server_change_revision` MAY be monotonic for diagnostics/feed construction, but clients still MUST use the opaque cursor for continuation.

### 4.3 Pull request and page

Keep the existing shape, with optional reset metadata during snapshot recovery:

```json
{
  "type": "pull_request",
  "protocol_version": "1",
  "cursor": "opaque-or-null",
  "scopes": ["tracks", "intervals", "playlists", "analysis_suggestions"],
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
  "server_generation": "opaque-generation"
}
```

Recovery uses `cursor = null` (or an explicitly negotiated snapshot endpoint) and paginates a coherent snapshot. The server should return a stable `snapshot_id/server_generation` on every page and mark final completion.

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

Metadata fields can follow once their server mutation is revision-conditioned and idempotent. Playlist membership/order should wait for a stable canonical playlist identity and an explicit ordering mutation model; current crate compatibility may serve browse/interchange without pretending generic ordered-list merge is solved.

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
| server commits, response lost | retry same ID/base/body | return original receipt, no duplicate apply |
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
- return original receipt for exact replay and reject same-ID/different-body reuse;
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
- add a receipt-query endpoint even though exact push replay remains the correctness path.

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

---

## 23. Open negotiation points

These are the items synthesis should resolve explicitly rather than bury in implementation:

1. **Revision schema:** change v1 `revision` from integer to opaque string/token now, or version the correction before any real client ships. Android advocates correcting it now.
2. **Server change-feed owner:** implement directly in Sample Lib or behind a thin Sample Lab facade. Android only requires that Sample Lib remains authority and the cursor/receipts are durable across facade restarts.
3. **Cursor retention horizon:** exact duration/count policy and how the server advertises it.
4. **Snapshot reset encoding:** reuse `POST /v1/pull` with `cursor:null` plus snapshot generation vs introduce a dedicated snapshot endpoint.
5. **Create base revision:** explicit `null`/sentinel vs a special token. Avoid conflating a valid revision value with "does not exist".
6. **Mutation family naming:** domain operation names such as `interval.editor_state/replace` vs generic CRUD. Android prefers bounded domain operations because they preserve atomic invariants.
7. **Receipt lookup endpoint:** optional for correctness, valuable for support and server-side mediation.
8. **Push ordering:** whether mutations in one batch are processed sequentially in request order or only individually; dependencies between mutations should be explicit rather than inferred.
9. **Playlist canonical model:** crates as transitional projection vs stable playlist entity with membership/order revisions.
10. **Media resume endpoint:** extend existing resource routes with Range/ETag/hash semantics vs add a dedicated download surface.
11. **Pairing credential:** per-device bearer token vs mTLS/device certificate; both must bind to stable server identity and support revocation.
12. **Background limits:** negotiated server backoff hints (`Retry-After`) and Android batching defaults; correctness must remain independent of exact timing.
13. **Suggestion feedback:** keep rejection local in v1 unless a separate feedback mutation family is intentionally specified.

---

## 24. Proposed synthesis decision

Adopt **REST + opaque cursor + durable mutation journal + authoritative idempotency receipts** as the v1 correctness core, with optional WebSocket wake hints and separate hash-verified/resumable media transfer.

Before freezing the current schema, make the following acceptance-blocking corrections:

```yaml
blocking:
  - entity/base revisions become opaque tokens, not integer-only
  - mutation capability is explicit per family/operation
  - cursor_expired + coherent paged reset semantics are specified
  - pull page + next_cursor atomicity is normative
  - non-destructive mutation-journal migration is normative
  - network pairing/auth/server identity is specified
  - media hash/resume semantics are specified or explicitly deferred with restart-only behavior
```

This protocol is intentionally conservative. It does not require CRDTs, a cloud service, permanent sockets, database replication, or exactly-once transport. It does require the server and phone to preserve the few durable identities that make ambiguous mobile execution recoverable: **server identity, cursor, canonical entity identity/revision, mutation identity/body, authoritative receipt, and media content hash**.
