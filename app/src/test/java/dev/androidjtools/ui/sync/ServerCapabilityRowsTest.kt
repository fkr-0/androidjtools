package dev.androidjtools.ui.sync

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.samplelib.toInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCapabilityRowsTest {
    /** Exactly what the real Sample Lib sync facade advertised in the live contract run. */
    private val realServer = ServerCapabilities(
        pull = setOf("asset", "interval", "marker", "playlist", "playlist_item", "tag", "tag_attachment"),
        mutations = setOf("interval.editor_state.replace"),
        playlists = "canonical_v1",
        analysis = emptySet(),
        media = setOf("resource_uri", "content_hash", "range"),
    )

    private fun rows(c: ServerCapabilities?) = capabilityRows(c?.toInfo()).associateBy { it.id }

    @Test
    fun theRealServerSupportsLibraryPlaylistsAndEditorButNotTheRest() {
        val r = rows(realServer)
        assertTrue(r.getValue("library").available)
        assertTrue(r.getValue("playlists").available)
        assertEquals("Read-only.", r.getValue("playlists").detail)
        assertTrue(r.getValue("editor").available)
        assertTrue(r.getValue("resume").available)
        assertFalse(r.getValue("metadata").available)
        assertFalse(r.getValue("tags").available)
        assertFalse(r.getValue("analysis").available)
        assertFalse(r.getValue("audio").available)
    }

    @Test
    fun unavailableRowsNameTheMissingServerCapability() {
        val r = rows(realServer)
        assertTrue(r.getValue("audio").detail.contains("asset_file"))
        assertTrue(r.getValue("metadata").detail.contains("asset.metadata.patch"))
        assertTrue(r.getValue("tags").detail.contains("tag.ensure_attach"))
        assertTrue(r.getValue("analysis").detail.contains("analysis"))
    }

    @Test
    fun aFutureServerThatAdvertisesAudioAndAnalysisLightsUpThoseRows() {
        val future = realServer.copy(
            pull = realServer.pull + setOf("asset_file"),
            analysis = setOf("tempo", "key", "beat_grid", "cue_candidates"),
            mutations = realServer.mutations + setOf("asset.metadata.patch", "tag.ensure_attach", "tag.detach"),
        )
        val r = rows(future)
        assertTrue(r.getValue("audio").available)
        assertTrue(r.getValue("analysis").available)
        assertTrue(r.getValue("metadata").available)
        assertTrue(r.getValue("tags").available)
    }

    @Test
    fun analysisIsAvailableFromTheAdvertisedKindsAndOnDemandAnalysisIsSaidSeparately() {
        val readOnly = realServer.copy(analysis = setOf("tempo", "key"))
        assertTrue(rows(readOnly).getValue("analysis").available)
        assertTrue(rows(readOnly).getValue("analysis").detail.contains("not offered", ignoreCase = true))

        val onDemand = readOnly.copy(analysisRequest = "enqueue_v1")
        assertTrue(rows(onDemand).getValue("analysis").detail.contains("on demand", ignoreCase = true))
        assertFalse(rows(onDemand).getValue("analysis").detail.contains("not offered", ignoreCase = true))
    }

    @Test
    fun anOldServerThatReportsPlaylistsAsUnsupportedSaysSo() {
        val old = realServer.copy(playlists = "unsupported_until_canonical_playlist_migration", pull = setOf("asset", "interval"))
        val row = rows(old).getValue("playlists")
        assertFalse(row.available)
        assertTrue(row.detail.contains("unsupported_until_canonical_playlist_migration"))
    }

    @Test
    fun readWritePlaylistsAreReportedAsSuch() {
        assertEquals("Read and write.", rows(realServer.copy(playlists = "read_write")).getValue("playlists").detail)
    }

    @Test
    fun missingLibraryFamiliesAreNamed() {
        val row = rows(realServer.copy(pull = setOf("asset"))).getValue("library")
        assertFalse(row.available)
        assertTrue(row.detail.contains("interval"))
    }

    @Test
    fun whenNotConnectedEveryRowIsUnavailableAndSaysSo() {
        val all = capabilityRows(null)
        assertEquals(8, all.size)
        assertTrue(all.none { it.available })
        assertTrue(all.all { it.detail == "Not connected." })
    }

    @Test
    fun everySyncStateHasADistinctNonBlankLabel() {
        val labels = SyncState.entries.map(::syncStateLabel)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun pairingNeedsBothFieldsAndNoPairingInFlight() {
        assertTrue(canPair("https://h", "123456", SyncState.UNCONFIGURED))
        assertFalse(canPair("", "123456", SyncState.UNCONFIGURED))
        assertFalse(canPair("https://h", "  ", SyncState.UNCONFIGURED))
        assertFalse(canPair("https://h", "123456", SyncState.PAIRING))
        assertFalse(canPair("https://h", "123456", SyncState.PULLING))
        assertTrue(canPair("https://h", "123456", SyncState.AUTH_REQUIRED))
    }
}
