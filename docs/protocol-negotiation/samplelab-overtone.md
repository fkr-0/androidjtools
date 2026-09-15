# Sync protocol negotiation — Sample Lab / intelligence / Overtone advocate

Status: negotiated protocol proposal for synthesis mediator integration

Canonical OCP task: `ANDROIDJTOOLS-PROTOCOL-SAMPLELAB-OVERTONE-20260915`

Date: 2026-09-15

## 1. Decision

Android DJ Tools MUST integrate Sample Lab, Sample Intelligence and Overtone through one mediated, provider-neutral protocol while preserving the existing authority split:

- **Sample Lib owns durable library/preparation truth**: asset/file identity, hashes, intervals, cues/loops/markers, tags, human annotations, analysis-run records, derived-file registration, accepted preparation decisions, provenance, revisions, conflicts and mutation receipts.
- **Sample Lab coordinates library/editor/analysis services**: API/UI coordination, provider brokering, capability discovery, analysis orchestration and mediation around Sample Lib. It MUST NOT create a competing durable library authority.
- **Sample Intelligence is advisory**: it consumes stable Sample Lib identity and normalized evidence and emits reproducible evidence/candidates. It MUST NOT execute model output directly or become a second catalog.
- **Overtone owns performance execution**: playback, audition, transport, mixer, sequencing, synthesis, effects, runtime render semantics and other performance/project-runtime behavior. It MAY cache or mirror Sample Lib data for execution, but MUST NOT become a second durable library/preparation authority.
- **Android is a synchronized client and confirmation surface**. It MUST NOT connect directly to provider workers, ComfyUI, Overtone's native RPC/WebSocket, SuperCollider or Sample Lib storage.

The key consequence is that a user tap on Android is not itself a library commit. A durable library mutation is complete only after the canonical Sample Lib/sync receipt says it is complete. Likewise, an Overtone runtime acknowledgement is not a Sample Lib commit.

## 2. Grounding in current repositories

This negotiation deliberately extends existing contracts instead of inventing a parallel AI or music-production protocol.

Current Android contracts already establish that:

- Sample Lib is canonical for synchronized entities;
- mutations are idempotent and carry `mutation_id`, `base_revision`, operation, payload and provenance;
- receipts are authoritative and conflicts are explicit;
- realtime messages are wake-up hints while cursor-based pull remains durable truth;
- intelligence suggestions are advisory and accepted suggestions become normal sync mutations;
- multiple candidates may coexist and stale suggestions are not auto-applied.

Current Sample Lab/Sample Lib behavior establishes that:

- original audio is immutable after ingest and derived files are registered with provenance;
- provider execution, credentials, paths, timeouts and raw diagnostics remain server-side;
- heavy analysis is represented through durable analysis/progress records and registered result resources;
- Overtone edit sessions are durable, idempotent handoffs; their proposal is presentation-only until accepted, and accepted results are revision/fingerprint guarded;
- browser-facing flows are mediated through Sample Lib/Sample Lab rather than browser-to-Overtone transport.

Current Sample Intelligence behavior further establishes that normalized evidence is path-free, parent identity is checked fail-closed, provider output is allow-listed rather than copied wholesale, and learned/advisory output must target bounded structured contracts.

Current Overtone integration establishes useful implementation precedent:

- Sample Lab resource references are allow-listed and local cache downloads are hash checked;
- ambiguous mutations are reconciled by deterministic readback rather than blind retry;
- public analysis results strip file paths, worker request/response bodies and other sensitive internals;
- runtime activity streams are diagnostic/UI data and may be lossy without compromising durable state.

### 2.1 Current-state caveat: ComfyUI ASR/BPM

The inspected Sample Lab checkout contains concrete provider paths for Demucs stem separation, Basic Pitch transcription/note evidence and Essentia MIR. It also contains ComfyUI-backed provider work for other audio workflows, but **no verified canonical ComfyUI ASR/BPM backend was found during this negotiation**.

Therefore `analysis.speech_transcript` and `analysis.tempo` below are stable semantic capability slots. A future ComfyUI ASR or BPM workflow MAY implement them, but Android MUST treat provider availability as runtime data and MUST NOT assume that a provider named ComfyUI exists. The protocol does not make an implementation claim that the current server cannot prove.

## 3. Authority invariants

The synthesis mediator MUST enforce the following invariants independently of transport or provider implementation.

| Concern | Durable authority | Android view | Overtone role |
| --- | --- | --- | --- |
| Asset/file identity and content hash | Sample Lib | stable IDs + safe media refs | cache/runtime binding only |
| Intervals, cues, loops, markers | Sample Lib | canonical state + proposals | consume state; propose changes |
| Tags and preparation metadata | Sample Lib | canonical state + explicit conflicts | consume/propose, never silently canonicalize |
| Analysis run/provenance | Sample Lib | normalized job/result references | request/consume through mediator |
| Similarity/feature evidence | Sample Intelligence derived from Sample Lib identity | advisory evidence/candidates | consume if useful |
| Stem/transcription derived media registration | Sample Lib | opaque media refs | fetch/cache by safe ref |
| Playback/transport/mixer/synthesis | Overtone | optional coarse runtime status/actions | canonical runtime authority |
| Runtime node/bus/synth/scheduler state | Overtone | not exposed | internal |
| Accepted Android library edit | Sample Lib receipt | authoritative success/conflict | receives committed snapshot if needed |
| Overtone-originated cue/loop proposal | mediator + Sample Lib on acceptance | confirmation request | requester/proposer, not library authority |

An Overtone project MAY contain local/runtime state, but cross-system library/preparation semantics MUST cross the boundary as proposals or committed Sample Lib snapshots. Existing local bridge compatibility such as an Overtone-preferred metadata resolution mode MUST NOT be interpreted by Android as equal canonical authority.

## 4. Stable identifiers and event envelope

Provider or language-specific identifiers MUST NOT become protocol keys. Every cross-system message SHOULD use the following envelope shape:

```json
{
  "schema": "androidjtools.protocol-event/v1",
  "event_id": "evt_...",
  "type": "analysis.job.changed",
  "occurred_at": "2026-09-15T13:00:00Z",
  "correlation_id": "corr_...",
  "causation_id": "evt_...",
  "origin": "sample_lab",
  "truth_scope": "analysis_orchestration",
  "subject": {
    "asset_id": "asset_...",
    "interval_id": "interval_...",
    "request_id": null,
    "job_id": "job_..."
  },
  "payload": {}
}
```

Normative fields:

- `event_id` MUST be globally unique enough for de-duplication.
- `correlation_id` MUST survive the full request → confirmation → mutation → receipt → requester-reply chain.
- `causation_id` SHOULD identify the immediately preceding event when known.
- `origin` identifies the producer (`sample_lib`, `sample_lab`, `sample_intelligence`, `overtone`, `mediator`, `android`).
- `truth_scope` MUST be one of `library`, `analysis_orchestration`, `advisory`, `performance`, or `mediation` so a client does not confuse a runtime observation with canonical library state.
- Subject identifiers MUST be stable domain identifiers. Clojure keywords, SuperCollider node IDs, worker prompt IDs and host paths MUST NOT appear here.

Realtime delivery MAY carry this envelope, but realtime delivery is only a wake-up/latency optimization. Android MUST be able to re-read authoritative state by cursor or stable identifier after any missed or duplicated event.

## 5. Android-visible DTOs

### 5.1 `CapabilityDTO`

```yaml
schema: androidjtools.analysis-capability/v1
capability_id: analysis.stems
label: Stem separation
availability: available | degraded | unavailable | unknown
operations: [request, read, cancel]
input_scopes: [asset, interval]
output_kinds: [stem_media]
estimated_cost: local_low | local_medium | local_high | remote_variable | unknown
provider:
  family: demucs            # diagnostic label; never a dispatch key
  version: optional-bounded-string
status_reason: optional-user-safe-string
observed_at: RFC3339
```

Stable semantic capability IDs SHOULD include at least:

- `analysis.stems`
- `analysis.note_transcription`
- `analysis.speech_transcript`
- `analysis.tempo`
- `analysis.key`
- `analysis.structure`
- `analysis.related_tracks`
- `analysis.audio_safety`

Android MUST branch on `capability_id`, input/output kinds and availability, not on provider family, model name or endpoint.

A capability snapshot is not execution proof. A previously available provider MAY fail when a job starts; the job result remains authoritative for that execution.

### 5.2 `AnalysisJobDTO`

```yaml
schema: androidjtools.analysis-job/v1
job_id: job_...
analysis_run_id: run_... | null
correlation_id: corr_...
capability_id: analysis.stems
asset_id: asset_...
interval_id: interval_... | null
input_identity:
  server_revision: 41
  content_sha256: optional-sha256
state: queued | running | ready | partial | failed | cancelled | unavailable | stale
progress:
  fraction: 0.0..1.0 | null
  stage: optional-user-safe-label
submitted_at: RFC3339
started_at: RFC3339 | null
completed_at: RFC3339 | null
result_refs:
  candidate_ids: []
  media_refs: []
  finding_ids: []
error:
  code: stable-error-code
  message: user-safe-message
  retryable: true | false
```

Rules:

- `job_id` MAY be an orchestration/mediator ID while `analysis_run_id` identifies the durable Sample Lib record. The mediator MUST preserve the mapping.
- A result MUST NOT be `ready` merely because a worker returned bytes. Required derived media/evidence MUST first be registered in Sample Lib with provenance.
- Current bridge state `deferred` caused by provider unavailability SHOULD normalize to `unavailable` plus `status_reason`; Android does not need a provider-specific `deferred` state.
- Provider logs, stdout/stderr, worker request bodies, worker URLs, local paths and credentials MUST NOT be copied into `error`.

### 5.3 `CandidateDTO`

```yaml
schema: androidjtools.analysis-candidate/v1
candidate_id: cand_...
analysis_run_id: run_... | null
asset_id: asset_...
interval_id: interval_... | null
kind: bpm | key | cue | loop | region | transcript_marker | related_track | mix_prep
value: {}
confidence: 0.0..1.0 | null
source:
  service_family: sample_intelligence | demucs | comfyui_asr | tempo_ensemble | other
  method_version: optional
  pipeline_version: optional
  parameters_digest: optional-digest
generated_at: RFC3339
input_identity:
  server_revision: 41
  content_sha256: optional-sha256
freshness:
  state: fresh | stale | unknown
  reason: optional-stable-reason
  observed_revision: 41 | null
decision: proposed | accepted | rejected | superseded
```

Rules:

- Candidates are advisory. `decision: accepted` is meaningful only after the associated normal sync mutation receives an authoritative receipt.
- Multiple BPM/key/loop candidates MAY coexist. The service MUST NOT collapse half/double-tempo alternatives merely to simplify the UI.
- Candidate input identity MUST be checked before acceptance. Revision/content drift MUST fail closed as stale; no automatic rebase is allowed.
- A model/provider family MAY be shown as provenance, but Android code MUST NOT depend on its raw model/checkpoint/workflow identity.
- Raw Sample Intelligence embedding vectors and index paths are server-internal.

A BPM candidate value SHOULD use a provider-independent shape such as:

```yaml
bpm: 89.8
meter: 4/4 | null
relation: primary | half_time | double_time | alternate
confidence_basis: optional-user-safe-string
```

### 5.4 `MediaRefDTO`

Derived media MUST cross the boundary by identity, never by host path.

```yaml
schema: androidjtools.media-ref/v1
resource_uri: stem://resource_...
file_id: file_... | null
parent_asset_id: asset_...
analysis_run_id: run_... | null
role: original | preview | stem | transcription | waveform | render
stem_role: vocals | drums | bass | other | null
mime_type: audio/wav | application/json | audio/midi | other
duration_ms: 12345 | null
content_sha256: sha256...
byte_size: 123456 | null
```

A server MAY mint a short-lived authenticated fetch URL from a `resource_uri`, but the signed URL is transport material and MUST NOT replace the durable resource identity.

For Demucs, Android sees registered stem resources and semantic stem roles. It MUST NOT receive Demucs output directories, CLI arguments, model paths, device selection or worker ports.

### 5.5 `TranscriptMarkerDTO`

```yaml
schema: androidjtools.transcript-marker/v1
marker_id: marker_... | null
candidate_id: cand_... | null
asset_id: asset_...
interval_id: interval_... | null
start_ms: 15500
end_ms: 17200 | null
text: bounded-user-visible-text
language: BCP47-tag | null
speaker_label: optional-neutral-label
confidence: 0.0..1.0 | null
source: <CandidateDTO.source>
freshness: <CandidateDTO.freshness>
durability: proposal | accepted
```

ASR word/token timestamps MAY remain server-side. Android receives bounded phrase/marker projections appropriate for navigation and confirmation. Accepting a proposal MUST create a normal Sample Lib marker/annotation mutation with candidate provenance; an ASR response alone is not a durable marker.

### 5.6 `SafetyFindingDTO`

Safety/quality findings are not ordinary accept/reject candidates because hiding them behind candidate ranking would be unsafe and confusing.

```yaml
schema: androidjtools.safety-finding/v1
finding_id: finding_...
asset_id: asset_...
interval_id: interval_... | null
category: loudness | clipping | spectral_outlier | silence | corrupted_media | speech_sensitive | other
severity: info | warning | blocking
message: bounded-user-safe-message
evidence_refs: []
source: <CandidateDTO.source>
observed_at: RFC3339
input_identity:
  server_revision: 41
freshness:
  state: fresh | stale | unknown
status: active | acknowledged | resolved | stale
policy_authority: sample_lib_policy | application_policy | none
```

Rules:

- A provider MUST NOT become a safety authority merely by emitting a warning. `policy_authority` is explicit.
- Warning/blocking findings MUST remain visible independently of unrelated candidate confidence/ranking.
- Acknowledgement MAY be user state; it MUST NOT rewrite the underlying evidence.

## 6. Mediated Overtone action requests

Overtone MUST NOT write canonical cue/loop state directly through an Android-specific bypass. Instead it sends a bounded request to the synthesis mediator.

### 6.1 `MediatedActionRequestDTO`

```yaml
schema: androidjtools.mediated-action-request/v1
request_id: req_uuid_owned_by_requester
correlation_id: corr_...
requester:
  kind: overtone
  instance_id: optional-stable-instance-id
  label: Overtone
intent: cue.confirm_set | loop.confirm_set | marker.confirm_set
target:
  asset_id: asset_...
  interval_id: interval_... | null
  expected_revision: 41
  content_sha256: optional-sha256
proposal:
  cue:
    position_ms: 4210
  loop:
    start_ms: 4210
    end_ms: 12450
reason: Please confirm this loop from the current performance context.
created_at: RFC3339
expires_at: RFC3339 | null
state: received | validated | awaiting_user | mutation_queued | mutation_submitted | committed | needs_reconfirmation | rejected | expired | failed | requester_replied
```

Canonical cue/loop coordinates MUST be asset/interval-relative time coordinates, preferably integer milliseconds under the current Sample Lib convention. Overtone MAY use beat/bar/sample-frame coordinates internally, but the mediator MUST resolve them to the canonical asset identity and explicit time positions before Android confirmation. Runtime tempo maps are not durable cue coordinates.

Overtone-local sample IDs, synth IDs, buffer IDs and scheduler identities MAY be retained in mediator-private correlation state but MUST NOT be required by Android.

### 6.2 Request lifecycle

```text
                 ┌──────────── invalid/unauthorized ───────> failed ──┐
received -> validated -> awaiting_user                               │
                           │      │      │                            │
                           │      │      └─ expires/cancel -> expired ┤
                           │      └─ reject -----------> rejected ────┤
                           │                                          │
                           └─ accept                                  │
                              v                                       │
                       mutation_queued                                │
                              v                                       │
                     mutation_submitted                               │
                       │       │       │                               │
                    applied   no_op   conflict/rejected                │
                       │       │       │                               │
                       └──> committed  └-> needs_reconfirmation/failed│
                              │                                       │
                              └---------------------------------------┤
                                                                      v
                                                               requester_replied
```

Required semantics:

1. `request_id` is requester-owned and idempotent.
2. Replaying the same `request_id` with an identical target/proposal returns current request state. Reusing it with materially different content is a protocol conflict.
3. The mediator validates target identity and expected revision before asking the user.
4. Android displays the proposal and origin and asks for explicit confirmation for durable cue/loop/marker mutation.
5. Acceptance creates the existing normal sync mutation with a fresh `mutation_id`; `request_id`, `correlation_id` and proposal/candidate identity are placed in provenance.
6. If Android is offline, acceptance MAY be journaled according to existing offline-first sync rules, but the mediator MUST NOT tell Overtone that the mutation is applied until the authoritative receipt arrives.
7. `applied` and `no_op` receipts advance the request to `committed`.
8. A conflict MUST NOT be auto-rebased. The mediator re-reads canonical state and transitions to `needs_reconfirmation`; the user must confirm a newly projected proposal against the new revision.
9. User rejection, expiry, canonical mutation rejection and terminal failure all require a terminal requester reply so Overtone cannot wait indefinitely.
10. Ambiguous transport failure MUST be reconciled by stable IDs/readback. The mediator MUST NOT blindly replay a mutation that may already have landed.

### 6.3 `RequesterReplyDTO`

```yaml
schema: androidjtools.mediated-action-reply/v1
request_id: req_...
correlation_id: corr_...
disposition: applied | no_op | rejected_by_user | expired | conflict | failed | pending
canonical:
  server_revision: 42 | null
  asset_id: asset_...
  interval_id: interval_... | null
  value: optional-canonical-cue-loop-marker
error:
  code: optional-stable-code
  retryable: true | false
```

A mediator MAY send `pending` as an intermediate reply when the requester benefits from low-latency acknowledgement, but exactly one terminal disposition MUST remain queryable by `request_id`.

### 6.4 Overtone runtime application after library commit

After a Sample Lib mutation commits, Overtone MAY consume the committed snapshot and apply it to performance/runtime state. That is a separate performance action.

If runtime application fails after the library commit:

- the Sample Lib commit remains authoritative and MUST NOT be silently rolled back;
- the mediator SHOULD report a distinct `performance_apply_failed` condition to the requester/Android;
- retrying runtime application MAY be offered without repeating the library mutation.

## 7. Analysis and intelligence lifecycle

### 7.1 Capability discovery

Android SHOULD obtain a mediator-normalized capability snapshot at pairing/session start and refresh it when signalled or before expensive work. Capabilities fail independently: Demucs may be unavailable while tempo analysis remains usable.

Android MUST NOT infer capability from hard-coded service names or ports.

### 7.2 Job lifecycle

```text
not_requested
     |
     v
   queued ---> cancelled
     |
     v
   running ---> failed
     |  \
     |   └----> partial
     v
   ready

queued/running may -> unavailable when the backing capability disappears.
ready/partial/candidate/media results may -> stale when input revision/content identity drifts.
```

`stale` describes result applicability, not whether the historical analysis run existed. Historical provenance remains durable.

### 7.3 Candidate generation and acceptance

Sample Intelligence and provider-specific services MAY generate multiple candidates from one analysis run. The mediator MUST preserve provenance and parent identity and MUST reject evidence whose parent asset/interval/content identity drifts.

Accepted candidates flow through the same sync mutation and receipt path as human-entered edits:

```text
analysis evidence
    -> candidate(proposed)
    -> Android review
    -> sync mutation(base_revision + provenance)
    -> Sample Lib receipt
    -> candidate(accepted) projection
```

There is no special AI commit path.

### 7.4 Stem handling

Demucs and future source-separation providers MAY produce multiple derived resources. Only Sample Lib-registered resources with stable parent identity/provenance may be surfaced as `MediaRefDTO`.

A stem is not a new canonical original asset unless Sample Lib explicitly models it that way. Android MUST preserve parent/source lineage.

### 7.5 Transcript handling

Speech ASR and note transcription are distinct semantic capabilities. A future ComfyUI ASR provider must map into `analysis.speech_transcript`; current Basic Pitch-style musical transcription maps into `analysis.note_transcription`.

The mediator MUST NOT conflate lyrics/speech text with note/pitch evidence. Transcript phrase markers, note events and human annotations retain distinct source kinds and provenance policy.

### 7.6 Tempo handling

Tempo inference is inherently multi-hypothesis. Android SHOULD show a primary candidate plus meaningful half/double/alternate candidates when confidence is close enough to matter. Human acceptance creates the durable BPM/preparation mutation. Provider estimates remain evidence, not silently canonical metadata.

## 8. Event taxonomy

The synthesis mediator SHOULD expose these stable event types:

| Event | Truth scope | Purpose |
| --- | --- | --- |
| `capability.snapshot` | analysis_orchestration | provider-neutral current capability projection |
| `analysis.job.changed` | analysis_orchestration | queued/running/terminal progress hint |
| `analysis.candidate.upserted` | advisory | new or changed BPM/key/cue/loop/etc candidate |
| `analysis.media.available` | library | registered derived media reference became readable |
| `analysis.safety.finding` | advisory/library-policy | normalized safety/quality finding |
| `action.requested` | mediation | Overtone or another service requests Android confirmation |
| `action.state.changed` | mediation | mediated request lifecycle changed |
| `library.mutation.receipted` | library | authoritative sync receipt available |
| `action.requester_replied` | mediation | terminal/intermediate reply delivered/queryable |
| `performance.activity` | performance | optional coarse Overtone runtime hint |
| `performance.apply_failed` | performance | committed library state could not be applied to runtime |

`performance.activity` MUST be explicitly lossy and non-authoritative. Dropped runtime events MUST NOT change durable sync semantics.

Unknown forward-compatible event types SHOULD be ignored after envelope validation; Android SHOULD then pull the relevant durable scopes if the event was delivered as a generic wake-up.

## 9. Android-visible versus server-internal data

### 9.1 Android-visible

Android MAY receive:

- stable asset/interval/file/resource identifiers and server revisions;
- provider-neutral capability IDs, availability, supported scopes/outputs and coarse cost class;
- sanitized job progress, state, stable errors and correlation IDs;
- candidates with values, confidence, provenance summary and staleness;
- opaque registered media references, semantic stem role, duration/hash/MIME metadata;
- bounded transcript phrase markers, language and confidence;
- safety finding category/severity/message/evidence references;
- Overtone-mediated action request reason, expiry, canonical target/revision and proposed change;
- authoritative mutation receipts/conflicts;
- optional coarse performance/request-completion status.

### 9.2 Server-internal

The following MUST remain server-side unless separately exposed through a future reviewed diagnostics contract:

- filesystem paths, storage roots, SQLite paths and cache paths;
- worker URLs/ports, provider credentials, auth tokens and raw HTTP bodies;
- raw worker stdout/stderr and unbounded diagnostics;
- Demucs CLI arguments, model paths and CPU/GPU selection;
- ComfyUI workflow JSON, node IDs, prompt IDs, server URL, checkpoint/model filenames and raw history output;
- raw Sample Intelligence vectors, sidecar database paths and model runtime internals;
- Clojure namespaces/keywords/functions and runtime object identities;
- SuperCollider synth/node/group/bus/buffer IDs and OSC messages;
- Overtone scheduler atoms, runtime mapper functions and DSP graph internals unless represented by a separately versioned public effect contract;
- Overtone local sample IDs when a stable Sample Lib identity is sufficient;
- retry/backoff bookkeeping and mediator-private crosswalk tables.

A bounded provider family/version label is acceptable provenance. Android schema/behavior MUST NOT branch on it.

## 10. Failure modes and required behavior

| Failure | Required behavior |
| --- | --- |
| capability reports unavailable/deferred | disable/request gracefully; other capabilities remain usable |
| capability was available but worker fails | job becomes `failed`/`unavailable` with sanitized retryability; do not falsify success |
| malformed provider result | reject at normalization boundary; no candidate/media registration without valid identity/provenance |
| Sample Intelligence parent identity drift | fail closed; mark/reject stale evidence |
| asset revision/content changes after candidate generation | candidate becomes stale; require rerun/reconfirmation |
| derived media missing/hash mismatch | do not surface as ready/playable; preserve failed evidence for diagnosis |
| duplicate mediated `request_id`, same content | return current state idempotently |
| duplicate mediated `request_id`, different content | protocol conflict; never overwrite first request |
| Android offline when request arrives | retain until expiry; do not fabricate user confirmation |
| Android accepts offline | journal normal mutation; final requester success waits for receipt |
| request expires while offline | terminal `expired` reply; later tap cannot resurrect it without a new request |
| sync conflict after user acceptance | enter `needs_reconfirmation`; pull canonical state; no automatic rebase |
| ambiguous mutation transport result | query by mutation/request identity; do not blind retry |
| requester disconnect/restart | request/reply remains queryable by stable `request_id` |
| Overtone runtime apply fails after Sample Lib commit | keep library commit; report separate performance failure; retry runtime apply only |
| realtime/performance event is dropped | durable state unaffected; next pull reconstructs truth |
| unknown schema/capability | fail closed for mutations; tolerate/ignore unknown read-only events and refresh capabilities |
| safety provider emits warning but has no policy authority | show finding with `policy_authority: none`; do not invent a block |
| partial provider output | expose `partial` only with valid registered sub-results and explicit missing pieces |
| unpaired/unauthorized client | reject before domain action; do not leak capabilities/resources beyond auth policy |

## 11. Normative requirements

### 11.1 MUST

The synthesis mediator and participating services MUST:

1. preserve Sample Lib as sole durable library/preparation authority;
2. preserve Overtone as performance/runtime authority without promoting its caches/project mirrors into canonical library state;
3. require stable asset/interval identity plus revision/content identity for mutation-sensitive candidates and mediated requests;
4. route accepted AI suggestions and Overtone proposals through the existing normal sync mutation/receipt mechanism;
5. keep a single end-to-end `correlation_id` and requester-owned idempotent `request_id` for mediated actions;
6. emit a terminal requester reply for applied/no-op/rejected/expired/conflict/failed requests;
7. make conflicts and staleness explicit and never auto-rebase or auto-accept across them;
8. use opaque registered resource identities instead of host paths;
9. redact provider/runtime internals at the Android boundary;
10. treat provider capability and analysis failures independently;
11. distinguish speech transcript, musical note transcription, tempo evidence and safety findings semantically;
12. preserve candidate/provenance identity when a suggestion becomes a durable mutation;
13. treat realtime events as hints, with durable cursor/readback as recovery truth;
14. fail closed on parent identity/provenance drift;
15. keep safety-policy authority explicit rather than inferred from provider output.

### 11.2 SHOULD

Implementations SHOULD:

- expose sanitized progress and retryability for long-running analysis;
- support offline journaling of user-confirmed normal mutations without claiming premature success;
- preserve multiple meaningful tempo/cue/loop candidates rather than prematurely collapsing them;
- keep provider family/version visible as diagnostic provenance while provider-neutral IDs drive behavior;
- offer a short-lived signed fetch URL derived from a stable resource reference when direct media retrieval is needed;
- allow an intermediate `pending` requester reply for latency-sensitive Overtone flows;
- expose request status readback by `request_id` after requester or Android restart;
- separate library commit from downstream runtime application status;
- keep transcript text/marker payloads bounded and paginate large transcript bodies if needed.

### 11.3 MAY

Implementations MAY:

- expose coarse Overtone transport/playback/performance status to Android;
- provide advanced diagnostics behind a separately versioned/developer-only view;
- add new provider families without changing Android behavior if they implement existing semantic capabilities;
- add new semantic capabilities through versioned capability discovery;
- cache Sample Lib resources inside Overtone by stable identity/hash;
- use provider-specific scheduling/cost policy entirely behind Sample Lab.

## 12. Required contract tests

The synthesis mediator should not be considered protocol-complete until at least the following tests exist.

1. **Authority gate** — an Overtone cue/loop request cannot mutate Sample Lib before explicit Android confirmation and an authoritative sync receipt.
2. **Correlation continuity** — `request_id`/`correlation_id` survive Overtone request → Android projection → mutation provenance → receipt → requester reply.
3. **Request replay** — identical duplicate request is idempotent; same ID with changed payload is rejected.
4. **Conflict re-confirmation** — remote revision advances before accepted mutation; mediator emits `needs_reconfirmation` and never silently rebases.
5. **Offline acceptance** — Android can journal the confirmed mutation offline, but Overtone receives no false `applied` reply before sync receipt.
6. **Request expiry** — expired offline request terminates and cannot be committed by a late tap.
7. **Stale intelligence** — candidate generated at revision N cannot be accepted at revision N+1 without explicit rerun/reconfirmation.
8. **Multiple tempo candidates** — primary/half/double alternatives coexist and one accepted candidate preserves its provenance.
9. **Demucs redaction** — stem DTO contains opaque resource/hash/role identity and no path, worker URL, CLI/model/device field.
10. **ASR redaction** — transcript marker DTO contains semantic text/timing/provenance but no ComfyUI prompt/node/workflow/checkpoint/server details.
11. **Provider absence** — no ComfyUI ASR/BPM backend present results in unavailable/unknown semantic capability, not a client error or hard-coded fallback.
12. **Partial outage** — stems unavailable while tempo/other capabilities remain usable.
13. **Evidence drift** — Sample Intelligence evidence whose parent hash/revision differs is rejected fail-closed.
14. **Safety visibility** — warning/blocking finding remains visible even when unrelated suggestions are low confidence or rejected.
15. **Media integrity** — hash mismatch prevents a derived resource from becoming ready/playable.
16. **Ambiguous mutation transport** — mediator reconciles by ID/readback and creates no duplicate mutation.
17. **Runtime event loss** — dropping `performance.activity` does not alter durable library/sync state.
18. **Post-commit runtime failure** — Overtone runtime apply failure does not roll back the Sample Lib commit and can be retried independently.
19. **Forward compatibility** — unknown read-only event type does not crash Android; mutation schema mismatch fails closed.
20. **Boundary leak test** — serialized Android DTO fixtures reject/omit filesystem paths, worker endpoints, credentials, Clojure/SuperCollider runtime IDs and raw provider payloads.

## 13. Demands to the synthesis mediator

The Sample Lab / intelligence / Overtone side requires the mediator to provide:

1. **One canonical mediated-action envelope**, not a special Overtone-only Android transport.
2. **Caller-owned idempotent request IDs plus end-to-end correlation IDs**.
3. **Sample Lib revision/content identity on every mutation-sensitive request/candidate**.
4. **Normal sync mutation + authoritative receipt for every accepted durable library edit**, whether proposed by a human, Sample Intelligence, Demucs/ASR/MIR output or Overtone.
5. **Provider-neutral capability discovery** with independent degradation.
6. **Opaque resource references and server-side resource resolution**, never host paths.
7. **Queryable request lifecycle and terminal requester replies**.
8. **Conflict/staleness fail-closed behavior**, including explicit re-confirmation after canonical drift.
9. **A redaction/normalization boundary** before Android sees analysis or runtime payloads.
10. **No browser/mobile direct Overtone or worker transport**.
11. **Separate scopes for library truth, advisory evidence and performance activity**.
12. **Durable recovery by cursor/stable ID even if all realtime events are lost**.

## 14. Concessions offered to the synthesis mediator

To avoid over-constraining implementation, this side accepts that:

- Sample Lab/mediator MAY own ephemeral orchestration `job_id` values as long as they map to durable Sample Lib `analysis_run_id` where one exists.
- Overtone MAY maintain local caches, Sample Lib-to-runtime crosswalks, performance project state and native effect graph/runtime identities behind the boundary.
- Provider family/version labels MAY be exposed for diagnostics and provenance; Android simply cannot dispatch or branch on provider internals.
- Realtime events MAY be lossy and transport-specific because cursor/readback remains the recovery contract.
- Stable resource references MAY be resolved to short-lived transport URLs when required for authenticated streaming/download.
- Overtone MAY generate proposals from live performance observations and MAY immediately use already-committed Sample Lib state for performance.
- A requester MAY receive an intermediate `pending` acknowledgement before final mutation receipt.
- New model/provider implementations, including future ComfyUI ASR/BPM workflows, MAY be introduced without protocol changes when they satisfy existing semantic capability/output contracts.

## 15. Non-goals

This negotiation does not:

- add production code or choose an Android networking library;
- define raw Overtone JSON-RPC, Clojure, OSC or SuperCollider APIs;
- expose ComfyUI workflow/node/model contracts to Android;
- promise that a currently unavailable provider exists;
- make Sample Intelligence a library owner;
- make Android a model-orchestration console;
- make Overtone a second Sample Lib database/catalog;
- replace existing sync mutation/receipt or offline journal semantics;
- authorize automatic application of AI/performance proposals without explicit policy/user confirmation.

## 16. Synthesis outcome

The synthesis mediator can therefore converge the Android sync and intelligence lanes around one rule:

> **Evidence and performance systems may propose; Sample Lib commits; Android confirms/reviews; Overtone performs.**

That rule is strong enough to support Demucs, current MIR/transcription providers, Sample Intelligence and future ComfyUI ASR/BPM implementations without coupling Android to any current server language, worker topology or model runtime.
