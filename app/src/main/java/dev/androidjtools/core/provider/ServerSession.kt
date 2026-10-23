package dev.androidjtools.core.provider

import dev.androidjtools.core.model.SyncState
import kotlinx.coroutines.flow.StateFlow

/** What the paired server advertised, in words the UI can use without knowing the sync protocol. */
data class ServerCapabilityInfo(
    val pull: Set<String>,
    val mutations: Set<String>,
    val playlists: String,
    val analysis: Set<String>,
    val media: Set<String>,
    val canRequestAnalysis: Boolean,
)

/** What the Sync screen needs; implemented by the Sample Lib session controller and by test fakes. */
interface ServerSessionController {
    val state: StateFlow<SyncState>
    val capabilities: StateFlow<ServerCapabilityInfo?>
    val error: StateFlow<String?>
    val serverUrl: StateFlow<String?>
    fun pair(baseUrl: String, code: String)
    fun refresh()
    fun forget()
}
