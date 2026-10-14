# Sample Lab integration design

Status: spec approved 2026-10-12. S1 implementation plan: `docs/superpowers/plans/2026-10-12-samplelab-s1-connect-and-read.md`. S2 (Android side) plan: `docs/superpowers/plans/2026-10-13-samplelab-s2-server-audio-android.md`. S3 to S5 not yet planned.
Branch: `feat/samplelab-integration`.

## Purpose

Android DJ Tools exists to be a cloudless client for a Sample Lib / Sample Lab server. Today the
production app (`AndroidDjToolsActivity`) never talks to one: `SampleLibClient`,
`SampleLibHttpTransport` and `SampleLibAnalysisProvider` are tested but never constructed, the
sync provider is permanently `UNCONFIGURED`, and `SyncScreen` shows no negotiated server
capabilities. This design connects them.

**Decision (user, 2026-10-12):** when a server is paired, the **server library is the primary
source**; device files are the fallback when unpaired and the playback source for tracks the
server cannot yet stream. Sample Lib stays the only canonical authority for metadata, intervals,
cues, tags, playlists and analysis.

**Success criteria**

1. A user can pair with a real Sample Lib server from the app and see what it supports.
2. The library, cues and markers shown come from the server's pull feed, survive process death
   and offline use, and are never styled as effective unless the server acknowledged them.
3. Every unavailable feature states the missing server capability.
4. Each stage is verified against a real server, not only the Python fake.
5. No credential is logged, backed up, or sent over cleartext to a non-loopback host.

## Evidence this design rests on

| Fact | Source |
| --- | --- |
| Client contract matches the real server for pair/hello/pull/push/receipts | `SampleLibLiveContractTest`, 5/5 against a server built from a clean `git archive HEAD` of sample-lib; negative control fails 4/5 |
| Server advertises one mutation (`interval.editor_state.replace`), empty `analysis`, playlists `canonical_v1`, `media: [resource_uri, content_hash, range]` | live `hello` response |
| Pull families: asset, interval, marker, tag, tag_attachment, playlist, playlist_item | `SUPPORTED_PULL` in `android_sync.py` |
| No pull family carries an audio file descriptor | same; `asset_files` rows exist (`mime_type`, `content_hash_sha256`, `relpath`) but are not projected |
| `GET /v1/resources/...` performs no sync-credential check; only `/v1/sync/*` calls `sync.authorize` | `api.py`; the `media.read` scope issued at pairing is not enforced |
| A durable atomic-file `MutationJournal` and `SyncState` enum already exist | `sync/journal/MutationJournal.kt`, `core/model/Domain.kt` |
| `allowBackup="true"` in the manifest | `AndroidManifest.xml` |
| Media3 playback, device providers, live engine already ship | existing code |

## Stages

Each stage ends with a verified, releasable increment.

### S1 - Connect and read metadata

- **Server profile + pairing UI** in the Sync destination: base URL (HTTPS, or loopback HTTP),
  pairing code field, connect/forget buttons, status line from `SyncState`.
- **Capability panel**: table of pull families, mutation operations, playlists mode, analysis,
  media; each row shows available or "unavailable: server does not advertise X".
- **`SampleLibProfileStore`**: persists base URL, pinned `server_id`, `installation_id`
  (random UUID, created once), `principal_id`, last `authority_generation`, last cursor. The
  credential is encrypted with an Android Keystore AES-GCM key and stored in app-private storage.
  `allowBackup` becomes `false` (or explicit backup rules exclude the store) so credentials and
  cache never enter device backups.
- **`SampleLibSessionManager`**: connect/reconnect with the pinned identity; maps failures to
  `SyncState` (`AUTH_REQUIRED`, `SERVER_INCOMPATIBLE`, `OFFLINE`, `DEGRADED`); an authority
  generation change or `cursor_expired` forces a fresh snapshot and is never treated as an empty
  delta.
- **`LibraryCache`**: plain SQLite via `SQLiteOpenHelper` (no annotation processor, no new
  build tooling). Tables for entities keyed by `(entity_type, entity_id)` with revision, schema
  and JSON value; tombstones delete rows. Disposable: a schema bump or reset drops it. The cursor
  advances **in the same transaction** that commits the batch.
- **`SampleLibSyncEngine`**: pages through pull until `has_more` is false, applies tombstones,
  honors the negotiated `pull_page_max`. Triggers: on connect, manual refresh, WorkManager
  periodic (bounded, no permanent service).
- **Providers**: `SampleLibLibraryProvider` (and preparation read models: intervals, markers)
  backed by the cache. `AndroidDjToolsActivity` composes `AppProviders` from these when paired and
  from the existing device providers when not. A paired track plays from the device only when its
  content hash matches a local file; otherwise it is shown as server-only (not playable) until content-hash matching ships (a follow-up to S2).
- **Manifest/network**: cleartext allowed only for loopback via a network security config;
  non-loopback must be HTTPS (the transport already enforces this).

### S2 - Server audio (needs backend change)

Backend (separate repo, additive, behind a capability):

- New pull family `asset_file`: `{id, asset_id, file_role, resource_uri, sha256, size_bytes,
  mime_type, etag}`; advertised as `asset_file` in `capabilities.pull`.
- New authenticated route `GET /v1/sync/media/{asset_file_id}` requiring `media.read`, with
  `Range`, `If-Range`, `ETag`, and `Content-Length`, 401/403 for missing or insufficient
  credentials, 404 for unknown ids. The legacy `/v1/resources/...` route is not changed.
- Both repos' `contracts/` document the family and route; the fake servers implement them.

Android:

- Stream through Media3 with an `Authorization`-adding data source, and use the existing
  `OfflineDownloadEngine`/cache for pinned downloads (SHA-256 verified, resumable by Range).
- If the server does not advertise `asset_file`, S2 features stay unavailable with a stated
  reason; S1 behavior is unchanged.

### S3 - Editor mutations

- Cue, loop and marker edits become `interval.editor_state.replace` mutations written to the
  existing durable `MutationJournal` before any network attempt; delivery uses
  `pushWithRecovery`, so a lost response is resolved by receipt lookup, never a re-minted intent.
- States stay distinct: draft, pending, applying, effective, conflict, rejected. Pending is
  never rendered as effective.
- Conflict screen exposes the local base revision and current server revision and offers
  exactly two actions: use server state, or reapply the local draft under a new mutation ID.
  Timestamp last-write-wins is not permitted.
- Operations the server does not advertise (`asset.metadata.patch`, tags, analysis accept) are
  not offered; the client already refuses to send them.

### S4 - Playlists (read-only)

Sort `playlist_item` by `position`; never infer order from pull order. No playlist writes are
offered because no playlist mutation family is advertised.

### S5 - Analysis

Wire `SampleLibAnalysisProvider` only when `suggestions.read` is advertised and
`analysis_suggestion` is in the pull set; otherwise the Suggest destination explains why it is
unavailable.

## Error handling

| Condition | Behavior |
| --- | --- |
| 401/403 or credential revoked | `AUTH_REQUIRED`; keep cache read-only; offer re-pair |
| Server identity differs from pinned | refuse; show mismatch; require explicit forget + re-pair |
| `hello` 426 | `SERVER_INCOMPATIBLE` with requested/supported versions |
| `cursor_expired` / generation change | drop cursor, fresh snapshot; cache stays readable until the new snapshot commits (S1 clears the cache first; a staged swap is a separate follow-up - see the S1 plan's deviation 4) |
| Network failure | `OFFLINE`; library remains readable; journal keeps pending mutations |
| Malformed or unknown response | `DEGRADED` with a diagnostic; never apply a partial batch |

## Security

- Credential and pairing code are never logged, put in provenance, or included in diagnostics
  (`MobileCredential`/`PairingCode` already redact `toString`).
- Credential encrypted at rest (Keystore); excluded from backup; deleted on "Forget server".
- The cache is non-secret library metadata but is also excluded from backup because it is
  rebuildable.
- Pairing requires explicit operator action on the server (code), as today.

## Testing

- Unit: profile store round trip with a fake keystore, session state machine, sync engine paging
  and atomic cursor commit (including crash between batch and cursor), provider mapping.
- Contract: extend `SampleLibLiveContractTest` each stage; keep the 32 + 7 Python tests green and
  extend the fake servers for new server behavior.
- UI: Compose tests for the pairing form, capability panel and unavailable states; screenshot
  test additions follow the existing CI screenshot lane.
- Honesty: anything needing a physical device or a real library with audio stays "blocked" in
  `docs/parity/parity.json`; no story is marked pass on fake or JVM evidence alone.

## Out of scope

Store distribution; Android Auto; USB export; proprietary hardware databases; credential
rotation/revocation protocol (a server-side gap recorded in `contracts/sync/v1/protocol.json`);
changing the existing `/v1/resources/...` route semantics; smart playlists; DJXML export from
Android.

## Open questions to resolve during planning

1. Exact `asset_file` revision semantics (use the file hash, or a row revision) - decide with the
   sample-lib owner.
2. Whether the Sync tab keeps its name or becomes "Server" (UX only; no protocol effect).
3. WorkManager cadence default (proposed: 15 minutes on unmetered network, manual otherwise).
