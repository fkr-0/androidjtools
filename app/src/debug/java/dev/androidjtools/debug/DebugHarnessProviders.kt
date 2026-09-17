package dev.androidjtools.debug

import android.content.Context
import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.MutationReceipt
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.PendingMutation
import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.ReceiptOutcome
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.DownloadProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.MutationJournalProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import dev.androidjtools.fixture.FixtureAppProviders
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class DebugProviderKind { FIXTURE, SAMPLE_LIB_SIMULATOR }
enum class DebugDataset { NOMINAL, EMPTY, LARGE_LIBRARY, FAILURE }
enum class DebugNetworkState { ONLINE, OFFLINE, DEGRADED }
enum class DebugFailureMode {
    NONE,
    PROVIDER_ERROR,
    CONFLICT,
    DOWNLOAD_FAILURE,
    MUTATION_REJECTED,
    AUTH_REQUIRED,
    SERVER_INCOMPATIBLE,
    LOCAL_MIGRATION_REQUIRED,
    BACKEND_UNHEALTHY,
}

enum class DebugBackendHealthState { HEALTHY, DEGRADED, DOWN, INCOMPATIBLE }

data class DebugBackendHealth(
    val state: DebugBackendHealthState,
    val endpoint: String,
    val protocol: String,
    val latencyMs: Int?,
    val detail: String,
)

data class DebugHarnessConfig(
    val providerKind: DebugProviderKind = DebugProviderKind.FIXTURE,
    val dataset: DebugDataset = DebugDataset.NOMINAL,
    val networkState: DebugNetworkState = DebugNetworkState.ONLINE,
    val syncState: SyncState = SyncState.PENDING_LOCAL,
    val failureMode: DebugFailureMode = DebugFailureMode.NONE,
)

class DebugHarnessController(context: Context) {
    private val appContext = context.applicationContext
    private val mutableConfig = MutableStateFlow(DebugHarnessConfig())
    private val mutableProviders = MutableStateFlow(buildProviders(mutableConfig.value))
    private val mutableHealth = MutableStateFlow(buildHealth(mutableConfig.value))

    val config: StateFlow<DebugHarnessConfig> = mutableConfig.asStateFlow()
    val providers: StateFlow<AppProviders> = mutableProviders.asStateFlow()
    val health: StateFlow<DebugBackendHealth> = mutableHealth.asStateFlow()

    init {
        DebugHarnessContract.assertMatches(appContext)
    }

    fun cycleProvider() = update { it.copy(providerKind = it.providerKind.next()) }
    fun cycleDataset() = update { it.copy(dataset = it.dataset.next()) }
    fun cycleNetwork() = update { it.copy(networkState = it.networkState.next()) }
    fun cycleSyncState() = update { it.copy(syncState = it.syncState.next()) }
    fun cycleFailure() = update { it.copy(failureMode = it.failureMode.next()) }
    fun reset() = setConfig(DebugHarnessConfig())

    private fun update(transform: (DebugHarnessConfig) -> DebugHarnessConfig) {
        setConfig(transform(mutableConfig.value))
    }

    private fun setConfig(next: DebugHarnessConfig) {
        mutableConfig.value = next
        mutableProviders.value = buildProviders(next)
        mutableHealth.value = buildHealth(next)
    }
}

private inline fun <reified T : Enum<T>> T.next(): T {
    val values = enumValues<T>()
    return values[(ordinal + 1) % values.size]
}

private fun buildProviders(config: DebugHarnessConfig): AppProviders {
    val base = FixtureAppProviders.create()
    val tracks = tracksFor(config.dataset)
    val journal = DebugMutationJournalProvider(config)
    return AppProviders(
        runtime = DebugRuntimeProvider(config),
        library = DebugLibraryProvider(tracks),
        playlists = base.playlists,
        preparation = base.preparation,
        playback = base.playback,
        downloads = DebugDownloadProvider(config, tracks, base.playlists.playlists.value),
        journal = journal,
        sync = DebugSyncProvider(config, journal),
        analysis = base.analysis,
    )
}

private class DebugRuntimeProvider(config: DebugHarnessConfig) : AppRuntimeProvider {
    override val uiState: StateFlow<ProviderUiState> = MutableStateFlow(config.toProviderUiState()).asStateFlow()
}

private class DebugLibraryProvider(items: List<Track>) : LibraryProvider {
    private val mutableTracks = MutableStateFlow(items)
    override val tracks: StateFlow<List<Track>> = mutableTracks.asStateFlow()
    override fun track(id: String): Track? = tracks.value.firstOrNull { it.id == id }
}

private class DebugDownloadProvider(
    private val config: DebugHarnessConfig,
    tracks: List<Track>,
    playlists: List<Playlist>,
) : DownloadProvider {
    private val trackById = tracks.associateBy { it.id }
    private val playlistById = playlists.associateBy { it.id }
    private val explicitTrackPins = trackById.values.filter(Track::offlineAvailable).mapTo(linkedSetOf(), Track::id)
    private val playlistPins = mutableSetOf<String>()
    private val states = mutableMapOf<String, MutableStateFlow<TrackDownloadState>>()
    private val mutableCacheSummary = MutableStateFlow(
        OfflineCacheSummary(
            usedBytes = 96_000_000L,
            maxBytes = 512_000_000L,
            evictableBytes = 32_000_000L,
            pinnedTrackIds = explicitTrackPins.toSet(),
        )
    )
    override val cacheSummary: StateFlow<OfflineCacheSummary> = mutableCacheSummary.asStateFlow()

    override fun state(trackId: String): StateFlow<TrackDownloadState> = states.getOrPut(trackId) {
        MutableStateFlow(downloadState(trackId))
    }.asStateFlow()

    override fun pinTrack(trackId: String) {
        if (trackId !in trackById) return
        explicitTrackPins += trackId
        publishPins()
        val flow = mutableState(trackId)
        if (flow.value.status == DownloadStatus.NOT_DOWNLOADED || flow.value.status == DownloadStatus.CANCELLED) {
            flow.value = flow.value.copy(status = DownloadStatus.QUEUED, error = null)
        }
    }

    override fun unpinTrack(trackId: String) {
        explicitTrackPins -= trackId
        publishPins()
    }

    override fun pinPlaylist(playlistId: String) {
        val playlist = playlistById[playlistId] ?: return
        playlistPins += playlistId
        publishPins()
        playlist.trackIds.forEach { trackId ->
            val flow = mutableState(trackId)
            if (flow.value.status == DownloadStatus.NOT_DOWNLOADED || flow.value.status == DownloadStatus.CANCELLED) {
                flow.value = flow.value.copy(status = DownloadStatus.QUEUED, error = null)
            }
        }
    }

    override fun unpinPlaylist(playlistId: String) {
        playlistPins -= playlistId
        publishPins()
    }

    override fun retry(trackId: String) {
        val flow = mutableState(trackId)
        if (flow.value.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.CORRUPT)) {
            flow.value = flow.value.copy(status = DownloadStatus.QUEUED, progress = 0.0, error = null)
        }
    }

    override fun cancel(trackId: String) {
        val flow = mutableState(trackId)
        if (flow.value.status == DownloadStatus.QUEUED || flow.value.status == DownloadStatus.DOWNLOADING) {
            flow.value = flow.value.copy(status = DownloadStatus.CANCELLED, error = "Cancelled")
        }
    }

    override fun pruneUnpinned() {
        mutableCacheSummary.value = cacheSummary.value.copy(
            usedBytes = (cacheSummary.value.usedBytes - cacheSummary.value.evictableBytes).coerceAtLeast(0L),
            evictableBytes = 0L,
        )
    }

    private fun mutableState(trackId: String): MutableStateFlow<TrackDownloadState> = states.getOrPut(trackId) {
        MutableStateFlow(downloadState(trackId))
    }

    private fun publishPins() {
        val inheritedTrackPins = playlistPins
            .asSequence()
            .mapNotNull(playlistById::get)
            .flatMap { it.trackIds.asSequence() }
            .toSet()
        mutableCacheSummary.value = cacheSummary.value.copy(
            pinnedTrackIds = explicitTrackPins + inheritedTrackPins,
            pinnedPlaylistIds = playlistPins.toSet(),
        )
    }

    private fun downloadState(trackId: String): TrackDownloadState {
        if (config.failureMode == DebugFailureMode.DOWNLOAD_FAILURE || config.dataset == DebugDataset.FAILURE) {
            return TrackDownloadState(
                trackId = trackId,
                status = DownloadStatus.FAILED,
                error = "Injected deterministic download failure",
            )
        }
        val track = trackById[trackId]
        return if (track?.offlineAvailable == true) {
            TrackDownloadState(trackId, DownloadStatus.AVAILABLE, progress = 1.0)
        } else {
            TrackDownloadState(trackId, DownloadStatus.NOT_DOWNLOADED)
        }
    }
}

private class DebugMutationJournalProvider(config: DebugHarnessConfig) : MutationJournalProvider {
    override val pending: StateFlow<List<PendingMutation>> = MutableStateFlow(
        listOf(
            PendingMutation(
                mutationId = "debug-mut-001",
                entityType = "marker",
                entityId = "cue-trk-001-debug",
                operation = "update",
                baseRevision = 11,
                createdAt = Instant.EPOCH,
                provenance = "debug-harness",
            ),
            PendingMutation(
                mutationId = "debug-mut-002",
                entityType = "track",
                entityId = "trk-001",
                operation = "accept_suggestion",
                baseRevision = 12,
                createdAt = Instant.EPOCH,
                provenance = "debug-harness",
            ),
        )
    ).asStateFlow()

    override val recentReceipts: StateFlow<List<MutationReceipt>> = MutableStateFlow(
        buildList {
            add(MutationReceipt("debug-receipt-applied", ReceiptOutcome.APPLIED, authoritativeRevision = 13))
            add(MutationReceipt("debug-receipt-noop", ReceiptOutcome.NO_OP, authoritativeRevision = 13))
            if (config.failureMode == DebugFailureMode.MUTATION_REJECTED || config.dataset == DebugDataset.FAILURE) {
                add(MutationReceipt("debug-receipt-rejected", ReceiptOutcome.REJECTED, 13, "Injected rejection"))
            }
            if (config.failureMode == DebugFailureMode.CONFLICT) {
                add(MutationReceipt("debug-receipt-conflict", ReceiptOutcome.CONFLICT, 14, "Injected conflict"))
            }
        }
    ).asStateFlow()
}

private class DebugSyncProvider(
    config: DebugHarnessConfig,
    journal: MutationJournalProvider,
) : SyncProvider {
    override val state: StateFlow<SyncState> = MutableStateFlow(config.effectiveSyncState()).asStateFlow()
    override val pendingMutationCount: StateFlow<Int> = MutableStateFlow(journal.pending.value.size).asStateFlow()
}

private fun DebugHarnessConfig.effectiveSyncState(): SyncState = when {
    failureMode == DebugFailureMode.CONFLICT -> SyncState.CONFLICT
    failureMode == DebugFailureMode.AUTH_REQUIRED -> SyncState.AUTH_REQUIRED
    failureMode == DebugFailureMode.SERVER_INCOMPATIBLE -> SyncState.SERVER_INCOMPATIBLE
    failureMode == DebugFailureMode.LOCAL_MIGRATION_REQUIRED -> SyncState.LOCAL_MIGRATION_REQUIRED
    networkState == DebugNetworkState.OFFLINE -> SyncState.OFFLINE
    networkState == DebugNetworkState.DEGRADED && syncState == SyncState.ONLINE_IDLE -> SyncState.DEGRADED
    else -> syncState
}

private fun DebugHarnessConfig.toProviderUiState(): ProviderUiState = when {
    dataset == DebugDataset.EMPTY -> ProviderUiState.Empty
    dataset == DebugDataset.FAILURE -> ProviderUiState.Error("Injected failure dataset")
    failureMode == DebugFailureMode.PROVIDER_ERROR -> ProviderUiState.Error("Injected provider error")
    failureMode == DebugFailureMode.CONFLICT -> ProviderUiState.Conflict("Injected conflict")
    failureMode == DebugFailureMode.AUTH_REQUIRED -> ProviderUiState.Error("Injected authentication requirement")
    failureMode == DebugFailureMode.SERVER_INCOMPATIBLE -> ProviderUiState.Error("Injected server incompatibility")
    failureMode == DebugFailureMode.LOCAL_MIGRATION_REQUIRED -> ProviderUiState.Error("Injected local migration requirement")
    networkState == DebugNetworkState.OFFLINE -> ProviderUiState.Offline
    syncState == SyncState.PAIRING || syncState == SyncState.PULLING || syncState == SyncState.PUSHING -> ProviderUiState.Loading
    else -> ProviderUiState.Ready
}

private fun buildHealth(config: DebugHarnessConfig): DebugBackendHealth {
    val endpoint = when (config.providerKind) {
        DebugProviderKind.FIXTURE -> "fixture://local"
        DebugProviderKind.SAMPLE_LIB_SIMULATOR -> "simulator://sample-lib"
    }
    val protocol = when (config.providerKind) {
        DebugProviderKind.FIXTURE -> "fixture-v1"
        DebugProviderKind.SAMPLE_LIB_SIMULATOR -> "samplelib-sync-v1"
    }
    return when {
        config.failureMode == DebugFailureMode.SERVER_INCOMPATIBLE -> DebugBackendHealth(
            DebugBackendHealthState.INCOMPATIBLE,
            endpoint,
            protocol,
            35,
            "Injected incompatible protocol/server version",
        )
        config.networkState == DebugNetworkState.OFFLINE -> DebugBackendHealth(
            DebugBackendHealthState.DOWN,
            endpoint,
            protocol,
            null,
            "Injected offline network",
        )
        config.failureMode == DebugFailureMode.BACKEND_UNHEALTHY -> DebugBackendHealth(
            DebugBackendHealthState.DOWN,
            endpoint,
            protocol,
            2_000,
            "Injected backend-health failure",
        )
        config.networkState == DebugNetworkState.DEGRADED -> DebugBackendHealth(
            DebugBackendHealthState.DEGRADED,
            endpoint,
            protocol,
            850,
            "Injected degraded network",
        )
        else -> DebugBackendHealth(
            DebugBackendHealthState.HEALTHY,
            endpoint,
            protocol,
            if (config.providerKind == DebugProviderKind.FIXTURE) 0 else 35,
            "Deterministic debug backend health",
        )
    }
}

private fun tracksFor(dataset: DebugDataset): List<Track> = when (dataset) {
    DebugDataset.EMPTY -> emptyList()
    DebugDataset.FAILURE -> listOf(
        Track(
            id = "trk-failure",
            title = "Broken Reference",
            artist = "Debug Harness",
            album = "Failure Dataset",
            durationMs = 180_000,
            bpm = null,
            key = null,
            rating = 1,
            offlineAvailable = false,
        )
    )
    DebugDataset.NOMINAL -> nominalTracks()
    DebugDataset.LARGE_LIBRARY -> List(512) { index ->
        val number = index + 1
        Track(
            id = "trk-large-${number.toString().padStart(4, '0')}",
            title = "Deterministic Cut ${number.toString().padStart(3, '0')}",
            artist = "Debug Artist ${(number % 17) + 1}",
            album = "Large Fixture ${(number % 9) + 1}",
            durationMs = 150_000L + ((number * 7_919L) % 240_000L),
            bpm = 70.0 + ((number * 13) % 101),
            key = "${(number % 12) + 1}${if (number % 2 == 0) "A" else "B"}",
            energy = ((number * 37) % 100) / 100.0,
            rating = (number % 5) + 1,
            offlineAvailable = number % 3 == 0,
        )
    }
}

private fun nominalTracks(): List<Track> = listOf(
    Track("trk-001", "Night Bus", "Sample Lab", "Debug Nominal", 244_000, 92.5, "8A", 0.72, 4, true),
    Track("trk-002", "Concrete Flash", "Fixture Artist", "Debug Nominal", 198_000, 140.0, "9A", 0.86, 5, false),
    Track("trk-003", "Dub Colony", "Fixture Artist", "Debug Nominal", 327_000, 74.0, "2A", 0.55, 3, true),
)

private object DebugHarnessContract {
    fun assertMatches(context: Context) {
        val raw = context.assets.open("debug-harness-contract.json").bufferedReader().use { it.readText() }
        val contract = JSONObject(raw)
        require(contract.getInt("contract_version") == 1)
        require(contract.names("provider_kinds") == DebugProviderKind.entries.map { it.name })
        require(contract.names("datasets") == DebugDataset.entries.map { it.name })
        require(contract.names("network_states") == DebugNetworkState.entries.map { it.name })
        require(contract.names("sync_states") == SyncState.entries.map { it.name })
        require(contract.names("failure_modes") == DebugFailureMode.entries.map { it.name })
        require(contract.getInt("large_library_size") == 512)
        require(contract.getString("deterministic_seed") == "androidjtools-debug-v1")
    }

    private fun JSONObject.names(key: String): List<String> {
        val array = getJSONArray(key)
        return List(array.length()) { index -> array.getString(index) }
    }
}
