package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAvailabilityTest {
    private val playable = Track(id = "t1", title = "T", artist = "A", durationMs = 1000)

    @Test
    fun aPlayableTrackCanBePreviewedWithoutAReason() {
        val availability = previewAvailability(playable)
        assertTrue(availability.enabled)
        assertEquals(null, availability.reason)
        assertEquals(PreviewAvailability.Available, availability)
    }

    @Test
    fun anUnplayableTrackCannotBePreviewedAndSaysWhy() {
        val availability = previewAvailability(playable.copy(playable = false, unplayableReason = "No audio on the server yet."))
        assertFalse(availability.enabled)
        assertEquals("No audio on the server yet.", availability.reason)
    }

    @Test
    fun anUnplayableTrackWithoutAReasonStillGetsOne() {
        val availability = previewAvailability(playable.copy(playable = false, unplayableReason = null))
        assertFalse(availability.enabled)
        assertTrue(availability.reason.orEmpty().isNotBlank())
    }

    @Test
    fun theAssistantStateDefaultsToPreviewAvailable() {
        assertEquals(PreviewAvailability.Available, AnalysisAssistantFixtures.competition().preview)
        assertEquals(PreviewAvailability.Available, AnalysisAssistantFixtures.empty().preview)
    }

    @Test
    fun theAvailabilityRidesAlongWhenTheStateIsCopied() {
        val blocked = previewAvailability(playable.copy(playable = false, unplayableReason = "nope"))
        val state = AnalysisAssistantFixtures.competition().copy(preview = blocked)
        assertFalse(state.preview.enabled)
        assertEquals("nope", state.preview.reason)
    }
}
