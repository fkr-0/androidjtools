package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.PullMode
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLibSyncEngineTest {
    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, "android-dj-tools", "install-1")
    private val engine = SampleLibSyncEngine(client, cache)

    @After
    fun close() = db.close()

    private fun session(): SampleLibSession = runBlocking { client.connect(MobileCredential.issued("token"), transport.serverId) }

    private fun seedCache(cursor: String, vararg ids: String, generation: String = "gen-1", server: String = "server-1") {
        cache.applyPage(ids.map { assetChange(it) }, emptyList(), CacheMeta(cursor, generation, server))
    }

    private fun sync(): SyncResult = runBlocking { engine.sync(session()) }

    @Test
    fun bootstrapsAMultiPageSnapshotAndCommitsEachPageWithItsCursor() {
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a1"), assetChange("a2"))))
        transport.enqueuePage(page("c2", changes = listOf(assetChange("a3"))))

        val result = sync()

        assertEquals(SyncResult.Synced(pages = 2, upserts = 3, tombstones = 0, bootstrapped = true), result)
        assertEquals(listOf(null, "c1"), transport.pullCursors)
        assertEquals(listOf("a1", "a2", "a3"), cache.entities("asset").map { it.entityId })
        assertEquals(CacheMeta("c2", "gen-1", "server-1"), cache.meta())
    }

    @Test
    fun incrementalPullAppliesChangesAndTombstonesFromTheStoredCursor() {
        seedCache("c1", "a1", "a2")
        transport.enqueuePage(page("c2", mode = PullMode.INCREMENTAL, changes = listOf(assetChange("a3")), tombstones = listOf(tombstoneOf("asset", "a1"))))

        val result = sync()

        assertEquals(SyncResult.Synced(pages = 1, upserts = 1, tombstones = 1, bootstrapped = false), result)
        assertEquals(listOf("c1"), transport.pullCursors)
        assertEquals(listOf("a2", "a3"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anInterruptedBootstrapResumesFromTheLastCommittedPage() {
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a1"))))
        transport.enqueueFailure(SampleLibFailure.Offline())

        assertThrows(SampleLibFailure.Offline::class.java) { sync() }
        assertEquals("c1", cache.meta().cursor)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })

        transport.enqueuePage(page("c2", changes = listOf(assetChange("a2"))))
        sync()

        assertEquals(listOf(null, "c1", "c1"), transport.pullCursors)
        assertEquals(listOf("a1", "a2"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aMalformedPageNeverAdvancesTheCursor() {
        seedCache("c1", "a1")
        transport.enqueueFailure(SampleLibFailure.InvalidResponse("bad page"))

        assertThrows(SampleLibFailure.InvalidResponse::class.java) { sync() }
        assertEquals("c1", cache.meta().cursor)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun cursorExpiredDropsTheCacheAndRebootstraps() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))
        transport.enqueuePage(page("c9", changes = listOf(assetChange("fresh"))))

        val result = sync() as SyncResult.Synced

        assertTrue(result.bootstrapped)
        assertEquals(listOf("old", null), transport.pullCursors)
        assertEquals(listOf("fresh"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun cursorExpiredForAnotherGenerationAsksForAReconnect() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-2"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun repeatedCursorExpiryDoesNotLoopForever() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertEquals(2, transport.pullCursors.size)
    }

    @Test
    fun aGenerationChangeClearsTheCacheBeforeAnythingIsPulled() {
        seedCache("old", "stale", generation = "gen-0")
        transport.enqueuePage(page("c1", changes = listOf(assetChange("fresh"))))

        val result = sync() as SyncResult.Synced

        assertTrue(result.bootstrapped)
        assertEquals(listOf<String?>(null), transport.pullCursors)
        assertEquals(listOf("fresh"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aPageFromAnotherGenerationMidSyncAsksForAReconnect() {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1")), generation = "gen-2"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun aDifferentServerIdentityIsRefusedAndTheCacheIsLeftAlone() {
        seedCache("c1", "a1", server = "other-server")

        assertThrows(SampleLibFailure.ServerIdentityMismatch::class.java) { sync() }
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertTrue(transport.pullCursors.isEmpty())
    }

    @Test
    fun aSyncWithNothingNewIsAnEmptyDeltaNotAReset() {
        seedCache("c1", "a1")
        transport.enqueuePage(page("c1", mode = PullMode.INCREMENTAL))

        assertEquals(SyncResult.Synced(pages = 1, upserts = 0, tombstones = 0, bootstrapped = false), sync())
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aPageThatRepeatsTheCursorWhileClaimingMoreIsRejectedAndNotCommitted() {
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a1"))))
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a2"))))

        assertThrows(SampleLibFailure.InvalidResponse::class.java) { sync() }

        assertEquals(listOf(null, "c1"), transport.pullCursors)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertEquals("c1", cache.meta().cursor)
    }
}
