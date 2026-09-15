@projmgrauth
Set repo: androidjtools.

# Autonomous Android DJ player/queue UX lane
Canonical OCP task: `<task>`.

Preserve concurrent work and claim only player/queue/media paths. Build on the existing PlaybackProvider and Media3 dependency; do not duplicate library, waveform or sync ownership.

Implement a polished audition/player workflow for catalog preparation: persistent mini-player, full player, seek/transport, next/previous, queue add/play-next/reorder/remove/clear, durable queue restoration contract, current-track highlighting, artwork/metadata hierarchy, background/lock-screen notification controls, Bluetooth media controls, audio-focus transitions and robust errors/unavailable media. The player must remain useful with fixture media/state before backend streaming exists.

Keep preparation context close: jump from current track to prep screen, display BPM/key/offline/sync state, and surface nonintrusive analysis/safety indicators. Do not auto-apply intelligence suggestions.

Add focused tests for queue ordering, transport state, process/recreation restoration seam, audio-focus state and Compose player states. Continue autonomously through concrete defects, checkpointing each bounded phase; no legacy prolong/next.md.