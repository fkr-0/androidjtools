package dev.androidjtools.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.device.DeviceMediaProviderBundle
import dev.androidjtools.device.mediaUriForDeviceTrack
import dev.androidjtools.live.AndroidMidiInputRouter
import dev.androidjtools.live.LivePerformanceController
import dev.androidjtools.live.Media3LiveAudioEngine
import dev.androidjtools.live.NativeAaudioLiveAudioEngine
import dev.androidjtools.playback.PlayerQueueController
import dev.androidjtools.playback.SharedPreferencesQueueStateStore
import dev.androidjtools.sync.samplelib.SampleLibRuntime
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AndroidDjToolsActivity : ComponentActivity() {
    private lateinit var deviceProviders: DeviceMediaProviderBundle
    private lateinit var livePerformanceController: LivePerformanceController
    private lateinit var midiInputRouter: AndroidMidiInputRouter
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::deviceProviders.isInitialized) deviceProviders.refresh(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceProviders = DeviceMediaProviderBundle.create(this)
        val runtime = SampleLibRuntime.get(this)
        val providers = runtime.compose(deviceProviders.providers, lifecycleScope)
        val permission = requiredAudioPermission()
        val permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        deviceProviders.refresh(permissionGranted)
        if (!permissionGranted) audioPermissionLauncher.launch(permission)

        val playerQueueController = PlayerQueueController(
            playback = providers.playback,
            store = SharedPreferencesQueueStateStore(this),
        )
        val onLivePosition: (dev.androidjtools.live.DeckId, Long) -> Unit = { deck, positionMs ->
            if (::livePerformanceController.isInitialized) {
                livePerformanceController.reportPosition(deck, positionMs)
            }
        }
        val liveEngine = NativeAaudioLiveAudioEngine.createOrNull(
            context = this,
            mediaUriResolver = ::mediaUriForDeviceTrack,
            onPosition = onLivePosition,
        ) ?: Media3LiveAudioEngine(
            context = this,
            mediaUriResolver = ::mediaUriForDeviceTrack,
            onPosition = onLivePosition,
        )
        livePerformanceController = LivePerformanceController(liveEngine)
        midiInputRouter = AndroidMidiInputRouter(this, livePerformanceController).also { it.start() }

        setContent {
            AndroidDjToolsTheme {
                AndroidDjToolsApp(providers, playerQueueController, livePerformanceController, server = runtime.controller)
            }
        }

        // Dispatchers.Default: lifecycleScope would otherwise run the stored-session load (file read and Keystore
        // decrypt) on the main thread until the first suspension.
        lifecycleScope.launch(Dispatchers.Default) {
            runtime.manager.refresh()
            if (runtime.manager.profile.value != null) runtime.scheduler.schedule()
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
        if (::midiInputRouter.isInitialized) midiInputRouter.close()
        if (::livePerformanceController.isInitialized) livePerformanceController.release()
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
