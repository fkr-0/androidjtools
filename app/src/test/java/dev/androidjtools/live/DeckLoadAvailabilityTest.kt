package dev.androidjtools.live

import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLoadAvailabilityTest {
    private val base = Track(id = "t", title = "T", artist = "A", durationMs = 1000)

    @Test
    fun aDeviceOrDownloadedTrackCanBeLoaded() {
        val availability = deckLoadAvailability(base)
        assertTrue(availability.enabled)
        assertNull(availability.reason)
    }

    @Test
    fun aStreamOnlyServerTrackNeedsADownloadFirst() {
        val availability = deckLoadAvailability(base.copy(deckBlockedReason = "Download this track to load it on a deck."))
        assertFalse(availability.enabled)
        assertEquals("Download this track to load it on a deck.", availability.reason)
    }

    @Test
    fun anUnplayableTrackExplainsWhyItsOwnReasonFirst() {
        val availability = deckLoadAvailability(base.copy(playable = false, unplayableReason = "No audio.", deckBlockedReason = "Download"))
        assertFalse(availability.enabled)
        assertEquals("No audio.", availability.reason)
    }

    @Test
    fun anUnplayableTrackWithoutAReasonStillGetsOne() {
        val availability = deckLoadAvailability(base.copy(playable = false))
        assertFalse(availability.enabled)
        assertTrue(availability.reason.orEmpty().isNotBlank())
    }
}
