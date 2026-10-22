# Sample Lab Integration S3 (Editor Mutations) Implementation Plan

**Goal:** Cue and loop edits made on the Preparation screen reach the paired Sample Lib server as `interval.editor_state.replace` mutations through the existing durable journal, with distinct visible states and exactly two conflict actions.

**Spec:** `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (stage S3). Backend contract: `third_party/sample-lib/docs/contracts/android-sync-v1.md` in the sample-lab checkout.

**Status: implemented on `main`; see the CHANGELOG for what is and is not verified on a device.** Executed in the current tree at the user's request (no worktree). TDD per task: failing test, watch it fail for the right reason, minimal code, full suite.

## What already exists (not rebuilt)

`DurableMutationJournal` + `SampleLibMutationJournalReplayer` (atomic file, receipts, recovery), `SampleLibClient.pushWithRecovery`, `CueEditorState` / `LoopEditorState` with `stage(...)` through the `PreparationMutationStager` seam, `IntervalEditorStateContext`, `ConflictResolver`. Nothing connects them to a session, a production journal or the screen; the Preparation screen only keeps local drafts.

## Decisions (reviewer: please confirm)

1. **Server marker vocabulary.** The server accepts only `slice`, `loop_start`, `loop_end`, `custom`. The cue editor emits `cue`, `mix_in`, `drop`, ... and `HotCueDraft.fromDomain` turns an existing `slice` marker into a plain cue, so editing any cue would silently convert the track's slice markers. Fix: a `SLICE` hot-cue role, and a wire mapping at the stager boundary (`slice`/`loop_start`/`loop_end` unchanged, everything else `custom`). Roles beyond cue/slice are local-only in v1 (as hot-cue numbers already are).
2. **Flush = refresh.** Delivering the journal is part of the existing connect step: `connect -> replay journal -> pull`. A staged edit just asks the controller to refresh. Replay before pull, so the pulled state includes the applied edit.
3. **Effective means the cache has it.** A journal entry with an APPLIED receipt is shown as "saved" only; the library projection changes solely from pulled server state. A pending or applying edit is never drawn as if it were effective.
4. **Journal gains `RESOLVED`** (terminal, compactable) so a conflict can be closed without inventing a receipt.
5. **Two conflict actions only:** "Use server state" (close the local intent) and "Reapply my draft" (new mutation id, `base_revision` = the revision the conflict receipt named, same payload). No timestamp last-write-wins.
6. **Scope.** Editable: canonical cues (add, move, rename, delete) and canonical loop bounds where the loop is a loopable interval (its id is the interval id). Loops made from `loop_start`/`loop_end` marker pairs stay read-only with a stated reason. Journal I/O never runs on the main thread (the provider's calls are `suspend` and switch to IO).
7. **Availability.** Editing is offered only when the paired server advertises `interval.editor_state.replace`; otherwise the controls state that the server does not support editing.
8. **Evidence honesty.** Compose screens, WorkManager and Keystore remain "built, not run on a device" in the changelog.

## Tasks

1. Journal: `RESOLVED` state, `resolve()`, compaction, persistence round trip.
2. Wire vocabulary + `SLICE` role (no silent marker conversion).
3. Aggregates from the cache (`IntervalEditorStateContext` per interval) and `Cue.intervalId`.
4. `JournalMutationStager`: intent -> canonical payload -> `enqueue`.
5. Session manager: replay the journal before pulling; failures keep the intent pending.
6. `ServerEditProvider`: availability, journal-backed edit list with states, stage, the two conflict actions.
7. Runtime wiring (file journal under `noBackupFilesDir`) and live-contract tests against the real server (edit applied, stale-revision conflict, reapply).
8. Preparation screen: save loop bounds, add/delete cue, status list, conflict card; pure logic functions unit-tested.
9. Changelog, spec status, full release checks.

## Findings during execution (beyond the plan)

- The journal was not thread-safe; with the UI and the sync worker both writing, a concurrent stress test lost 79 of 100 mutations. All operations are now synchronized.
- Colours: the cue editor's `#AARRGGBB` is reduced to `#RRGGBB` at the wire, because the server stores free text and the library projection only reads six digits.
- Forget and server switching clear the journal (an edit belongs to the server it was made for).
- The loop editor's overlap rule does not apply to server intervals (a marker-pair loop always sits inside its interval's loop), so only the loop being edited is handed to it.
