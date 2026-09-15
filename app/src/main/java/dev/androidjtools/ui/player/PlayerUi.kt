package dev.androidjtools.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.playback.PlayerQueueController

enum class PlayerSurfaceState {
    EMPTY,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR,
}

data class PlayerUiState(
    val surface: PlayerSurfaceState,
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val queueTrackIds: List<String> = emptyList(),
    val syncState: SyncState = SyncState.UNCONFIGURED,
    val downloadState: TrackDownloadState? = null,
    val warningMessages: List<String> = emptyList(),
    val errorMessage: String? = null,
)

object PlayerStateMapper {
    fun map(
        providerState: ProviderUiState,
        track: Track?,
        isPlaying: Boolean,
        positionMs: Long,
        queueTrackIds: List<String>,
        syncState: SyncState,
        downloadState: TrackDownloadState?,
        warningMessages: List<String>,
    ): PlayerUiState {
        val surface = when {
            providerState is ProviderUiState.Error -> PlayerSurfaceState.ERROR
            providerState == ProviderUiState.Loading && track == null -> PlayerSurfaceState.LOADING
            track == null -> PlayerSurfaceState.EMPTY
            syncState == SyncState.OFFLINE && !track.offlineAvailable -> PlayerSurfaceState.UNAVAILABLE
            downloadState?.status == DownloadStatus.FAILED -> PlayerSurfaceState.ERROR
            else -> PlayerSurfaceState.READY
        }
        return PlayerUiState(
            surface = surface,
            track = track,
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0),
            queueTrackIds = queueTrackIds,
            syncState = syncState,
            downloadState = downloadState,
            warningMessages = warningMessages,
            errorMessage = when {
                providerState is ProviderUiState.Error -> providerState.message
                downloadState?.status == DownloadStatus.FAILED -> downloadState.error ?: "Media download failed"
                else -> null
            },
        )
    }
}

@Composable
fun PlayerHost(
    providers: AppProviders,
    controller: PlayerQueueController,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onJumpToPrep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrackId by providers.playback.currentTrackId.collectAsState()
    val isPlaying by providers.playback.isPlaying.collectAsState()
    val positionMs by providers.playback.positionMs.collectAsState()
    val tracks by providers.library.tracks.collectAsState()
    val queue by controller.queue.collectAsState()
    val providerState by providers.runtime.uiState.collectAsState()
    val syncState by providers.sync.state.collectAsState()
    val track = tracks.firstOrNull { it.id == currentTrackId }
    val downloadState = track?.let { providers.downloads.state(it.id).collectAsState().value }
    val suggestions = track?.let { providers.analysis.suggestions(it.id).collectAsState().value }.orEmpty()
    val warnings = suggestions.mapNotNull { suggestion ->
        (suggestion.payload as? SuggestionPayload.Warning)?.message
    }
    val state = PlayerStateMapper.map(
        providerState = providerState,
        track = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        queueTrackIds = queue,
        syncState = syncState,
        downloadState = downloadState,
        warningMessages = warnings,
    )
    if (expanded) {
        FullPlayer(
            state = state,
            tracks = tracks.associateBy(Track::id),
            onToggle = controller::toggle,
            onPrevious = controller::previous,
            onNext = controller::next,
            onSeek = controller::seek,
            onMove = controller::move,
            onRemove = controller::remove,
            onClear = controller::clear,
            onJumpToPrep = { track?.id?.let(onJumpToPrep) },
            onClose = onCollapse,
            modifier = modifier,
        )
    } else {
        MiniPlayer(
            state = state,
            onToggle = controller::toggle,
            onOpen = onExpand,
            modifier = modifier,
        )
    }
}

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.surface == PlayerSurfaceState.EMPTY) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("mini-player"),
        tonalElevation = 3.dp,
    ) {
        when (state.surface) {
            PlayerSurfaceState.LOADING -> PlayerMessage("Loading player…")
            PlayerSurfaceState.ERROR -> PlayerMessage(state.errorMessage ?: "Playback error")
            PlayerSurfaceState.UNAVAILABLE -> PlayerMessage("Media unavailable offline")
            PlayerSurfaceState.EMPTY -> Unit
            PlayerSurfaceState.READY -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(state.track?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onToggle, modifier = Modifier.testTag("mini-toggle")) {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
                    }
                    IconButton(onClick = onOpen, modifier = Modifier.testTag("mini-open")) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Open player and queue")
                    }
                }
            }
        }
    }
}

@Composable
fun FullPlayer(
    state: PlayerUiState,
    tracks: Map<String, Track>,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onJumpToPrep: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag("full-player"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Now auditioning", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close player") }
        }
        when (state.surface) {
            PlayerSurfaceState.LOADING -> PlayerMessage("Loading player…")
            PlayerSurfaceState.ERROR -> PlayerMessage(state.errorMessage ?: "Playback error")
            PlayerSurfaceState.UNAVAILABLE -> PlayerMessage("This track is not cached and playback is offline.")
            PlayerSurfaceState.EMPTY -> PlayerMessage("Choose a track to start an audition.")
            PlayerSurfaceState.READY -> ReadyPlayerBody(state, onToggle, onPrevious, onNext, onSeek, onJumpToPrep)
        }
        QueuePanel(
            currentTrackId = state.track?.id,
            queueTrackIds = state.queueTrackIds,
            tracks = tracks,
            onMove = onMove,
            onRemove = onRemove,
            onClear = onClear,
        )
    }
}

@Composable
private fun ReadyPlayerBody(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onJumpToPrep: () -> Unit,
) {
    val track = requireNotNull(state.track)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp))
            }
            Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(track.artist, style = MaterialTheme.typography.titleMedium)
            track.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                track.bpm?.let { Text("${"%.1f".format(it)} BPM") }
                track.key?.let { Text(it) }
                Text(if (track.offlineAvailable) "Offline ready" else "Stream/cache")
                Text("Sync: ${state.syncState.name.lowercase().replace('_', ' ')}")
            }
            val duration = track.durationMs.coerceAtLeast(1L)
            Slider(
                value = (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                onValueChange = { onSeek((it * duration).toLong()) },
                modifier = Modifier.testTag("player-seek"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.testTag("player-previous")) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.testTag("player-toggle")) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onNext, modifier = Modifier.testTag("player-next")) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
            Button(onClick = onJumpToPrep, modifier = Modifier.testTag("jump-to-prep")) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open prep")
            }
        }
    }
    if (state.warningMessages.isNotEmpty()) {
        Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().testTag("safety-indicators")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Analysis indicators", fontWeight = FontWeight.SemiBold)
                state.warningMessages.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Review only — nothing is applied automatically.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun QueuePanel(
    currentTrackId: String?,
    queueTrackIds: List<String>,
    tracks: Map<String, Track>,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear, enabled = queueTrackIds.isNotEmpty(), modifier = Modifier.testTag("queue-clear")) { Text("Clear") }
    }
    if (queueTrackIds.isEmpty()) {
        Text("Queue is empty", style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().testTag("queue-list")) {
        itemsIndexed(queueTrackIds, key = { _, id -> id }) { index, id ->
            val track = tracks[id]
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("queue-item-$id"),
                tonalElevation = if (id == currentTrackId) 4.dp else 0.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(track?.title ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (id == currentTrackId) FontWeight.Bold else FontWeight.Normal)
                        Text(track?.artist ?: "Unavailable catalog entry", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) { Text("↑") }
                    TextButton(onClick = { onMove(index, index + 1) }, enabled = index < queueTrackIds.lastIndex) { Text("↓") }
                    TextButton(onClick = { onRemove(id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun PlayerMessage(message: String) {
    Surface(modifier = Modifier.fillMaxWidth().testTag("player-message"), tonalElevation = 2.dp) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
