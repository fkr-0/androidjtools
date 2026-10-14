package dev.androidjtools.offline.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDownloadCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Scheduler : DownloadScheduler {
        var allCancelled = 0
        override fun enqueue(asset: OfflineAsset, replace: Boolean) = Unit
        override fun cancel(assetId: String) = Unit
        override fun cancelAll() { allCancelled++ }
    }

    private fun asset(id: String) = OfflineAsset(id, "original://$id", "ab".repeat(32), 3)

    private fun fixture(): Triple<OfflineDownloadStore, OfflineCacheStorage, File> {
        val root = tmp.newFolder()
        return Triple(OfflineDownloadStore(File(root, "state")), OfflineCacheStorage(File(root, "media")), root)
    }

    @Test
    fun clearRemovesEveryRecordFromMemoryObserversAndDisk() {
        val (store, _, root) = fixture()
        store.ensure(asset("a"))
        store.ensure(asset("b"))
        val observed = store.observe("a")

        assertTrue(store.clear())

        assertTrue(store.all().isEmpty())
        assertNull(observed.value)
        assertTrue(store.allRecords.value.isEmpty())
        assertTrue(OfflineDownloadStore(File(root, "state")).all().isEmpty())
    }

    @Test
    fun deleteAllRemovesPartialAndFinalFilesOfEveryAsset() {
        val (_, storage, _) = fixture()
        storage.partialFile("a").writeBytes(byteArrayOf(1, 2))
        storage.finalFile("b").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(storage.deleteAll())

        assertEquals(0L, storage.usedBytes())
    }

    @Test
    fun clearOfflineDownloadsStopsWorkFlagsRunningDownloadsAndWipesEverything() {
        val (store, storage, _) = fixture()
        val scheduler = Scheduler()
        store.ensure(asset("a"))
        store.update("a") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, pinOwners = setOf("track:t1")) }
        storage.partialFile("a").writeBytes(byteArrayOf(1))
        store.ensure(asset("b"))
        store.update("b") { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        storage.finalFile("b").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(clearOfflineDownloads(store, storage, scheduler))

        assertEquals(1, scheduler.allCancelled)
        assertTrue(store.all().isEmpty())
        assertEquals(0L, storage.usedBytes())
    }

    @Test
    fun clearOfflineDownloadsReportsFailureWhenSomethingSurvives() {
        val (store, storage, root) = fixture()
        storage.finalFile("stuck").writeBytes(byteArrayOf(1, 2, 3))
        val media = File(root, "media")
        media.setWritable(false) // a read-only directory refuses deletions
        try {
            // Root ignores directory permissions; skip rather than assert something the OS will not enforce.
            org.junit.Assume.assumeTrue(!media.canWrite())
            assertFalse(clearOfflineDownloads(store, storage, Scheduler()))
        } finally {
            media.setWritable(true)
        }
    }
}
