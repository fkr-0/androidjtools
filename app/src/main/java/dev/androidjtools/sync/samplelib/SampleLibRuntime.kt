package dev.androidjtools.sync.samplelib

import android.content.Context
import dev.androidjtools.core.provider.AppProviders
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide Sample Lib stack shared by the activity and the background worker. */
class SampleLibRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext

    // noBackupFilesDir is excluded from backups even if the manifest policy ever changes.
    private val directory = File(appContext.noBackupFilesDir, "samplelib").also { it.mkdirs() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cache: LibraryCache = SqlLibraryCache(AndroidSqlDatabase(appContext, File(directory, "library-cache.db").path))
    val manager = SampleLibSessionManager(SampleLibProfileStore(directory, AesGcmKeystoreCipher()), cache)
    val scheduler: SyncScheduler = WorkManagerSyncScheduler(appContext)
    val controller: ServerSessionController = SampleLibSessionController(manager, scope, scheduler)
    private val projection = SampleLibProjectionState(cache, scope)

    /**
     * Wraps the device providers; their content then follows pairing state at runtime.
     * [wrapperScope] is used for the composite/wrapper flows and should be tied to the activity's lifecycle
     * (e.g. `lifecycleScope`) so that wrapper collectors do not leak across activity recreations.
     * The projection state, manager, controller and scheduler remain process-scoped.
     */
    fun compose(device: AppProviders, wrapperScope: CoroutineScope = scope): AppProviders {
        val serverLibrary = SampleLibLibraryProvider(projection)
        val isServer: (String) -> Boolean = { projection.projection.value.hasTrack(it) }
        return device.copy(
            runtime = CompositeRuntimeProvider(device.runtime, serverLibrary, wrapperScope),
            library = CompositeLibraryProvider(serverLibrary, device.library, wrapperScope),
            preparation = SampleLibPreparationProvider(projection, wrapperScope),
            playback = ServerAwarePlayback(device.playback, isServer),
            downloads = ServerAwareDownloads(device.downloads, isServer),
            sync = SampleLibSyncProvider(manager),
        )
    }

    companion object {
        @Volatile
        private var instance: SampleLibRuntime? = null

        fun get(context: Context): SampleLibRuntime =
            instance ?: synchronized(this) {
                instance ?: SampleLibRuntime(context.applicationContext).also { instance = it }
            }
    }
}
