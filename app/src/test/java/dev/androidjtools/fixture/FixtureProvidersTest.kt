package dev.androidjtools.fixture

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.SuggestionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureProvidersTest {
    @Test
    fun fixtureHasTracksAndAdvisoryIntelligence() {
        val providers = FixtureAppProviders.create()
        val track = providers.library.tracks.value.first()
        val suggestions = providers.analysis.suggestions(track.id).value
        assertTrue(suggestions.isNotEmpty())
        val first = suggestions.first()
        providers.analysis.accept(first.id)
        assertEquals(
            SuggestionDecision.ACCEPTED,
            providers.analysis.suggestions(track.id).value.first { it.id == first.id }.decision,
        )
    }

    @Test
    fun fixtureExposesCollectionsDownloadsAndOfflineJournal() {
        val providers = FixtureAppProviders.create()
        assertTrue(providers.playlists.playlists.value.any { it.smart })
        assertEquals(
            DownloadStatus.AVAILABLE,
            providers.downloads.state("trk-001").value.status,
        )
        assertEquals(2, providers.journal.pending.value.size)
        assertEquals(2, providers.sync.pendingMutationCount.value)
        assertTrue(providers.journal.pending.value.any { it.provenance?.startsWith("suggestion:") == true })
    }
}
