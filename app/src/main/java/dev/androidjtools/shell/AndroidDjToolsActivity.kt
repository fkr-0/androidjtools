package dev.androidjtools.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import dev.androidjtools.AndroidDjToolsApp
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
                AndroidDjToolsApp(providers, playerQueueController)
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
