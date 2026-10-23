package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.ServerCapabilityInfo
import dev.androidjtools.core.provider.ServerSessionController
import dev.androidjtools.remote.samplelib.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SyncScheduler {
    fun schedule()
    fun cancel()
}

fun ServerCapabilities.toInfo() = ServerCapabilityInfo(
    pull = pull,
    mutations = mutations,
    playlists = playlists,
    analysis = analysis,
    media = media,
    canRequestAnalysis = canRequestAnalysis,
)

class SampleLibSessionController(
    private val manager: SampleLibSessionManager,
    private val scope: CoroutineScope,
    private val scheduler: SyncScheduler,
) : ServerSessionController {
    override val state: StateFlow<SyncState> = manager.state
    override val capabilities: StateFlow<ServerCapabilityInfo?> = manager.capabilities
        .map { it?.toInfo() }
        .stateIn(scope, SharingStarted.Eagerly, manager.capabilities.value?.toInfo())
    override val error: StateFlow<String?> = manager.error
    override val serverUrl: StateFlow<String?> = manager.profile
        .map { it?.baseUrl }
        .stateIn(scope, SharingStarted.Eagerly, manager.profile.value?.baseUrl)

    private var job: Job? = null
    private var pendingForget: Job? = null

    override fun pair(baseUrl: String, code: String) = enqueue(forget = false) {
        manager.pair(baseUrl, code)
        if (manager.profile.value != null) scheduler.schedule()
    }

    override fun refresh() = enqueue(forget = false) { manager.refresh() }

    override fun forget() {
        scheduler.cancel()
        enqueue(forget = true) {
            withContext(NonCancellable) { manager.forget() }
            // A pair that returned from the manager just before the forget may still have scheduled; make sure
            // the periodic sync cannot outlive the forget.
            scheduler.cancel()
        }
    }

    /**
     * Every request first waits for the latest forget, so a forget always wins over anything queued after it
     * (not just the request right behind it). Then it supersedes the previous pair or refresh, but never
     * cancels a forget.
     */
    private fun enqueue(forget: Boolean, block: suspend () -> Unit) {
        val previous = job
        val barrier = pendingForget
        val created = scope.launch {
            barrier?.join()
            if (previous != null && previous !== barrier) previous.cancelAndJoin()
            block()
        }
        job = created
        if (forget) pendingForget = created
    }
}
