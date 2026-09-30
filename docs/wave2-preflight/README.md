# Wave Two parallel preflight / guarded execution

Canonical OCP task: `ANDROIDJTOOLS-WAVE2-PARALLEL-PREFLIGHT-20260927`

Current workflow packet: `f41b51dd-2e91-40c4-ad04-faea34553759`
Lease: `87fd7365-fd20-4982-9ae1-fe811bf0e410`
Claim: `3c2ee024-f67d-40da-9e84-a5b548dd19b6` on `docs/wave2-preflight/**`

This is a documentation-only guarded preflight. No separate Wave Two implementation
packet/lease/claim was issued to this agent, so this run does not mutate
`app/**`, `contracts/**`, `test-server/**`, Gradle/build files, or the
canonical epic/workflow/user-story YAML files.

## Verified facts

### Repository/workflow state

- Branch: `main`, HEAD `131b33ef3118882e7aa414f9b245be10a5ef37c7`.
- The checkout is substantially dirty; inherited/concurrent work is preserved.
- Workflow policy requires managed reservation, transactional mutation and
  independent review.
- Current live project presence includes this preflight session plus the
  separately scoped protocol-negotiation lane. The protocol lane claim is only
  `docs/protocol-negotiation/android-client.md`; it does not authorize Wave Two
  implementation.
- Wave Two policy is EPIC-02, EPIC-05, EPIC-06 and EPIC-08 and depends on
  `wave_1_ui_primitives`.

### Dependency graph

| Wave Two epic | Direct dependency | Wave-level gate |
|---|---|---|
| EPIC-02 SEARCH/FILTER | EPIC-01 LIBRARY | all Wave One primary screens accepted |
| EPIC-05 CUES/LOOPS | EPIC-04 WAVEFORM | all Wave One primary screens accepted |
| EPIC-06 BEATGRID | EPIC-04 WAVEFORM | all Wave One primary screens accepted |
| EPIC-08 PLAYLISTS | EPIC-01 LIBRARY | all Wave One primary screens accepted |

Direct dependency completion does not waive the Wave One exit condition.

### Wave Zero observations

Wave Zero has strong implementation evidence but is not a single uniformly
closed lifecycle state.

- EPIC-00 foundation packet
  `47d6c3b8-f64c-4ad5-91e2-f535c17beb20`: current workflow state `ready`
  after a review request for missing real Android runtime launch evidence.
  Source/build evidence exists; actual device/emulator launch remains unclaimed.
- EPIC-10 sync protocol: independently approved canonical protocol synthesis
  exists from the 2026-09-15 line.
- EPIC-16 debug harness: independently approved implementation evidence exists
  from the 2026-09-15 line.
- Newer 2026-09-27 OCP task generation contains additional queued/dispatched
  Wave Zero task records; they are not treated as substitutes for reviewed
  historical evidence.

### Wave One observations

Wave One is **not a valid implementation gate yet** because the current EPIC-01
library/search packet is still awaiting independent review.

- EPIC-01 library/search: packet
  `f022dc85-15cd-48d3-838b-79cfbcba6319` is `awaiting_review`.
  Its revision fixed the reviewer-found false no-match instrumentation
  expectation and reran focused unit/androidTest-compilation/lint/assemble
  successfully, but approval has not yet been recorded.
- EPIC-03 playback/queue: packet
  `762d1d37-e56f-42e6-bc63-ed9ba0692d78` is `completed` with independent
  `approve`.
- EPIC-04 waveform: the original packet
  `7b16444c-2083-487c-a258-54fe27e7a229` is historical
  `recovery_required`, but a later qualification packet
  `72a4a210-25f7-4f82-8c4f-c5f0238d1ab0` is `completed` with independent
  `approve` for the preserved waveform revision. Device execution remains
  unclaimed.
- EPIC-07 collections/metadata evidence is independently approved; packet
  `07f4c85f-d905-42b5-a9bf-62af1a34591a` is `completed`.

Therefore the authoritative Wave One exit is still blocked by current EPIC-01
review state even though EPIC-03/04/07 have strong approved implementation
evidence.

### Existing Wave Two implementation evidence

These are observed completed packets, not authority for this agent to edit them.

- EPIC-05 cues/loops:
  `5a012762-a280-46a1-94d6-2fa38d3721ed` is `completed` with independent
  `approve`; exact-scope packet
  `cf743824-2a2d-4992-8e8e-67389414cf5d` is also independently approved.
- EPIC-06 beatgrid:
  `ee8ee89e-c9ff-41a0-99dc-15381fbc1e93` is `completed` with independent
  `approve`; convergence packet
  `e94e96fe-a539-4406-8c69-61add6e36977` is also approved.
- EPIC-08 playlist/collections behavior has approved implementation evidence in
  packet `07f4c85f-d905-42b5-a9bf-62af1a34591a`.
- EPIC-02 search/filter behavior is already materially present in the library
  implementation and tests, but the current EPIC-01 dependency packet remains
  awaiting review.

These facts mean the repository contains substantial Wave Two-capable code.
They do **not** make this preflight packet an implementation packet, and they do
not prove the Wave Two wave exit while EPIC-01 is still awaiting review.

## Observed implementation seams

### Shared domain/provider seams

Reuse the existing backend-neutral domain/provider surface rather than creating
parallel types:

- `core/model/Domain.kt`: track, cue, loop, beatgrid, playlist and related
  domain state.
- `core/provider/Providers.kt`: library/preparation/playlist/provider state
  seams.
- UI must not make direct network calls or expose backend-specific types in
  composable signatures.

### EPIC-02 — search/filter

Observed implementation is colocated in:

- `app/src/main/java/dev/androidjtools/ui/library/LibraryLogic.kt`
- `app/src/main/java/dev/androidjtools/ui/library/LibraryScreen.kt`

Observed tests:

- `app/src/test/java/dev/androidjtools/ui/library/LibraryLogicTest.kt`
- `app/src/androidTest/java/dev/androidjtools/ui/library/LibraryScreenTest.kt`

Reuse `LibraryBrowseState`, existing normalized search, reversible sort,
compound filters and saved-view state. Do not fork a second search model merely
to match a directory name.

### EPIC-05 — cues/loops

Observed seams:

- `prep/cues/CueEditor.kt`
- `prep/cues/CueEditorSemantics.kt`
- `prep/cues/PreparationStaging.kt`
- `prep/loops/LoopEditor.kt`
- `prep/loops/LoopEditorSemantics.kt`

The approved implementation already models deterministic edit/undo/cancel,
beat-aware snapping, validation, accessibility semantics and fail-closed
canonical staging. Unsupported canonical create/delete/metadata operations stay
explicit rather than being fabricated.

### EPIC-06 — beatgrid

Observed seams:

- `prep/beatgrid/BeatGridEditor.kt`
- `prep/beatgrid/BeatGridPreparation.kt`

The approved implementation covers anchor/BPM/phase changes, half/double BPM,
candidate preview, undo/cancel, revision-guarded restoration and typed commit
intent.

### EPIC-08 — playlists

Observed seams:

- `ui/playlist/PlaylistLogic.kt`
- `ui/collections/CollectionsLogic.kt`
- `ui/collections/CollectionsScreen.kt`

Reuse stable playlist IDs, ordered track IDs, crate hierarchy and deterministic
smart-rule preview. Durable writes continue through provider/journal authority;
preview remains non-mutating.

## Fixture/state contracts

| Contract | Deterministic minimum | Used by |
|---|---|---|
| Track catalog | stable id, title/artist/album, duration, nullable BPM/key/energy/rating, availability/offline state | 02, 08 |
| Provider state | Loading, Ready, Empty, Error, Offline, Conflict | 02, 08 |
| Browse state | query, sort field, sort direction, compound filters | 02 |
| Saved view | stable id, name, complete serialized browse state | 02 |
| Cue aggregate | track id, marker identity, position, label/color/number/role, canonical revision where applicable | 05 |
| Loop aggregate | stable id, bounds, active state, role/label/color, canonical revision where applicable | 05 |
| Beatgrid | track id, anchor, BPM, phase/downbeat state, revision | 05, 06 |
| Playlist | stable id, name, ordered track ids, crate path, static/smart discriminator | 08 |
| Smart rule | nested ALL/ANY groups, typed field/operator/value, validation result | 08 |
| Mutation staging | mutation id, entity id/type, operation, base revision, provenance, queued/rejected/result state | 05, 06 |
| Recreation snapshot | typed state plus revision guard | 02, 05, 06, 08 |

Invariants:

1. Fixture IDs and revisions are stable across recreation.
2. Loading/empty/error/offline/conflict are injectable without a backend.
3. Saved views and editor snapshots contain no backend implementation types.
4. Unsupported protocol operations fail closed.
5. Smart-rule preview is read-only until explicit save.
6. Mutation application is receipt-authoritative; UI staging never invents
   canonical success.

## Acceptance-to-test matrix

| Story | Acceptance target | Existing seam / next evidence |
|---|---|---|
| US-020 | incremental search | `LibraryLogicTest`; retain deterministic query behavior and restored UI state |
| US-021 | reversible sort | `LibraryLogicTest`; verify fields, null ordering and direction restoration |
| US-022 | compound filters | library filter tests; reconcile canonical tag seam before claiming generic tag filtering |
| US-023 | saved view | saved-view state + recreation/rename/delete coverage |
| US-050 | cue at playhead | cue editor + accepted waveform/playhead integration |
| US-051 | cue edits | cue unit + semantics/instrumentation |
| US-052 | loop create/resize | loop unit + waveform gesture integration |
| US-053 | structural roles | cue/loop role round-trip and overlay semantics |
| US-054 | safe delete | confirmation + undo tests |
| US-055 | offline cue/loop edits | journal/process-death/reconnect/receipt integration; do not infer device proof |
| US-060 | rendered aligned grid | beatgrid host tests + device/render evidence when a runtime is available |
| US-061 | anchor/BPM edit | beatgrid state + commit-intent integration |
| US-062 | half/double BPM | reversible state/unit/UI coverage |
| US-063 | jump/quantize | shared beat arithmetic across grid and cue/loop placement |
| US-080 | playlist CRUD | `PlaylistLogicTest` + provider-backed UI/persistence |
| US-081 | reorder | ordered-ID/drag semantics |
| US-082 | nested crates | crate hierarchy logic + persistence |
| US-083 | smart rules | typed rule validation/persistence/reevaluation |
| US-084 | preview before save | non-mutating preview assertion |

All 19 Wave Two stories from `docs/user-stories.yml` are represented above.

## Blockers

Verified blockers:

1. Current EPIC-01 packet
   `f022dc85-15cd-48d3-838b-79cfbcba6319` is still
   `awaiting_review`; therefore the Wave One exit is not proven.
2. This agent has no separate Wave Two implementation packet, lease or claim.
3. The checkout contains substantial inherited dirty implementation state and
   must not be reset/cleaned/stashed/discarded.
4. OCP/workflow history contains older/unbound task/packet correlations. Future
   implementation dispatch should reuse canonical identities and reconcile
   authority rather than duplicate work.
5. Device-dependent UI/render/offline interaction is not newly verified here.
   No Android device, emulator, hosted CI or real backend execution is claimed
   by this preflight.
6. Generic tag filtering for US-022 needs an authoritative model/provider seam;
   the existing `Track` shape should not be silently widened from a UI packet.

## Dependency-ready entry conditions

A Wave Two implementation lane may write only when all of the following are
true:

1. Wave One exit is durably satisfied; in the current snapshot this requires at
   least an independent approval/current completion state for EPIC-01.
2. EPIC-01 is accepted before EPIC-02/08; EPIC-04 accepted evidence remains
   prerequisite for EPIC-05/06.
3. The target epic is separately admitted/published and the agent receives its
   own explicit packet, lease and non-overlapping claim.
4. Target paths are re-read after claim acquisition because the checkout is
   concurrently dirty.
5. Existing implementation is verified/refined in place; no accepted work is
   regenerated or replaced merely to obtain ownership.
6. Protocol gaps become separate contract work, not UI-scope expansion.

## Concrete implementation handoff

This is proposed follow-up ordering, not current authorization.

1. **Finish EPIC-01 review first.**
   - Re-review packet `f022dc85-15cd-48d3-838b-79cfbcba6319` after its
     corrected no-match test revision.
   - Do not dispatch EPIC-02/08 implementation merely because code exists.
2. **EPIC-02 after EPIC-01 + Wave One exit.**
   - Prefer the existing `ui/library/**` model unless an explicit packet
     partitions `ui/library/search/**`.
   - Reuse `LibraryBrowseState`; keep saved views backend-neutral.
   - Resolve US-022 generic tag authority separately if still absent.
3. **EPIC-08 after EPIC-01 + Wave One exit.**
   - Reuse `PlaylistLogic.kt` and collections UI.
   - Keep ordered identity and smart preview deterministic.
   - Partition writes from EPIC-02 so both can run concurrently only when claims
     do not overlap.
4. **EPIC-05 / EPIC-06 after Wave One exit.**
   - Existing approved implementations should be treated as preserved inputs,
     not regenerated.
   - Any continuation should receive exact prep/cues+loops or prep/beatgrid
     claims; waveform ownership should remain read-only unless a separate
     integration packet says otherwise.

Do not use a broad `app/**` claim for Wave Two convergence.

## Files changed and checks

Changed by this packet:

- `docs/wave2-preflight/README.md`

No implementation, contract, fake-server, Gradle/build or canonical planning
YAML path is changed by this packet.

Checks for this refresh are documentation/scope checks only. Existing Gradle,
device, backend and hosted-CI results mentioned above are prior inspected
evidence, not newly executed evidence.
