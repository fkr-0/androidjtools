package dev.androidjtools.sync.samplelib

import android.content.Context
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.offline.download.DownloadScheduler
import dev.androidjtools.offline.download.OFFLINE_QUOTA_BYTES
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadRuntime
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.offline.download.WorkManagerDownloadScheduler
import dev.androidjtools.offline.download.clearOfflineDownloads
import dev.androidjtools.remote.samplelib.newMediaHttpClient
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Process-wide Sample Lib stack shared by the activity and the background worker. */
class SampleLibRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext

    // noBackupFilesDir is excluded from backups even if the manifest policy ever changes.
    private val directory = File(appContext.noBackupFilesDir, "samplelib").also { it.mkdirs() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cache: LibraryCache = SqlLibraryCache(AndroidSqlDatabase(appContext, File(directory, "library-cache.db").path))
    val manager = SampleLibSessionManager(
        SampleLibProfileStore(directory, AesGcmKeystoreCipher()),
        cache,
        onLibraryDiscarded = { withContext(Dispatchers.IO) { clearOfflineDownloads(offline.store, offline.storage, downloadScheduler) } },
    )
    val scheduler: SyncScheduler = WorkManagerSyncScheduler(appContext)
    val controller: ServerSessionController = SampleLibSessionController(manager, scope, scheduler)

    /** One authenticating client for Media3 streaming and downloads: bearer only for the paired media path, no redirects. */
    val mediaHttpClient: OkHttpClient = newMediaHttpClient(manager)
    val rangeTransport: RangeTransport = SampleLibRangeTransport(manager, mediaHttpClient)

    /** Lazy: reading the offline state is disk I/O that the service and the sync worker never need. */
    val offline: OfflineDownloadRuntime by lazy { OfflineDownloadRuntime.create(appContext, rangeTransport) }
    val downloadScheduler: DownloadScheduler by lazy { WorkManagerDownloadScheduler(appContext) }
    private val projection = SampleLibProjectionState(
        cache = cache,
        scope = scope,
        capabilities = manager.capabilities,
        downloaded = flow { emitAll(downloadedFiles(offline.store.allRecords)) },
    )
    private val ioDispatcher = Executors.newSingleThreadExecutor { Thread(it, "samplelib-offline").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    val mediaResolver = SampleLibMediaResolver(
        projection = { projection.projection.value },
        baseUrl = { manager.profile.value?.baseUrl },
        offline = { offline.resolver },
    )

    /**
     * Wraps the device providers; their content then follows pairing state at runtime.
     * [wrapperScope] is used for the composite/wrapper flows and should be tied to the activity's lifecycle
     * (e.g. `lifecycleScope`) so that wrapper collectors do not leak across activity recreations.
     * The projection state, manager, controller and scheduler remain process-scoped.
     */
    fun compose(device: AppProviders, wrapperScope: CoroutineScope = scope): AppProviders {
        val serverLibrary = SampleLibLibraryProvider(projection)
        return device.copy(
            runtime = CompositeRuntimeProvider(device.runtime, serverLibrary, wrapperScope),
            library = CompositeLibraryProvider(serverLibrary, device.library, wrapperScope),
            preparation = SampleLibPreparationProvider(projection, wrapperScope),
            playback = ServerAwarePlayback(device.playback) { id -> playbackRefusal(projection.projection.value, id) },
            downloads = SampleLibDownloadProvider(
                delegate = device.downloads,
                projection = projection.projection,
                rangeSupported = { serverAudioSupport(manager.capabilities.value).range },
                coordinator = OfflineDownloadCoordinator(offline.store, downloadScheduler, offline.resolver),
                store = offline.store,
                storage = offline.storage,
                scope = wrapperScope,
                maxBytes = OFFLINE_QUOTA_BYTES,
                freeSpaceBytes = { offline.root.usableSpace },
                dispatcher = ioDispatcher,
            ),
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
