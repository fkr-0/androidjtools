package dev.androidjtools.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.ProviderUiState

@Composable
fun OfflineScreen(providers: AppProviders) {
    val tracks by providers.library.tracks.collectAsState()
    val playlists by providers.playlists.playlists.collectAsState()
    val cache by providers.downloads.cacheSummary.collectAsState()
    val runtime by providers.runtime.uiState.collectAsState()
    val sync by providers.sync.state.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp).testTag("offline-screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Offline", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Pin tracks or playlists for dependable playback away from the network.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OfflineAvailabilityBanner(runtime, sync)
        CacheSummaryCard(
            used = cacheUsageText(cache),
            evictable = formatBytes(cache.evictableBytes),
            onPrune = providers.downloads::pruneUnpinned,
            pruneEnabled = cache.evictableBytes > 0L,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text("Playlists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(playlists, key = Playlist::id) { playlist ->
                PlaylistOfflineRow(
                    playlist = playlist,
                    pinned = playlist.id in cache.pinnedPlaylistIds,
                    onPin = { providers.downloads.pinPlaylist(playlist.id) },
                    onUnpin = { providers.downloads.unpinPlaylist(playlist.id) },
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text("Tracks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(tracks, key = Track::id) { track ->
                OfflineTrackRow(
                    track = track,
                    pinned = track.id in cache.pinnedTrackIds,
                    providers = providers,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun OfflineAvailabilityBanner(runtime: ProviderUiState, sync: SyncState) {
    val message = when {
        runtime == ProviderUiState.Offline || sync == SyncState.OFFLINE -> "Offline mode: cached media remains available. New downloads wait for connectivity."
        runtime is ProviderUiState.Error -> "Provider issue: ${runtime.message}"
        runtime is ProviderUiState.Conflict || sync == SyncState.CONFLICT -> "Sync conflict does not remove verified cached media."
        sync == SyncState.DEGRADED -> "Connection degraded: active downloads may retry automatically."
        else -> null
    } ?: return
    Surface(Modifier.fillMaxWidth().testTag("offline-state-banner"), tonalElevation = 2.dp) {
        Text(message, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CacheSummaryCard(
    used: String,
    evictable: String,
    onPrune: () -> Unit,
    pruneEnabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("offline-cache-summary"),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cache", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(used, style = MaterialTheme.typography.bodyMedium)
                Text("$evictable can be pruned", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onPrune,
                enabled = pruneEnabled,
                modifier = Modifier.testTag("offline-prune").semantics { contentDescription = "Prune unpinned offline media" },
            ) { Text("Prune") }
        }
    }
}

@Composable
private fun PlaylistOfflineRow(
    playlist: Playlist,
    pinned: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("offline-playlist-${playlist.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("${playlist.trackIds.size} tracks${if (pinned) " · pinned" else ""}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = if (pinned) onUnpin else onPin,
            modifier = Modifier.testTag("offline-playlist-pin-${playlist.id}").semantics {
                contentDescription = if (pinned) "Unpin playlist ${playlist.name}" else "Pin playlist ${playlist.name}"
            },
        ) { Text(if (pinned) "Unpin" else "Pin") }
    }
}

@Composable
private fun OfflineTrackRow(
    track: Track,
    pinned: Boolean,
    providers: AppProviders,
) {
    val state by providers.downloads.state(track.id).collectAsState()
    val actions = offlineTrackActions(state, pinned)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("offline-track-${track.id}"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${track.artist} · ${downloadStatusText(state)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    if (pinned) providers.downloads.unpinTrack(track.id) else providers.downloads.pinTrack(track.id)
                },
                modifier = Modifier.testTag("offline-track-pin-${track.id}").semantics {
                    contentDescription = if (pinned) "Unpin ${track.title} for offline use" else "Pin ${track.title} for offline use"
                },
            ) { Text(if (pinned) "Unpin" else "Pin") }
        }

        if (state.status == DownloadStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { state.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Download progress for ${track.title}" },
            )
        }
        if (state.bytesDownloaded > 0L || state.totalBytes != null) {
            Text(
                buildString {
                    append(formatBytes(state.bytesDownloaded))
                    state.totalBytes?.let { append(" / ").append(formatBytes(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.error?.takeIf(String::isNotBlank)?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (actions.canPlay) {
                Button(
                    onClick = { providers.playback.play(track.id) },
                    modifier = Modifier.testTag("offline-track-play-${track.id}").semantics { contentDescription = "Play cached ${track.title}" },
                ) { Text("Play") }
            }
            if (actions.canCancel) {
                OutlinedButton(
                    onClick = { providers.downloads.cancel(track.id) },
                    modifier = Modifier.testTag("offline-track-cancel-${track.id}").semantics { contentDescription = "Cancel download for ${track.title}" },
                ) { Text("Cancel") }
            }
            if (actions.canRetry) {
                OutlinedButton(
                    onClick = { providers.downloads.retry(track.id) },
                    modifier = Modifier.testTag("offline-track-retry-${track.id}").semantics { contentDescription = "Retry download for ${track.title}" },
                ) { Text("Retry") }
            }
        }
    }
}
