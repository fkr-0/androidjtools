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

    /** Wraps the device providers once; their content then follows pairing state at runtime. */
    fun compose(device: AppProviders): AppProviders {
        val serverLibrary = SampleLibLibraryProvider(projection)
        return device.copy(
            runtime = CompositeRuntimeProvider(device.runtime, serverLibrary, scope),
            library = CompositeLibraryProvider(serverLibrary, device.library, scope),
            preparation = SampleLibPreparationProvider(projection, scope),
            playback = ServerAwarePlayback(device.playback) { projection.projection.value.hasTrack(it) },
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
