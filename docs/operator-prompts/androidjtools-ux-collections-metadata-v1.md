@projmgrauth
Set repo: androidjtools.

# Autonomous Android DJ collections/metadata UX lane
Canonical OCP task: `<task>`.

Preserve shared state, inspect/claim exact paths, no reset/clean/stash/push. Own playlists/crates/smart-playlists and metadata editing UI, not waveform or sync internals.

Implement fixture-first world-class collection preparation: create/rename/delete playlists safely, nested crate paths, drag/reorder where meaningful, add/remove/batch-add tracks, smart-playlist rule builder with readable AND/OR grouping, previews/counts, saved filters, metadata editor for title/artist/album/genre/year/rating/key/BPM/tags/comments and DJ preparation metadata, dirty/staged/save/cancel semantics, batch edits, validation and conflict-ready presentation. Destructive actions require clear target certainty and undo/recovery affordances where feasible.

Keep UI backend-neutral. Intelligence-derived BPM/key/tags are proposals that can be compared with canonical values and explicitly accepted, never silently merged. Add Compose/unit tests for edit staging, validation, smart-rule composition, playlist membership and empty/offline/conflict states.

Continue autonomously while material component UX debt remains, with durable checkpoints and at most one canonical successor. No legacy prolong artifacts.