package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibSession

sealed interface SyncResult {
    data class Synced(val pages: Int, val upserts: Int, val tombstones: Int, val bootstrapped: Boolean) : SyncResult

    /** The server's authority generation moved under us; reconnect (fresh hello) and sync again. */
    data object NeedsReconnect : SyncResult
}

class SampleLibSyncEngine(
    private val client: SampleLibClient,
    private val cache: LibraryCache,
) {
    suspend fun sync(session: SampleLibSession, scopes: Set<String> = setOf("library")): SyncResult {
        val stored = cache.meta()
        val serverId = session.identity.serverId
        if (stored.serverId != null && stored.serverId != serverId) {
            throw SampleLibFailure.ServerIdentityMismatch(stored.serverId, serverId)
        }
        if (stored.authorityGeneration != null && stored.authorityGeneration != session.identity.authorityGeneration) {
            // Cached rows and the cursor describe another authority generation: they are meaningless now.
            cache.reset()
        }
        var cursor = cache.meta().cursor
        var bootstrapped = cursor == null
        // The generation was just reconciled against the cache, so the client must not demand a reset again.
        val active = session.copy(requiresReset = false)

        var pages = 0
        var upserts = 0
        var tombstones = 0
        var retriedExpiredCursor = false

        while (true) {
            val page = try {
                client.pull(active, cursor, scopes)
            } catch (expired: SampleLibFailure.CursorExpired) {
                cache.reset()
                val otherGeneration = expired.authorityGeneration.isNotBlank() &&
                    expired.authorityGeneration != session.identity.authorityGeneration
                if (retriedExpiredCursor || otherGeneration) return SyncResult.NeedsReconnect
                retriedExpiredCursor = true
                cursor = null
                bootstrapped = true
                pages = 0
                upserts = 0
                tombstones = 0
                continue
            } catch (reset: SampleLibFailure.ResetRequired) {
                cache.reset()
                return SyncResult.NeedsReconnect
            }

            cache.applyPage(page.changes, page.tombstones, CacheMeta(page.nextCursor, page.authorityGeneration, serverId))
            pages++
            upserts += page.changes.size
            tombstones += page.tombstones.size
            cursor = page.nextCursor
            if (!page.hasMore) return SyncResult.Synced(pages, upserts, tombstones, bootstrapped)
        }
    }
}
