package dev.androidjtools.analysis

import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.Track
import dev.androidjtools.remote.samplelib.AnalysisCandidateDecision
import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.HelloAccepted
import dev.androidjtools.remote.samplelib.HelloRequest
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.MutationConflict
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.PairingRequest
import dev.androidjtools.remote.samplelib.PullMode
import dev.androidjtools.remote.samplelib.PullPage
import dev.androidjtools.remote.samplelib.PushResult
import dev.androidjtools.remote.samplelib.ReceiptOutcome
import dev.androidjtools.remote.samplelib.ResourceChunk
import dev.androidjtools.remote.samplelib.ResourceDescriptor
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibMutation
import dev.androidjtools.remote.samplelib.SampleLibTransport
import dev.androidjtools.remote.samplelib.pullAnalysisCandidates
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.ServerIdentity
import dev.androidjtools.remote.samplelib.ServerLimits
import dev.androidjtools.sync.analysis.SampleLibAnalysisProvider
import dev.androidjtools.sync.journal.AnalysisAcceptanceState
import dev.androidjtools.sync.journal.AnalysisSuggestionAcceptanceCoordinator
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import dev.androidjtools.sync.journal.StaleAnalysisCandidateException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-layer qualification for EPIC-14 without introducing a second analysis architecture. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisAdvisoryConvergenceTest {
    @Test
    fun `sanitized bpm and key remain proposals until authoritative acceptance receipt`() = runTest {
        val canonical = Track(
            id = "asset-1",
            title = "Fixture One",
            artist = "Fixture Artist",
            durationMs = 180_000,
            bpm = 88.0,
            key = "7B",
        )
        val transport = AnalysisTransport(
            changes = listOf(
                candidateChange("bpm-1", "bpm", JsonPrimitive(92.48)),
                candidateChange("key-1", "key", JsonPrimitive("8A")),
            ),
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")
        val journal = DurableMutationJournal(MemoryStorage())
        val acceptance = AnalysisSuggestionAcceptanceCoordinator(journal)
        val provider = SampleLibAnalysisProvider(client, session, this, acceptance, clockMillis = { 1_000L })

        provider.refresh(canonical.id)
        advanceUntilIdle()

        val proposals = provider.suggestions(canonical.id).value.associateBy { it.id }
        assertEquals(2, proposals.size)
        assertEquals(92.48, (proposals.getValue("bpm-1").payload as SuggestionPayload.Bpm).value, 0.0001)
        assertEquals("8A", (proposals.getValue("key-1").payload as SuggestionPayload.Key).value)
        assertEquals("sample-lib", proposals.getValue("bpm-1").source.service)
        assertEquals("essentia-rhythm", proposals.getValue("bpm-1").source.model)
        assertEquals(88.0, canonical.bpm!!, 0.0)
        assertEquals("7B", canonical.key)

        provider.accept("bpm-1")
        val pending = journal.all().single()
        assertEquals("analysis.suggestion.accept", pending.operation)
        assertEquals("asset", pending.entityType)
        assertEquals("asset-1", pending.entityId)
        assertEquals("rev:asset-1:7", pending.baseRevision)
        assertEquals(JournalMutationState.PENDING, pending.state)
        assertEquals(AnalysisCandidateDecision.PROPOSED, provider.candidate("bpm-1")!!.decision)
        val provenance = Json.parseToJsonElement(pending.canonicalProvenance).jsonObject
        assertEquals("rev:asset-1:7", provenance.getValue("input_identity").jsonObject.getValue("revision").jsonPrimitive.content)
        assertEquals("essentia-rhythm", provenance.getValue("candidate_source").jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals(88.0, canonical.bpm!!, 0.0)

        val receipt = MutationReceipt(
            mutationId = pending.mutationId,
            outcome = ReceiptOutcome.APPLIED,
            serverChangeRevision = 8,
            entityRevision = OpaqueRevision("rev:asset-1:8"),
            canonical = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Fixture One"),
                    "bpm" to JsonPrimitive(92.48),
                ),
            ),
        )
        assertEquals(AnalysisAcceptanceState.ACCEPTED, provider.recordReceipt("bpm-1", receipt))
        val resolution = acceptance.recordAuthoritativeReceipt(provider.candidate("bpm-1")!!, receipt)
        assertEquals(AnalysisAcceptanceState.ACCEPTED, resolution.state)
        assertEquals(92.48, resolution.canonical!!.getValue("bpm").jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals("rev:asset-1:8", resolution.entityRevision)
        assertEquals(AnalysisCandidateDecision.ACCEPTED, provider.candidate("bpm-1")!!.decision)
    }

    @Test
    fun `reject stale conflict and related-track paths remain advisory and fail closed`() = runTest {
        val transport = AnalysisTransport(
            changes = listOf(
                candidateChange("related-1", "related_track", JsonObject(mapOf(
                    "track_id" to JsonPrimitive("asset-2"),
                    "dimensions" to JsonArray(listOf(JsonPrimitive("bpm"), JsonPrimitive("key"), JsonPrimitive("energy"))),
                ))),
                candidateChange("stale-1", "bpm", JsonPrimitive(91.0), freshness = "stale"),
                candidateChange("conflict-1", "key", JsonPrimitive("9A")),
            ),
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")
        val journal = DurableMutationJournal(MemoryStorage())
        val acceptance = AnalysisSuggestionAcceptanceCoordinator(journal)
        val provider = SampleLibAnalysisProvider(client, session, this, acceptance, clockMillis = { 2_000L })

        provider.refresh("asset-1")
        advanceUntilIdle()

        val related = provider.suggestions("asset-1").value.single { it.id == "related-1" }
        assertEquals(listOf("bpm", "key", "energy"), (related.payload as SuggestionPayload.Related).dimensions)
        assertEquals("analysis-v1", related.source.pipelineVersion)
        provider.reject("related-1")
        assertEquals(AnalysisCandidateDecision.REJECTED, provider.candidate("related-1")!!.decision)
        assertTrue(journal.all().isEmpty())

        assertThrows(StaleAnalysisCandidateException::class.java) { provider.accept("stale-1") }
        assertTrue(journal.all().isEmpty())

        provider.accept("conflict-1")
        val pending = journal.all().single()
        val conflict = MutationReceipt(
            mutationId = pending.mutationId,
            outcome = ReceiptOutcome.CONFLICT,
            serverChangeRevision = 8,
            conflict = MutationConflict(
                mutationId = pending.mutationId,
                entityId = "asset-1",
                baseRevision = OpaqueRevision("rev:asset-1:7"),
                authoritativeRevision = OpaqueRevision("rev:asset-1:8"),
                localValue = JsonObject(mapOf("key" to JsonPrimitive("9A"))),
                remoteValue = JsonObject(mapOf("key" to JsonPrimitive("8A"))),
                mergeClass = "safe_fieldwise",
                code = "stale_base_revision",
            ),
        )
        assertEquals(AnalysisAcceptanceState.CONFLICT, provider.recordReceipt("conflict-1", conflict))
        assertTrue(provider.candidate("conflict-1")!!.toPresentationSuggestion().stale)
        assertFalse(provider.candidate("conflict-1")!!.decision == AnalysisCandidateDecision.ACCEPTED)
    }

    @Test
    fun `missing suggestion capabilities degrade without manufacturing candidates`() = runTest {
        val transport = AnalysisTransport(
            changes = listOf(candidateChange("bpm-1", "bpm", JsonPrimitive(92.0))),
            capabilities = ServerCapabilities(
                pull = setOf("asset"),
                mutations = emptySet(),
                playlists = "unsupported_until_canonical_playlist_migration",
                analysis = emptySet(),
                media = emptySet(),
            ),
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")
        val provider = SampleLibAnalysisProvider(
            client,
            session,
            this,
            AnalysisSuggestionAcceptanceCoordinator(DurableMutationJournal(MemoryStorage())),
        )

        assertTrue(provider.capabilities.value.all { !it.available })
        assertTrue(provider.suggestions("asset-1").value.isEmpty())
        var failure: Throwable? = null
        try {
            client.pullAnalysisCandidates(session)
        } catch (error: Throwable) {
            failure = error
        }
        assertTrue(failure is SampleLibFailure.CapabilityUnavailable)
    }

    private fun candidateChange(
        id: String,
        kind: String,
        value: JsonElement,
        freshness: String = "fresh",
    ) = EntityChange(
        entityType = "analysis_suggestion",
        entityId = id,
        revision = OpaqueRevision("rev:analysis:$id:1"),
        schema = "androidjtools.analysis-candidate/v1",
        value = JsonObject(
            mapOf(
                "candidate_id" to JsonPrimitive(id),
                "analysis_run_id" to JsonPrimitive("run-1"),
                "asset_id" to JsonPrimitive("asset-1"),
                "interval_id" to JsonNull,
                "kind" to JsonPrimitive(kind),
                "value" to value,
                "confidence" to JsonPrimitive(0.94),
                "source" to JsonObject(mapOf(
                    "service" to JsonPrimitive("sample-lib"),
                    "model" to JsonPrimitive("essentia-rhythm"),
                    "version" to JsonPrimitive("1.2.0"),
                    "pipeline_version" to JsonPrimitive("analysis-v1"),
                )),
                "generated_at" to JsonPrimitive("2026-09-15T12:00:00Z"),
                "input_identity" to JsonObject(mapOf(
                    "entity_type" to JsonPrimitive("asset"),
                    "entity_id" to JsonPrimitive("asset-1"),
                    "revision" to JsonPrimitive("rev:asset-1:7"),
                    "content_sha256" to JsonPrimitive("a".repeat(64)),
                )),
                "freshness" to JsonPrimitive(freshness),
                "decision" to JsonPrimitive("proposed"),
            ),
        ),
    )

    private class AnalysisTransport(
        val changes: List<EntityChange>,
        val capabilities: ServerCapabilities = ServerCapabilities(
            pull = setOf("asset", "analysis_suggestion"),
            mutations = setOf("analysis.suggestion.accept"),
            playlists = "unsupported_until_canonical_playlist_migration",
            analysis = setOf("suggestions.read"),
            media = emptySet(),
        ),
    ) : SampleLibTransport {
        val credential = MobileCredential.issued("secret")

        override suspend fun pair(request: PairingRequest) = error("not used")

        override suspend fun hello(credential: MobileCredential, request: HelloRequest) = HelloAccepted(
            protocolVersion = "1",
            identity = ServerIdentity("sample-lib-a", "generation-1"),
            serverChangeRevision = 7,
            capabilities = capabilities,
            limits = ServerLimits(1000, 100, 1000, 1000),
        )

        override suspend fun pull(
            credential: MobileCredential,
            cursor: String?,
            scopes: Set<String>,
            limit: Int,
        ) = PullPage(PullMode.SNAPSHOT, "cursor-1", false, changes, emptyList(), "generation-1", 7)

        override suspend fun push(
            credential: MobileCredential,
            clientId: String,
            mutations: List<SampleLibMutation>,
        ): PushResult = error("not used")

        override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt = error("not used")

        override suspend fun fetchResource(
            credential: MobileCredential,
            descriptor: ResourceDescriptor,
            offset: Long,
        ): ResourceChunk = error("not used")
    }

    private class MemoryStorage(var snapshot: JournalSnapshot = JournalSnapshot()) : MutationJournalStorage {
        override fun load(): JournalSnapshot = snapshot
        override fun store(snapshot: JournalSnapshot) {
            this.snapshot = snapshot
        }
    }
}
