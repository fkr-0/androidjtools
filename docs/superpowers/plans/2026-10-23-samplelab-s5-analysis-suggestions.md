# Sample Lab Integration S5 (Analysis Suggestions) — wire contract and plan

**Goal:** Android can see tempo, key, beat-grid and cue-candidate suggestions that Sample Lib already holds, ask for them on demand, and accept them — without analysis ever becoming mandatory or automatic.

**Parties:** `sample-lib` server (worktree `/home/user/code/sample-lib-s5`, branch `feat/android-analysis-suggestions`) and this app. The same suggestion shape is meant to be reusable by the web frontend and the oversampler bridge, so it is derived from Sample Lib's existing persisted analysis, not invented per client.

## Principles (user decision, binding)

1. **Low key.** No pull, hello, open or sync ever starts analysis. Suggestions are a read-only view of analysis results that already exist. Analysis runs only when a client explicitly asks (`analysis/request`), and a cached result is reused instead of recomputed.
2. **No mandatory confidence.** `confidence` is optional and informational: whatever the producing run recorded, else `null`. The server never computes a confidence on pull; the app never blocks, ranks away or hides a suggestion because it is missing. No "review required" gate on accept (the accept is the human review).
3. **No provider is not an error.** If no analysis worker is configured, request answers `unavailable` (HTTP 200) with a human message; pull simply has no suggestions.
4. **Server stays the authority.** Accepting changes the server's interval; the app shows the new value only after the next pull. A dismissal is local to the device.
5. **Open vocabulary.** `kind` is an open string. Clients ignore kinds they do not know. This is the extension point for later kinds (stems, vocal transcript/alignment, generic timed events); none of those are implemented in this stage.

## 1. Capabilities (hello)

`capabilities.analysis` stays a **list of strings**: the suggestion kinds this server can present. After this change: `["tempo", "key", "beat_grid", "cue_candidates"]` (always, they are derived from persisted rows).

New key `capabilities.analysis_request`: `"enqueue_v1"` when the request endpoint exists **and** at least one analysis provider is configured; the string `"unavailable"` otherwise. Clients that do not know the key ignore it.

`capabilities.mutations` additionally contains `analysis_suggestion.accept`.

## 2. Pull: `analysis_suggestion` family

- New pull scope `analysis`, authorized by pairing scope `analysis.read` (already in `PAIRING_SCOPES`). It is **not** part of the `library` scope. A pull whose scopes include `analysis` without the credential holding `analysis.read` fails with 403 `scope_forbidden` like other scopes. Android sends `["library", "analysis"]` in one pull when `capabilities.analysis` is non-empty and the credential has the scope.
- Entity type `analysis_suggestion`, schema `sample-lib.analysis-suggestion/v1`, default value-derived revision, tombstones like other families.
- Entity id: `"{analysis_run_id}:{kind}:{scope_id}"` where `scope_id` is the interval id if the run is interval-scoped, else the asset id.
- Value:

| Field | Meaning |
| --- | --- |
| `id` | the entity id |
| `kind` | `tempo`, `key`, `beat_grid` or `cue_candidates` |
| `asset_id` | owning asset |
| `interval_id` | interval the run was scoped to, or `null` for an asset-level run |
| `analysis_run_id` | the persisted `analysis_runs.id` it came from |
| `tool` | `analysis_runs.tool_name` |
| `tool_version` | `analysis_runs.tool_version` or `null` |
| `created_at` | run `completed_at` |
| `confidence` | number or `null` (see principles) |
| `data` | kind specific, below |

`data` by kind:

- `tempo`: `{"bpm": number, "alternatives": [number]}` — `alternatives` may be empty; if present it carries the half/double-time aliases (`bpm/2`, `bpm*2`) so the user can choose; the server does **not** decide between them.
- `key`: `{"key_label": "A minor", "root": "A", "mode": "minor"}` — `root`/`mode` null when the label could not be split.
- `beat_grid`: `{"bpm": number, "first_beat_ms": int, "beat_times_ms": [int]}` — `beat_times_ms` capped at 512 entries (first 512), `first_beat_ms` its first element.
- `cue_candidates`: `{"points_ms": [int]}` — ascending, deduplicated, absolute track milliseconds (capped at 256).

A suggestion is emitted only when the run is `completed` and the needed values are valid; otherwise nothing is emitted (no placeholder entities). Only the **latest completed run per (asset or interval, kind)** yields a suggestion, so a re-run replaces (tombstone + new id) the previous one. Source: the same persisted runs the existing `GET /v1/mir/*` routes read (`mir.essentia_lowlevel` / `mir.tempo_candidates` / `mir.onsets` / `sample-intelligence.mir_chop_workbench` and the `ai_analysis_features` row), reusing their normalisation. Pull never calls a provider.

## 3. Request: `POST /v1/sync/analysis/request`

- Bearer credential with scope `sync.write`. Body: `{"asset_id": str, "kinds": [str] (optional, default all four), "force": bool (default false)}`.
- Never blocks on a provider: work is queued on the existing AI job lane (`AiJobManager`) or an equivalent bounded background mechanism.
- 200 response: `{"asset_id", "status": "cached" | "queued" | "running" | "unavailable", "kinds": {"<kind>": "cached"|"queued"|"running"|"unavailable"}, "job_id": str|null, "message": str|null}`.
  - `cached`: a completed run already yields that kind and `force` is false; nothing is started.
  - `queued`/`running`: a job exists (an in-flight job for the same asset is joined, not duplicated).
  - `unavailable`: no configured provider can produce the kind; `message` says so.
- Unknown asset → 404 `entity_not_found`; unknown kind strings are ignored in `kinds` response as `unavailable`.
- Result delivery is through the next pull (no polling endpoint is required). The app refreshes when the user asks.

## 4. Accept: mutation `analysis_suggestion.accept`

Envelope: `entity_type="analysis_suggestion"`, `entity_id=<suggestion id>`, `base_revision=<the suggestion's pulled revision>` (required), `payload={"target_interval_id": "<interval id>"}`.

- `target_interval_id` is required for asset-level suggestions (`interval_id == null`); it must belong to the suggestion's asset. For interval-scoped suggestions it must equal `interval_id` (or be omitted).
- Effects (one SQLite transaction, through Sample Lib's existing interval/hypothesis authority, not raw SQL scattered in the facade):
  - `tempo`: set the interval's `bpm` to the suggested value (the client may instead send `payload.bpm` equal to one of `alternatives`; any other value → `invalid_payload`); record a `bpm_hypotheses` row (`source_kind` `android_accept`, `analysis_run_id`, `requires_human_review = 0`); set `bpm_confidence` from the run confidence only if present.
  - `beat_grid`: same as `tempo` using `data.bpm`. The downbeat offset is **not** persisted server-side in v1 (no authority for it); it stays a display/local value on the device.
  - `key`: set `key_label`, `root`, `mode` on the interval.
  - `cue_candidates`: not accepted over this mutation (`rejected`, code `use_editor_state_replace`); the app turns candidates into cues with the S3 `interval.editor_state.replace` path.
- Receipt outcomes: `applied` (canonical = the interval's editor state after the change), `no_op` (interval already has exactly these values), `conflict` (the suggestion was replaced or removed since `base_revision`; code `suggestion_changed`; `remote_value` the current suggestion or `null`), `rejected` (`entity_not_found`, `target_interval_required`, `target_interval_mismatch`, `invalid_payload`, `use_editor_state_replace`, `base_revision_required`). Idempotent exactly like the editor mutation (same pending/receipt wrapper, same id-reuse rejection).
- The interval entity's editor-state revision changes when its bpm/key fields change, so the next pull delivers the new value; nothing else needs to be pushed.

## 5. Android behavior

- Cache + projection: `analysis_suggestion` entities are cached like other families and projected per track (grouped by asset, kind). Unknown kinds and malformed values are skipped, never fatal.
- Analysis screen: shows, for the loaded track, the cached suggestions with source tool and the optional confidence (shown only when present, never required), and Accept / Dismiss per suggestion. Tempo with alternatives lets the user pick one. Beat-grid shows bpm and first beat. Key shows the label. Cue candidates show a count and "Add as cues" (S3 path, provenance `source=analysis`, `analysis_run_id`).
- "Analyse this track" button calls the request endpoint, only offered when `analysis_request == "enqueue_v1"`; otherwise one line says the server has no analysis available. Never called automatically.
- Accept of tempo/beat-grid/key goes through the durable journal as `analysis_suggestion.accept`; statuses reuse the S3 vocabulary (waiting / sending / saved / needs decision / not accepted). A `conflict` offers only "Dismiss" (refresh will show the replacement suggestion); there is no reapply.
- Dismissals are stored locally keyed by `suggestion id + revision`; a replaced suggestion reappears.
- The library projection changes only from pulled server state (accepted BPM/key appear after the next pull), as in S3.

## 6. Out of scope here (stated so nothing is claimed)

Server-side analysis computation of its own (it still depends on configured workers), confidence benchmarking, Demucs stem products, vocal transcription / word alignment to the beat grid, persisting a downbeat offset, device verification of the Compose UI.

## Evidence required from each side

- Server: pytest for each pull kind (including "never calls a provider on pull"), scope gating, request (`cached`/`unavailable`/`queued`, joins in-flight), accept (applied / no_op / conflict / rejected / idempotent replay), OpenAPI snapshot regenerated, contract doc `docs/contracts/android-sync-v1.md` updated, branch committed (no push).
- Android: TDD unit tests (projection, provider, planner, journal replay of the new operation), fake server updated, live-contract tests against the real server worktree, lint, debug+release build, CHANGELOG, known limitations.
