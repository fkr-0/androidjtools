package dev.androidjtools.sync.journal

import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.MutationConflict
import dev.androidjtools.remote.samplelib.MutationError
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.PushResult
import dev.androidjtools.remote.samplelib.ReceiptOutcome
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.sync.samplelib.AnalysisSuggestionRecord
import dev.androidjtools.sync.samplelib.ScriptedTransport
import dev.androidjtools.sync.samplelib.SuggestionData
import dev.androidjtools.sync.samplelib.SuggestionWireKind
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSuggestionAcceptanceTest {
    private class MemoryStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private fun record(
        id: String = "run-1:tempo:a1",
        kind: SuggestionWireKind = SuggestionWireKind.TEMPO,
        revision: String = "rev-s1",
        confidence: Double? = 0.8,
        data: SuggestionData = SuggestionData.Tempo(120.0, listOf(60.0, 240.0)),
    ) = AnalysisSuggestionRecord(
        id, revision, kind, "a1", null, "run-1", "mir.essentia_lowlevel", "2.1", Instant.EPOCH, confidence, data,
    )

    private val journal = DurableMutationJournal(MemoryStorage())
    private val coordinator = AnalysisSuggestionAcceptanceCoordinator(journal)

    // ---- the envelope ----

    @Test
    fun anAcceptIsADurableMutationOnTheSuggestionAtItsPulledRevision() {
        val queued = coordinator.queueAccept(record(), "iv-full", bpm = null, createdAtEpochMillis = 1_000L)

        assertEquals("analysis_suggestion.accept", queued.operation)
        assertEquals("analysis_suggestion", queued.entityType)
        assertEquals("run-1:tempo:a1", queued.entityId)
        assertEquals("rev-s1", queued.baseRevision)
        assertEquals(JournalMutationState.PENDING, queued.state)
        val payload = Json.parseToJsonElement(queued.canonicalPayload).jsonObject
        assertEquals(setOf("target_interval_id"), payload.keys)
        assertEquals("iv-full", payload.getValue("target_interval_id").jsonPrimitive.content)
        assertEquals(queued, journal.mutation(queued.mutationId))
    }

    @Test
    fun aChosenAlternativeIsSentAsBpmAndOnlyThen() {
        val chosen = coordinator.queueAccept(record(), "iv-full", bpm = 60.0, createdAtEpochMillis = 1_000L)
        val payload = Json.parseToJsonElement(chosen.canonicalPayload).jsonObject
        assertEquals(60.0, payload.getValue("bpm").jsonPrimitive.content.toDouble(), 0.0)

        val plain = coordinator.queueAccept(record(), "iv-full", bpm = 120.0, createdAtEpochMillis = 1_000L)
        assertFalse("the suggestion's own bpm needs no choice", "bpm" in Json.parseToJsonElement(plain.canonicalPayload).jsonObject)
        assertTrue(chosen.mutationId != plain.mutationId)
    }

    @Test
    fun theProvenanceNamesTheRunAndToolButNeverRequiresOrInventsAConfidence() {
        val withConfidence = Json.parseToJsonElement(coordinator.queueAccept(record(), "iv", null, 1L).canonicalProvenance).jsonObject
        assertEquals("run-1", withConfidence.getValue("analysis_run_id").jsonPrimitive.content)
        assertEquals("mir.essentia_lowlevel", withConfidence.getValue("tool").jsonPrimitive.content)
        assertEquals("analysis_suggestion", withConfidence.getValue("source").jsonPrimitive.content)

        val without = coordinator.queueAccept(record(id = "run-1:key:a1", kind = SuggestionWireKind.KEY, confidence = null, data = SuggestionData.Key("A minor", "A", "minor")), "iv", null, 1L)
        assertFalse("confidence" in Json.parseToJsonElement(without.canonicalProvenance).jsonObject)
    }

    @Test
    fun theSameAcceptTwiceIsOneMutationAndAnotherRevisionOrChoiceIsAnother() {
        val first = coordinator.queueAccept(record(), "iv", null, 1_000L)
        val again = coordinator.queueAccept(record(), "iv", null, 9_999L)
        assertEquals(first.mutationId, again.mutationId)
        assertEquals(1, journal.all().size)

        val newRevision = coordinator.queueAccept(record(revision = "rev-s2"), "iv", null, 1_000L)
        assertTrue(newRevision.mutationId != first.mutationId)
        assertEquals(2, journal.all().size)
    }

    @Test
    fun cueCandidatesAreNeverQueuedAsAnAccept() {
        val cues = record(id = "run-1:cue_candidates:a1", kind = SuggestionWireKind.CUE_CANDIDATES, data = SuggestionData.CueCandidates(listOf(1000L)))
        assertThrows(IllegalArgumentException::class.java) { coordinator.queueAccept(cues, "iv", null, 1L) }
        assertTrue(journal.all().isEmpty())
    }

    @Test
    fun theJournalNowKnowsTheServersOperationNameAndEntityType() {
        journal.enqueue("mutation-x1", "analysis_suggestion", "s", "r", "analysis_suggestion.accept", "{}", "{}", 1L)
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue("mutation-x2", "interval", "iv", "r", "analysis_suggestion.accept", "{}", "{}", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue("mutation-x3", "analysis_suggestion", "s", null, "analysis_suggestion.accept", "{}", "{}", 1L)
        }
        assertThrows("the old, never-served name is gone", IllegalArgumentException::class.java) {
            journal.enqueue("mutation-x4", "asset", "a", "r", "analysis.suggestion.accept", "{}", "{}", 1L)
        }
    }

    // ---- delivery next to the editor family ----

    private val editorOp = "interval.editor_state.replace"
    private val acceptOp = "analysis_suggestion.accept"

    private fun session(transport: ScriptedTransport) = runBlocking {
        SampleLibClient(transport, "android-dj-tools", "install-1").connect(MobileCredential.issued("t"), "server-1")
    }

    private fun queueEdit(id: String = "mutation-edit-1") = journal.enqueue(
        id, "interval", "iv-1", "rev-iv", editorOp, """{"start_ms":0,"end_ms":5000,"markers":[]}""", """{"source":"android_manual"}""", 1_000L,
    )

    private fun applied(id: String, entityRevision: String = "rev-s1") =
        MutationReceipt(id, ReceiptOutcome.APPLIED, 9L, OpaqueRevision(entityRevision), canonical = buildJsonObject { put("revision", "rev-iv-2") })

    @Test
    fun bothFamiliesAreDeliveredInOrderAndEachRecordsItsOwnReceipt() = runBlocking {
        val transport = ScriptedTransport().apply {
            capabilities = capabilities.copy(mutations = setOf(editorOp, acceptOp))
            pushScript = { sent -> PushResult(sent.map { applied(it.mutationId) }, 9L) }
        }
        queueEdit()
        val accept = coordinator.queueAccept(record(), "iv-full", null, 2_000L)

        val replayed = SampleLibMutationJournalReplayer(journal, SampleLibClient(transport, "c", "i")).replay(session(transport))

        assertEquals(listOf("mutation-edit-1", accept.mutationId), replayed.map { it.mutationId })
        assertEquals(JournalMutationState.RECEIPT_RECORDED, journal.mutation(accept.mutationId)?.state)
        assertEquals("the suggestion's revision", "rev-s1", journal.mutation(accept.mutationId)?.receipt?.entityRevision)
        assertEquals(JournalMutationState.RECEIPT_RECORDED, journal.mutation("mutation-edit-1")?.state)
    }

    @Test
    fun aServerThatCannotAcceptDoesNotHoldBackTheEditorEditsBehindIt() = runBlocking {
        val transport = ScriptedTransport().apply {
            capabilities = capabilities.copy(mutations = setOf(editorOp))
            pushScript = { sent -> PushResult(sent.map { applied(it.mutationId) }, 9L) }
        }
        val accept = coordinator.queueAccept(record(), "iv-full", null, 1_000L)
        queueEdit()

        SampleLibMutationJournalReplayer(journal, SampleLibClient(transport, "c", "i")).replay(session(transport))

        assertEquals("kept for a server that can take it", JournalMutationState.PENDING, journal.mutation(accept.mutationId)?.state)
        assertEquals(JournalMutationState.RECEIPT_RECORDED, journal.mutation("mutation-edit-1")?.state)
    }

    @Test
    fun aConflictForAReplacedSuggestionIsRecordedWithEvidenceAndNoRemoteValueIsNeeded() = runBlocking {
        val transport = ScriptedTransport().apply {
            capabilities = capabilities.copy(mutations = setOf(editorOp, acceptOp))
            pushScript = { sent ->
                PushResult(
                    sent.map {
                        MutationReceipt(
                            it.mutationId, ReceiptOutcome.CONFLICT, 9L,
                            conflict = MutationConflict(it.mutationId, "run-1:tempo:a1", OpaqueRevision("rev-s1"), null, JsonObject(emptyMap()), null, "analysis_suggestion", "suggestion_changed"),
                        )
                    },
                    9L,
                )
            }
        }
        val accept = coordinator.queueAccept(record(), "iv-full", null, 1_000L)

        SampleLibMutationJournalReplayer(journal, SampleLibClient(transport, "c", "i")).replay(session(transport))

        val recorded = journal.mutation(accept.mutationId)!!
        assertEquals(JournalMutationState.CONFLICT, recorded.state)
        assertEquals("suggestion_changed", recorded.receipt?.conflict?.code)
        assertNull(recorded.receipt?.conflict?.remoteValueJson)
        journal.resolve(accept.mutationId)
        assertEquals(JournalMutationState.RESOLVED, journal.mutation(accept.mutationId)?.state)
    }

    @Test
    fun aRefusedAcceptKeepsTheServersCodeAndCanBeClosed() = runBlocking {
        val transport = ScriptedTransport().apply {
            capabilities = capabilities.copy(mutations = setOf(acceptOp))
            pushScript = { sent ->
                PushResult(sent.map { MutationReceipt(it.mutationId, ReceiptOutcome.REJECTED, 9L, error = MutationError("target_interval_mismatch", "wrong asset")) }, 9L)
            }
        }
        val accept = coordinator.queueAccept(record(), "iv-full", null, 1_000L)

        SampleLibMutationJournalReplayer(journal, SampleLibClient(transport, "c", "i")).replay(session(transport))

        assertEquals(JournalMutationState.REJECTED, journal.mutation(accept.mutationId)?.state)
        assertTrue(journal.mutation(accept.mutationId)?.receipt?.detail.orEmpty().contains("target_interval_mismatch"))
    }
}
