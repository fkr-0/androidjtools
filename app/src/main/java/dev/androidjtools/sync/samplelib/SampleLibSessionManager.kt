package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MediaAuth
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import dev.androidjtools.remote.samplelib.PairingCode
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibHttpTransport
import dev.androidjtools.remote.samplelib.SampleLibSession
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.normalizeBaseUrl
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val SAMPLE_LIB_CLIENT_ID = "android-dj-tools"

val defaultSampleLibClientFactory: (String, String) -> SampleLibClient = { baseUrl, installationId ->
    SampleLibClient(SampleLibHttpTransport(baseUrl), SAMPLE_LIB_CLIENT_ID, installationId)
}

/**
 * Owns the connection lifecycle: pair, reconnect, sync, forget. Every operation holds one lock, so a
 * forget can never interleave with a sync. User-visible messages never contain secrets.
 */
class SampleLibSessionManager(
    private val store: SampleLibProfileStore,
    private val cache: LibraryCache,
    private val clientFactory: (baseUrl: String, installationId: String) -> SampleLibClient = defaultSampleLibClientFactory,
    private val newInstallationId: () -> String = { UUID.randomUUID().toString() },
    /** Removes server-derived local data outside the library cache (downloaded audio). False aborts the caller's operation. */
    private val onLibraryDiscarded: suspend () -> Boolean = { true },
    /** Queued local edits, delivered right after connecting and before the library is pulled. */
    private val outbox: EditOutbox = EditOutbox.None,
) : MediaAuthProvider {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(SyncState.UNCONFIGURED)
    private val mutableCapabilities = MutableStateFlow<ServerCapabilities?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableProfile = MutableStateFlow<SampleLibProfile?>(null)

    // The media bearer is cached in memory so playback does not touch the Keystore per request. The generation
    // counter stops a slow store load from re-caching a credential that was forgotten or rejected meanwhile.
    private val authLock = Any()
    private var cachedAuth: MediaAuth? = null
    private var authUsable = true
    private var authGeneration = 0L

    override fun mediaAuth(): MediaAuth? {
        val generation: Long
        synchronized(authLock) {
            if (!authUsable) return null
            cachedAuth?.let { return it }
            generation = authGeneration
        }
        val session = (store.load() as? StoredSessionResult.Loaded)?.session ?: return null
        val auth = MediaAuth.create(session.profile.baseUrl, session.credential) ?: return null
        synchronized(authLock) {
            if (!authUsable || authGeneration != generation) return null
            cachedAuth = auth
        }
        return auth
    }

    private fun revokeMediaAuth() = synchronized(authLock) {
        authGeneration++
        authUsable = false
        cachedAuth = null
    }

    private fun acceptMediaAuth(baseUrl: String, session: SampleLibSession) = synchronized(authLock) {
        authGeneration++
        authUsable = true
        cachedAuth = MediaAuth.create(baseUrl, session.credential)
    }

    val state: StateFlow<SyncState> = mutableState.asStateFlow()
    val capabilities: StateFlow<ServerCapabilities?> = mutableCapabilities.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val profile: StateFlow<SampleLibProfile?> = mutableProfile.asStateFlow()

    /** Loads the stored session (if any), reconnects with the pinned server identity and syncs. */
    suspend fun refresh() = lock.withLock {
        val startState = mutableState.value
        when (val loaded = store.load()) {
            StoredSessionResult.Absent -> {
                revokeMediaAuth()
                mutableProfile.value = null
                mutableCapabilities.value = null
                mutableState.value = SyncState.UNCONFIGURED
            }
            is StoredSessionResult.Unreadable -> {
                revokeMediaAuth()
                mutableProfile.value = loaded.profile
                mutableCapabilities.value = null
                fail(SyncState.AUTH_REQUIRED, "The stored server credential could not be read. Pair again.")
            }
            is StoredSessionResult.Loaded -> {
                val stored = loaded.session
                mutableProfile.value = stored.profile
                guarded(startState, ::applyRefreshFailure) {
                    val client = clientFactory(stored.profile.baseUrl, stored.profile.installationId)
                    val session = client.connect(stored.credential, stored.profile.serverId, null, stored.profile.principalId)
                    syncSession(client, session)
                }
            }
        }
    }

    suspend fun pair(rawUrl: String, rawCode: String) = lock.withLock {
        val baseUrl = try {
            normalizeBaseUrl(rawUrl)
        } catch (invalid: IllegalArgumentException) {
            mutableError.value = invalid.message
            return@withLock
        }
        val code = rawCode.trim()
        if (code.isEmpty()) {
            mutableError.value = "Enter the pairing code."
            return@withLock
        }

        val previousState = mutableState.value
        mutableError.value = null
        mutableState.value = SyncState.PAIRING
        val installationId = mutableProfile.value?.installationId ?: newInstallationId()
        val (client, session) = try {
            val created = clientFactory(baseUrl, installationId)
            created to created.pair(PairingCode.entered(code))
        } catch (cancelled: CancellationException) {
            mutableState.value = previousState
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = previousState
            mutableError.value = describePairingFailure(failure)
            return@withLock
        }

        guarded(previousState, ::applyRefreshFailure) {
            val cachedServer = cache.meta().serverId
            if (cachedServer != null && cachedServer != session.identity.serverId) {
                // The old server's files are meaningless (and unmanageable) under a new server.
                if (!attempt { onLibraryDiscarded() }) {
                    mutableState.value = previousState
                    mutableError.value = "Could not remove the previous server's downloads. Try pairing again."
                    return@guarded
                }
                cache.reset()
            }
            val saved = SampleLibProfile(baseUrl, session.identity.serverId, session.principalId, installationId)
            store.save(saved, session.credential)
            mutableProfile.value = saved
            syncSession(client, session)
        }
    }

    /**
     * Removes the stored connection and the cached library. Both removals are attempted independently; the
     * manager only reports UNCONFIGURED when both really succeeded, otherwise it stays DEGRADED with the
     * profile still known so the person can retry. Never throws (except to propagate cancellation).
     */
    suspend fun forget() = lock.withLock {
        revokeMediaAuth()
        val downloadsCleared = attempt { onLibraryDiscarded() }
        val storeCleared = attempt { store.clear() }
        // Keep the cached server identity while foreign downloads remain: it is what lets a later pairing or a
        // retried Forget recognise them as belonging to another server.
        val cacheReset = downloadsCleared && attempt {
            cache.reset()
            true
        }
        if (downloadsCleared && storeCleared && cacheReset) {
            mutableProfile.value = null
            mutableCapabilities.value = null
            mutableError.value = null
            mutableState.value = SyncState.UNCONFIGURED
        } else {
            fail(SyncState.DEGRADED, "Could not remove the stored server connection from this device. Try again.")
        }
    }

    private inline fun attempt(block: () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun syncSession(client: SampleLibClient, session: SampleLibSession) {
        mutableProfile.value?.let { acceptMediaAuth(it.baseUrl, session) }
        mutableCapabilities.value = session.capabilities
        if (outbox.hasPending()) {
            mutableState.value = SyncState.PUSHING
            outbox.deliver(client, session)
        }
        mutableState.value = SyncState.PULLING
        val engine = SampleLibSyncEngine(client, cache)
        var result = engine.sync(session)
        if (result is SyncResult.NeedsReconnect) {
            val fresh = client.connect(session.credential, session.identity.serverId, null, session.principalId)
            mutableCapabilities.value = fresh.capabilities
            result = engine.sync(fresh)
            if (result is SyncResult.NeedsReconnect) {
                throw SampleLibFailure.InvalidResponse("Server authority kept changing during sync")
            }
        }
        mutableError.value = null
        mutableState.value = SyncState.ONLINE_IDLE
    }

    private suspend fun guarded(restoreOnCancel: SyncState, onFailure: (Exception) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            // Do not leave PAIRING/PULLING behind: no operation is running any more.
            mutableState.value = restoreOnCancel
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }

    private fun applyRefreshFailure(failure: Exception) {
        when (failure) {
            is SampleLibFailure.AuthRequired -> {
                revokeMediaAuth()
                fail(SyncState.AUTH_REQUIRED, "The server rejected the stored credential. Pair again.")
            }
            is SampleLibFailure.Incompatible ->
                fail(SyncState.SERVER_INCOMPATIBLE, "This server does not support a compatible sync protocol.")
            is SampleLibFailure.Offline ->
                fail(SyncState.OFFLINE, "Server unreachable. Showing the last synced library.")
            is SampleLibFailure.ServerIdentityMismatch ->
                fail(SyncState.DEGRADED, "The server identity changed. Forget this server and pair again.")
            is SampleLibFailure ->
                fail(SyncState.DEGRADED, "The server returned an unexpected response.")
            else -> fail(SyncState.DEGRADED, "Local storage error.")
        }
    }

    private fun describePairingFailure(failure: Exception): String = when (failure) {
        is SampleLibFailure.PairingRejected -> "The server rejected the pairing code."
        is SampleLibFailure.InsecureTransport -> "Use an https:// address for servers outside this device."
        is SampleLibFailure.Offline -> "Could not reach the server."
        is SampleLibFailure.Incompatible -> "This server does not support a compatible sync protocol."
        is SampleLibFailure.ServerIdentityMismatch -> "The server identity could not be confirmed."
        is SampleLibFailure.AuthRequired -> "The server did not authorize this pairing."
        is SampleLibFailure -> "The server returned an unexpected response."
        is IllegalArgumentException -> failure.message ?: "Invalid pairing input."
        else -> "Pairing failed."
    }

    private fun fail(state: SyncState, message: String) {
        mutableError.value = message
        mutableState.value = state
    }
}
