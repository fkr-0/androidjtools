package dev.androidjtools.ui.offline

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import dev.androidjtools.fixture.FixtureAppProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePresentationTest {
    @Test
    fun `actions follow download state and pin state deterministically`() {
        val downloading = offlineTrackActions(
            TrackDownloadState("a", DownloadStatus.DOWNLOADING, progress = 0.5),
            pinned = true,
        )
        assertTrue(downloading.canCancel)
        assertTrue(downloading.canUnpin)
        assertFalse(downloading.canRetry)
        assertFalse(downloading.canPlay)

        val failed = offlineTrackActions(TrackDownloadState("a", DownloadStatus.FAILED), pinned = false)
        assertTrue(failed.canRetry)
        assertTrue(failed.canPin)

        val ready = offlineTrackActions(TrackDownloadState("a", DownloadStatus.AVAILABLE), pinned = true)
        assertTrue(ready.canPlay)
        assertFalse(ready.canCancel)
    }

    @Test
    fun `cache and progress formatting is locale stable`() {
        assertEquals("142.0 MB of 1.0 GB used", cacheUsageText(OfflineCacheSummary(142_000_000, 1_000_000_000)))
        assertEquals("Downloading 42%", downloadStatusText(TrackDownloadState("a", DownloadStatus.DOWNLOADING, 0.42)))
    }

    @Test
    fun `fixture pin cancel retry and prune mutate only offline provider state`() {
        val providers = FixtureAppProviders.create()
        val downloads = providers.downloads

        assertTrue("trk-001" in downloads.cacheSummary.value.pinnedTrackIds)
        assertEquals(DownloadStatus.DOWNLOADING, downloads.state("trk-002").value.status)
        downloads.cancel("trk-002")
        assertEquals(DownloadStatus.CANCELLED, downloads.state("trk-002").value.status)
        downloads.retry("trk-002")
        assertEquals(DownloadStatus.QUEUED, downloads.state("trk-002").value.status)
        downloads.pinPlaylist("pl-energy")
        assertTrue("pl-energy" in downloads.cacheSummary.value.pinnedPlaylistIds)
        assertTrue("trk-002" in downloads.cacheSummary.value.pinnedTrackIds)
        downloads.unpinPlaylist("pl-energy")
        assertFalse("pl-energy" in downloads.cacheSummary.value.pinnedPlaylistIds)
        assertFalse("trk-002" in downloads.cacheSummary.value.pinnedTrackIds)
        assertTrue("trk-001" in downloads.cacheSummary.value.pinnedTrackIds)

        val before = downloads.cacheSummary.value.usedBytes
        downloads.pruneUnpinned()
        assertTrue(downloads.cacheSummary.value.usedBytes <= before)
        assertEquals(0L, downloads.cacheSummary.value.evictableBytes)
    }
}
