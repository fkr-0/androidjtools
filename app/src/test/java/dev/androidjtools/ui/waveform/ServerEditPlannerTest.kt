package dev.androidjtools.ui.waveform

import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.Track
import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStateContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEditPlannerTest {
    private val track = Track(id = "t1", title = "Alpha", artist = "Ann", durationMs = 10_000L)

    private val markers = listOf(
        EditorMarkerDraft("m1", 1_500L, "Intro", "custom"),
        EditorMarkerDraft("m2", 3_000L, null, "slice"),
        EditorMarkerDraft("ls", 4_000L, "in", "loop_start"),
        EditorMarkerDraft("le", 6_000L, "out", "loop_end"),
    )
    private val aggregate = IntervalEditorStateContext("iv-1", "r1", 1_000L, 9_000L, markers, completeMarkerSet = true)
    private val cues = listOf(
        Cue("m1", "t1", 1_500L, role = "cue", label = "Intro", intervalId = "iv-1"),
        Cue("m2", "t1", 3_000L, role = "slice", intervalId = "iv-1"),
    )
    private val loops = listOf(
        Loop("iv-1", "t1", 1_000L, 9_000L, intervalId = "iv-1"),
        Loop("ls~le", "t1", 4_000L, 6_000L, intervalId = "iv-1"),
    )

    private fun planner(
        aggregates: List<IntervalEditorStateContext> = listOf(aggregate),
        cues: List<Cue> = this.cues,
        loops: List<Loop> = this.loops,
    ) = ServerEditPlanner(track, cues, loops, aggregates, { 777L }, { "android-mutation-1" })

    private fun EditBuild.ready() = (this as EditBuild.Ready).intent
    private fun EditBuild.reason() = (this as EditBuild.Refused).reason

    // ---- cue candidates from analysis ----

    @Test
    fun allCandidatesBecomeCuesInOneEditWithAnalysisProvenance() {
        val intent = planner().addCues(listOf(2_000L, 5_000L, 8_000L), analysisRunId = "run-7").ready()

        assertEquals("iv-1", intent.entityId)
        assertEquals("r1", intent.baseRevision)
        val added = intent.payload.markers.filter { it.id == null }
        assertEquals(listOf(2_000L, 5_000L, 8_000L), added.map { it.positionMs })
        assertTrue(added.all { it.markerType == "cue" })
        assertEquals(mapOf("source" to "analysis", "surface" to "analysis_cues", "analysis_run_id" to "run-7"), intent.provenance)
        // The existing markers, including the loop markers, are all still there.
        assertEquals(setOf("m1", "m2", "ls", "le"), intent.payload.markers.mapNotNull { it.id }.toSet())
    }

    @Test
    fun candidatesAtPositionsThatAlreadyHoldACueAreNotAddedTwice() {
        val intent = planner().addCues(listOf(1_500L, 6_500L), "run-7").ready()
        assertEquals(listOf(6_500L), intent.payload.markers.filter { it.id == null }.map { it.positionMs })

        assertTrue(planner().addCues(listOf(1_500L, 3_000L), "run-7").reason().contains("already"))
    }

    @Test
    fun withoutASyncedSectionCoveringThemTheCandidatesAreRefusedCleanly() {
        assertTrue(planner(aggregates = emptyList()).addCues(listOf(2_000L), "run-7").reason().contains("No synced section"))
        assertTrue(planner().addCues(listOf(9_500L), "run-7").reason().contains("No synced section"))
    }

    @Test
    fun candidatesSpreadOverSeveralSectionsAreRefusedRatherThanSplitIntoSeveralEdits() {
        val second = IntervalEditorStateContext("iv-2", "r2", 9_000L, 10_000L, emptyList(), completeMarkerSet = true)
        val reason = planner(aggregates = listOf(aggregate, second)).addCues(listOf(2_000L, 9_500L), "run-7").reason()
        assertTrue(reason, reason.contains("one synced section"))
    }

    @Test
    fun anIncompleteMarkerSetStillRefusesCandidates() {
        val incomplete = aggregate.copy(completeMarkerSet = false)
        assertTrue(planner(aggregates = listOf(incomplete)).addCues(listOf(2_000L), "run-7").reason().contains("complete"))
    }

    @Test
    fun noCandidatesIsNothingToDo() {
        assertTrue(planner().addCues(emptyList(), "run-7").reason().contains("no cue points", ignoreCase = true))
    }

    @Test
    fun aMissingRunIdLeavesItOutOfTheProvenance() {
        val intent = planner().addCues(listOf(2_000L), analysisRunId = null).ready()
        assertEquals(mapOf("source" to "analysis", "surface" to "analysis_cues"), intent.provenance)
    }

    // ---- cues ----

    @Test
    fun addingACueAtThePlayheadReplacesTheCoveringIntervalWithOneMoreMarker() {
        val intent = planner().addCue(7_000L).ready()

        assertEquals("android-mutation-1", intent.mutationId)
        assertEquals("iv-1", intent.entityId)
        assertEquals("r1", intent.baseRevision)
        assertEquals(777L, intent.createdAtEpochMillis)
        assertEquals("cue_editor", intent.provenance["surface"])
        assertEquals(1_000L, intent.payload.startMs)
        assertEquals(9_000L, intent.payload.endMs)
        val added = intent.payload.markers.single { it.id == null }
        assertEquals(7_000L, added.positionMs)
        assertEquals("cue", added.markerType)
        // Every existing marker is still there, including the loop markers the cue UI does not manage.
        assertEquals(setOf("m1", "m2", "ls", "le"), intent.payload.markers.mapNotNull { it.id }.toSet())
        assertEquals("loop_start", intent.payload.markers.single { it.id == "ls" }.markerType)
        assertEquals("slice", intent.payload.markers.single { it.id == "m2" }.markerType)
    }

    @Test
    fun aCueCannotBeAddedWhereNoSyncedSectionCoversThePlayhead() {
        assertTrue(planner().addCue(9_500L).reason().contains("No synced section"))
        assertTrue(planner(aggregates = emptyList()).addCue(2_000L).reason().contains("No synced section"))
    }

    @Test
    fun anIncompleteMarkerSetRefusesBecauseAReplacementWouldDeleteMarkers() {
        val incomplete = aggregate.copy(completeMarkerSet = false)
        assertTrue(planner(aggregates = listOf(incomplete)).addCue(2_000L).reason().contains("complete"))
    }

    @Test
    fun movingACueChangesOnlyThatMarkersPosition() {
        val intent = planner().moveCue("m1", 2_000L).ready()
        assertEquals(2_000L, intent.payload.markers.single { it.id == "m1" }.positionMs)
        assertEquals("Intro", intent.payload.markers.single { it.id == "m1" }.label)
        assertEquals(3_000L, intent.payload.markers.single { it.id == "m2" }.positionMs)
        assertEquals(4, intent.payload.markers.size)
    }

    @Test
    fun movingToAPositionOutsideTheIntervalIsRefused() {
        assertTrue(planner().moveCue("m1", 9_500L).reason().contains("inside"))
    }

    @Test
    fun movingOrDeletingAnUnknownCueIsRefused() {
        assertTrue(planner().moveCue("nope", 2_000L) is EditBuild.Refused)
        assertTrue(planner().deleteCue("nope") is EditBuild.Refused)
    }

    @Test
    fun aCueFromDeviceMediaWithoutAnIntervalCannotBeEditedOnTheServer() {
        val deviceCue = Cue("d1", "t1", 2_000L, role = "cue")
        assertTrue(planner(cues = cues + deviceCue).moveCue("d1", 2_500L).reason().contains("server"))
    }

    @Test
    fun deletingACueRemovesOnlyThatMarker() {
        val intent = planner().deleteCue("m1").ready()
        assertEquals(setOf("m2", "ls", "le"), intent.payload.markers.mapNotNull { it.id }.toSet())
        assertNull(intent.payload.markers.firstOrNull { it.id == "m1" })
        assertEquals("cue_editor", intent.provenance["surface"])
    }

    // ---- loops ----

    @Test
    fun savingLoopBoundsReplacesTheIntervalBoundsAndKeepsEveryMarker() {
        val intent = planner().saveLoop("iv-1", 1_200L, 8_000L).ready()

        assertEquals("iv-1", intent.entityId)
        assertEquals("r1", intent.baseRevision)
        assertEquals(1_200L, intent.payload.startMs)
        assertEquals(8_000L, intent.payload.endMs)
        assertEquals("loop_editor", intent.provenance["surface"])
        assertEquals(setOf("m1", "m2", "ls", "le"), intent.payload.markers.mapNotNull { it.id }.toSet())
    }

    @Test
    fun boundsThatWouldStrandAMarkerAreRefusedBeforeTheServerCanRejectThem() {
        // m1 sits at 1500, before the new start of 2000, which the server would refuse.
        assertTrue(planner().saveLoop("iv-1", 2_000L, 8_000L).reason().contains("markers"))
        // le sits at 6000, at or after the new end.
        assertTrue(planner().saveLoop("iv-1", 1_000L, 6_000L).reason().contains("markers"))
    }

    @Test
    fun aLoopMadeFromTwoMarkersCannotBeEditedYet() {
        assertTrue(planner().saveLoop("ls~le", 4_000L, 5_000L).reason().contains("two markers"))
    }

    @Test
    fun unchangedOrInvalidBoundsAreRefused() {
        assertTrue(planner().saveLoop("iv-1", 1_000L, 9_000L).reason().contains("no change", ignoreCase = true))
        assertTrue(planner().saveLoop("iv-1", 1_000L, 11_000L) is EditBuild.Refused)
        assertTrue(planner().saveLoop("iv-1", 5_000L, 5_010L) is EditBuild.Refused)
        assertTrue(planner().saveLoop("unknown", 1_000L, 2_000L) is EditBuild.Refused)
    }
}
