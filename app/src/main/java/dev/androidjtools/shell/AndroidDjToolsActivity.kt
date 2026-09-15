package dev.androidjtools.shell

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.ProviderSelectionStore
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.playback.PlayerQueueController
import dev.androidjtools.playback.SharedPreferencesQueueStateStore
import dev.androidjtools.ui.theme.AndroidDjToolsTheme

class AndroidDjToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectionStore = SharedPreferencesProviderSelectionStore(this)
        val registry = AppProviderRegistry.fixtureOnly()
        val selectedProviderId = selectionStore.selectedProviderId()
        val resolved = registry.resolve(selectedProviderId)
        if (selectedProviderId != resolved.id) {
            selectionStore.selectProvider(resolved.id)
        }
        val playerQueueController = PlayerQueueController(
            playback = resolved.providers.playback,
            store = SharedPreferencesQueueStateStore(this),
        )

        setContent {
            AndroidDjToolsTheme {
                ProviderAwareShell(resolved.providers, playerQueueController)
            }
        }
    }
}

private data class ResolvedProvider(
    val id: String,
    val providers: AppProviders,
)

private class AppProviderRegistry(
    private val factories: Map<String, () -> AppProviders>,
    private val defaultProviderId: String,
) {
    fun resolve(requestedId: String?): ResolvedProvider {
        val id = requestedId?.takeIf(factories::containsKey) ?: defaultProviderId
        return ResolvedProvider(id, factories.getValue(id).invoke())
    }

    companion object {
        fun fixtureOnly() = AppProviderRegistry(
            factories = mapOf(FixtureAppProviders.PROVIDER_ID to { FixtureAppProviders.create() }),
            defaultProviderId = FixtureAppProviders.PROVIDER_ID,
        )
    }
}

private class SharedPreferencesProviderSelectionStore(context: Context) : ProviderSelectionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun selectedProviderId(): String? = preferences.getString(KEY_SELECTED_PROVIDER_ID, null)

    override fun selectProvider(id: String) {
        preferences.edit().putString(KEY_SELECTED_PROVIDER_ID, id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "android-dj-tools-provider-selection"
        const val KEY_SELECTED_PROVIDER_ID = "selected-provider-id"
    }
}

@Composable
private fun ProviderAwareShell(
    providers: AppProviders,
    playerQueueController: PlayerQueueController,
) {
    val providerState by providers.runtime.uiState.collectAsState()
    Box(Modifier.fillMaxSize()) {
        AndroidDjToolsApp(providers, playerQueueController)
        ProviderStateBanner(providerState)
    }
}

@Composable
private fun ProviderStateBanner(state: ProviderUiState) {
    when (state) {
        ProviderUiState.Ready -> Unit
        ProviderUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        ProviderUiState.Empty -> StatusSurface("Provider is ready, but the selected dataset is empty.")
        is ProviderUiState.Error -> StatusSurface("Provider error: ${state.message}")
        ProviderUiState.Offline -> StatusSurface("Provider is offline. Cached fixture data remains available.")
        is ProviderUiState.Conflict -> StatusSurface("Provider conflict: ${state.detail}")
    }
}

@Composable
private fun StatusSurface(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
