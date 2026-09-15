# Sample Lib authority negotiation for Android DJ sync

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260915`

Status: **defensible negotiation position**. This document is intentionally an authority/integrity contract, not a production patch. It was derived from the current `androidjtools` sync/intelligence contracts and the actual Sample Lib implementation under `/home/user/code/sample-lab/third_party/sample-lib`, including its schema, editor-state transaction, DJXML adapter, provider jobs and Sample Intelligence boundary.

## 1. Position

Sample Lib must remain the canonical metadata authority. Android may cache canonical projections, prepare edits offline, and submit durable mutation intents; Sample Intelligence, Demucs, transcription/ASR services, MIR providers and other analysis systems may produce evidence and suggestions; none of those systems may independently commit authoritative asset/interval/marker/tag/playlist metadata.

The core rule is:

> evidence systems propose; Sample Lib validates and commits; Android reviews and synchronizes the committed result.

The current Android sync v1 fixture has the right broad shape—negotiation, cursor pull, idempotent push and explicit conflicts—but it is not yet faithful to the real authority semantics. In particular, its integer revisions and generic CRUD model must not be frozen as a legacy protocol. The implementation should repair the still-unshipped v1 contract in place rather than ship an integer-revision v1 and immediately add a v2.

The smallest justified new wire surface is a **narrow, authenticated sync facade** over Sample Lib, not a second metadata service and not a mobile mirror of every existing REST endpoint:

- `POST /v1/sync/hello`
- `POST /v1/sync/pull`
- `POST /v1/sync/push`
- `GET /v1/sync/receipts/{mutation_id}` for recovery/debugging, subject to the same principal scope
- optional `WS /v1/sync/events` carrying wake-up hints only
- existing `/v1/resources/{resource_uri}` or an equivalent authenticated media resolver for bytes

The existing public API remains useful for local tooling and one-shot operations. The sync facade exists because durable offline correctness requires semantics that ordinary CRUD, search pagination and physical deletes do not currently provide.

## 2. Current implementation facts that constrain the protocol

These are not theoretical concerns; they are observable in the current repository.

### 2.1 Stable identities exist, but revision semantics are uneven

Sample Lib already has stable opaque IDs for core rows. `assets.id`, `intervals.id`, `markers.id`, `tags.id`, `asset_files.id` and analysis-run IDs are stored as text primary keys. Asset ingest additionally enforces `content_hash_sha256 UNIQUE NOT NULL`, and canonical original-file integrity is checked against that content hash.

The interval editor is the strongest existing mutation primitive and should be reused as the semantic model for sync writes:

- `GET /v1/intervals/{id}/editor-state` returns interval state, the complete marker set and a 64-hex fingerprint revision.
- `PATCH /v1/intervals/{id}/editor-state` requires a UUID `mutation_id` and exact 64-hex `base_revision`.
- it acquires `BEGIN IMMEDIATE` before the revision read;
- a matching replay of the same mutation returns the stored result;
- mutation-ID reuse with a different body, interval or base revision is a `409` conflict;
- stale state is a `409` containing current revision/current state;
- unknown persisted marker IDs are rejected;
- new markers may omit `id`, in which case Sample Lib allocates canonical `marker_*` IDs;
- omitted old markers are deleted;
- interval bounds, marker replacement, human annotation and the idempotency receipt commit atomically.

That is already a valid authority pattern. The wire protocol should generalize it; it should not replace it with numeric revisions.

By contrast, ordinary asset update, interval CRUD, marker CRUD and tag attachment currently do not require base revisions or durable mutation IDs. They use transactions for local integrity, but not offline optimistic concurrency.

### 2.2 Deletion is currently physical

Current deletion paths physically delete assets/intervals/markers and rely on foreign-key cascades where applicable. There is no canonical tombstone/change-log table. A disconnected Android client therefore cannot distinguish “not in this page” from “deleted while offline.” A durable delta feed requires explicit tombstones retained long enough for reconnecting clients.

### 2.3 Search pagination is not sync pagination

Asset/interval search uses `LIMIT/OFFSET` and mutable sort keys. That is suitable for interactive search, not for a durable change cursor. A sync client must never use search offsets as a change-watermark.

### 2.4 There is no mobile authorization boundary today

The current FastAPI surface does not implement an inbound bearer/OAuth/API-key authorization boundary for core routes, and CORS is broadly permissive. That is acceptable only in its current local-first deployment assumptions. A phone-facing LAN/VPN sync API must be authenticated and must not simply expose the present API unchanged.

### 2.5 There is no canonical playlist entity today

The database contains tags and tag attachments, but no `playlists` table and no ordered playlist-item model. The DJXML adapter maps playlist/crate paths to `namespace="crate"` tags. That is useful interoperability, not sufficient canonical playlist identity:

- tag identity is currently `(namespace, value)`;
- renaming a crate path changes that semantic identity;
- membership is unordered;
- nested path text is not a stable playlist ID;
- Android EPIC-08 requires create/rename/reorder/move/delete behavior.

Do **not** prolong crate tags into the canonical ordered playlist model. Add a proper playlist aggregate before advertising offline playlist writes.

### 2.6 Provider outputs are provenance-rich but raw outputs are not mobile-safe DTOs

Sample Lib persists `analysis_runs` with tool name/version, operation, input hash, config, output and completion status. The provider lane currently has concrete integrations for:

- Demucs stem separation;
- Essentia low-level MIR;
- Basic Pitch musical transcription.

Provider jobs are durable status records but are deliberately **not** assumed idempotent: interrupted heavy jobs are failed on restart rather than blindly replayed.

Raw normalized provider results may still contain local paths or worker details. The Sample Intelligence adapters demonstrate the correct export boundary by projecting path-free evidence:

- Demucs evidence retains registered child asset IDs and `asset://...` references, not stem filesystem paths;
- Basic Pitch evidence retains portable artifact URIs and explicitly refuses to fabricate symbolic note events that its parent contract did not actually expose;
- Essentia evidence exposes a bounded normalized schema with provider/schema version and validated features;
- source/tool/operation drift fails closed;
- evidence receives deterministic fingerprints/IDs.

There is **no verified canonical speech-ASR provider** in the current Sample Lab checkout. The Android protocol may define a future `speech.transcript` advisory capability, but must not claim that a specific ASR stack is presently authoritative or available.

## 3. Authority model

### 3.1 Canonical entities

The first sync projection should cover these authoritative entity families:

| Entity | Canonical identity | Revision unit | Android write stance |
| --- | --- | --- | --- |
| `asset` | Sample Lib `asset_*`; immutable content hash separately | asset metadata projection | initially narrow metadata patch only; no remote path ingest |
| `interval` | Sample Lib `int_*` | interval row or editor aggregate | revision-checked writes only |
| `marker` | Sample Lib `marker_*` | preferably interval editor aggregate | create/update/delete through editor aggregate first |
| `tag` | Sample Lib `tag_*`, semantic uniqueness `(namespace,value)` | tag row | read; ensure/attach through guarded operation |
| `tag_attachment` | deterministic target+tag relation | attachment relation | attach/detach with receipt/tombstone |
| `playlist` | new Sample Lib ID | playlist aggregate | only after migration below |
| `playlist_item` | new stable item ID | item/order under playlist aggregate | only after migration below |
| `analysis_suggestion` | immutable suggestion/evidence ID | immutable advisory record | never direct canonical metadata write |

Asset bytes and derived media are resources, not mutable metadata entities. Analysis jobs/runs are provenance entities, not alternate asset metadata authorities.

### 3.2 Revision tokens are opaque strings

Wire-level `revision` and `base_revision` must be **opaque non-empty strings**. Android must compare for equality only. It must not increment, order or parse them.

A SHA-256 canonical fingerprint, as already used by interval editor state, is a suitable implementation. Other entity families may use a different token later without changing the wire contract.

A separate internal monotonic change sequence may back cursors, but it must not be confused with an entity revision.

Therefore the current Android fixture definitions using integer `revision`, `server_revision`, `PendingMutation.baseRevision: Long?`, `MutationReceipt.authoritativeRevision: Long?` and suggestion `inputRevision: Long?` should be corrected before the real adapter is implemented.

## 4. Proposed sync surface

### 4.1 `POST /v1/sync/hello`

Purpose: negotiate protocol/schema compatibility, bind the client to one Sample Lib authority generation, and truthfully advertise capabilities.

Request:

```json
{
  "type": "hello_request",
  "protocol_versions": ["1"],
  "client_id": "android-dj-tools",
  "installation_id": "device-installation-id",
  "capabilities": [
    "cursor_pull",
    "idempotent_push",
    "explicit_conflicts",
    "analysis_suggestions"
  ]
}
```

Accepted response:

```json
{
  "type": "hello_accepted",
  "protocol_version": "1",
  "server_id": "sample-lib-instance-id",
  "authority_generation": "opaque-generation-id",
  "capabilities": {
    "pull": ["asset", "interval", "marker", "tag", "tag_attachment"],
    "mutations": [
      "interval.editor_state.replace",
      "asset.metadata.patch",
      "tag.ensure_attach",
      "tag.detach"
    ],
    "playlists": "unsupported_until_canonical_playlist_migration",
    "analysis": ["suggestions.read"],
    "media": ["resource_uri", "range"]
  },
  "limits": {
    "pull_page_max": 1000,
    "push_batch_max": 100,
    "tombstone_retention_seconds": 7776000,
    "mutation_replay_horizon_seconds": 31536000
  }
}
```

No-overlap is an explicit incompatible response; there is no silent downgrade. `authority_generation` changes when a database is replaced/restored in a way that invalidates cursors. Android must treat a generation mismatch as a required bootstrap, never as an empty delta.

Capabilities are per operation family, not a generic `write=true`. A server that has editor-state idempotency but has not yet revision-wrapped asset metadata must advertise exactly that distinction.

### 4.2 `POST /v1/sync/pull`

Request:

```json
{
  "type": "pull_request",
  "protocol_version": "1",
  "cursor": null,
  "scopes": ["library", "analysis_suggestions"],
  "limit": 500
}
```

Response:

```json
{
  "type": "pull_response",
  "protocol_version": "1",
  "mode": "snapshot",
  "next_cursor": "opaque-cursor",
  "has_more": true,
  "changes": [],
  "tombstones": [],
  "authority_generation": "opaque-generation-id"
}
```

Cursor requirements:

1. Cursor content is opaque to the client and integrity-protected by the server.
2. It binds at least authority generation, protocol projection version, requested scope and internal high-water position.
3. `cursor=null` begins a bootstrap snapshot. Snapshot pages use immutable entity IDs for deterministic keyset traversal, not offsets.
4. The snapshot captures an incremental high-water before traversal. Mutations racing the snapshot may be observed in the snapshot and again in the following change feed; duplicates are safe because entity revisions are idempotent. Changes that fall lexically before an already-scanned snapshot key are still recovered from the post-snapshot change feed.
5. The final snapshot cursor transitions to incremental mode from the captured high-water.
6. Expired cursors return an explicit `cursor_expired` result requiring a new snapshot. They never silently become “no changes.”
7. A client only prunes local rows not seen in a reset snapshot after the final snapshot page commits successfully.

Suggested change DTO:

```json
{
  "entity_type": "interval",
  "entity_id": "int_...",
  "revision": "opaque-revision",
  "schema": "sample-lib.interval.sync/v1",
  "value": {}
}
```

Suggested tombstone DTO:

```json
{
  "entity_type": "marker",
  "entity_id": "marker_...",
  "revision": "opaque-delete-revision",
  "deleted_at": "2026-09-15T14:00:00Z"
}
```

The feed is a convergence protocol, not an audit log. Internal events may be compacted as long as every still-valid cursor converges to the same authoritative state and deletes remain visible as tombstones.

### 4.3 `POST /v1/sync/push`

Each mutation requires:

```json
{
  "mutation_id": "uuid-or-other-stable-client-idempotency-key",
  "entity_type": "interval",
  "entity_id": "int_...",
  "base_revision": "opaque-revision",
  "operation": "interval.editor_state.replace",
  "payload": {},
  "created_at": "2026-09-15T14:00:00Z",
  "provenance": {}
}
```

Semantics:

- every accepted mutation executes under a writer transaction that checks the current authoritative revision after obtaining the appropriate write reservation;
- canonical changes, sync change/outbox rows, annotations/provenance and the mutation receipt commit in the same transaction;
- the same `mutation_id` plus the same canonical request digest returns the original receipt/result;
- the same `mutation_id` with a different digest is rejected as idempotency-key reuse;
- mutation receipts are scoped to the authenticated principal/installation so a principal cannot retrieve another principal's response by guessing an ID;
- `created_at` is diagnostic/admission metadata, never the concurrency authority;
- batch order is explicit. v1 should process mutations serially in request order and return one receipt per mutation; it must not imply all-or-nothing cross-entity batch atomicity unless a specific operation declares it.

Receipt outcomes remain useful and should be retained:

- `applied` — canonical state changed;
- `no_op` — intent was already satisfied without a new authoritative change;
- `rejected` — invalid/unauthorized/unsupported intent, safe to surface as terminal until edited;
- `conflict` — base revision no longer matches and current authority state is supplied or fetchable.

A conflict DTO should carry the **opaque** base/current revision, current canonical value where bounded, a merge class and a stable conflict code. It must not claim that generic field-wise merge is always safe.

### 4.4 `GET /v1/sync/receipts/{mutation_id}`

This is a recovery endpoint, not a second mutation path. It allows a client that lost a response after the server committed to recover the durable receipt without resubmitting a potentially expensive request. It returns only receipts owned by the authenticated principal/installation.

### 4.5 Optional `WS /v1/sync/events`

The socket is only a wake-up optimization. Example:

```json
{
  "type": "sync.changed.v1",
  "scopes": ["library"],
  "hint": "pull"
}
```

No canonical entity payload on the socket is authoritative. Loss, duplication or reordering of WebSocket messages must not affect correctness; Android always reconciles via `pull`.

## 5. Canonical sync projections

Sync DTOs must be path-free and intentionally narrower than internal DB rows.

### 5.1 Asset DTO

Minimum useful projection:

```yaml
schema: sample-lib.asset.sync/v1
id: asset_...
revision: <opaque>
content_hash_sha256: <64hex>
title: <string|null>
artist: <string|null>
collection: <string|null>
rating: <1..5|null>
quality_score: <0..1|null>
duration_ms: <int>
sample_rate: <int|null>
channels: <int|null>
source_kind: <string>
rights_scope: <string>
resources:
  - resource_uri: asset://asset_...
    role: original
    content_hash_sha256: <64hex>
    mime_type: <string|null>
    byte_size: <int|null>
    fetch_path: /v1/resources/asset%3A%2F%2Fasset_...
```

`storage_relpath`, arbitrary host paths and worker paths are forbidden in mobile sync DTOs. Stable identity is `(resource_uri, content_hash)`; fetch URLs are transport locators and may change or expire.

Initial Android asset mutation should be restricted to the fields the current public API already treats as human-curation fields: `collection`, `rating`, `quality_score`. Broader title/artist/rights edits should become writable only after their authoritative validation/provenance policy is explicit.

Asset creation/ingest is **not** a generic sync `create` in v1. The current ingest API takes a server-side file path; exposing that to Android would leak/extend a filesystem authority model. A future upload protocol can be designed separately.

### 5.2 Interval DTO

Use the existing stable interval ID and expose the canonical time bounds plus DJ-relevant metadata required by the app. Do not expose `storage_relpath` pulled through search joins.

The highest-integrity initial mutation is `interval.editor_state.replace`, whose payload mirrors the existing editor-state contract:

```yaml
start_ms: 12000
end_ms: 20000
markers:
  - id: marker_existing     # omit for a newly created marker
    position_ms: 13000
    label: cue 1
    marker_type: custom
    color: '#ffcc00'
comment: Android DJ offline edit
```

Sample Lib allocates IDs for new markers and returns the full canonical result. This naturally supports offline cue/loop preparation without granting Android canonical-ID allocation authority.

If Android needs creation of new non-default intervals, introduce a dedicated idempotent `interval.create` operation whose receipt returns the server-generated ID. Do not use the current one-shot POST as an offline replay primitive until it is wrapped by the mutation ledger.

### 5.3 Marker DTO

Current canonical fields are `id`, `interval_id`, `position_ms`, `label`, `marker_type`, `color`, `source_kind` and bounded public metadata. Marker positions must remain inside the interval's half-open `[start_ms,end_ms)` bounds.

For v1, avoid separately advertising marker CRUD while `interval.editor_state.replace` already provides stronger aggregate invariants. Separate marker operations can be added later if they preserve the same concurrency and ADSR-cleanup semantics.

### 5.4 Tag and tag-attachment DTOs

A canonical tag remains an ID plus `(namespace,value)`. Android may attach a known tag ID, or request an idempotent `tag.ensure_attach` with `(namespace,value,target)` and let Sample Lib converge on its unique tag row.

Attachments should be sync-visible relation entities so detach can produce a tombstone. A deterministic wire ID such as a hash of `(target_type,target_id,tag_id)` is sufficient even though the storage table uses a composite primary key.

Tag “rename” is not a v1 in-place mutation because current semantic uniqueness is the name itself. A user rename can be represented as attach/create new tag plus detach old tag until Sample Lib deliberately introduces a separate stable vocabulary-term identity for that use case.

### 5.5 Playlist DTO — required migration before write capability

Add canonical tables rather than overloading `crate` tags:

```sql
playlists(
  id TEXT PRIMARY KEY,
  parent_id TEXT REFERENCES playlists(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,              -- manual | smart
  smart_rule_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
)

playlist_items(
  id TEXT PRIMARY KEY,
  playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  asset_id TEXT NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  created_at TEXT NOT NULL
)
```

The important point is the stable item ID: it allows rename/reorder/move operations without making `(playlist name,path,position)` into identity, and it permits repeated occurrences of the same asset if the product chooses to allow them.

The playlist aggregate revision should cover playlist metadata plus ordered item IDs. Reorder/move operations must be transactional and revision checked. Reasonable v1 operations are:

- `playlist.create`
- `playlist.rename`
- `playlist.move`
- `playlist.item.add`
- `playlist.item.remove`
- `playlist.order.replace` with a complete bounded item-ID list
- `playlist.delete`

Until this migration exists and is change-logged, `hello.capabilities.mutations` must not advertise playlist writes.

Smart playlists are rules, not a material ordered membership list. They can initially be read-only unless rule editing and evaluation-version semantics are defined.

## 6. Sync change log, tombstones and transactions

A new sync persistence layer is justified. It should be an outbox/change journal owned by Sample Lib, not Android-specific shadow tables of canonical metadata.

Conceptually:

```sql
sync_changes(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  entity_revision TEXT NOT NULL,
  change_kind TEXT NOT NULL,       -- upsert | delete
  projection_schema TEXT NOT NULL,
  projection_json TEXT,            -- null only for delete
  occurred_at TEXT NOT NULL
)

sync_mutation_receipts(
  mutation_id TEXT PRIMARY KEY,
  principal_id TEXT NOT NULL,
  installation_id TEXT NOT NULL,
  request_sha256 TEXT NOT NULL,
  outcome TEXT NOT NULL,
  receipt_json TEXT NOT NULL,
  created_at TEXT NOT NULL
)
```

Implementation details may differ, but these invariants are mandatory:

1. A canonical write and its change record are in the same SQLite transaction.
2. A canonical delete and tombstone are in the same transaction.
3. A successful/no-op/conflict terminal receipt is durably recoverable according to the advertised replay horizon.
4. Every production mutation path affecting sync-visible entities emits through the same mechanism, including local UI writes, DJXML import effects, batch annotation and deletion—not only Android requests.
5. Direct database migrations may seed a new authority generation rather than attempting to fake historical events.

The change journal may be compacted because it is a convergence feed, but compaction must preserve correctness for every non-expired cursor.

### Bounded retention

Bounded storage is required and must be explicit in `hello`.

Recommended starting policy:

- tombstone/change cursor horizon: **90 days**, additionally protected by a configured row/byte cap;
- full mutation receipt/replay horizon: **365 days**, because receipts are much smaller and stale replay is more dangerous than a stale pull cursor;
- when a cap shortens the effective horizon, cursors older than the actual retained boundary fail as `cursor_expired`;
- unresolved Android journal entries older than the advertised mutation replay horizon must not be blindly replayed with their old mutation ID. Android must first bootstrap/reconcile and require an intentional fresh mutation if the user still wants the edit;
- the server must never silently forget a still-advertised replay guarantee.

These periods are policy defaults, not protocol constants; the authoritative values are negotiated in `hello`.

## 7. Authorization and device trust

The sync facade must introduce a real authentication boundary before phone access is enabled.

Minimum contract:

- pairing is an explicit operator action that issues a device/installation credential;
- non-loopback transport uses TLS or an equivalently authenticated private transport;
- bearer credential material is never embedded in resource URIs, cursors, logs or WebSocket messages;
- server derives an authenticated principal and installation from the credential rather than trusting request JSON;
- scopes are at least `sync.read`, `sync.write`, `media.read`, `analysis.read`, with an optional separate `analysis.request` if Android may launch jobs;
- every pushed operation is authorized again server-side, including target entity access;
- receipts, cursors and media resolution are principal-scoped;
- revoking a device invalidates future sync/media access without rewriting canonical metadata provenance.

`hello` may return an explicit `auth_required` state, but it must not become an unauthenticated metadata dump simply to make discovery convenient.

## 8. Media and resource URLs

Reuse Sample Lib's resource abstraction, not filesystem paths.

Current stable concepts worth preserving include `asset://...`, `preview://...`, `waveform://...`, `render://...` and registered derived resources. Range-capable media delivery is compatible with Android offline download/resume.

Rules:

1. A sync DTO exposes stable resource identity plus content hash/size/MIME where known.
2. A fetch URL is generated/resolved by Sample Lib and may be refreshed independently of entity identity.
3. The client verifies the content hash before marking an offline download authoritative/complete.
4. A provider-normalized URI is not automatically mobile-visible. It must resolve to a registered Sample Lib resource or a deliberately supported portable resource scheme.
5. Raw fields such as `file_path`, `output_dir`, `storage_relpath`, worker request path and command path never cross the mobile boundary.

This distinction matters today: Demucs/Basic Pitch normalization can contain internal path fields, while the later Sample Intelligence evidence adapter deliberately strips them. The sync API should expose the stripped/registered projection, not the raw analysis JSON.

## 9. Advisory analysis, caching and acceptance provenance

### 9.1 Suggestions are immutable advisory records

Analysis services may generate suggestions such as BPM, key, cue, loop, region, stem, transcript marker, related-track recommendation, warning or mix-prep proposal. A suggestion must not mutate canonical Sample Lib rows when merely generated or downloaded.

Suggested portable envelope:

```yaml
schema: sample-lib.analysis-suggestion/v1
id: sug_<stable fingerprint>
target:
  asset_id: asset_...
  interval_id: int_... | null
kind: bpm | key | cue | loop | region | stem | transcript_marker | related_track | warning | mix_prep
payload: <kind-specific bounded object>
confidence: 0.0..1.0 | null
input:
  content_hash_sha256: <64hex>
  entity_revision: <opaque|null>
producer:
  family: mir | stem_separation | transcription | speech_asr | sample_intelligence
  semantic_version: <string>
  pipeline_schema: <string>
analysis_run_id: analysis_...
evidence_id: <portable evidence fingerprint|null>
generated_at: <RFC3339>
stale: <boolean>
```

`producer.family` is semantic, not a hard dependency on a model brand. Current server-side provenance may still record `demucs-worker`, `essentia-worker`, Basic Pitch version, model name, etc. Android does not need those implementation details in order to accept a suggestion.

For future ASR, use the same pattern: expose a bounded speech/transcript evidence schema with stable analysis-run provenance. Do not couple the sync protocol to ComfyUI, Whisper, a cloud provider, or another particular stack.

### 9.2 Cache identity

A reusable analysis result/suggestion cache key should include at least:

```text
source content hash
+ exact interval/source scope
+ input entity revision/fingerprint where metadata affects interpretation
+ semantic operation/capability
+ normalized provider/pipeline version
+ normalized parameters/config digest
+ suggestion/evidence schema version
```

Sample Lib already persists many of these ingredients in `analysis_runs` (`input_hash`, tool/version, operation, config). Derived semantic evidence may add deterministic `evidence_id`/suggestion IDs.

Cached analysis remains advisory. A cache hit does not turn its value into canonical metadata.

### 9.3 Android acceptance mutation

Acceptance is an ordinary revision-checked Sample Lib mutation with a bounded provenance reference, not a call to “apply this model output” by provider name.

Example:

```json
{
  "mutation_id": "...",
  "entity_type": "interval",
  "entity_id": "int_...",
  "base_revision": "opaque-current-revision",
  "operation": "interval.metadata.patch",
  "payload": {"bpm": 91.98},
  "provenance": {
    "kind": "analysis_suggestion_acceptance",
    "suggestion_ref": {
      "suggestion_id": "sug_...",
      "schema": "sample-lib.analysis-suggestion/v1",
      "analysis_run_id": "analysis_...",
      "evidence_id": "providerfeat_...",
      "input_revision": "opaque-revision-at-analysis-time",
      "input_content_hash_sha256": "..."
    }
  }
}
```

Server validation must:

- resolve or validate the referenced suggestion/evidence;
- verify that it belongs to the target asset/interval;
- reject stale `base_revision` exactly like any other human mutation;
- record bounded acceptance provenance/human annotation atomically with the canonical write;
- preserve enough provenance after analysis cache cleanup to explain why the canonical value was accepted.

The mutation does **not** need `model="htdemucs"`, `worker_url`, a filesystem path or a Sample Intelligence package version. Those implementation details remain reachable through the referenced analysis/evidence record when retained.

One current Sample Lib behavior deserves explicit care: a narrow BPM-hypothesis path can already auto-apply a value under its own server-side review policy. That local policy must not be generalized into “analysis is authoritative.” Android-visible suggestions remain advisory unless an explicit canonical acceptance policy/mutation says otherwise.

## 10. DJXML interoperability

Keep the existing DJXML directionality:

**Sample Lib is canonical; DJXML is import/export interoperability.**

Existing semantics worth retaining:

- exported tracks carry `Grouping="sample-lib:<asset-id>"` as the strongest stable identity hint;
- import falls back carefully and skips unmatched/ambiguous tracks rather than inventing assets;
- canonical Sample Lib fields win over stale DJXML provenance;
- cue/loop translation preserves semantic marker structure;
- external XML never moves/repoints source audio;
- crate paths currently map to `crate` tags.

What must change for the Android playlist product is **not** the existing crate compatibility behavior; it is the addition of a canonical playlist model. Once that exists:

- export canonical playlists/crates to DJXML hierarchy;
- import legacy DJXML crate paths as compatibility membership/provenance;
- do not infer a canonical playlist rename merely because an external path string changed, because DJXML does not currently carry a proven Sample Lib playlist ID;
- if a future adapter can embed/recover stable Sample Lib playlist IDs, only then may it safely reconcile rename/move identity;
- the old `crate` tags can be migrated as input to one-time playlist creation, but should not remain the wire identity for new playlist sync.

This preserves working DJXML behavior without making its weakest identity mechanism the architecture of the Android app.

## 11. Conflict semantics

The server decides whether a mutation is valid against current authority. Suggested merge classes:

| Class | Example | Server behavior |
| --- | --- | --- |
| `safe_fieldwise` | asset rating changed remotely while Android only changes collection, after explicit field-base evidence | server may offer merged proposal, but only if operation contract declares fields independently mergeable |
| `editor_aggregate` | cue/loop/interval editor snapshot changed | conflict with current full editor state; user/client rebase |
| `ordered_collection` | playlist reordered by two devices | no implicit last-writer-wins; return current ordered item IDs |
| `delete_vs_edit` | Android edits a marker/playlist deleted elsewhere | explicit conflict/tombstone, never recreate implicitly |
| `identity_conflict` | submitted marker/tag/playlist item ID belongs to another aggregate | reject |
| `incompatible_schema` | queued mutation payload no longer understood | reject and require migration/review |

Do not use wall-clock timestamps for winner selection. Do not silently last-write-wins structural editor or playlist state.

## 12. Requests Sample Lib can reasonably accept from Android

The first real adapter should intentionally be narrower than the product's eventual UI.

**Accept once the sync ledger exists:**

- `interval.editor_state.replace` — strongest existing implementation semantics; priority 1;
- `asset.metadata.patch` — only `collection`, `rating`, `quality_score`, after adding revision+receipt wrapping;
- `tag.ensure_attach` / `tag.detach` — after change/tombstone emission is wired;
- read-only canonical assets/intervals/markers/tags/resources;
- read-only advisory suggestions/evidence;
- media download by authenticated resource reference.

**Accept only after dedicated canonical migration:**

- playlist create/rename/move/item/reorder/delete;
- additional offline interval creation if a server-ID-returning idempotent create operation is added;
- broader canonical metadata patches once validation/provenance rules are explicit.

**Do not accept through v1 generic sync:**

- arbitrary server filesystem paths;
- client-supplied canonical asset IDs for ingest;
- raw SQL-shaped row replacement;
- raw provider output as canonical metadata;
- arbitrary model/provider execution arguments;
- direct “make this suggestion authoritative” without a revision-checked canonical mutation;
- silent recreation of deleted entities;
- generic playlist writes backed only by crate-tag paths.

## 13. Migration plan

### Phase A — repair the draft wire contract before it becomes legacy

1. Change all entity/base revisions from integer to opaque string in Android sync schema/models/fake server.
2. Separate internal server change sequence from entity revision.
3. Namespace real endpoints under `/v1/sync/*` or otherwise make the sync boundary explicit; do not imply all current `/v1/*` CRUD shares offline semantics.
4. Replace generic `create/update/delete` capability claims with operation-family capability negotiation.
5. Keep incompatible negotiation explicit; no silent downgrade.

### Phase B — Sample Lib sync persistence

1. Add authority/server-generation identity.
2. Add change/tombstone journal and mutation receipt ledger.
3. Add snapshot + opaque incremental cursor implementation.
4. Wrap `interval.editor_state.replace` first, reusing its existing transaction/idempotency semantics.
5. Add authenticated device principal/pairing boundary and resource authorization.
6. Make every sync-visible local mutation path emit into the journal in the same transaction.
7. Add retention/compaction plus explicit cursor/replay expiry behavior.

### Phase C — narrow additional writes

1. Revision-wrap asset curation patch.
2. Revision-wrap tag attachment/detachment.
3. Add idempotent server-ID-returning interval creation only if Android actually needs it offline.
4. Add sanitized analysis-suggestion projection and acceptance provenance.

### Phase D — canonical playlists

1. Add `playlists` and `playlist_items` with stable IDs.
2. Add aggregate revisions and transactional reorder/move operations.
3. Migrate legacy crate tags as compatibility input where unambiguous.
4. Update DJXML adapter to project canonical playlists without treating path text as identity.
5. Only then advertise playlist mutation capabilities to Android.

## 14. Verification requirements

A real implementation is not acceptable until the following are automated.

### Contract and compatibility

- hello selects a common version and rejects no-overlap with explicit supported versions;
- opaque revisions validate as strings and clients/tests never perform arithmetic on them;
- unknown/unsupported mutation operations are rejected rather than guessed;
- generation mismatch/cursor expiry forces bootstrap;
- schema evolution tests prove no silent downgrade.

### Cursor convergence

- snapshot pagination under concurrent create/update/delete converges after subsequent incremental pull;
- duplicated pages/events are harmless;
- dropped WebSocket events do not affect correctness;
- a cursor just inside retention works; one just outside returns `cursor_expired`;
- tombstones prevent deleted data from reappearing after reconnect;
- page application is locally atomic on Android before cursor advancement.

### Mutation/idempotency

- lost-response replay returns the exact prior receipt without a second canonical write;
- mutation-ID reuse with a different body is rejected;
- two writers from the same base revision produce one success and one deterministic conflict;
- receipt + canonical write + change event are crash-atomic;
- replay after client process death works;
- mutations outside the negotiated replay horizon are not blindly re-applied.

### Entity integrity

- asset content identity never changes under metadata sync;
- interval shrink rejects out-of-range notes/markers;
- editor-state replacement creates server IDs for new markers, preserves known IDs and rejects foreign IDs;
- marker deletion cleans dependent ADSR state exactly as current service logic requires;
- tag uniqueness/attach races converge;
- playlist rename preserves playlist ID; reorder preserves item IDs; concurrent reorder conflicts rather than last-write-wins.

### Security and privacy

- unauthenticated sync/media access fails;
- revoked device credentials fail;
- receipt lookup cannot cross principal boundaries;
- authorization is checked for each mutation target;
- no sync/analysis/media DTO leaks `storage_relpath`, host file paths, worker URLs, command lines or secrets;
- resource download hash verification catches corruption/substitution.

### Analysis provenance

- suggestions are not canonical metadata before acceptance;
- stale suggestion acceptance still obeys current entity `base_revision`;
- suggestion source scope must match target asset/interval;
- Demucs projection exposes registered child asset identities, not paths;
- Basic Pitch projection does not fabricate note events absent from its parent contract;
- a future ASR provider can satisfy the same semantic suggestion/evidence contract without Android changes;
- provider/model version changes invalidate analysis cache identity without invalidating stable asset identity.

### DJXML

- existing asset identity priority and cue/loop round trips remain stable;
- stale DJXML does not override newer canonical fields;
- ambiguous/unmatched imports do not create phantom assets;
- canonical playlists round-trip only after stable playlist identity exists;
- legacy crate tags remain compatibility data, not ordered-playlist authority.

## 15. Required follow-up changes to the current Android draft

The current fixture-first protocol should be treated as scaffolding, not implementation truth. Before the real Sample Lib adapter lands, the owning protocol lane should change:

1. `contracts/sync/v1/sync.schema.json`: revision/base/current revision types from integer to opaque string.
2. `Domain.kt`: `PendingMutation.baseRevision`, `MutationReceipt.authoritativeRevision`, `AnalysisSuggestion.inputRevision` from `Long?` to opaque string type.
3. fake sync server: maintain an internal monotonic feed sequence if useful, but generate opaque entity revisions independently.
4. fake server delete path: persist tombstones rather than only removing a dict entry.
5. capability fixture: advertise operation-family support instead of implying arbitrary generic entity CRUD.
6. playlist fixture/API: mark mutations unsupported until the canonical Sample Lib migration exists.
7. suggestion acceptance fixture: attach a stable suggestion/analysis/evidence provenance reference to an ordinary canonical mutation.
8. auth scenarios: remain deterministic but model an authenticated principal rather than only an unauthenticated `auth_required` switch.

Those are contract corrections, not reasons to build a parallel sync service.

## 16. Negotiated conclusion

Sample Lib can support a strong offline-first Android DJ workflow without surrendering authority if the implementation stays narrow:

```text
Sample Lib canonical DB
    │
    ├── canonical transactions ──> sync outbox/tombstones ──> opaque cursor pull ──> Android cache
    │                              └── durable mutation receipts <── Android mutation journal
    │
    ├── registered resources ──> authenticated resource resolver ──> Android offline media
    │
    ├── analysis_runs / provider evidence ──> sanitized immutable suggestions ──> Android review
    │                                                                     │
    └──────────────────────── revision-checked acceptance mutation <──────┘

DJXML <── projection/import adapter ──> Sample Lib
          (interoperability, not authority)
```

The existing interval editor proves the hard part—revision-conditioned, atomic, idempotent authority—is already achievable in this codebase. The next implementation should generalize that pattern through a small authenticated sync facade, add the missing change/tombstone ledger, and introduce a real playlist aggregate rather than canonizing current fixture assumptions or crate-tag workarounds.
