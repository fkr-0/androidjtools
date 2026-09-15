@projmgrauth
Set repo: androidjtools.

# Autonomous Android DJ library/search UX lane
Canonical OCP task: `<task>`.

Work from current shared state. Preserve concurrent dirty work, inspect ws-bridge claims/presence, claim only your exact component paths, never reset/clean/stash, and never push/publish. The fixture-first Android shell and provider contracts already exist; improve them rather than creating a parallel architecture.

Own the library/search/browse experience only. Build a world-class mobile DJ collection surface: dense but legible track rows, artwork/placeholder treatment, BPM/key/rating/offline/analysis indicators, smooth large-list behavior, fast search, sort/filter chips and saved-view affordances, multi-select/batch affordances, contextual actions, selection/playback feedback, loading/empty/error/offline states, accessibility, one-handed ergonomics, small/large phone layouts, and state restoration. Use Compose primitives and current provider interfaces; add narrowly scoped provider/model seams only when unavoidable and coordinate first.

Design for end users dogfooding against fixture providers immediately. Do not implement sync internals or waveform editing in this lane. Add deterministic Compose/unit/UI tests for major states and interaction flows. Measure/reason about list recomposition and scrolling rather than adding decorative complexity.

Autonomous continuation: continue through bounded review→implement→test→self-critique passes while concrete in-scope UX defects remain. Persist ws-bridge checkpoints with exact evidence. If terminal with one bounded continuation, create at most one canonical successor referencing `<task>`; never use legacy prolong/next.md.

Acceptance: library is usable without backend; major states are fixture-drivable; search/sort/filter/multi-select feel coherent; accessibility and large-library behavior are tested; changes stay inside library/search component scope except explicitly coordinated seams.