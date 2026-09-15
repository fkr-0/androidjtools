# Wave Four preflight evidence ledger

This file records the evidence used by `README.md`. It is a point-in-time observer ledger, not an implementation completion claim.

## Observer authority

- OCP task: `ANDROIDJTOOLS-WAVE4-PARALLEL-OBSERVER-20260915`
- Catalog prompt: `androidjtools-wave4-parallel-observer-v1`
- Dispatch attempt supplied by operator: `3071f481ec1b491a8a1e70a2ce818553`
- Workflow packet: `fe88d2e2-7422-4de1-8101-8259afbd7799`
- Opportunity: `admitted-76f4f9d26e75f32752d155e8`
- Active lease at authoring: `dd65503a-0010-4ae6-b5de-6196fa1cb92b`
- Write claim: `b7380f2a-5235-4574-a130-2b0de68dc1f7`
- Allowed write scope: `docs/wave4-preflight/**`
- Required review policy: independent, author mutation/review not allowed
- Initial exact-packet reservation was rejected at managed capacity 4/4. No other packet was consumed. The same exact opportunity was retried after a slot opened and then reserved/begun normally.

## Planning authority inspected

- `docs/agent-workflows.yml`: Wave Four comprises EPIC-14/15/17 and depends on both `wave_2_prep_and_catalog` and `wave_3_offline_backend`.
- `docs/epics.yml`: EPIC-14 depends EPIC-12; EPIC-15 depends EPIC-03; EPIC-17 depends EPIC-01/04/08.
- `docs/user-stories.yml`: Wave Four intelligence, media-integration, accessibility and performance stories.
- `docs/wave3-preflight/README.md` and `docs/wave3-preflight/evidence.md`: older Wave Three observer snapshot; current live packet state was re-read and takes precedence where it changed.

## Current accepted protocol authority

Protocol synthesis packet `f65ae278-e6ed-483e-8bb3-7ce4c26d926d` is completed and independently approved.

- Final review: `wsbridge:review:ws-29fe5ce01a58c90621e8f5ca53e95ef8:f65ae278-e6ed-483e-8bb3-7ce4c26d926d:sha256:721172467b52be08135b2620e85ccfed948032722d1ec2009415475a9239a64f`
- Final phase result: `wsbridge:checkpoint:ws-29fe5ce01a58c90621e8f5ca53e95ef8:f65ae278-e6ed-483e-8bb3-7ce4c26d926d:phase-result:sha256:2ddc43e4f29899f3ad32ddcb9218313355a6d6af78abd175bdd6c90e9e34a922`
- Revision qualification reported 26/26 protocol/fake-server tests passing.
- Relevant decisions: opaque entity/base revisions, separate diagnostic server-change sequence, authority generation, opaque cursors/reset, operation-family mutation capabilities, authoritative receipts, mediated intelligence confirmation, playlist-write withholding until canonical playlist migration.

## Wave Two evidence

### Library / EPIC-01

Current continuation packet: `82d646e5-8299-493c-9a0b-af244e473e26`, opportunity `admitted-056c6cfd3349afa12738a410`.

- State at snapshot: `awaiting_review`.
- Checkpoint ID: `93021262-6261-463a-b44b-2b6537894f08`.
- Checkpoint digest: `5b5745cb88727cbe585ed7ee460f3ff6fe2abb661307ced554e58eab4bcc05c9`.
- Submission digest: `71a1f06c536242b757aad308d0ff7953b91097e2bced99c00dc6ee712e8b11e5`.
- Evidence reports stable track IDs, dense browse/search/filter/sort/saved-view behavior, deterministic 10k-track unit coverage, androidTest compilation, lint and assemble success.
- Explicit evidence boundary: no device/AVD execution claimed.
- Old packet `62aea69d-4d30-439b-9d12-832e7c499dbc` remains `recovery_required`; it is preserved history, not current completion authority.

### Waveform / EPIC-04

Packet: `7b16444c-2083-487c-a258-54fe27e7a229`, opportunity `admitted-cbeabee06965812cae50332e`.

- Current state: `recovery_required`, review round 1.
- Independent review requested changes: `wsbridge:review:ws-29fe5ce01a58c90621e8f5ca53e95ef8:7b16444c-2083-487c-a258-54fe27e7a229:sha256:9c782a8c13a7fc4ef1e2ad6bd6c26d189aa4d16adbe210afaf4dc14e063317fb`.
- Revision checkpoint ID: `89fce506-5e8a-42ee-9784-9b6d523624d7`, digest `5ad29b588445742b97b46add003ae4c5561be99f2a34c6b92b74d0e25db3849b`.
- Packet final-at-time phase result: `wsbridge:checkpoint:ws-29fe5ce01a58c90621e8f5ca53e95ef8:7b16444c-2083-487c-a258-54fe27e7a229:phase-result:sha256:1fc7a3e2cd7d3696e03bacf9b4d5ae1b029498030f5e7f6e9eef71eaf5b7cf4c`.
- Revised compile/androidTest compile/lint/assemble evidence was green, but the focused revised JVM model test run was blocked at that time by library-owned test/API drift. Lifecycle remains incomplete regardless of source/checkpoint evidence.

### Playlists / EPIC-08

Collections/metadata packet: `29ed0789-0062-4227-9521-da76eaafac01`, opportunity `admitted-5999049cbc5985cc4e8fcf9a`.

- State: completed after independent review.
- Review: `wsbridge:review:ws-29fe5ce01a58c90621e8f5ca53e95ef8:29ed0789-0062-4227-9521-da76eaafac01:sha256:f3d70555375ddfe8e19f303d946c8fc86449162b094e73011efea16e98dd6694`.
- Final phase: `wsbridge:checkpoint:ws-29fe5ce01a58c90621e8f5ca53e95ef8:29ed0789-0062-4227-9521-da76eaafac01:phase-result:sha256:a284cb8f5d22a2a91f40b7e06531cc5e34a13b056be54c520770ca5f981970c8`.
- Canonical OCP correlation still reported the task nonterminal when inspected; this is a control-plane reconciliation item, not grounds to duplicate the implementation.

### Remaining Wave Two canonical work

- `ANDROIDJTOOLS-WAVE2-CUES-LOOPS-20260915`: queued.
- `ANDROIDJTOOLS-WAVE2-BEATGRID-20260915`: queued.

These queued tasks alone prove the Wave Two workflow gate is not complete.

## EPIC-03 and player shell evidence

Playback/queue packet `762d1d37-e56f-42e6-bc63-ed9ba0692d78` is completed and independently approved.

- Review: `wsbridge:review:ws-29fe5ce01a58c90621e8f5ca53e95ef8:762d1d37-e56f-42e6-bc63-ed9ba0692d78:sha256:c45ef99dc6871e9ebecee3db4e216cba70412670ecf54bd4ccb42320944d3fa2`.
- Final phase: `wsbridge:checkpoint:ws-29fe5ce01a58c90621e8f5ca53e95ef8:762d1d37-e56f-42e6-bc63-ed9ba0692d78:phase-result:sha256:96e56317f7473f3f4a5de32e03de82c942c751ac9a20a1d3d6dfd6e73bfbb6fb`.
- Qualified implementation includes queue/controller/restoration, mini/full player, `Media3PlaybackService`, and `AudioFocusPolicy`.
- Explicit evidence boundary: no device/AVD execution; root-shell persistence and manifest registration were outside that packet.

Application-boundary follow-up packet `d67eb4b1-c225-479b-a52d-2425730f9a7b` was active at snapshot:

- Lease: `453864e4-f0e1-41d8-a8ed-c7cfe0a48c75`.
- Claim: `c329363c-4a75-4488-8035-8f9e8d11dd45`.
- Write scope includes `App.kt`, shell paths, `AndroidManifest.xml`, and shell androidTests while player internals are read-only.
- Future EPIC-15 must not race this claim or rewrite the same shell/manifest responsibilities.

## Wave Three evidence

### EPIC-11 mutation journal/conflicts

Packet `b2d88277-e568-4986-9944-22e49a5c6a87`, opportunity `admitted-d543384173b0b80894f8f55c`.

- State: active.
- Lease: `f94ccdaf-fc7d-4af6-831d-590e0581f3ac`.
- Claim: `8ddfd1b2-349a-4bff-88a9-36eb1a3fca23`.
- No completed independent-review result existed at snapshot.

### EPIC-12 Sample Lib integration

Packet `73054b6e-f593-4fae-bcaf-d2bd19d68139`, opportunity `admitted-0e405c54f88ec693cd9950a6`.

- State: active.
- Lease: `f707be62-505b-44c6-95a3-d95e587efa94`.
- Claim: `06378c12-3aad-4647-b8ea-16eaece04bdf`.
- No completed independent-review result existed at snapshot.

### Remaining Wave Three canonical work

- `ANDROIDJTOOLS-WAVE3-OFFLINE-DOWNLOADS-20260915`: queued.
- `ANDROIDJTOOLS-WAVE3-DJXML-INTEROP-20260915`: queued.

Therefore the Wave Three workflow gate is not complete and EPIC-14's direct EPIC-12 dependency is also unsatisfied.

## Source seams inspected read-only

- `app/src/main/java/dev/androidjtools/core/provider/Providers.kt`
- `app/src/main/java/dev/androidjtools/core/model/Domain.kt`
- `app/src/main/java/dev/androidjtools/playback/PlayerQueueController.kt`
- `app/src/main/java/dev/androidjtools/playback/Media3PlaybackService.kt`
- `app/src/main/java/dev/androidjtools/playback/AudioFocusPolicy.kt`
- `app/src/main/java/dev/androidjtools/App.kt`
- library/search, waveform and playlist/collections UI/model seams
- `contracts/intelligence/v1/protocol.json`
- `contracts/intelligence/v1/suggestion-contract.yml`

Observed architecture facts used by the preflight:

- `AppProviders` is the shared backend-neutral provider bundle.
- Analysis candidates already feed advisory waveform/metadata presentation rather than replacing canonical values.
- `PlayerQueueController` owns queue/current-item/restoration state; Media3 service/focus code exists beneath it.
- Current approved wire protocol uses opaque revisions; fixture-era Kotlin numeric revision assumptions remain a migration risk.
- Library has stable-ID/10k deterministic evidence but awaits review; waveform lifecycle remains recovery-required; playlists/metadata are approved with explicit proposal acceptance.

## Static/read-only checks performed by this observer

- Read canonical workflow/epic/story definitions.
- Read current Wave Three preflight and live packet/OCP state rather than relying on the older snapshot.
- Read current provider/domain/player/waveform/library/playlist/intelligence seams.
- Queried `benchmark/`; the path does not exist (`ENOENT`) at this snapshot.
- No Android build, emulator/device run, backend call, hosted-CI run, or benchmark was executed by this observer because the task is a docs-only preflight and those would not change dependency readiness.

## Evidence boundary

This ledger supports implementation planning and lifecycle gating only. It does **not** claim:

- that Wave Two or Wave Three is complete;
- that any Wave Four implementation packet is authorized;
- real Sample Lib success for the active EPIC-12 lane;
- real-device TalkBack, notification, lock-screen, Bluetooth, audio-focus or performance behavior;
- hosted CI or benchmark results;
- that a checkpoint or passing focused test substitutes for independent workflow approval.
