package dev.androidjtools.sync.samplelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorAggregatesTest {
    private fun interval(json: String, revision: String = "rev-7") =
        CachedEntity("interval", "i1", revision, "sample-lib.interval-editor-state/v1", json)

    private val full = """{"interval":{"id":"i1","asset_id":"a1","start_ms":1000,"end_ms":9000},"markers":[
        {"id":"m1","position_ms":1500,"label":"Intro","marker_type":"custom","color":"#112233"},
        {"id":"m2","position_ms":2500,"marker_type":"slice"},
        {"id":"m3","position_ms":3000,"label":"in","marker_type":"loop_start"}]}"""

    @Test
    fun theContextCarriesTheOpaqueRevisionTheBoundsAndEveryMarkerIncludingTypesTheCueUiIgnores() {
        val context = EditorAggregates.contextOf(interval(full))!!
        assertEquals("i1", context.intervalId)
        assertEquals("rev-7", context.baseRevision)
        assertEquals(1000L, context.startMs)
        assertEquals(9000L, context.endMs)
        assertTrue(context.completeMarkerSet)
        assertEquals(listOf("m1", "m2", "m3"), context.markers.map { it.id })
        assertEquals(listOf("custom", "slice", "loop_start"), context.markers.map { it.markerType })
        assertEquals("Intro", context.markers.first().label)
        assertEquals("#112233", context.markers.first().color)
        assertNull(context.markers[1].color)
    }

    @Test
    fun anUnreadableMarkerMakesTheSetIncompleteSoNoReplacementCanDropIt() {
        val context = EditorAggregates.contextOf(
            interval("""{"interval":{"id":"i1","asset_id":"a1","start_ms":0,"end_ms":5000},"markers":[{"id":"m1","position_ms":10,"marker_type":"custom"},{"id":"broken"}]}"""),
        )!!
        assertFalse(context.completeMarkerSet)
        assertEquals(listOf("m1"), context.markers.map { it.id })
    }

    @Test
    fun aMissingMarkerListIsNotComplete() {
        val context = EditorAggregates.contextOf(interval("""{"interval":{"id":"i1","asset_id":"a1","start_ms":0,"end_ms":5000}}"""))!!
        assertFalse(context.completeMarkerSet)
    }

    @Test
    fun anEmptyMarkerListIsACompleteSet() {
        val context = EditorAggregates.contextOf(interval("""{"interval":{"id":"i1","asset_id":"a1","start_ms":0,"end_ms":5000},"markers":[]}"""))!!
        assertTrue(context.completeMarkerSet)
        assertTrue(context.markers.isEmpty())
    }

    @Test
    fun entitiesThatCannotBeEditedYieldNoContext() {
        assertNull(EditorAggregates.contextOf(interval("not json")))
        assertNull(EditorAggregates.contextOf(interval("""{"markers":[]}""")))
        assertNull(EditorAggregates.contextOf(interval("""{"interval":{"id":"i1","asset_id":"a1","start_ms":5000,"end_ms":5000},"markers":[]}""")))
        assertNull(EditorAggregates.contextOf(interval(full, revision = "")))
        assertNotNull(EditorAggregates.contextOf(interval(full)))
    }
}
