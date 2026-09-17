package dev.androidjtools.remote.samplelib

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.androidjtools.sync.journal.AnalysisAcceptanceState
import dev.androidjtools.sync.journal.AnalysisSuggestionAcceptanceCoordinator
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import dev.androidjtools.sync.analysis.SampleLibAnalysisProvider

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibAnalysisTest {
    @Test
    fun `bpm and key pull entities decode to sanitized proposals with opaque input identity`() = runTest {
        val transport = AnalysisTransport(listOf(candidateChange("bpm-1", "bpm", JsonPrimitive(92.48)), candidateChange("key-1", "key", JsonPrimitive("8A"))))
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")

        val page = client.pullAnalysisCandidates(session)

        assertEquals("1", page.protocolVersion)
        assertEquals(listOf("bpm-1", "key-1"), page.candidates.map { it.candidateId })
        val bpm = page.candidates.first()
        assertEquals(92.48, (bpm.value as AnalysisCandidateValue.Bpm).value, 0.0001)
        assertEquals("rev:asset-1:7", bpm.inputIdentity.revision.value)
        assertEquals("essentia-rhythm", bpm.source.model)
        assertEquals(0.94, bpm.confidence!!, 0.0001)
        val presentation = bpm.toPresentationSuggestion()
        assertNull("opaque revisions must not be coerced into legacy numeric inputRevision", presentation.inputRevision)
        assertFalse(presentation.stale)
        assertEquals("sample-lib", presentation.source.service)
        assertEquals("1.2.0", presentation.source.version)
        assertEquals("8A", (page.candidates[1].value as AnalysisCandidateValue.Key).value)
    }

    @Test
    fun `transport candidate matrix preserves preparation kinds and provider provenance`() = runTest {
        val transport = AnalysisTransport(
            listOf(
                candidateChange("bpm-1", "bpm", JsonPrimitive(92.48)),
                candidateChange("key-1", "key", JsonPrimitive("8A")),
                candidateChange("cue-1", "cue", JsonObject(mapOf(
                    "position_ms" to JsonPrimitive(16_000),
                    "role" to JsonPrimitive("mix_in"),
                    "label" to JsonPrimitive("Mix in"),
                ))),
                candidateChange("loop-1", "loop", JsonObject(mapOf(
                    "start_ms" to JsonPrimitive(32_000),
                    "end_ms" to JsonPrimitive(40_000),
                    "role" to JsonPrimitive("loop"),
                    "label" to JsonPrimitive("Drums"),
                ))),
                candidateChange("region-1", "region", JsonObject(mapOf(
                    "start_ms" to JsonPrimitive(48_000),
                    "end_ms" to JsonPrimitive(64_000),
                    "role" to JsonPrimitive("break"),
                    "label" to JsonPrimitive("Break"),
                ))),
                candidateChange("related-1", "related_track", JsonObject(mapOf(
                    "track_id" to JsonPrimitive("asset-2"),
                    "dimensions" to JsonArray(listOf(JsonPrimitive("bpm"), JsonPrimitive("key"), JsonPrimitive("energy"))),
                ))),
            ),
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")

        val candidates = client.pullAnalysisCandidates(session).candidates.associateBy { it.candidateId }

        assertEquals(setOf("bpm", "key", "cue", "loop", "region", "related_track"), candidates.values.map { it.kind.wireName }.toSet())
        assertTrue(candidates.getValue("cue-1").toPresentationSuggestion().payload is SuggestionPayload.Point)
        assertTrue(candidates.getValue("loop-1").toPresentationSuggestion().payload is SuggestionPayload.Range)
        assertTrue(candidates.getValue("region-1").toPresentationSuggestion().payload is SuggestionPayload.Range)
        val related = candidates.getValue("related-1").toPresentationSuggestion()
        assertEquals(SuggestionKind.RELATED_TRACK, related.kind)
        assertEquals(listOf("bpm", "key", "energy"), (related.payload as SuggestionPayload.Related).dimensions)
        assertEquals("sample-lib", related.source.service)
        assertEquals("essentia-rhythm", related.source.model)
        assertEquals("1.2.0", related.source.version)
        assertEquals("analysis-v1", related.source.pipelineVersion)
    }

    @Test
    fun `stem remains an explicit candidate model gap instead of inventing a wire payload`() = runTest {
        // androidjtools.analysis-candidate/v1 currently excludes stem; stems cross the boundary as
        // registered media refs. Core presentation still supports SuggestionKind.STEM, but the
        // candidate transport must fail closed until the contract defines a candidate shape.
        val transport = AnalysisTransport(
            listOf(
                candidateChange("stem-1", "stem", JsonObject(mapOf(
                    "stem" to JsonPrimitive("drums"),
                    "resource_uri" to JsonPrimitive("stem://resource-1"),
                ))),
                candidateChange("bpm-1", "bpm", JsonPrimitive(92.0)),
            ),
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")

        val page = client.pullAnalysisCandidates(session)

        assertEquals(listOf("bpm-1"), page.candidates.map { it.candidateId })
        assertTrue(SuggestionKind.STEM in SuggestionKind.entries)
    }

    @Test
    fun `provider refresh reject and receipt gated accept preserve advisory authority`() = runTest {
        val transport = AnalysisTransport(
            listOf(
                candidateChange("bpm-1", "bpm", JsonPrimitive(92.48)),
                candidateChange("key-1", "key", JsonPrimitive("8A")),
                candidateChange(
                    "related-1",
                    "related_track",
                    JsonObject(mapOf(
                        "track_id" to JsonPrimitive("asset-2"),
                        "dimensions" to JsonArray(listOf(JsonPrimitive("bpm"), JsonPrimitive("key"), JsonPrimitive("energy"))),
                    )),
                ),
            )
        )
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(transport.credential, "sample-lib-a")
        val storage = MemoryStorage()
        val journal = DurableMutationJournal(storage)
        val provider = SampleLibAnalysisProvider(
            client,
            session,
            this,
            AnalysisSuggestionAcceptanceCoordinator(journal),
            clockMillis = { 1000L },
        )

        provider.refresh("asset-1")
        advanceUntilIdle()
        assertEquals(3, provider.suggestions("asset-1").value.size)
        val relatedCapability = provider.capabilities.value.single { it.id == "analysis.related_tracks" }
        assertTrue(relatedCapability.available)
        assertEquals(setOf(SuggestionKind.RELATED_TRACK), relatedCapability.kinds)
        val structureCapability = provider.capabilities.value.single { it.id == "analysis.structure" }
        assertEquals(setOf(SuggestionKind.CUE, SuggestionKind.LOOP, SuggestionKind.REGION), structureCapability.kinds)
        val related = provider.suggestions("asset-1").value.single { it.id == "related-1" }
        assertEquals(listOf("bpm", "key", "energy"), (related.payload as SuggestionPayload.Related).dimensions)
        assertEquals("sample-lib", related.source.service)
        assertEquals("essentia-rhythm", related.source.model)
        assertEquals("1.2.0", related.source.version)
        assertEquals("analysis-v1", related.source.pipelineVersion)

        provider.reject("key-1")
        assertEquals(AnalysisCandidateDecision.REJECTED, provider.candidate("key-1")?.decision)
        assertTrue(journal.all().isEmpty())

        provider.accept("bpm-1")
        val pending = journal.all().single()
        assertEquals(JournalMutationState.PENDING, pending.state)
        assertEquals(AnalysisCandidateDecision.PROPOSED, provider.candidate("bpm-1")?.decision)

        val accepted = provider.recordReceipt(
            "bpm-1",
            MutationReceipt(
                pending.mutationId,
                ReceiptOutcome.APPLIED,
                8,
                entityRevision = OpaqueRevision("rev:asset-1:8"),
                canonical = JsonObject(mapOf("title" to JsonPrimitive("Fixture One"), "bpm" to JsonPrimitive(92.48))),
            ),
        )
        assertEquals(AnalysisAcceptanceState.ACCEPTED, accepted)
        assertEquals(AnalysisCandidateDecision.ACCEPTED, provider.candidate("bpm-1")?.decision)
    }

    @Test
    fun `unknown future candidate kind degrades independently`() = runTest {
        val transport = AnalysisTransport(listOf(candidateChange("future-1", "future_kind", JsonPrimitive("x")), candidateChange("bpm-1", "bpm", JsonPrimitive(91.0))))
        val page = SampleLibClient(transport, "android", "install-1")
            .pullAnalysisCandidates(SampleLibClient(transport, "android", "install-1").connect(transport.credential, "sample-lib-a"))
        assertEquals(listOf("bpm-1"), page.candidates.map { it.candidateId })
    }

    @Test
    fun `stale and unknown freshness are fail closed in presentation`() {
        for (freshness in listOf("stale", "unknown")) {
            val change = candidateChange("bpm-$freshness", "bpm", JsonPrimitive(90.0), freshness)
            val candidate = decodeAnalysisCandidate(change)!!
            assertTrue(candidate.toPresentationSuggestion().stale)
        }
    }

    private fun candidateChange(id: String, kind: String, value: JsonElement, freshness: String = "fresh") = EntityChange(
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
            )
        ),
    )

    private class AnalysisTransport(val changes: List<EntityChange>) : SampleLibTransport {
        val credential = MobileCredential.issued("secret")
        private val capabilities = ServerCapabilities(
            pull = setOf("asset", "analysis_suggestion"),
            mutations = setOf("analysis.suggestion.accept"),
            playlists = "unsupported_until_canonical_playlist_migration",
            analysis = setOf("suggestions.read"),
            media = emptySet(),
        )
        override suspend fun pair(request: PairingRequest) = error("not used")
        override suspend fun hello(credential: MobileCredential, request: HelloRequest) = HelloAccepted(
            "1", ServerIdentity("sample-lib-a", "generation-1"), 7, capabilities,
            ServerLimits(1000, 100, 1000, 1000),
        )
        override suspend fun pull(credential: MobileCredential, cursor: String?, scopes: Set<String>, limit: Int): PullPage {
            assertEquals(setOf("analysis_suggestion"), scopes)
            return PullPage(PullMode.SNAPSHOT, "cursor-1", false, changes, emptyList(), "generation-1", 7)
        }
        override suspend fun push(credential: MobileCredential, clientId: String, mutations: List<SampleLibMutation>) = error("not used")
        override suspend fun receipt(credential: MobileCredential, mutationId: String) = error("not used")
        override suspend fun fetchResource(credential: MobileCredential, descriptor: ResourceDescriptor, offset: Long) = error("not used")
    }

    private class MemoryStorage(var snapshot: JournalSnapshot = JournalSnapshot()) : MutationJournalStorage {
        override fun load(): JournalSnapshot = snapshot
        override fun store(snapshot: JournalSnapshot) { this.snapshot = snapshot }
    }
}
