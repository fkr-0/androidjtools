package dev.androidjtools.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.fixture.FixtureScenario
import dev.androidjtools.playback.InMemoryQueueStateStore
import dev.androidjtools.playback.PlayerQueueController
import org.junit.Rule
import org.junit.Test

class PlayerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val track = Track(
        id = "trk-001",
        title = "Night Bus",
        artist = "Sample Lab",
        album = "Fixture Cuts",
        durationMs = 244_000,
        bpm = 92.5,
        key = "8A",
        offlineAvailable = true,
    )

    @Test
    fun miniPlayerRepresentsReadyPlayingState() {
        compose.setContent {
            MaterialTheme {
                MiniPlayer(
                    state = PlayerUiState(PlayerSurfaceState.READY, track = track, isPlaying = true),
                    onToggle = {},
                    onOpen = {},
                )
            }
        }
        compose.onNodeWithTag("mini-player").assertExists()
        compose.onNodeWithText("Night Bus").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun miniAndFullPlayer_keepTransportAndSeekContinuityAcrossExpansion() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        val controller = PlayerQueueController(providers.playback, InMemoryQueueStateStore())
        controller.playNow("trk-001")
        controller.seek(61_000)
        var expanded by mutableStateOf(false)

        compose.setContent {
            MaterialTheme {
                PlayerHost(
                    providers = providers,
                    controller = controller,
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                    onJumpToPrep = {},
                )
            }
        }

        compose.onNodeWithTag("mini-player").assertExists()
        compose.onNodeWithText("Night Bus").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithTag("mini-open").performClick()

        compose.onNodeWithTag("full-player").assertExists()
        compose.onNodeWithText("1:01").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Close player").performClick()

        compose.onNodeWithTag("mini-player").assertExists()
        compose.onNodeWithTag("mini-toggle").performClick()
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithTag("mini-open").performClick()
        compose.onNodeWithTag("full-player").assertExists()
        compose.onNodeWithText("1:01").assertExists()
        compose.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun fixturePlaybackDrivesReadyAndPausedMiniPlayer() {
        val providers = FixtureAppProviders.create(FixtureScenario.NOMINAL)
        val controller = PlayerQueueController(providers.playback, InMemoryQueueStateStore())
        controller.playNow("trk-001")
        compose.setContent {
            MaterialTheme {
                PlayerHost(
                    providers = providers,
                    controller = controller,
                    expanded = false,
                    onExpand = {},
                    onCollapse = {},
                    onJumpToPrep = {},
                )
            }
        }
        compose.onNodeWithText("Night Bus").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()

        compose.runOnIdle { controller.toggle() }
        compose.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun fixtureLoadingStateReachesTheMiniPlayer() {
        val loadingProviders = FixtureAppProviders.create(FixtureScenario.LOADING)
        compose.setContent {
            MaterialTheme {
                PlayerHost(
                    providers = loadingProviders,
                    controller = PlayerQueueController(loadingProviders.playback, InMemoryQueueStateStore()),
                    expanded = false,
                    onExpand = {},
                    onCollapse = {},
                    onJumpToPrep = {},
                )
            }
        }
        compose.onNodeWithText("Loading player…").assertExists()
    }

    @Test
    fun fixtureErrorStateReachesTheMiniPlayer() {
        val errorProviders = FixtureAppProviders.create(FixtureScenario.ERROR)
        compose.setContent {
            MaterialTheme {
                PlayerHost(
                    providers = errorProviders,
                    controller = PlayerQueueController(errorProviders.playback, InMemoryQueueStateStore()),
                    expanded = false,
                    onExpand = {},
                    onCollapse = {},
                    onJumpToPrep = {},
                )
            }
        }
        compose.onNodeWithText("Fixture provider failure").assertExists()
    }

    @Test
    fun fullPlayerShowsQueueCurrentTrackMetadataAndAdvisoryWarning() {
        compose.setContent {
            MaterialTheme {
                FullPlayer(
                    state = PlayerUiState(
                        surface = PlayerSurfaceState.READY,
                        track = track,
                        queueTrackIds = listOf("trk-001"),
                        syncState = SyncState.PENDING_LOCAL,
                        warningMessages = listOf("Possible harsh transient"),
                    ),
                    tracks = mapOf(track.id to track),
                    onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {},
                    onMove = { _, _ -> }, onRemove = {}, onClear = {},
                    onJumpToPrep = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithTag("full-player").assertExists()
        compose.onNodeWithTag("queue-item-trk-001").assertExists()
        compose.onNodeWithText("92.5 BPM").assertExists()
        compose.onNodeWithTag("safety-indicators").assertExists()
        compose.onNodeWithText("Review only — nothing is applied automatically.").assertExists()
    }

    @Test
    fun unavailableStateIsRenderedWithoutCrashingQueue() {
        compose.setContent {
            MaterialTheme {
                FullPlayer(
                    state = PlayerUiState(PlayerSurfaceState.UNAVAILABLE, track = track.copy(offlineAvailable = false)),
                    tracks = emptyMap(),
                    onToggle = {}, onPrevious = {}, onNext = {}, onSeek = {},
                    onMove = { _, _ -> }, onRemove = {}, onClear = {},
                    onJumpToPrep = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("This track is not cached and playback is offline.").assertExists()
        compose.onNodeWithText("Queue is empty").assertExists()
    }
}
