# Wave Four guarded implementation preflight

Canonical OCP task: `ANDROIDJTOOLS-WAVE4-PARALLEL-OBSERVER-20260915`
Owned packet: `fe88d2e2-7422-4de1-8101-8259afbd7799`
Opportunity: `admitted-76f4f9d26e75f32752d155e8`
Snapshot basis: live OCP/ws-bridge state observed through 2026-09-15 15:34Z, repository planning files, approved protocol contracts, and current provider/UI seams.

## Readiness verdict

**Wave Four implementation is not legally ready to start.** The workflow-level gates `wave_2_prep_and_catalog` and `wave_3_offline_backend` are both incomplete. No currently authorized Wave Four implementation packet exists. This observer therefore remains documentation-only and must not create implementation claims.

This verdict intentionally does not promote passing focused tests, checkpoints, browser completion messages, or source presence into lifecycle completion. A prerequisite counts as satisfied only when its authoritative workflow packet has completed the required independent-review lifecycle and any canonical OCP/ws-bridge discrepancy that affects dispatch authority is reconciled.

## Evidence classification

### Verified facts

- The canonical workflow assigns Wave Four to EPIC-14, EPIC-15, and EPIC-17 and hard-depends on both Wave Two and Wave Three.
- EPIC-14 additionally depends on EPIC-12 Sample Lib integration.
- EPIC-15 additionally depends on EPIC-03 playback/queue.
- EPIC-17 additionally depends on EPIC-01 library, EPIC-04 waveform, and EPIC-08 playlists.
- EPIC-03 packet `762d1d37-e56f-42e6-bc63-ed9ba0692d78` is completed and independently approved, but its canonical OCP task is still reported running. A separate shell/manifest integration packet `d67eb4b1-c225-479b-a52d-2425730f9a7b` is active and owns the application-boundary Media3 wiring.
- EPIC-08 collections/metadata packet `29ed0789-0062-4227-9521-da76eaafac01` is completed and independently approved, but the corresponding canonical OCP task still has a nonterminal correlation discrepancy.
- The library continuation packet `82d646e5-8299-493c-9a0b-af244e473e26` is awaiting independent review. The old packet `62aea69d-4d30-439b-9d12-832e7c499dbc` remains recovery-required history and is not completion evidence.
- EPIC-04 waveform packet `7b16444c-2083-487c-a258-54fe27e7a229` remains recovery-required after a review revision; revised production/androidTest compilation, lint and assembly evidence exists, but the required lifecycle is not complete.
- Canonical Wave Two cue/loop and beatgrid tasks remain queued.
- EPIC-11 mutation journal packet `b2d88277-e568-4986-9944-22e49a5c6a87` and EPIC-12 Sample Lib packet `73054b6e-f593-4fae-bcaf-d2bd19d68139` are active, not completed.
- Canonical Wave Three offline-download and DJXML tasks remain queued.
- Protocol synthesis packet `f65ae278-e6ed-483e-8bb3-7ce4c26d926d` is completed and independently approved. Its repaired v1 contract is current protocol authority.
- There is currently no `benchmark/` directory. EPIC-17 benchmark ownership is therefore a future implementation scope, not an existing runnable benchmark module.
- Relevant Android UI/player lanes repeatedly report no connected device or configured AVD; instrumentation source compilation is not real-device behavioral evidence.

### Blockers

1. `wave_2_prep_and_catalog` is not durably complete: cue/loop and beatgrid work is queued; library is awaiting review; waveform is recovery-required.
2. `wave_3_offline_backend` is not durably complete: mutation journal and Sample Lib are active; offline downloads and DJXML remain queued.
3. EPIC-14 cannot start until EPIC-12 is independently accepted in addition to the common Wave Four gates.
4. EPIC-15 must not race the active player shell/manifest packet; although EPIC-03 itself is approved, application-boundary Media3 registration is still being integrated.
5. EPIC-17 cannot start while EPIC-01 is awaiting review or EPIC-04 is recovery-required. EPIC-08 implementation is approved, but OCP correlation should be reconciled before using canonical task state as dispatch evidence.
6. OCP/ws-bridge correlation discrepancies exist for several runtime packets and canonical tasks. They are lifecycle/control-plane issues, not reasons to duplicate work.

### Assumptions for future implementation packets

- The independently approved protocol synthesis remains the wire-contract authority unless a separately reviewed contract revision supersedes it.
- Wave Four app implementations should consume backend-neutral domain/provider adapters and keep wire/backend DTOs outside composable signatures.
- Production UI fixes found by EPIC-17 qualification should be routed to the owning UI lane instead of widening the qualification packet.
- Device-only acceptance can be deferred until an authorized device/AVD exists, but it must remain explicitly unproven until executed.

### Proposed work — future only

The following items are proposals, not current authorization: complete the remaining Wave Two/Wave Three lifecycle; admit separate EPIC-14 and EPIC-15 implementation packets only after their gates clear; admit EPIC-17 as a test/benchmark qualification packet after EPIC-01/04/08 and the common gates are accepted; keep intelligence backend DTOs below provider/domain adapters; keep Android system-media integration around the existing playback authority; and route production defects found by EPIC-17 back to their owning implementation lanes. The detailed scopes, acceptance commands, design notes and dispatch order below refine these proposals without creating claims.

## Current prerequisite matrix

| Prerequisite | Canonical / workflow identity | Current authoritative state | Review / claim evidence | Wave Four consequence |
|---|---|---|---|---|
| Wave Two common gate | `wave_2_prep_and_catalog` | **incomplete** | cue/loop OCP `ANDROIDJTOOLS-WAVE2-CUES-LOOPS-20260915` queued; beatgrid OCP `ANDROIDJTOOLS-WAVE2-BEATGRID-20260915` queued | Blocks EPIC-14/15/17 |
| EPIC-01 library | packet `82d646e5-8299-493c-9a0b-af244e473e26` | awaiting review | checkpoint `93021262-6261-463a-b44b-2b6537894f08`; submission digest `71a1f06c...` | Blocks EPIC-17 and Wave Two completion |
| EPIC-04 waveform | packet `7b16444c-2083-487c-a258-54fe27e7a229` | recovery-required | review requested changes; revision checkpoint `89fce506-5e8a-42ee-9784-9b6d523624d7` | Blocks EPIC-17 and Wave Two completion |
| EPIC-08 playlists/collections UX | packet `29ed0789-0062-4227-9521-da76eaafac01` | completed, approved | review digest `f3d70555...`; final phase digest `a284cb8f...` | Specific EPIC-17 dependency satisfied at packet level; reconcile OCP discrepancy |
| EPIC-03 playback/queue | packet `762d1d37-e56f-42e6-bc63-ed9ba0692d78` | completed, approved | review digest `c45ef99d...`; final phase digest `96e56317...` | Specific EPIC-15 dependency satisfied at packet level; common gates still block |
| Player shell/Media3 integration | packet `d67eb4b1-c225-479b-a52d-2425730f9a7b` | active | lease `453864e4-f0e1-41d8-a8ed-c7cfe0a48c75`; claim `c329363c-4a75-4488-8035-8f9e8d11dd45` | EPIC-15 must wait or consume result; no overlapping writes |
| Protocol authority | packet `f65ae278-e6ed-483e-8bb3-7ce4c26d926d` | completed, approved | review digest `72117246...`; final phase digest `2ddc43e4...`; 26/26 contract tests | Enables future Wave Three/Wave Four adapters, but does not complete Wave Three |
| EPIC-11 journal/conflicts | packet `b2d88277-e568-4986-9944-22e49a5c6a87` | active | lease `f94ccdaf-fc7d-4af6-831d-590e0581f3ac`; claim `8ddfd1b2-349a-4bff-88a9-36eb1a3fca23` | Blocks Wave Three common gate and EPIC-14 accepted-suggestion persistence path |
| EPIC-12 Sample Lib | packet `73054b6e-f593-4fae-bcaf-d2bd19d68139` | active | lease `f707be62-505b-44c6-95a3-d95e587efa94`; claim `06378c12-3aad-4647-b8ea-16eaece04bdf` | Blocks EPIC-14 and Wave Three common gate |
| Wave Three offline downloads | OCP `ANDROIDJTOOLS-WAVE3-OFFLINE-DOWNLOADS-20260915` | queued | no completed implementation packet | Blocks Wave Three common gate |
| Wave Three DJXML | OCP `ANDROIDJTOOLS-WAVE3-DJXML-INTEROP-20260915` | queued | no completed implementation packet | Blocks Wave Three common gate |

## Wave Four epic start matrix

| Epic | Hard dependencies | Current gate | Earliest legal start | Proposed future non-overlapping write scope | Executable acceptance tests |
|---|---|---|---|---|---|
| EPIC-14 Analysis & Related | common Wave2 + Wave3; EPIC-12 | **blocked**: both common gates incomplete; EPIC-12 active | After Wave2 and Wave3 are independently accepted, EPIC-12 is completed/approved, and a separate EPIC-14 packet is admitted/published | `app/src/main/java/dev/androidjtools/analysis/**`, `app/src/test/java/dev/androidjtools/analysis/**`, optionally `app/src/androidTest/java/dev/androidjtools/analysis/**`; treat `contracts/intelligence/**` as read-only unless a separate contract-revision packet is authorized | `./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.analysis.*'`; `./gradlew :app:compileDebugAndroidTestKotlin`; on an authorized device/AVD: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.androidjtools.analysis`. Tests must prove capability degradation, stale/offline cache visibility, provenance/revision mapping, explicit reject/no mutation, and accepted suggestions entering the normal journal/receipt path |
| EPIC-15 Android Media Integration | common Wave2 + Wave3; EPIC-03 | **blocked**: common gates incomplete; EPIC-03 approved but shell/manifest integration active | After common gates complete, EPIC-03 packet authority is reconciled, `d67...` shell integration is completed/reviewed, and a separate EPIC-15 packet is admitted | `app/src/main/java/dev/androidjtools/platform/media/**`, `app/src/test/java/dev/androidjtools/platform/media/**`, `app/src/androidTest/java/dev/androidjtools/platform/media/**`; playback/controller/service and shell/manifest are read-only integration seams | `./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.platform.media.*'`; `./gradlew :app:compileDebugAndroidTestKotlin`; on an authorized device/AVD: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.androidjtools.platform.media`. Device evidence covers media-session, notification/lock-screen, Bluetooth/media keys, focus/interruption, and recreation |
| EPIC-17 Accessibility & Performance | common Wave2 + Wave3; EPIC-01, EPIC-04, EPIC-08 | **blocked**: common gates incomplete; library awaiting review; waveform recovery-required | After common gates and EPIC-01/04/08 are independently accepted and the target surfaces are stable; then admit a qualification packet | `app/src/androidTest/java/dev/androidjtools/a11y/**`, `app/src/androidTest/java/dev/androidjtools/performance/**`, future `benchmark/**`; production UI remains read-only and fixes return to owners | `./gradlew :app:compileDebugAndroidTestKotlin`; on an authorized device/AVD run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.androidjtools.a11y` and `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.androidjtools.performance`. If/when a benchmark module is created, discover and run its actual Gradle tasks rather than assuming `:benchmark:*` exists. Evidence must cover TalkBack semantics/focus/touch targets/font scale/large window, 10k-track responsiveness and waveform frame/gesture behavior |

## Current seams and architecture risks

### Provider and domain boundary

`AppProviders` is the backend-neutral application seam. Existing library, playlist/preparation, analysis, playback, download/sync, mutation-journal and backend-health providers must remain the dependency direction for UI. Composables should receive domain/view-state objects, stable IDs and callbacks—not Sample Lib, Demucs, ComfyUI, ASR, tempo-engine, Media3 transport, or sync wire DTOs.

The approved protocol migrated entity/base revisions to opaque strings while fixture-era Kotlin models still include numeric revision assumptions in places. EPIC-14 must not copy numeric revision semantics into a new advisory contract. The adapter should preserve the authoritative opaque `input_revision`/entity revision as a value object/string at the boundary and expose stale/mismatch state explicitly.

### Existing intelligence seam

The approved `contracts/intelligence/v1` contract already establishes semantic capability/job/candidate concepts, provenance/model identity, confidence, input revision/freshness, media derivation, safety warnings and mediated requester confirmation. Waveform and metadata surfaces already demonstrate the correct UX principle: advisory candidates are visually distinct from canonical preparation state and require explicit user acceptance.

EPIC-14 should therefore be an adapter/orchestration layer, not a second protocol. Contract changes are only justified if implementation falsifies the approved contract and should then be reviewed in a separate contract-owned packet.

### Existing playback/media seam

`PlayerQueueController` is the queue/current-item/restoration authority. `Media3PlaybackService` already owns the ExoPlayer/MediaSession substrate and `AudioFocusPolicy` already models focus transitions. The active shell integration packet owns persistent root mounting and manifest/service registration.

EPIC-15 must translate Android external controls into this existing authority. Creating another ExoPlayer, queue store, session state machine, or process-restoration store would split authority and is prohibited by design.

### Existing dense-UI seam

Library uses stable track identity and deterministic pure filter/sort/restoration logic, with a 10k-track determinism test in the current continuation; that packet still awaits review. Waveform has deterministic viewport/gesture logic and explicit canonical-versus-candidate display, but is lifecycle recovery-required. Collections/playlists use stable local identities, nested crates and staged metadata and are independently approved.

EPIC-17 should qualify these surfaces without silently taking ownership of them. A11y/performance failures that require production changes should become owner-scoped follow-ups.

## EPIC-14 implementation-ready design

Use a single backend-neutral advisory model with these layers:

- **Capability snapshot**: semantic capability IDs and availability for Sample Intelligence, Demucs, ComfyUI-ASR and tempo/beat analysis. Each engine can be unavailable/degraded independently without disabling unrelated suggestions.
- **Suggestion envelope**: stable suggestion/job ID, candidate kind, target entity/region, provenance engine/provider, model/version, confidence, generated-at/freshness state, authoritative input revision, rationale, and optional safety/outlier warnings.
- **Candidate families**: transcript, stem/media derivation, BPM, key, cue, loop, region/interval and related-track candidates. Related tracks carry an explicit rationale/features rather than only a score.
- **Cache state**: suggestions may be cached for offline use; cached entries visibly report `fresh`, `stale`, or `revision_mismatch`. Stale candidates remain inspectable but never auto-apply.
- **Decision state**: pending/accepted/rejected. Reject is local decision metadata only and must not enqueue a canonical mutation. Accept converts the candidate into the same backend-neutral edit command used by manual preparation/metadata editing.
- **Mutation path**: accepted edits are durably inserted into the EPIC-11 journal before local success is reported. Canonical/applied state appears only after the authoritative receipt path says applied/no-op. Conflict/rejection receipts remain visible and retain evidence.
- **Composable boundary**: UI gets `SuggestionViewState`-style domain projections plus `onAccept(id)` / `onReject(id)` callbacks. Wire/backend engine DTOs terminate in adapters below the UI layer.

Minimum falsification cases: one engine down while others work; offline cache; input revision changes after generation; low-confidence/outlier warning; duplicate accepted mutation/replay; server reject/conflict; reject proves zero journal entries; related-track rationale survives mapping; transcript/stem candidates cannot masquerade as canonical assets before receipt.

## EPIC-15 implementation-ready design

Treat Android/system media integration as an adapter around the existing player authority:

- MediaSession callbacks map play/pause/seek/next/previous and queue selection to `PlayerQueueController`/existing playback provider commands.
- Notification and lock-screen metadata derive from the same current queue item identity used in-app.
- Bluetooth/headset/media-key input maps through the same command path. External commands must not maintain an independent queue index.
- Audio focus transient loss, ducking, permanent loss and regain are translated using the existing focus policy and persisted playback intent. Regain must not restart playback if the prior/user intent was paused.
- Interruption behavior covers phone/other-app focus, becoming-noisy/headset disconnect where supported, service teardown and process recreation.
- Process recreation restores queue identity/current item/position/play intent from the existing durable queue store and reconnects the MediaSession to that state.
- The adapter must prove there is one ExoPlayer/MediaSession playback authority, not a second stack.

Static/JVM tests can prove callback mapping, identity, focus transition logic and reconstruction inputs. Notification rendering, lock-screen controls, Bluetooth/media-key delivery and OS focus/interruption behavior are device/system evidence and must not be claimed from JVM tests alone.

## EPIC-17 implementation-ready qualification design

Qualification owns test/benchmark evidence, not target production screens.

- **TalkBack/semantics**: meaningful track/cue/loop/playlist semantics; selected/current/pending/conflict states announced; decorative waveform elements not polluting traversal; actions discoverable without relying on color/position.
- **Touch/focus**: minimum usable targets, deterministic focus order, keyboard/D-pad where relevant, no focus trap in dense filters/editors, range handles separately addressable.
- **Font scaling**: major surfaces remain usable at large system font scales; metadata and transport actions do not clip critical labels/actions.
- **Large-screen**: deterministic adaptive layout assertions for phone and expanded windows without duplicating state authority.
- **10k tracks**: fixture-backed stable-key collection, bounded filter/sort work, list restoration and representative scroll interaction. Static algorithm tests are not frame-time evidence.
- **Waveform**: gesture correctness stays deterministic under dense overlays; frame/jank and input-latency evidence comes from instrumentation/benchmark tooling, not unit tests.
- **Evidence classes**: (a) static/semantic source assertions; (b) JVM deterministic model tests; (c) instrumentation on emulator/device; (d) benchmark/frame metrics on a named target. Reports must state which class supports each claim.

Because `benchmark/` does not exist today, the future EPIC-17 packet must create/configure benchmark ownership before claiming a benchmark task. The preflight intentionally does not invent a module, Gradle task, target device, frame budget, or hosted-CI result.

## Recommended dispatch order after gates clear

1. Finish the existing Wave Two and Wave Three lifecycle before admitting Wave Four implementation: independently review the library continuation; recover and re-qualify/re-review waveform; execute queued cue/loop and beatgrid work; complete/review journal and Sample Lib; execute/review offline downloads and DJXML; reconcile material OCP/ws-bridge state discrepancies.
2. Complete and independently review `d67eb4b1-c225-479b-a52d-2425730f9a7b` so EPIC-15 has a stable application boundary and does not race manifest/shell ownership.
3. Once all common gates are accepted, admit separate non-overlapping EPIC-14 and EPIC-15 packets. They can execute in parallel because the proposed analysis and platform-media scopes do not overlap.
4. Admit EPIC-17 after EPIC-01/04/08 and the common gates are accepted. For the most useful qualification pass, run it after EPIC-14/15 settle even though those two are not canonical hard dependencies of EPIC-17.

## Exact next lifecycle actions

- Review and resolve packet `82d646e5-8299-493c-9a0b-af244e473e26`; only an independent approval makes the current library continuation complete.
- Resume/recover packet `7b16444c-2083-487c-a258-54fe27e7a229` from its preserved revision checkpoint, rerun the formerly blocked focused tests against the now-qualified library sources, submit, and obtain independent approval.
- Dispatch the already-canonical queued Wave Two cue/loop and beatgrid tasks through normal admission/reservation; do not synthesize replacement packets from this observer.
- Complete and independently review active EPIC-11 packet `b2d88277-e568-4986-9944-22e49a5c6a87` and EPIC-12 packet `73054b6e-f593-4fae-bcaf-d2bd19d68139`.
- Dispatch and independently qualify the queued Wave Three offline-download and DJXML tasks when their declared dependencies permit.
- Complete/review active player shell integration packet `d67eb4b1-c225-479b-a52d-2425730f9a7b`.
- Reconcile OCP task states with authoritative packet lifecycle for completed player/collections work and other unbound runtime packets without replaying ambiguous browser sends.
- Only then admit/publish Wave Four implementation packets with the scopes above; this observer packet itself must never be promoted into implementation ownership.
