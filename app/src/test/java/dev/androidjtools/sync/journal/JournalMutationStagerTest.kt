package dev.androidjtools.sync.journal

import dev.androidjtools.prep.cues.EditorMarkerDraft
import dev.androidjtools.prep.cues.IntervalEditorStatePayload
import dev.androidjtools.prep.cues.IntervalEditorStateReplaceIntent
import dev.androidjtools.prep.cues.PreparationStageResult
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalMutationStagerTest {
    private class MemoryStorage(var failStores: Int = 0) : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            if (failStores > 0) {
                failStores--
                throw IOException("disk full")
            }
            durable = snapshot
        }
    }

    private fun intent(
        mutationId: String = "mutation-0001",
        markers: List<EditorMarkerDraft> = listOf(EditorMarkerDraft("m1", 1500L, "Intro", "cue", "#112233")),
        comment: String? = "Android DJ cue preparation edit",
    ) = IntervalEditorStateReplaceIntent(
        mutationId = mutationId,
        entityId = "interval-1",
        baseRevision = "a".repeat(64),
        payload = IntervalEditorStatePayload(startMs = 1000L, endMs = 9000L, markers = markers, comment = comment),
        createdAtEpochMillis = 1234L,
        provenance = mapOf("source" to "android_manual", "surface" to "cue_editor"),
    )

    private fun payloadOf(journal: DurableMutationJournal, id: String = "mutation-0001"): JsonObject =
        Json.parseToJsonElement(journal.mutation(id)!!.canonicalPayload).jsonObject

    @Test
    fun theIntentBecomesADurableReplaceMutationOnTheSameInterval() {
        val journal = DurableMutationJournal(MemoryStorage())
        val result = JournalMutationStager(journal).stage(intent())

        assertEquals(PreparationStageResult.Queued("mutation-0001"), result)
        val queued = journal.mutation("mutation-0001")!!
        assertEquals("interval", queued.entityType)
        assertEquals("interval-1", queued.entityId)
        assertEquals("a".repeat(64), queued.baseRevision)
        assertEquals("interval.editor_state.replace", queued.operation)
        assertEquals(JournalMutationState.PENDING, queued.state)
        assertEquals(1234L, queued.createdAtEpochMillis)
        assertEquals("android_manual", Json.parseToJsonElement(queued.canonicalProvenance).jsonObject.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun thePayloadUsesExactlyTheFieldsTheServerAccepts() {
        val journal = DurableMutationJournal(MemoryStorage())
        JournalMutationStager(journal).stage(intent())

        val payload = payloadOf(journal)
        assertEquals(setOf("start_ms", "end_ms", "markers", "comment"), payload.keys)
        assertEquals(1000, payload.getValue("start_ms").jsonPrimitive.int)
        assertEquals(9000, payload.getValue("end_ms").jsonPrimitive.int)
        val marker = payload.getValue("markers").jsonArray.single().jsonObject
        assertEquals(setOf("id", "position_ms", "label", "marker_type", "color"), marker.keys)
        assertEquals("m1", marker.getValue("id").jsonPrimitive.content)
        assertEquals(1500, marker.getValue("position_ms").jsonPrimitive.int)
    }

    @Test
    fun editorMarkerTypesAreSentInTheServersVocabulary() {
        val journal = DurableMutationJournal(MemoryStorage())
        JournalMutationStager(journal).stage(
            intent(
                markers = listOf(
                    EditorMarkerDraft("m1", 1, null, "cue"),
                    EditorMarkerDraft("m2", 2, null, "mix_in"),
                    EditorMarkerDraft("m3", 3, null, "drop"),
                    EditorMarkerDraft("m4", 4, null, "slice"),
                    EditorMarkerDraft("m5", 5, null, "loop_start"),
                    EditorMarkerDraft("m6", 6, null, "loop_end"),
                    EditorMarkerDraft("m7", 7, null, "custom"),
                    EditorMarkerDraft("m8", 8, null, "transcript"),
                ),
            ),
        )
        val types = payloadOf(journal).getValue("markers").jsonArray.map { it.jsonObject.getValue("marker_type").jsonPrimitive.content }
        assertEquals(listOf("custom", "custom", "custom", "slice", "loop_start", "loop_end", "custom", "custom"), types)
    }

    @Test
    fun aNewCueHasNoIdAndAbsentLabelAndColorAreExplicitNulls() {
        val journal = DurableMutationJournal(MemoryStorage())
        JournalMutationStager(journal).stage(intent(markers = listOf(EditorMarkerDraft(null, 2500L, null, "cue", null))))

        val marker = payloadOf(journal).getValue("markers").jsonArray.single().jsonObject
        assertFalse("id" in marker)
        assertEquals(JsonNull, marker.getValue("label"))
        assertEquals(JsonNull, marker.getValue("color"))
    }

    @Test
    fun anAlphaColourIsReducedToTheSixDigitFormTheServerAndProjectionUnderstand() {
        val journal = DurableMutationJournal(MemoryStorage())
        JournalMutationStager(journal).stage(
            intent(markers = listOf(EditorMarkerDraft("m1", 1, null, "cue", "#FF112233"), EditorMarkerDraft("m2", 2, null, "cue", "#AABBCC"))),
        )
        val colours = payloadOf(journal).getValue("markers").jsonArray.map { it.jsonObject.getValue("color").jsonPrimitive.content }
        assertEquals(listOf("#112233", "#AABBCC"), colours)
    }

    @Test
    fun stagingTheSameIntentTwiceQueuesOnceButADifferentBodyUnderTheSameIdIsRefused() {
        val journal = DurableMutationJournal(MemoryStorage())
        val stager = JournalMutationStager(journal)
        assertEquals(PreparationStageResult.Queued("mutation-0001"), stager.stage(intent()))
        assertEquals(PreparationStageResult.Queued("mutation-0001"), stager.stage(intent()))
        assertEquals(1, journal.all().size)

        val different = stager.stage(intent(markers = listOf(EditorMarkerDraft("m1", 9999L, "Moved", "cue"))))
        assertTrue(different is PreparationStageResult.Rejected)
        assertEquals("idempotency_key_reused", (different as PreparationStageResult.Rejected).code)
        assertEquals(1, journal.all().size)
    }

    @Test
    fun aFailedDiskWriteIsReportedAndNothingIsQueued() {
        val storage = MemoryStorage(failStores = 1)
        val journal = DurableMutationJournal(storage)

        val result = JournalMutationStager(journal).stage(intent())

        assertEquals("journal_unavailable", (result as PreparationStageResult.Rejected).code)
        assertTrue(journal.all().isEmpty())
    }
}
