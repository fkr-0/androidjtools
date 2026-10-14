# Sample Lib authority negotiation for Android DJ sync

> Historical negotiation record. The shipped contract is `contracts/sync/v1` (asset_file pull family and `GET /v1/sync/media/{asset_file_id}`); resource URIs here such as `asset://…` were superseded by `<role>://<asset_file id>`.

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-SAMPLELIB-20260927`

Status: **defensible current-state negotiation position, revalidated 2026-09-28 against the live Android client/contracts/tests, Sample Lib implementation/tests, and Sample Lab integration boundary**.

Revalidation anchors: androidjtools `131b33ef3118882e7aa414f9b245be10a5ef37c7`; Sample Lib `b2937a6ebdf7e7eaaaa72438e50287e8fda61a5f`; live Sample Lib sync implementation `src/sample_lib/android_sync.py` SHA-256 `c69f4f6dca4415f92d3f9f045f82cf9d05f65c6d4740bd860de6c491e89b4362`; Android sync contract `contracts/sync/v1/protocol.json` SHA-256 `fc114729e13277f2e53bf90a3e419b2b1c843e4715046c0e441a08be93853766`; Android transport `SampleLibHttpTransport.kt` SHA-256 `4fc626b372d64674fde34efd15bd635427f36e26433aab41f1d72df8aa4e8e08`; Android mutation journal `MutationJournal.kt` SHA-256 `f679c73e841c78bca2331c09854f1d11cdc9c7a0c30e50ea6269fe5b8e584490`. The revalidation found no production behavior that invalidates the authority position: capability truth remains narrower than the target contract, signed cursors still carry O(n) entity revision state, tombstones are still cursor-diff inferred rather than durably journaled, access tokens still have no expiry/revocation protocol, and the advertised 365-day mutation replay horizon is still not age-enforced.

This document is an authority/integrity contract, not a production patch. It was re-derived from the current Android contract/client, the actual Sample Lib implementation and tests under `/home/user/code/sample-lab/third_party/sample-lib`, and the Sample Lab / Sample Intelligence integration boundary. It supersedes stale assumptions in the 2026-09-15 version where current code has falsified them.

## 1. Decision

Sample Lib remains the only durable authority for library and DJ-preparation state. Android may cache canonical projections, prepare edits offline, persist a mutation journal across process death, review analysis suggestions, and cache authenticated media. Analysis systems may produce immutable evidence and advisory candidates; they do not become metadata authorities.

> analysis proposes; a user or policy selects an intent; Sample Lib validates and commits; only a Sample Lib receipt makes the result authoritative.

Keep one narrow, capability-negotiated `/v1/sync/*` facade over Sample Lib. Do not introduce a second metadata service and do not treat ordinary CRUD as an offline protocol.

## 2. Verified baseline and 2026-09-28 conformance

| Area | Verified current behavior | Consequence |
| --- | --- | --- |
| Sync facade | `src/sample_lib/android_sync.py` implements pair, hello, pull, push, receipt recovery | Reuse it |
| Auth | signed bearer credential binds server/client/installation/principal/scopes; fail-closed if secret/pairing code absent | Keep; add expiry/revocation |
| Pull | asset, interval, marker, tag, tag_attachment, playlist, playlist_item | Current readable authority |
| Writes | only `interval.editor_state.replace` advertised | Capability truth wins |
| Interval mutation | `BEGIN IMMEDIATE`, opaque fingerprint revision, idempotent receipt, stale conflict | Generalize this pattern |
| Playlists | migration 0013 + service/tests provide canonical stable playlists/items/order/hierarchy | Older “no playlist authority” assumption is false |
| Playlist write | transactional replacement and internal revision, but no sync base-revision/idempotency wrapper | Read now; guard before offline write |
| DJXML | stable Sample Lib playlist/item identities; crate tags are compatibility | Interop, not authority |
| Cursor | signed opaque cursor embeds full entity→revision map | Correct wire abstraction; O(n) implementation |
| Deletes | tombstones inferred by prior cursor vs current rows | No durable retention guarantee yet |
| Analysis sync | server advertises `analysis: []` | Android suggestion acceptance remains future/capability-gated |
| Resources | stable registered resource URIs exist | Reuse identity; mobile auth boundary needs hardening |
| WebSocket | none implemented | Optional wake-up only |
| Android transport | HTTPS required off-loopback; server identity/generation pinned; cursor-expiry reset; ambiguous/offline/unavailable push attempts receipt recovery; resource fetch is same-origin and size/hash verified | Server can rely on exact-replay/receipt recovery rather than inventing a second acknowledgement protocol |
| Android mutation journal | immutable canonical request body + stable mutation ID persisted before send; APPLYING recovers to PENDING after process death; unresolved intent blocks overtaking; only applied/no-op receipts compact as success | Keep server idempotency strong; do not weaken it because the client is offline-first |
| Android analysis acceptance | already journals `analysis.suggestion.accept`, but Sample Lib does not advertise or implement that family | Keep code capability-gated; do not treat client model support as server capability |

Current server capability truth:

```yaml
protocol_version: "1"
pull: [asset, interval, marker, tag, tag_attachment, playlist, playlist_item]
mutations: [interval.editor_state.replace]
playlists: canonical_v1
analysis: []
media: [resource_uri, content_hash, range]
```

Android's `contracts/sync/v1/protocol.json` records this as `observed_sample_lib_baseline_2026_09_27` and now also records `observed_sample_lib_conformance_2026_09_28`: the 365-day replay horizon is advertised but not age-enforced; access tokens have no expiry claim; credential rotation/revocation are undefined; cursor state grows O(n); no additional mutation families or analysis sync capability are advertised; and WebSocket events are not implemented. The Sep-28 synthesis has corrected playlist capability wording to `canonical_v1`; the remaining broader mutation/analysis declarations are target capabilities, not current Sample Lib support. Runtime negotiation, not enum presence or target design, is authoritative.

## 3. Falsified assumptions and current integrity gaps

### 3.1 A production sync facade now exists

The prior proposal treated the facade as future work. It is now implemented and tested. Extend this facade; do not build a parallel sync service.

### 3.2 Canonical playlists now exist

`playlists` and `playlist_items` have stable IDs, hierarchy, source identity, order and revisions. `replace_playlist()` uses an immediate writer transaction, validates hierarchy/assets, converges source identity, preserves item IDs where possible, no-ops exact replacement, and increments playlist revision when changed.

Still missing for offline authority: a sync wrapper that checks `base_revision` while holding the writer lock and records/recoverably binds an idempotency receipt.

### 3.3 The advertised 90-day tombstone retention is not yet a durable guarantee

Current deletes are inferred only because the previous cursor remembered an entity that is now absent. There is no persistent deletion journal with historical deletion time. Therefore `tombstone_retention_seconds=7776000` is not yet a durable feed guarantee for clients that never held the deleted identity.

Until migration, capability detail should distinguish:

```yaml
deletion_feed:
  mode: cursor_diff
  durable_retention: false
```

After migration:

```yaml
deletion_feed:
  mode: durable_journal
  durable_retention: true
  retention_seconds: 7776000
```

### 3.4 Current cursor internals are O(n)

The wire cursor is properly opaque, but its signed payload contains the synchronized entity revision map. Keep the opaque wire contract; migrate internals to a persistent journal/high-water cursor for large libraries.

### 3.5 `server_change_revision` is not durable monotonic authority

The server currently derives it from wall-clock milliseconds. Treat it as diagnostic only. Android must not use it as a base revision, infer causality from it, or skip pulls because of it.

### 3.6 Authority generation can miss an in-place database restore

Generation is derived from secret + DB path + max schema migration. Restoring different contents at the same path with the same schema and secret can preserve the generation while invalidating cursors.

Persist an explicit authority epoch in canonical storage and rotate it on restore/import/reset operations that invalidate sync state.

### 3.7 Current entity projections are not a safe mobile allowlist

`AndroidSyncFacade._entities()` projects full DB rows for several families. A mobile DTO must be allowlisted. Never expose `storage_relpath`, host paths, worker URLs, command lines, credentials or provider-private diagnostics.

### 3.8 General resource routes are not yet the Android bearer boundary

The current `GET /v1/resources/...` routes resolve registered resources but do not depend on the Android bearer authorization path. Preserve the resolver as storage authority, but place Android fetches behind `media.read`, either by adding the auth dependency or by a narrow authenticated sync-resource facade.

### 3.9 Analysis acceptance is not implemented merely because Android models it

Android already has `analysis.suggestion.accept` journaling/provenance code. Sample Lib currently advertises no analysis sync capability and durably rejects unsupported operations. Server capability is truth.

### 3.10 The advertised mutation replay horizon is not currently enforced

`hello` advertises `mutation_replay_horizon_seconds=31536000`, but the current receipt lookup/store path does not apply an age cutoff or retention cleanup: Android sync wrappers are stored in `operation_events`, and `_stored_wrapper()` returns the newest matching receipt regardless of age. That is useful durability, but it is not yet a bounded 365-day replay contract.

Until retention is implemented, capability detail should say whether receipt replay is effectively unbounded/best-effort rather than promising a finite enforced horizon. Once bounded retention is enabled, pruning and lookup expiry MUST be coordinated so a mutation ID is never forgotten while the advertised replay horizon still says it is recoverable. Full receipt payloads may expire, but Sample Lib should retain at least a compact `(principal_id, mutation_id, request_sha256, terminal_outcome_or_expired_marker)` reservation long enough to prevent an old mutation ID from becoming a fresh mutation. After the full receipt horizon, lookup/replay must return an explicit `receipt_expired` / `idempotency_key_expired` result that forces user-visible rebase/new intent rather than silently reusing the key.

### 3.11 Android already implements the recovery contract Sample Lib needs

The current `SampleLibHttpTransport` classifies an I/O failure after push as ambiguous delivery, queries `/v1/sync/receipts/{mutation_id}`, and only retries the exact immutable mutation when no receipt is recoverable. `DurableMutationJournal` persists intent before network I/O, recovers `APPLYING` after process death, replays in sequence order without overtaking unresolved intent, and compacts only applied/no-op receipts. Sample Lib should therefore preserve exact replay and receipt lookup as the authority boundary; a second client-specific acknowledgement or timestamp-based dedupe scheme would weaken the system.

### 3.12 Client operation enums are intentionally ahead of server capability

Android currently knows operation names for asset metadata, tag attach/detach and analysis acceptance, and its local policy validates their entity/base-revision shape. This does **not** authorize those writes. `SampleLibClient` checks negotiated mutation capability before push, and current Sample Lib advertises only `interval.editor_state.replace`. Server capability remains the final allowlist.

## 4. Canonical identity and revision model

| Entity | Canonical identity | Sync revision | Initial Android write stance |
| --- | --- | --- | --- |
| asset | Sample Lib asset ID; SHA-256 is immutable content identity | opaque projection token | restricted metadata patch after wrapper |
| interval | Sample Lib interval ID | existing editor aggregate fingerprint | supported now |
| marker | Sample Lib marker ID | preferably governed by interval aggregate | no separate offline CRUD first |
| tag | Sample Lib tag ID, server-owned semantic uniqueness | opaque projection token | read; guarded ensure later |
| tag_attachment | stable/deterministic relation identity | opaque relation token | guarded attach/detach later |
| playlist | Sample Lib playlist ID | expose internal revision as opaque string/fingerprint | read now; aggregate write later |
| playlist_item | stable Sample Lib item ID | opaque projection token | mutate through playlist aggregate |
| analysis_suggestion | immutable evidence-derived candidate ID | immutable advisory identity | never canonical by itself |

Clients compare revisions for equality only. They never increment, sort or parse them. Internal numeric playlist revision is acceptable because the wire treats it as opaque.

Asset content identity and metadata revision are separate. A metadata write must never alter content hash.

## 5. Reuse versus narrowly versioned sync operations

Reuse as semantic authority:

- interval editor-state fingerprint and transaction;
- interval mutation idempotency/receipt semantics;
- canonical asset/interval/marker/tag identities;
- canonical playlist/item service invariants;
- registered resource URI resolver;
- `analysis_runs` provenance;
- DJXML identity mapping.

Wrap existing mutation semantics behind sync operations. Each offline-safe operation requires:

1. stable `mutation_id`;
2. operation-specific schema;
3. opaque `base_revision` unless explicitly create/ensure;
4. writer serialization before revision read;
5. canonical validation;
6. authoritative result;
7. replay/collision semantics;
8. eventual same-transaction change/tombstone emission.

Operation order:

```yaml
now:
  - interval.editor_state.replace

next:
  - asset.metadata.patch
  - tag.ensure_attach
  - tag.detach
  - playlist.aggregate.replace

later:
  - analysis.suggestion.accept
```

Do not add generic create/update/delete sync verbs.

## 6. Sync surface and compatibility

Keep:

```text
POST /v1/sync/pair
POST /v1/sync/hello
POST /v1/sync/pull
POST /v1/sync/push
GET  /v1/sync/receipts/{mutation_id}
```

Do not create `/v2/sync` merely because capabilities expand. A protocol-major bump is justified only for incompatible message/semantic changes that cannot be represented by an additive response field, a new operation family, a new entity schema, or a capability.

If added, WebSocket is only:

```json
{"type":"sync.changed.v1","scopes":["library"],"hint":"pull"}
```

Loss, duplication and reordering must not affect correctness.

Use three version axes:

```yaml
protocol_version: message/negotiation semantics
entity_schema: projected entity DTO contract
provider_output_schema: normalized analysis evidence contract
```

Opaque cursor encoding and internal DB revision representation may change without protocol bump.

For core canonical entity families, an unknown `entity_schema` is not safely skippable during a pull: dropping it and advancing the cursor can make the Android cache falsely complete. The client must fail the page without advancing the cursor and surface `local_migration_required` / incompatibility. Advisory analysis candidate *kinds* may degrade independently when their envelope schema is understood, because ignoring an unsupported advisory kind cannot corrupt canonical state.

### 6.1 Concrete v1 wire surface and justified deltas

| Surface | Decision | Contract |
| --- | --- | --- |
| `POST /v1/sync/pair` | reuse | bootstrap secret in; scoped installation credential out |
| `POST /v1/sync/hello` | reuse/additive | exact protocol version + operation-family capabilities + truthful retention/security modes |
| `POST /v1/sync/pull` | reuse | opaque cursor, complete page of canonical changes/tombstones, explicit expiry |
| `POST /v1/sync/push` | reuse | serial mutation envelopes; one authoritative receipt per mutation; batch is not a transaction |
| `GET /v1/sync/receipts/{mutation_id}` | reuse | principal-scoped recovery for ambiguous delivery |
| `GET /v1/sync/resources/{opaque_fetch_id}` | **narrow new facade justified** | `media.read`, Range/If-Range, stable `resource_uri` stays in the descriptor while the fetch locator is refreshable; size/hash/validator required; internally reuse the existing Sample Lib resolver |
| `WS /v1/sync/events` | optional/additive | wake-up hint only; payload contains no authoritative state |
| analysis suggestions | reuse pull plane when implemented | `analysis_suggestion` is an advisory entity family; no provider-specific public endpoint |
| analysis acceptance | reuse push plane when implemented | `analysis.suggestion.accept`; no model-specific write route |

The resource facade is justified because the current general `/v1/resources/...` route is a local product resource resolver, not an Android bearer authorization boundary. Reusing its resolver implementation is correct; changing its trust model for every local caller is unnecessary coupling.

Transaction boundary is **one semantic mutation**, not one push batch. An operation such as `playlist.aggregate.replace` may atomically update a playlist header plus ordered items because those rows form one canonical aggregate. Sample Lib should not accept a generic Android request to make unrelated mutation envelopes cross-entity atomic; if a future domain action genuinely requires that invariant, define a specific operation family and payload for it.

## 7. Pairing and authorization

Keep the existing principal model, and enforce:

- explicit operator pairing;
- pairing code is bootstrap-only;
- token binds server/client/installation/principal/scopes;
- route scope checks for hello/pull/push/receipt/media/analysis;
- receipt lookup is principal-scoped;
- mutation target authorization;
- non-loopback authenticated TLS or equivalent private authenticated transport.

Before long-lived deployment add:

```yaml
credential:
  credential_id: required
  issued_at: required
  expires_at: required
  signing_key_id: required
  revocable_per_installation: true
```

Per-device revocation is preferable to rotating the global sync secret for one lost phone.

## 8. Pull, pagination, tombstones and retention

Wire invariants:

1. cursor is opaque and integrity protected;
2. it binds authority generation, projection version and scopes;
3. `cursor=null` starts complete bootstrap;
4. Android commits a page atomically before cursor advancement;
5. duplicates are harmless;
6. cursor expiry/generation mismatch is explicit, never an empty delta;
7. reset pruning happens only after final snapshot page commits;
8. search offsets are never sync cursors;
9. WebSocket is never state authority.

Current fail-closed behavior—expiring a paged snapshot/incremental when authority changes mid-page—is safe but can be expensive in an active large library.

Target persistence:

```yaml
sync_change:
  sequence: server-internal-monotonic
  entity_type: ...
  entity_id: ...
  entity_revision: opaque
  kind: upsert|delete
  committed_at: ...
```

Compaction may discard intermediate updates, but every unexpired cursor must converge and deletes within retention must remain observable.

A durable tombstone needs:

```yaml
entity_type: marker
entity_id: marker_...
revision: opaque-delete-revision
deleted_at: authoritative-server-time
sequence: internal
```

At retention expiry, old cursors fail with `cursor_expired` and bootstrap again. Never silently resurrect locally retained state.

## 9. Mutation, receipts and transactions

Canonical envelope:

```json
{
  "mutation_id": "stable-client-id",
  "entity_type": "interval",
  "entity_id": "interval-1",
  "base_revision": "opaque-revision",
  "operation": "interval.editor_state.replace",
  "payload": {},
  "created_at": "2026-09-27T18:00:00Z",
  "provenance": {}
}
```

Rules:

- `created_at` is diagnostic, never concurrency authority;
- same ID + same canonical body returns original receipt;
- same ID + different body => durable `idempotency_key_reused`;
- stale base => explicit conflict, never implicit last-write-wins;
- unsupported operation => durable rejection;
- batch is serial request order; no cross-entity atomicity implied;
- canonical change and sync journal entry should ultimately share a transaction;
- receipt remains recoverable after response loss.

Outcomes stay:

```text
applied | no_op | rejected | conflict
```

The current pending wrapper plus interval editor receipt recovery closes the critical disconnect-after-commit window for the supported operation. New operations need equally strong recovery.

## 10. Conflict semantics

Conflicts are operation-specific evidence, not a generic merge engine.

```yaml
editor_aggregate:
  example: interval bounds + complete marker set
  default: review/rebase

ordered_collection:
  example: playlist header + ordered items
  default: review/rebase

safe_fieldwise:
  example: explicitly whitelisted independent asset curation fields
  default: merge only server-declared safe fields

delete_vs_edit:
  default: never silently recreate

identity_conflict:
  default: canonical Sample Lib identity wins

incompatible_schema:
  default: block and renegotiate/update
```

Playlist reorder is never generic fieldwise merge.

## 11. Canonical playlist sync

Canonical playlists are ready for **read** sync now.

For offline write, add a guarded aggregate operation over current `replace_playlist()`:

```yaml
operation: playlist.aggregate.replace
entity_type: playlist
entity_id: playlist_...
base_revision: opaque-current-playlist-revision
payload:
  name: Openers
  kind: playlist
  parent_id: folder_warmup
  items:
    - id: playlist_item_...
      asset_id: asset_a
      position: 0
    - id: playlist_item_...
      asset_id: asset_b
      position: 1
```

Creation may use `base_revision: null` only under an explicit create contract. Sample Lib allocates canonical IDs unless validating an existing source identity.

Preserve hierarchy acyclicity, folder parents, asset existence, unique positions, stable item IDs, semantic no-op behavior, revision update only on change and idempotent receipts.

## 12. DJXML interoperability

DJXML remains a format adapter:

- same-library export/import carries Sample Lib playlist/item identity and order;
- external source identities converge;
- crate tags are compatibility projection;
- audio location strings do not create content identity;
- ambiguous matching fails closed;
- canonical audio identity is protected.

Current DJXML documentation itself contains one stale contradiction: the canonical-playlist section explicitly says stable Sample Lib IDs preserve hierarchy, membership and order, while a later “deliberate limits” bullet still says playlist folder structure is intentionally flattened to crate paths. Current implementation and tests settle the authority question in favor of canonical playlists: same-library export/import preserves nested hierarchy and ordered playlist identity, while crate tags remain compatibility projection/fallback. Fix the stale documentation separately; sync must not infer authority from the old crate wording.

## 13. Media/resource contract

Mobile sync exposes a sanitized descriptor:

```yaml
resource_uri: asset://asset_123
role: original
sha256: 64hex
size_bytes: 12345678
mime_type: audio/flac
etag: optional
fetch_path: refreshable-authorized-location
```

Requirements:

- only registered resources;
- `media.read` authorization;
- range only when advertised;
- validator suitable for resume;
- Android verifies final size and SHA-256;
- URI is stable identity; fetch path is not;
- no filesystem path on wire.

Reuse the existing resolver internally.

## 14. Advisory analysis contract

Sample Lib owns canonical entities, persisted `analysis_runs`, registered resources and the acceptance transaction. Analysis providers own computation output only.

Expose a sanitized immutable provider-neutral suggestion:

```yaml
schema: androidjtools.analysis-candidate/v1
candidate_id: suggestion_...
analysis_run_id: analysis_...
asset_id: asset_...
interval_id: interval_...|null
kind: bpm|key|cue|loop|region|transcript_marker|related_track|mix_prep
value: {}
confidence: 0.0..1.0|null
generated_at: ...
source:
  service: semantic-provider-family
  model: null|string
  version: null|string
  pipeline_version: null|string
input_identity:
  entity_type: asset|interval
  entity_id: ...
  revision: opaque
  content_sha256: 64hex|null
freshness: fresh|stale|unknown
decision: proposed|accepted|rejected|superseded
```

The server-side analysis cache identity must include source content/input identity and analysis implementation/config identity. A defensible internal key is derived from `(target identity + input revision/content hash + operation + normalized config hash + provider-contract/pipeline version + model revision when applicable)`. Model/config upgrades invalidate the analysis cache without changing asset identity. Sample Lib should issue the immutable `candidate_id`; Android should not reconstruct authority identifiers from model names, and no cache-key field is required in the current mobile candidate contract.

Sample Lib already persists `analysis_runs` with target identity, tool/version/operation, input hash, config, normalized output, status/confidence/warnings, and provider execution provenance. That is the right durable evidence anchor. Candidate cache payloads may have a shorter retention period, but acceptance must either resolve durable attributable evidence or reject with `suggestion_expired`; cached Android values alone are never enough to canonicalize state.

Raw provider output can remain server-side. Mobile projection is an allowlist.

Demucs exposes registered derived asset/resource identities, never worker paths. Basic Pitch exposes normalized note/event evidence only when actually produced. Future ASR uses the same provider-neutral contract; the wire must not depend on Whisper, ComfyUI or another specific stack.

## 15. Analysis acceptance

Android's current coordinator has the right conceptual pattern: reviewed suggestion -> ordinary durable journal mutation -> Sample Lib receipt -> accepted state.

Future envelope:

```json
{
  "mutation_id": "analysis-accept-...",
  "entity_type": "asset",
  "entity_id": "asset-1",
  "base_revision": "current-opaque-revision",
  "operation": "analysis.suggestion.accept",
  "payload": {
    "candidate_id": "suggestion-1",
    "analysis_run_id": "analysis-1",
    "kind": "tempo",
    "value": {"bpm": 128.0},
    "input_identity": {
      "entity_type": "asset",
      "entity_id": "asset-1",
      "revision": "revision-used-for-analysis",
      "content_sha256": "..."
    }
  },
  "provenance": {
    "source": "analysis_suggestion",
    "candidate_id": "suggestion-1",
    "analysis_run_id": "analysis-1"
  }
}
```

Android's current acceptance coordinator also serializes a `candidate_source` object containing service/model/version/pipeline metadata. Treat that as transitional diagnostic detail, not a required authority field. The durable mutation contract should need only `candidate_id`, `analysis_run_id`, target/input identity and the reviewed candidate value; Sample Lib resolves and verifies detailed provider/model/cache provenance from its persisted analysis record. This keeps a switch from Essentia to another tempo engine, Demucs to another stemmer, or one ASR stack to another from changing the acceptance envelope.

Server acceptance MUST:

1. resolve/validate persisted suggestion or attributable analysis evidence;
2. verify target identity;
3. check current `base_revision`;
4. check input revision/content freshness;
5. map candidate kind through an explicit canonical mutation policy;
6. reject arbitrary model-specific field injection;
7. store acceptance provenance;
8. return a normal authoritative receipt.

Android's local freshness check is UX only. Sample Lib rechecks authority at delivery.

## 16. Requests Sample Lib can accept

### Accept now

- pair/hello/pull;
- current canonical read projection, subject to projection sanitization hardening;
- `interval.editor_state.replace`;
- receipt recovery;
- media once the Android fetch path actually enforces `media.read`.

### Accept after narrow wrappers

- restricted `asset.metadata.patch`;
- `tag.ensure_attach`;
- `tag.detach`;
- `playlist.aggregate.replace`.

### Accept after advisory projection exists

- read-only analysis suggestions;
- `analysis.suggestion.accept` with persisted provider-neutral provenance.

### Reject

- arbitrary filesystem paths;
- raw SQL-shaped row replacement;
- generic CRUD;
- provider output promoted directly to metadata;
- arbitrary provider execution arguments in metadata mutation;
- unversioned analysis blobs;
- playlist writes without a base revision;
- silent recreation after deletion;
- transport URLs as stable identity;
- credentials in provenance.

## 17. Migration sequence

### M0 — synchronize contract truth

1. Keep current observed Sample Lib baseline machine-visible.
2. Correct stale desired playlist wording in the synthesis lane.
3. Clearly mark unsupported desired operations as future capability.

### M1 — sanitize and secure existing facade

1. Replace full-row projections with allowlisted DTOs.
2. Put mobile media behind `media.read`.
3. Add credential issuance/expiry/revocation.
4. Persist explicit authority epoch independent of path/schema.
5. Stop treating wall-clock `server_change_revision` as durable monotonic authority.
6. Either enforce the advertised mutation-replay horizon in receipt storage/lookup or negotiate a truthful retention mode before pruning.

### M2 — persistent change/tombstone journal

1. Add transactional change rows and deletion tombstones.
2. Emit them from every sync-visible canonical mutation.
3. Replace O(n) cursor internals with high-water cursors.
4. Advertise retention only when enforced.
5. Preserve explicit reset behavior.

### M3 — more guarded operations

1. revision-wrap restricted asset curation;
2. wrap tag attach/detach;
3. wrap playlist aggregate replacement;
4. add operation-specific conflicts and receipt recovery.

### M4 — advisory intelligence

1. define sanitized suggestion projection;
2. derive immutable candidate/cache identity;
3. advertise `analysis_suggestion` only when implemented;
4. implement revision-checked acceptance;
5. keep provider/model details in provenance, never authority structure.

## 18. Required verification

### Authority/revision

- two writers from same interval revision yield one accepted path and one deterministic conflict;
- stale playlist aggregate conflicts;
- clients never do revision arithmetic;
- metadata edits never alter asset content hash;
- restore/reset invalidating cursors rotates authority epoch.

### Idempotency/crash

- lost response replay returns exact prior receipt;
- Android process death from `APPLYING` replays the exact same mutation ID/body and produces at most one canonical effect;
- an unresolved earlier mutation is not overtaken by later journal intent;
- mutation-ID/body collision rejected;
- post-commit/pre-response crash recoverable;
- receipts cannot cross principals;
- receipt replay remains valid through the entire advertised horizon;
- pruning cannot make an in-horizon `mutation_id` reusable; post-horizon behavior is explicit and tested;
- every new operation family passes the same replay tests.

### Pull/pagination

- duplicates harmless;
- page apply atomic before cursor advance;
- scope/generation/cursor corruption explicit;
- concurrent create/update/delete converges;
- retention boundary tested;
- cursor stays bounded after journal migration.

### Deletes

- marker/tag attachment/playlist/item deletion observable through durable tombstone;
- delete-vs-edit explicit;
- reset does not resurrect.

### Security

- unpaired denied;
- wrong scope denied;
- expired/revoked credential denied;
- media requires `media.read`;
- no server path/token/pairing code/worker URL/command line in mobile DTOs.

### Resources

- resume validates range offset and validator;
- final size/hash verified;
- content drift fails integrity;
- stable URI survives locator rotation.

### DJXML

- playlist IDs/item IDs/order/hierarchy round-trip;
- external source identity converges;
- ambiguous tracks do not create assets;
- crate tags never override canonical playlist authority.

### Analysis

- candidate alone never mutates canonical state;
- candidate/cache identity changes with input/model/config version;
- raw provider paths redacted;
- stale input rejected at acceptance;
- source/target mismatch fails;
- only applied/no-op receipt marks Android acceptance;
- switching analysis provider/model does not change the acceptance envelope.

## 19. Convergence architecture

```text
Sample Intelligence / Demucs / Basic Pitch / ASR
                 │ advisory evidence only
                 ▼
       Sample Lib analysis_runs/resources
                 │ sanitized candidate projection
                 ▼
Android review ─────> durable Android mutation journal
                          │
                          ▼
                 POST /v1/sync/push
                          │
              auth + capability + revision
                          ▼
                 Sample Lib transaction
                   /       |        \
          canonical DB   receipt   change/tombstone journal
                   \       |        /
                    \      ▼       /
                     opaque pull cursor
                          │
                          ▼
                    Android cache

DJXML <──── format adapter ────> Sample Lib canonical identity
resource_uri ─ authenticated resolver ─> range/hash-verified Android media cache
```

Do not prolong the old assumptions. The hard primitives now exist: a real sync facade, strong editor transactions, canonical playlists, scoped pairing and receipt recovery. The next work is convergence and integrity hardening: sanitize projections, persist authority generation, scope media, add a durable change/tombstone journal, then wrap existing canonical mutation semantics one operation family at a time.
