package dev.androidjtools.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.samplelib.ServerSessionController
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncScreenServerTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController : ServerSessionController {
        override val state = MutableStateFlow(SyncState.UNCONFIGURED)
        override val capabilities = MutableStateFlow<ServerCapabilities?>(null)
        override val error = MutableStateFlow<String?>(null)
        val savedUrl = MutableStateFlow<String?>(null)
        override val serverUrl: StateFlow<String?> = savedUrl
        var forgets = 0
        val pairs = mutableListOf<Pair<String, String>>()
        override fun pair(baseUrl: String, code: String) { pairs += baseUrl to code }
        override fun refresh() = Unit
        override fun forget() { forgets++ }
    }

    private fun show(controller: FakeController) {
        compose.setContent {
            AndroidDjToolsTheme { SyncScreen(FixtureAppProviders.create(), controller) }
        }
    }

    @Test
    fun pairStaysDisabledUntilBothFieldsAreFilled() {
        show(FakeController())
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
        compose.onNodeWithTag("server-url-field").performTextInput("https://samplelab.example")
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
        compose.onNodeWithTag("pairing-code-field").performTextInput("246810")
        compose.onNodeWithTag("pair-button").assertIsEnabled()
    }

    @Test
    fun pairingSendsBothValuesAndClearsTheCodeField() {
        val controller = FakeController()
        show(controller)
        compose.onNodeWithTag("server-url-field").performTextInput("https://samplelab.example")
        compose.onNodeWithTag("pairing-code-field").performTextInput("246810")
        compose.onNodeWithTag("pair-button").performClick()
        assertEquals(listOf("https://samplelab.example" to "246810"), controller.pairs)
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
    }

    @Test
    fun theErrorAndEveryCapabilityRowAreShown() {
        val controller = FakeController()
        controller.error.value = "The server rejected the pairing code."
        controller.capabilities.value = ServerCapabilities(
            pull = setOf("asset", "interval"),
            mutations = emptySet(),
            playlists = "unsupported_until_canonical_playlist_migration",
            analysis = emptySet(),
            media = emptySet(),
        )
        show(controller)
        // The rows live in a LazyColumn, so later rows are not composed until scrolled to.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("sync-error"))
        compose.onNodeWithTag("sync-error").assertTextContains("rejected the pairing code", substring = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("capability-library"))
        compose.onNodeWithTag("capability-library").assertTextContains("Available", substring = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("capability-audio"))
        compose.onNodeWithTag("capability-audio").assertTextContains("asset_file", substring = true)
    }

    @Test
    fun theStateComesFromTheControllerAndABusyPairingDisablesPair() {
        val controller = FakeController()
        show(controller)
        compose.onNodeWithTag("server-url-field").performTextInput("https://samplelab.example")
        compose.onNodeWithTag("pairing-code-field").performTextInput("246810")
        compose.onNodeWithTag("pair-button").assertIsEnabled()

        controller.state.value = SyncState.PAIRING

        compose.onNodeWithTag("sync-state").assertTextEquals("State: ${syncStateLabel(SyncState.PAIRING)}")
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
    }

    @Test
    fun forgetAsksForConfirmationAndMentionsDownloads() {
        val controller = FakeController().also { it.savedUrl.value = "https://samplelab.example" }
        show(controller)

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("forget-button"))
        compose.onNodeWithTag("forget-button").performClick()
        assertEquals(0, controller.forgets)
        compose.onNodeWithTag("forget-dialog-text").assertTextContains("downloaded", substring = true)

        compose.onNodeWithTag("forget-cancel").performClick()
        assertEquals(0, controller.forgets)

        compose.onNodeWithTag("forget-button").performClick()
        compose.onNodeWithTag("forget-confirm").performClick()
        assertEquals(1, controller.forgets)
    }
}
