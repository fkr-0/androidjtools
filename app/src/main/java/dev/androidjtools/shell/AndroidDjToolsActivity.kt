package dev.androidjtools.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
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
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.device.DeviceMediaProviderBundle
import dev.androidjtools.playback.PlayerQueueController
import dev.androidjtools.playback.SharedPreferencesQueueStateStore
import dev.androidjtools.ui.theme.AndroidDjToolsTheme

class AndroidDjToolsActivity : ComponentActivity() {
    private lateinit var deviceProviders: DeviceMediaProviderBundle
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::deviceProviders.isInitialized) deviceProviders.refresh(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceProviders = DeviceMediaProviderBundle.create(this)
        val providers = deviceProviders.providers
        val permission = requiredAudioPermission()
        val permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        deviceProviders.refresh(permissionGranted)
        if (!permissionGranted) audioPermissionLauncher.launch(permission)

        val playerQueueController = PlayerQueueController(
            playback = providers.playback,
            store = SharedPreferencesQueueStateStore(this),
        )

        setContent {
            AndroidDjToolsTheme {
                ProviderAwareShell(providers, playerQueueController)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::deviceProviders.isInitialized) {
            val permission = requiredAudioPermission()
            deviceProviders.refresh(
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    override fun onDestroy() {
        if (::deviceProviders.isInitialized) deviceProviders.close()
        super.onDestroy()
    }
}

private fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
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
