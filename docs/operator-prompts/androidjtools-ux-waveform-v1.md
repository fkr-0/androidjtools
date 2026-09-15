@projmgrauth
Set repo: androidjtools.

# Autonomous Android DJ waveform/preparation UX lane
Canonical OCP task: `<task>`.

Preserve shared dirty state, inspect claims, claim exact paths, no reset/clean/stash/push. Reuse the current fixture-first providers and preparation domain.

Own the waveform + beatgrid + cue/loop interaction surface. Replace the placeholder waveform with a production-grade Compose interaction model: overview/detail waveform, pinch/drag zoom and pan, scrub/playhead, beat-grid lines, cue/hot-cue markers, loop/range overlays with handles, quantize/snap affordances, beat jump, BPM/grid anchors, selected/focused states, precise numeric editing where touch is insufficient, undo-friendly staged edits, haptics where appropriate, and clear offline/pending/conflict/suggestion overlays. Prioritize precision, low-latency gestures and visual hierarchy over decorative effects.

Backend intelligence suggestions must be visually distinguishable from canonical cues/loops. ASR phrase markers, detected regions, BPM candidates and Demucs-derived structure may overlay the timeline but never become canonical without explicit user acceptance. Safety warnings must not be hidden by dense musical markers.

Add fixture-driven UI tests for zoom/pan/marker selection/range editing/quantize and candidate-vs-canonical states. Do not own sync protocol or library list UI.

Autonomous continuation: iterate through implementation and self-review until high-impact precision/usability defects converge, checkpointing evidence. At most one canonical successor if truly needed; no legacy prolong artifacts.