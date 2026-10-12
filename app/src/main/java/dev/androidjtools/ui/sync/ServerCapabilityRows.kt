package dev.androidjtools.ui.sync

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.ServerCapabilities

data class CapabilityRow(val id: String, val label: String, val available: Boolean, val detail: String)

private val PLAYLIST_MODES = setOf("canonical_v1", "read_only", "read_write")

private fun row(id: String, label: String, missing: List<String>, availableDetail: String) =
    if (missing.isEmpty()) {
        CapabilityRow(id, label, true, availableDetail)
    } else {
        CapabilityRow(id, label, false, "Server does not advertise ${missing.joinToString(", ")}.")
    }

/** One row per feature, derived only from what the server advertised in `hello`. */
fun capabilityRows(c: ServerCapabilities?): List<CapabilityRow> {
    if (c == null) {
        return listOf(
            "library" to "Library metadata",
            "playlists" to "Playlists",
            "editor" to "Cue, loop and marker editing",
            "metadata" to "Metadata editing",
            "tags" to "Tag editing",
            "analysis" to "Analysis suggestions",
            "audio" to "Server audio",
            "resume" to "Resumable downloads",
        ).map { (id, label) -> CapabilityRow(id, label, false, "Not connected.") }
    }
    val playlists = when {
        c.playlists !in PLAYLIST_MODES ->
            CapabilityRow("playlists", "Playlists", false, "Server reports playlists as '${c.playlists}'.")
        else -> row(
            "playlists",
            "Playlists",
            listOf("playlist", "playlist_item").filter { it !in c.pull },
            if (c.playlists == "read_write") "Read and write." else "Read-only.",
        )
    }
    return listOf(
        row("library", "Library metadata", listOf("asset", "interval").filter { it !in c.pull }, "Assets, intervals and markers sync from the server."),
        playlists,
        row("editor", "Cue, loop and marker editing", listOf("interval.editor_state.replace").filter { it !in c.mutations }, "Edits are sent as acknowledged mutations."),
        row("metadata", "Metadata editing", listOf("asset.metadata.patch").filter { it !in c.mutations }, "Title, artist and rating edits are supported."),
        row("tags", "Tag editing", listOf("tag.ensure_attach", "tag.detach").filter { it !in c.mutations }, "Tags can be attached and detached."),
        row(
            "analysis",
            "Analysis suggestions",
            listOf("suggestions.read").filter { it !in c.analysis } + listOf("analysis_suggestion").filter { it !in c.pull },
            "Suggestions are read from the server.",
        ),
        row(
            "audio",
            "Server audio",
            listOf("asset_file").filter { it !in c.pull } + listOf("resource_uri", "content_hash").filter { it !in c.media }.map { "media:$it" },
            "Tracks can be streamed and downloaded from the server.",
        ),
        row("resume", "Resumable downloads", listOf("range").filter { it !in c.media }.map { "media:$it" }, "Interrupted downloads can resume."),
    )
}

fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.UNCONFIGURED -> "Not connected"
    SyncState.DISCOVERED -> "Server found"
    SyncState.PAIRING -> "Pairing..."
    SyncState.ONLINE_IDLE -> "Connected and up to date"
    SyncState.PULLING -> "Syncing..."
    SyncState.PUSHING -> "Sending changes..."
    SyncState.PENDING_LOCAL -> "Local changes waiting to sync"
    SyncState.OFFLINE -> "Offline (showing last synced library)"
    SyncState.DEGRADED -> "Connected with problems"
    SyncState.CONFLICT -> "Conflicts need review"
    SyncState.AUTH_REQUIRED -> "Pairing required again"
    SyncState.SERVER_INCOMPATIBLE -> "Server version not supported"
    SyncState.LOCAL_MIGRATION_REQUIRED -> "Local data needs migration"
}

fun canPair(url: String, code: String, state: SyncState): Boolean =
    url.isNotBlank() && code.isNotBlank() && state != SyncState.PAIRING && state != SyncState.PULLING
