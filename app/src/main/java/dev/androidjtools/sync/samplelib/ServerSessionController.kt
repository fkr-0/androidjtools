package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
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

/** What the Sync screen needs; implemented by [SampleLibSessionController] and by test fakes. */
interface ServerSessionController {
    val state: StateFlow<SyncState>
    val capabilities: StateFlow<ServerCapabilities?>
    val error: StateFlow<String?>
    val serverUrl: StateFlow<String?>
    fun pair(baseUrl: String, code: String)
    fun refresh()
    fun forget()
}

class SampleLibSessionController(
    private val manager: SampleLibSessionManager,
    private val scope: CoroutineScope,
    private val scheduler: SyncScheduler,
) : ServerSessionController {
    override val state: StateFlow<SyncState> = manager.state
    override val capabilities: StateFlow<ServerCapabilities?> = manager.capabilities
    override val error: StateFlow<String?> = manager.error
    override val serverUrl: StateFlow<String?> = manager.profile
        .map { it?.baseUrl }
        .stateIn(scope, SharingStarted.Eagerly, manager.profile.value?.baseUrl)

    private var job: Job? = null
    private var lastWasForget = false

    override fun pair(baseUrl: String, code: String) = enqueue(forget = false) {
        manager.pair(baseUrl, code)
        if (manager.profile.value != null) scheduler.schedule()
    }

    override fun refresh() = enqueue(forget = false) { manager.refresh() }

    override fun forget() {
        scheduler.cancel()
        enqueue(forget = true) { withContext(NonCancellable) { manager.forget() } }
    }

    /** A new request supersedes an in-flight pair or refresh, but never cancels a pending forget. */
    private fun enqueue(forget: Boolean, block: suspend () -> Unit) {
        val previous = job
        val previousWasForget = lastWasForget
        lastWasForget = forget
        job = scope.launch {
            if (previousWasForget) previous?.join() else previous?.cancelAndJoin()
            block()
        }
    }
}
