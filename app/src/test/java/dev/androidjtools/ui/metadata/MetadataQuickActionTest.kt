package dev.androidjtools.ui.metadata

import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataQuickActionTest {
    private val track = Track(
        id = "track",
        title = "Night Bus",
        artist = "Sample Lab",
        album = "Fixture Cuts",
        durationMs = 244_000,
        bpm = 92.5,
        key = "8A",
        energy = 0.72,
        rating = 4,
        offlineAvailable = true,
    )

    @Test
    fun oneTapRatingAndNamespacedTagStayStagedUntilSaveIntent() {
        val initial = startMetadataEditor(track)
        val staged = applyBatchMetadataPatch(
            initial,
            BatchMetadataPatch(rating = 5, addTags = setOf("mood:late-night")),
        )

        assertEquals(4, staged.canonical.rating)
        assertEquals(5, staged.staged.rating)
        assertEquals(setOf("mood:late-night"), staged.staged.tags)
        assertTrue(staged.dirty)

        val saved = queueMetadataSave(staged)
        assertEquals(4, saved.canonical.rating)
        assertEquals(setOf("rating", "tags"), saved.queuedIntent?.changedFields)
        assertFalse(saved.dirty)
    }
}
