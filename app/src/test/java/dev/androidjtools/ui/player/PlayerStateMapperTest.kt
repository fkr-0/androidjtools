package dev.androidjtools.ui.player

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.ProviderUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStateMapperTest {
    private val onlineTrack = Track("a", "Track", "Artist", durationMs = 90_000, offlineAvailable = false)

    @Test
    fun loadingEmptyReadyUnavailableAndErrorStatesAreExplicit() {
        assertEquals(PlayerSurfaceState.LOADING, map(ProviderUiState.Loading, null).surface)
        assertEquals(PlayerSurfaceState.EMPTY, map(ProviderUiState.Ready, null).surface)
        assertEquals(PlayerSurfaceState.READY, map(ProviderUiState.Ready, onlineTrack).surface)
        assertEquals(PlayerSurfaceState.UNAVAILABLE, map(ProviderUiState.Offline, onlineTrack, SyncState.OFFLINE).surface)

        val failed = TrackDownloadState("a", DownloadStatus.FAILED, error = "cache corrupt")
        val state = map(ProviderUiState.Ready, onlineTrack, download = failed)
        assertEquals(PlayerSurfaceState.ERROR, state.surface)
        assertEquals("cache corrupt", state.errorMessage)
    }

    @Test
    fun playingStateAndSafetyIndicatorsRemainAdvisoryData() {
        val state = PlayerStateMapper.map(
            providerState = ProviderUiState.Ready,
            track = onlineTrack,
            isPlaying = true,
            positionMs = 12_000,
            queueTrackIds = listOf("a", "b"),
            syncState = SyncState.PENDING_LOCAL,
            downloadState = null,
            warningMessages = listOf("Harsh transient"),
        )
        assertEquals(PlayerSurfaceState.READY, state.surface)
        assertEquals(true, state.isPlaying)
        assertEquals(listOf("Harsh transient"), state.warningMessages)
    }

    private fun map(
        provider: ProviderUiState,
        track: Track?,
        sync: SyncState = SyncState.ONLINE_IDLE,
        download: TrackDownloadState? = null,
    ) = PlayerStateMapper.map(
        providerState = provider,
        track = track,
        isPlaying = false,
        positionMs = 0,
        queueTrackIds = emptyList(),
        syncState = sync,
        downloadState = download,
        warningMessages = emptyList(),
    )
}
