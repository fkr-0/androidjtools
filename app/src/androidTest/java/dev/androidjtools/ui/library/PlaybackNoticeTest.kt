package dev.androidjtools.ui.library

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.ui.player.PlaybackNoticeBanner
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class PlaybackNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private class NoticePlayback(private val delegate: PlaybackProvider) : PlaybackProvider by delegate {
        override val playbackNotice: StateFlow<String?> = MutableStateFlow("Server audio is not available yet.")
    }

    private class NoNoticePlayback(private val delegate: PlaybackProvider) : PlaybackProvider by delegate {
        override val playbackNotice: StateFlow<String?> = MutableStateFlow(null)
    }

    @Test
    fun theBannerShowsTheProvidersPlaybackNotice() {
        val fixtures = FixtureAppProviders.create()
        val providers = fixtures.copy(playback = NoticePlayback(fixtures.playback))
        compose.setContent { AndroidDjToolsTheme { PlaybackNoticeBanner(providers) } }
        compose.onNodeWithTag("playback-notice").assertIsDisplayed()
    }

    @Test
    fun theNoticeIsAnnouncedAsAPoliteLiveRegion() {
        val fixtures = FixtureAppProviders.create()
        val providers = fixtures.copy(playback = NoticePlayback(fixtures.playback))
        compose.setContent { AndroidDjToolsTheme { PlaybackNoticeBanner(providers) } }
        compose.onNodeWithTag("playback-notice")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun noNodeWhenTheNoticeIsNull() {
        val fixtures = FixtureAppProviders.create()
        val providers = fixtures.copy(playback = NoNoticePlayback(fixtures.playback))
        compose.setContent { AndroidDjToolsTheme { PlaybackNoticeBanner(providers) } }
        compose.onNodeWithTag("playback-notice").assertDoesNotExist()
    }
}
