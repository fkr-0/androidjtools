package dev.androidjtools.offline.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDownloadJobGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val asset = OfflineAsset("a", "original://a", "ab".repeat(32), 3)

    private fun store() = OfflineDownloadStore(File(tmp.newFolder(), "state"))

    @Test
    fun aQueuedJobWithoutACredentialIsUnauthorized() {
        val store = store().also { it.ensure(asset) }
        assertEquals(DownloadJobGate.UNAUTHORIZED, downloadJobGate(store, "a", hasMediaAuth = false))
    }

    @Test
    fun aJobWhoseRecordWasClearedIsStaleAndMustNotRecreateIt() {
        val store = store()
        assertEquals(DownloadJobGate.STALE, downloadJobGate(store, "a", hasMediaAuth = true))
        assertEquals(DownloadJobGate.STALE, downloadJobGate(store, "a", hasMediaAuth = false))
    }

    @Test
    fun aJobWithARecordAndACredentialRuns() {
        val store = store().also { it.ensure(asset) }
        assertEquals(DownloadJobGate.RUN, downloadJobGate(store, "a", hasMediaAuth = true))
    }
}
