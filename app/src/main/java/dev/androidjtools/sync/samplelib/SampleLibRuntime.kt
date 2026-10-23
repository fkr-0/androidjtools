package dev.androidjtools.sync.samplelib

import android.content.Context
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.offline.download.DownloadScheduler
import dev.androidjtools.offline.download.OFFLINE_QUOTA_BYTES
import dev.androidjtools.offline.download.OfflineDownloadCoordinator
import dev.androidjtools.offline.download.OfflineDownloadRuntime
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.offline.download.WorkManagerDownloadScheduler
import dev.androidjtools.offline.download.clearOfflineDownloads
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibSession
import dev.androidjtools.remote.samplelib.newMediaHttpClient
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.FileMutationJournalStorage
import dev.androidjtools.sync.analysis.SampleLibAnalysisProvider
import dev.androidjtools.sync.journal.JournalEditOutbox
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Process-wide Sample Lib stack shared by the activity and the background worker. */
class SampleLibRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext

    // noBackupFilesDir is excluded from backups even if the manifest policy ever changes.
    private val directory = File(appContext.noBackupFilesDir, "samplelib").also { it.mkdirs() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cache: LibraryCache = SqlLibraryCache(AndroidSqlDatabase(appContext, File(directory, "library-cache.db").path))

    /**
     * Cue and loop edits waiting for (or answered by) the server. Lazy: opening it reads a file, which must not
     * happen on the main thread; saved edits from an earlier run are dropped when it is first opened.
     */
    val journal: Lazy<DurableMutationJournal> = lazy {
        DurableMutationJournal(FileMutationJournalStorage(File(directory, "mutations.journal"))).also { it.compactAcknowledged() }
    }

    /** Suggestions the person dismissed on this device. Local only, and cleared with the server they belong to. */
    private val dismissals: SuggestionDismissals = FileSuggestionDismissals(File(directory, "dismissed-suggestions.json"))
    private val journalOutbox by lazy { JournalEditOutbox(journal.value) }
    private val outbox = object : EditOutbox {
        override fun hasPending(): Boolean = journalOutbox.hasPending()
        override suspend fun deliver(client: SampleLibClient, session: SampleLibSession) = journalOutbox.deliver(client, session)
    }

    val manager = SampleLibSessionManager(
        SampleLibProfileStore(directory, AesGcmKeystoreCipher()),
        cache,
        onLibraryDiscarded = {
            withContext(Dispatchers.IO) {
                // Unsent edits and dismissals were made for the server being discarded; they must never reach another one.
                discardServerBoundState(journal, dismissals) && clearOfflineDownloads(offline.store, offline.storage, downloadScheduler)
            }
        },
        outbox = outbox,
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
        pairingRequired = manager.state.map { it == SyncState.AUTH_REQUIRED },
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
        val journalProvider = SampleLibJournalProvider(journal, wrapperScope, Dispatchers.IO)
        return device.copy(
            runtime = CompositeRuntimeProvider(device.runtime, serverLibrary, wrapperScope),
            library = CompositeLibraryProvider(serverLibrary, device.library, wrapperScope),
            playlists = SampleLibPlaylistProvider(projection),
            preparation = SampleLibPreparationProvider(projection, wrapperScope),
            playback = ServerAwarePlayback(device.playback) { id -> playbackRefusal(projection.projection.value, id) },
            downloads = SampleLibDownloadProvider(
                delegate = device.downloads,
                projection = projection.projection,
                rangeSupported = { serverAudioSupport(manager.capabilities.value).range },
                offline = lazy {
                    OfflineParts(OfflineDownloadCoordinator(offline.store, downloadScheduler, offline.resolver), offline.store, offline.storage)
                },
                scope = wrapperScope,
                maxBytes = OFFLINE_QUOTA_BYTES,
                freeSpaceBytes = { offline.root.usableSpace },
                dispatcher = ioDispatcher,
            ),
            analysis = SampleLibAnalysisProvider(
                projection = projection.projection,
                capabilities = manager.capabilities,
                syncState = manager.state,
                lazyJournal = journal,
                dismissals = dismissals,
                requester = manager,
                requestFlush = controller::refresh,
                ioDispatcher = Dispatchers.IO,
                scope = wrapperScope,
            ),
            journal = journalProvider,
            sync = SampleLibSyncProvider(manager, journalProvider.pendingCount),
            edits = SampleLibServerEditProvider(
                lazyJournal = journal,
                projection = projection.projection,
                capabilities = manager.capabilities,
                syncState = manager.state,
                requestFlush = controller::refresh,
                ioDispatcher = Dispatchers.IO,
                scope = wrapperScope,
            ),
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
