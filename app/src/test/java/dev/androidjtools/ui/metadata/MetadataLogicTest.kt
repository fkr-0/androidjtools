package dev.androidjtools.ui.metadata

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLogicTest {
    private val track = Track("t", "Night Bus", "Sample Lab", "Fixture Cuts", 180_000, 92.5, "8A", 0.72, 4, true)

    @Test
    fun `editing is staged cancellable and save produces local mutation intent`() {
        val initial = startMetadataEditor(track)
        val staged = initial.copy(staged = initial.staged.copy(title = "Night Bus Edit", genre = "Hip-Hop", tags = setOf("mood:late")))
        assertTrue(staged.dirty)
        assertEquals(initial.canonical, cancelMetadataChanges(staged).staged)

        val saved = queueMetadataSave(staged)
        assertFalse(saved.dirty)
        assertNotNull(saved.queuedIntent)
        assertEquals(setOf("title", "genre", "tags"), saved.queuedIntent!!.changedFields)
    }

    @Test
    fun `validation rejects unsafe bpm year and unscoped tags`() {
        val invalid = track.toMetadataDraft().copy(
            title = "",
            year = "3026",
            bpm = "500",
            tags = setOf("late-night"),
        )
        val errors = validateMetadataDraft(invalid)
        assertTrue("title" in errors)
        assertTrue("year" in errors)
        assertTrue("bpm" in errors)
        assertTrue("tags" in errors)
    }

    @Test
    fun `intelligence proposal never changes canonical value until explicitly accepted and saved`() {
        val initial = startMetadataEditor(track)
        val suggestion = AnalysisSuggestion(
            id = "sg-bpm",
            trackId = track.id,
            kind = SuggestionKind.BPM,
            payload = SuggestionPayload.Bpm(94.0),
            source = IntelligenceSource("sample-intelligence", "tempo", "2"),
            confidence = 0.97,
        )
        assertEquals("92.5", initial.canonical.bpm)
        assertEquals("92.5", initial.staged.bpm)

        val accepted = acceptSuggestionIntoStage(initial, suggestion)
        assertEquals("92.5", accepted.canonical.bpm)
        assertEquals("94.0", accepted.staged.bpm)
        assertEquals("sg-bpm", accepted.acceptedProposals["bpm"]?.suggestionId)

        val saved = queueMetadataSave(accepted)
        assertEquals("92.5", saved.canonical.bpm)
        assertEquals("94.0", saved.staged.bpm)
        assertFalse(saved.dirty)
        assertEquals("sg-bpm", saved.queuedIntent?.provenance?.get("bpm")?.suggestionId)

        val resaved = queueMetadataSave(saved.copy(staged = saved.staged.copy(genre = "Dub")))
        assertEquals("sg-bpm", resaved.queuedIntent?.provenance?.get("bpm")?.suggestionId)
        assertTrue("genre" in resaved.queuedIntent!!.changedFields)
    }

    @Test
    fun `cancel after queued save returns to queued local baseline without pretending it is canonical`() {
        val initial = startMetadataEditor(track)
        val queued = queueMetadataSave(initial.copy(staged = initial.staged.copy(genre = "Dub")))
        val editedAgain = queued.copy(staged = queued.staged.copy(genre = "Techno"))

        val cancelled = cancelMetadataChanges(editedAgain)

        assertEquals("", cancelled.canonical.genre)
        assertEquals("Dub", cancelled.staged.genre)
        assertEquals("Dub", cancelled.queuedIntent?.staged?.genre)
        assertFalse(cancelled.dirty)
    }

    @Test
    fun `batch patch applies only declared fields`() {
        val initial = startMetadataEditor(track)
        val patched = applyBatchMetadataPatch(
            initial,
            BatchMetadataPatch(genre = "Dub", addTags = setOf("set:warmup"), preparationNote = "Long intro"),
        )
        assertEquals("Night Bus", patched.staged.title)
        assertEquals("Dub", patched.staged.genre)
        assertEquals(setOf("set:warmup"), patched.staged.tags)
        assertEquals("Long intro", patched.staged.preparationNote)
        assertNull(patched.queuedIntent)
    }
}
