package dev.androidjtools.sync.journal

import dev.androidjtools.remote.samplelib.AnalysisCandidateDecision
import dev.androidjtools.remote.samplelib.AnalysisCandidateFreshness
import dev.androidjtools.remote.samplelib.AnalysisCandidateKind
import dev.androidjtools.remote.samplelib.AnalysisCandidateSource
import dev.androidjtools.remote.samplelib.AnalysisCandidateValue
import dev.androidjtools.remote.samplelib.AnalysisInputIdentity
import dev.androidjtools.remote.samplelib.MutationConflict
import dev.androidjtools.remote.samplelib.MutationError
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.ReceiptOutcome
import dev.androidjtools.remote.samplelib.SampleLibAnalysisCandidate
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSuggestionAcceptanceTest {
    @Test
    fun `queue preserves opaque input revision and remains noncanonical before receipt`() {
        val journal = DurableMutationJournal(MemoryStorage())
        val coordinator = AnalysisSuggestionAcceptanceCoordinator(journal)
        val candidate = bpmCandidate()

        val mutation = coordinator.queue(candidate, 1000)

        assertEquals("analysis.suggestion.accept", mutation.operation)
        assertEquals("rev:asset-1:7", mutation.baseRevision)
        val provenance = Json.parseToJsonElement(mutation.canonicalProvenance).jsonObject
        assertEquals("fresh", provenance.getValue("freshness").jsonPrimitive.content)
        assertEquals(0.94, provenance.getValue("confidence").jsonPrimitive.double, 0.0001)
        val inputIdentity = provenance.getValue("input_identity").jsonObject
        assertEquals("asset", inputIdentity.getValue("entity_type").jsonPrimitive.content)
        assertEquals("asset-1", inputIdentity.getValue("entity_id").jsonPrimitive.content)
        assertEquals("rev:asset-1:7", inputIdentity.getValue("revision").jsonPrimitive.content)
        assertEquals("a".repeat(64), inputIdentity.getValue("content_sha256").jsonPrimitive.content)
        val source = provenance.getValue("candidate_source").jsonObject
        assertEquals("essentia-rhythm", source.getValue("model").jsonPrimitive.content)
        assertEquals("1.2.0", source.getValue("version").jsonPrimitive.content)
        assertEquals(AnalysisAcceptanceState.QUEUED, coordinator.state(candidate))
        assertNull(mutation.receipt)
    }

    @Test
    fun `all transport supported candidate kinds queue only journal intent with bounded payloads`() {
        val journal = DurableMutationJournal(MemoryStorage())
        val coordinator = AnalysisSuggestionAcceptanceCoordinator(journal)
        val base = bpmCandidate()
        val candidates = listOf(
            base,
            base.copy(candidateId = "candidate-key-1", kind = AnalysisCandidateKind.KEY, value = AnalysisCandidateValue.Key("8A")),
            base.copy(candidateId = "candidate-cue-1", kind = AnalysisCandidateKind.CUE, value = AnalysisCandidateValue.Point(16_000, "mix_in", "Mix in")),
            base.copy(candidateId = "candidate-loop-1", kind = AnalysisCandidateKind.LOOP, value = AnalysisCandidateValue.Range(32_000, 40_000, "loop", "Drums")),
            base.copy(candidateId = "candidate-region-1", kind = AnalysisCandidateKind.REGION, value = AnalysisCandidateValue.Range(48_000, 64_000, "break", "Break")),
            base.copy(candidateId = "candidate-related-1", kind = AnalysisCandidateKind.RELATED_TRACK, value = AnalysisCandidateValue.RelatedTrack("asset-2", listOf("bpm", "key", "energy"))),
        )

        candidates.forEachIndexed { index, candidate ->
            val mutation = coordinator.queue(candidate, 1_000L + index)
            val payload = Json.parseToJsonElement(mutation.canonicalPayload).jsonObject
            assertEquals(candidate.kind.wireName, payload.getValue("kind").jsonPrimitive.content)
            assertEquals(AnalysisAcceptanceState.QUEUED, coordinator.state(candidate))
            assertNull(mutation.receipt)
        }

        assertEquals(candidates.size, journal.all().size)
        val cuePayload = Json.parseToJsonElement(journal.all().first { it.mutationId == AnalysisSuggestionAcceptanceCoordinator.mutationIdFor(candidates[2].candidateId) }.canonicalPayload).jsonObject
        assertEquals(16_000L, cuePayload.getValue("value").jsonObject.getValue("position_ms").jsonPrimitive.content.toLong())
        val rangePayload = Json.parseToJsonElement(journal.all().first { it.mutationId == AnalysisSuggestionAcceptanceCoordinator.mutationIdFor(candidates[4].candidateId) }.canonicalPayload).jsonObject
        assertEquals(64_000L, rangePayload.getValue("value").jsonObject.getValue("end_ms").jsonPrimitive.content.toLong())
        val relatedPayload = Json.parseToJsonElement(journal.all().first { it.mutationId == AnalysisSuggestionAcceptanceCoordinator.mutationIdFor(candidates[5].candidateId) }.canonicalPayload).jsonObject
        assertEquals("asset-2", relatedPayload.getValue("value").jsonObject.getValue("track_id").jsonPrimitive.content)
    }

    @Test
    fun `applied and no op are the only receipt outcomes that expose canonical promotion`() {
        for (outcome in listOf(ReceiptOutcome.APPLIED, ReceiptOutcome.NO_OP)) {
            val journal = DurableMutationJournal(MemoryStorage())
            val coordinator = AnalysisSuggestionAcceptanceCoordinator(journal)
            val candidate = bpmCandidate()
            val queued = coordinator.queue(candidate, 1000)
            val canonical = buildJsonObject { put("bpm", 92.48); put("title", "Fixture One") }
            val resolution = coordinator.recordAuthoritativeReceipt(
                candidate,
                MutationReceipt(
                    mutationId = queued.mutationId,
                    outcome = outcome,
                    serverChangeRevision = 8,
                    entityRevision = OpaqueRevision("rev:asset-1:8"),
                    canonical = canonical,
                ),
            )
            assertEquals(AnalysisAcceptanceState.ACCEPTED, resolution.state)
            assertEquals(canonical, resolution.canonical)
            assertEquals("rev:asset-1:8", resolution.entityRevision)
            assertEquals(AnalysisAcceptanceState.ACCEPTED, coordinator.state(candidate))
        }
    }

    @Test
    fun `rejected and conflict receipts retain evidence without canonical promotion`() {
        val rejectedJournal = DurableMutationJournal(MemoryStorage())
        val rejectedCoordinator = AnalysisSuggestionAcceptanceCoordinator(rejectedJournal)
        val candidate = bpmCandidate()
        val rejectedMutation = rejectedCoordinator.queue(candidate, 1000)
        val rejected = rejectedCoordinator.recordAuthoritativeReceipt(
            candidate,
            MutationReceipt(
                rejectedMutation.mutationId,
                ReceiptOutcome.REJECTED,
                7,
                error = MutationError("fixture_rejected", "no"),
            ),
        )
        assertEquals(AnalysisAcceptanceState.REJECTED, rejected.state)
        assertNull(rejected.canonical)
        assertEquals(JournalMutationState.REJECTED, rejectedJournal.mutation(rejectedMutation.mutationId)?.state)

        val conflictJournal = DurableMutationJournal(MemoryStorage())
        val conflictCoordinator = AnalysisSuggestionAcceptanceCoordinator(conflictJournal)
        val conflictMutation = conflictCoordinator.queue(candidate, 1000)
        val conflict = conflictCoordinator.recordAuthoritativeReceipt(
            candidate,
            MutationReceipt(
                conflictMutation.mutationId,
                ReceiptOutcome.CONFLICT,
                8,
                conflict = MutationConflict(
                    mutationId = conflictMutation.mutationId,
                    entityId = "asset-1",
                    baseRevision = OpaqueRevision("rev:asset-1:7"),
                    authoritativeRevision = OpaqueRevision("rev:asset-1:8"),
                    localValue = buildJsonObject { put("bpm", 92.48) },
                    remoteValue = buildJsonObject { put("bpm", 93.0) },
                    mergeClass = "safe_fieldwise",
                    code = "stale_base_revision",
                ),
            ),
        )
        assertEquals(AnalysisAcceptanceState.CONFLICT, conflict.state)
        assertNull(conflict.canonical)
        assertEquals("rev:asset-1:8", conflictJournal.mutation(conflictMutation.mutationId)?.receipt?.conflict?.authoritativeRevision)
    }

    @Test
    fun `stale or unknown candidates fail before creating a journal mutation`() {
        for (freshness in listOf(AnalysisCandidateFreshness.STALE, AnalysisCandidateFreshness.UNKNOWN)) {
            val journal = DurableMutationJournal(MemoryStorage())
            val coordinator = AnalysisSuggestionAcceptanceCoordinator(journal)
            assertThrows(StaleAnalysisCandidateException::class.java) {
                coordinator.queue(bpmCandidate().copy(freshness = freshness), 1000)
            }
            assertTrue(journal.all().isEmpty())
        }
    }

    private fun bpmCandidate() = SampleLibAnalysisCandidate(
        candidateId = "candidate-bpm-1",
        analysisRunId = "run-1",
        assetId = "asset-1",
        kind = AnalysisCandidateKind.BPM,
        value = AnalysisCandidateValue.Bpm(92.48),
        confidence = 0.94,
        source = AnalysisCandidateSource("sample-lib", "essentia-rhythm", "1.2.0", "analysis-v1"),
        generatedAt = Instant.parse("2026-09-15T12:00:00Z"),
        inputIdentity = AnalysisInputIdentity("asset", "asset-1", OpaqueRevision("rev:asset-1:7"), "a".repeat(64)),
        freshness = AnalysisCandidateFreshness.FRESH,
        decision = AnalysisCandidateDecision.PROPOSED,
    )

    private class MemoryStorage(var snapshot: JournalSnapshot = JournalSnapshot()) : MutationJournalStorage {
        override fun load(): JournalSnapshot = snapshot
        override fun store(snapshot: JournalSnapshot) { this.snapshot = snapshot }
    }
}
