package dev.androidjtools.sync.journal

import dev.androidjtools.remote.samplelib.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MutationJournalTest {
    @Test
    fun `enqueue is successful only after durable persistence and canonicalizes body`() {
        val storage = RecordingStorage(failStores = 1)
        val journal = DurableMutationJournal(storage)

        assertThrows(IOException::class.java) {
            journal.enqueue(
                mutationId = "mutation-001",
                entityType = "asset",
                entityId = "asset-1",
                baseRevision = "sha256:old",
                operation = "asset.metadata.patch",
                payloadJson = "{\"z\":2,\"a\":1}",
                provenanceJson = "{\"source\":\"user\"}",
                createdAtEpochMillis = 100,
            )
        }
        assertTrue(journal.all().isEmpty())

        val mutation = journal.enqueue(
            mutationId = "mutation-001",
            entityType = "asset",
            entityId = "asset-1",
            baseRevision = "sha256:old",
            operation = "asset.metadata.patch",
            payloadJson = "{\"z\":2,\"a\":1}",
            provenanceJson = "{\"source\":\"user\"}",
            createdAtEpochMillis = 100,
        )
        assertEquals("{\"a\":1,\"z\":2}", mutation.canonicalPayload)
        val wire = Json.parseToJsonElement(mutation.canonicalRequestBody).jsonObject
        assertEquals(
            setOf("mutation_id", "entity_type", "entity_id", "base_revision", "operation", "payload", "created_at", "provenance"),
            wire.keys,
        )
        assertEquals("1970-01-01T00:00:00.100Z", wire.getValue("created_at").jsonPrimitive.content)
        assertTrue(wire.getValue("payload") is JsonObject)
        assertTrue(wire.getValue("provenance") is JsonObject)
        assertTrue("created_at_epoch_millis" !in wire)
        assertEquals(mutation, DurableMutationJournal(storage).mutation("mutation-001"))
    }

    @Test
    fun `same mutation id is idempotent only for the same canonical body`() {
        val journal = DurableMutationJournal(RecordingStorage())
        val first = sample(journal, "mutation-002", "{\"b\":2,\"a\":1}")
        val replay = sample(journal, "mutation-002", "{\"a\":1,\"b\":2}")
        assertEquals(first, replay)
        assertEquals(1, journal.all().size)

        assertThrows(IdempotencyKeyReuseException::class.java) {
            sample(journal, "mutation-002", "{\"a\":9}")
        }
        assertThrows(IdempotencyKeyReuseException::class.java) {
            journal.enqueue(
                mutationId = "mutation-002",
                entityType = "asset",
                entityId = "asset-1",
                baseRevision = "sha256:old",
                operation = "asset.metadata.patch",
                payloadJson = "{\"a\":1,\"b\":2}",
                provenanceJson = "{\"origin\":\"other\"}",
                createdAtEpochMillis = 1234,
            )
        }
        assertThrows(IdempotencyKeyReuseException::class.java) {
            journal.enqueue(
                mutationId = "mutation-002",
                entityType = "asset",
                entityId = "asset-1",
                baseRevision = "sha256:old",
                operation = "asset.metadata.patch",
                payloadJson = "{\"a\":1,\"b\":2}",
                provenanceJson = "{\"origin\":\"android\"}",
                createdAtEpochMillis = 1235,
            )
        }
    }

    @Test
    fun `wire payload and provenance must be json objects`() {
        val journal = DurableMutationJournal(RecordingStorage())
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue(
                "mutation-obj-1",
                "asset",
                "asset-1",
                "sha256:old",
                "asset.metadata.patch",
                "[1,2,3]",
                createdAtEpochMillis = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue(
                "mutation-obj-2",
                "asset",
                "asset-1",
                "sha256:old",
                "asset.metadata.patch",
                "{}",
                provenanceJson = "\"caller\"",
                createdAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun `process death after insert and while applying replays exact immutable intent`() {
        val storage = RecordingStorage()
        val firstProcess = DurableMutationJournal(storage)
        val inserted = sample(firstProcess, "mutation-003")

        val afterInsert = DurableMutationJournal(storage).replayBatch().single()
        assertEquals(inserted.canonicalRequestBody, afterInsert.canonicalRequestBody)

        firstProcess.markApplying("mutation-003")
        val afterApplyingCrash = DurableMutationJournal(storage).replayBatch().single()
        assertEquals(JournalMutationState.PENDING, afterApplyingCrash.state)
        assertEquals(inserted.mutationId, afterApplyingCrash.mutationId)
        assertEquals(inserted.canonicalRequestBody, afterApplyingCrash.canonicalRequestBody)
    }

    @Test
    fun `server commit before receipt persistence is recovered by exact replay`() {
        val storage = RecordingStorage()
        val journal = DurableMutationJournal(storage)
        val original = sample(journal, "mutation-004")
        journal.markApplying(original.mutationId)

        storage.failStores = 1 // Simulate death or IO failure while persisting the authoritative receipt.
        assertThrows(IOException::class.java) {
            journal.recordReceipt(
                JournalReceipt(original.mutationId, JournalReceiptOutcome.APPLIED, entityRevision = "opaque:new"),
            )
        }

        val recovered = DurableMutationJournal(storage).replayBatch().single()
        assertEquals(original.mutationId, recovered.mutationId)
        assertEquals(original.canonicalRequestBody, recovered.canonicalRequestBody)
        assertNull(recovered.receipt)
    }

    @Test
    fun `durable receipt gates compaction and rejected or conflict evidence is retained`() {
        val storage = RecordingStorage()
        val journal = DurableMutationJournal(storage)
        val applied = sample(journal, "mutation-005")
        val rejected = sample(journal, "mutation-006")
        val conflict = sample(journal, "mutation-007")

        journal.recordReceipt(JournalReceipt(applied.mutationId, JournalReceiptOutcome.APPLIED, "rev:new"))
        journal.recordReceipt(JournalReceipt(rejected.mutationId, JournalReceiptOutcome.REJECTED, detail = "invalid"))
        journal.recordReceipt(
            JournalReceipt(
                conflict.mutationId,
                JournalReceiptOutcome.CONFLICT,
                conflict = JournalConflict(
                    code = "revision_conflict",
                    mergeClass = "editor_aggregate",
                    authoritativeRevision = "rev:remote",
                    localValueJson = "{\"cue\":1}",
                    remoteValueJson = "{\"cue\":2}",
                ),
            ),
        )

        assertEquals(1, journal.compactAcknowledged())
        val recovered = DurableMutationJournal(storage)
        assertNull(recovered.mutation(applied.mutationId))
        assertEquals(JournalMutationState.REJECTED, recovered.mutation(rejected.mutationId)?.state)
        assertEquals(JournalMutationState.CONFLICT, recovered.mutation(conflict.mutationId)?.state)
        assertNotNull(recovered.mutation(conflict.mutationId)?.receipt?.conflict)
    }

    @Test
    fun `receipt persistence is atomic from client view`() {
        val storage = RecordingStorage()
        val journal = DurableMutationJournal(storage)
        val mutation = sample(journal, "mutation-008")
        storage.failStores = 1

        assertThrows(IOException::class.java) {
            journal.recordReceipt(JournalReceipt(mutation.mutationId, JournalReceiptOutcome.NO_OP, "rev:same"))
        }
        assertNull(journal.mutation(mutation.mutationId)?.receipt)
        assertNull(DurableMutationJournal(storage).mutation(mutation.mutationId)?.receipt)

        journal.recordReceipt(JournalReceipt(mutation.mutationId, JournalReceiptOutcome.NO_OP, "rev:same"))
        assertEquals(JournalReceiptOutcome.NO_OP, DurableMutationJournal(storage).mutation(mutation.mutationId)?.receipt?.outcome)
    }

    @Test
    fun `v1 unresolved mutation migrates without losing identity or opaque revision`() {
        val directory = Files.createTempDirectory("androidjtools-journal-test").toFile()
        try {
            val file = File(directory, "journal.db")
            val seedStore = RecordingStorage()
            val mutation = sample(DurableMutationJournal(seedStore), "mutation-009")
            file.writeText(FileMutationJournalStorage.encodeV1ForMigrationTest(mutation))

            val storage = FileMutationJournalStorage(file)
            val loaded = DurableMutationJournal(storage)
            val migrated = loaded.mutation(mutation.mutationId)
            assertEquals("sha256:old", migrated?.baseRevision)
            assertEquals(mutation.canonicalRequestBody, migrated?.canonicalRequestBody)

            loaded.markApplying(mutation.mutationId) // Any successful write upgrades storage to v2.
            assertTrue(file.readLines().first().endsWith("\t2"))
            val afterUpgrade = DurableMutationJournal(storage).replayBatch().single()
            assertEquals(mutation.canonicalRequestBody, afterUpgrade.canonicalRequestBody)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `operation family validates opaque revision policy`() {
        val journal = DurableMutationJournal(RecordingStorage())
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue("mutation-010", "asset", "asset-1", null, "asset.metadata.patch", "{}", createdAtEpochMillis = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.enqueue("mutation-011", "asset", "asset-1", "42", "tag.ensure_attach", "{}", createdAtEpochMillis = 1)
        }
        val tag = journal.enqueue(
            "mutation-012",
            "tag_attachment",
            "tag-1:asset-1",
            null,
            "tag.ensure_attach",
            "{}",
            createdAtEpochMillis = 1,
        )
        assertNull(tag.baseRevision)
        assertNotEquals("", tag.canonicalRequestBody)
    }

    @Test
    fun `offline cue loop metadata and namespaced tag edits survive process recreation and reconnect`() = runTest {
        val directory = Files.createTempDirectory("androidjtools-resilience-integration").toFile()
        try {
            val storage = FileMutationJournalStorage(File(directory, "journal.db"))
            val offlineProcess = DurableMutationJournal(storage)
            val interval = offlineProcess.enqueue(
                mutationId = "resilience-interval-001",
                entityType = "interval",
                entityId = "interval-1",
                baseRevision = "rev:interval-1:4",
                operation = "interval.editor_state.replace",
                payloadJson = """{"cues":[{"position_ms":1200}],"loops":[{"start_ms":4000,"end_ms":8000}]}""",
                provenanceJson = """{"origin":"offline-editor"}""",
                createdAtEpochMillis = 1_000,
            )
            val metadata = offlineProcess.enqueue(
                mutationId = "resilience-metadata-001",
                entityType = "asset",
                entityId = "asset-1",
                baseRevision = "rev:asset-1:7",
                operation = "asset.metadata.patch",
                payloadJson = """{"rating":5,"title":"Offline title"}""",
                provenanceJson = """{"origin":"offline-editor"}""",
                createdAtEpochMillis = 1_001,
            )
            val tag = offlineProcess.enqueue(
                mutationId = "resilience-tag-attach-001",
                entityType = "tag_attachment",
                entityId = "mood:night:asset-1",
                baseRevision = null,
                operation = "tag.ensure_attach",
                payloadJson = """{"asset_id":"asset-1","namespace":"mood","value":"night"}""",
                provenanceJson = """{"origin":"offline-editor"}""",
                createdAtEpochMillis = 1_002,
            )

            // No in-memory journal survives this point: this models process recreation before reconnect.
            val recoveredJournal = DurableMutationJournal(storage)
            assertEquals(listOf(interval.mutationId, metadata.mutationId, tag.mutationId), recoveredJournal.replayBatch().map { it.mutationId })

            val transport = ResilienceTransport().apply {
                ambiguousAfterCommitIds += interval.mutationId
                offlineOnceIds += metadata.mutationId
            }
            val client = SampleLibClient(transport, "android", "install-resilience")
            val reconciledCanonicals = linkedMapOf<String, JsonObject>()
            val session = client.connect(transport.credential, "sample-lib-resilience")
            val firstReplay = SampleLibMutationJournalReplayer(
                recoveredJournal,
                client,
                CanonicalMutationReconciler { receipt ->
                    receipt.canonical?.let { reconciledCanonicals[receipt.mutationId] = it }
                },
            ).replay(session)

            assertEquals(listOf(interval.mutationId, metadata.mutationId), firstReplay.map { it.mutationId })
            assertEquals(JournalMutationState.RECEIPT_RECORDED, recoveredJournal.mutation(interval.mutationId)?.state)
            assertEquals(JournalMutationState.PENDING, recoveredJournal.mutation(metadata.mutationId)?.state)
            assertEquals(JournalMutationState.PENDING, recoveredJournal.mutation(tag.mutationId)?.state)
            assertEquals(1, transport.effectCounts[interval.mutationId])
            assertNull(transport.effectCounts[metadata.mutationId])
            assertNull(transport.effectCounts[tag.mutationId])

            // A second process starts while the metadata delivery was pending. The durable PENDING
            // state and sequence barrier ensure reconnect cannot overtake it with the later tag edit.
            val secondProcessJournal = DurableMutationJournal(storage)
            assertEquals(JournalMutationState.PENDING, secondProcessJournal.mutation(metadata.mutationId)?.state)
            val reconnected = client.connect(
                transport.credential,
                "sample-lib-resilience",
                previousAuthorityGeneration = "generation-1",
            )
            val secondReplay = SampleLibMutationJournalReplayer(
                secondProcessJournal,
                client,
                CanonicalMutationReconciler { receipt ->
                    receipt.canonical?.let { reconciledCanonicals[receipt.mutationId] = it }
                },
            ).replay(reconnected)

            assertEquals(listOf(metadata.mutationId, tag.mutationId), secondReplay.map { it.mutationId })
            assertEquals(JournalMutationState.RECEIPT_RECORDED, secondProcessJournal.mutation(metadata.mutationId)?.state)
            assertEquals(JournalMutationState.RECEIPT_RECORDED, secondProcessJournal.mutation(tag.mutationId)?.state)
            assertEquals(1, transport.effectCounts[metadata.mutationId])
            assertEquals(1, transport.effectCounts[tag.mutationId])
            assertEquals(2, transport.pushAttempts[metadata.mutationId])
            assertEquals(1, transport.wireFingerprints.getValue(metadata.mutationId).distinct().size)
            assertEquals(setOf(interval.mutationId, metadata.mutationId, tag.mutationId), reconciledCanonicals.keys)
            assertTrue(secondProcessJournal.replayBatch().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `server commit followed by local reconciliation crash replays without duplicate remote effect`() = runTest {
        val directory = Files.createTempDirectory("androidjtools-receipt-boundary").toFile()
        try {
            val storage = FileMutationJournalStorage(File(directory, "journal.db"))
            val journal = DurableMutationJournal(storage)
            val mutation = journal.enqueue(
                mutationId = "receipt-boundary-001",
                entityType = "asset",
                entityId = "asset-1",
                baseRevision = "rev:asset-1:7",
                operation = "asset.metadata.patch",
                payloadJson = """{"rating":4}""",
                createdAtEpochMillis = 2_000,
            )
            val transport = ResilienceTransport()
            val client = SampleLibClient(transport, "android", "install-resilience")
            val session = client.connect(transport.credential, "sample-lib-resilience")
            var canonicalApplyCount = 0

            try {
                SampleLibMutationJournalReplayer(
                    journal,
                    client,
                    CanonicalMutationReconciler {
                        canonicalApplyCount++
                        throw IOException("simulated process death after canonical apply")
                    },
                ).replay(session)
                fail("expected reconciliation failure")
            } catch (_: IOException) {
                // The authoritative server effect exists, but no terminal journal receipt was persisted.
            }
            assertEquals(1, transport.effectCounts[mutation.mutationId])
            assertEquals(JournalMutationState.APPLYING, journal.mutation(mutation.mutationId)?.state)

            val restarted = DurableMutationJournal(storage)
            assertEquals(JournalMutationState.PENDING, restarted.mutation(mutation.mutationId)?.state)
            SampleLibMutationJournalReplayer(
                restarted,
                client,
                CanonicalMutationReconciler { canonicalApplyCount++ },
            ).replay(client.connect(transport.credential, "sample-lib-resilience", "generation-1"))

            assertEquals(2, canonicalApplyCount)
            assertEquals(2, transport.pushAttempts[mutation.mutationId])
            assertEquals(1, transport.wireFingerprints.getValue(mutation.mutationId).distinct().size)
            assertEquals(1, transport.effectCounts[mutation.mutationId])
            assertEquals(JournalMutationState.RECEIPT_RECORDED, restarted.mutation(mutation.mutationId)?.state)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authoritative conflict and rejection remain durable and never reconcile as success`() = runTest {
        val storage = RecordingStorage()
        val journal = DurableMutationJournal(storage)
        val conflict = journal.enqueue(
            mutationId = "resilience-conflict-001",
            entityType = "interval",
            entityId = "interval-1",
            baseRevision = "rev:interval-1:stale",
            operation = "interval.editor_state.replace",
            payloadJson = """{"cues":[{"position_ms":1500}],"loops":[]}""",
            createdAtEpochMillis = 3_000,
        )
        val rejected = journal.enqueue(
            mutationId = "resilience-rejected-001",
            entityType = "asset",
            entityId = "asset-1",
            baseRevision = "rev:asset-1:7",
            operation = "asset.metadata.patch",
            payloadJson = """{"rating":999}""",
            createdAtEpochMillis = 3_001,
        )
        val transport = ResilienceTransport().apply {
            conflictIds += conflict.mutationId
            rejectedIds += rejected.mutationId
        }
        val client = SampleLibClient(transport, "android", "install-resilience")
        var successfulReconciliations = 0
        SampleLibMutationJournalReplayer(
            journal,
            client,
            CanonicalMutationReconciler { successfulReconciliations++ },
        ).replay(client.connect(transport.credential, "sample-lib-resilience"))

        assertEquals(0, successfulReconciliations)
        assertEquals(JournalMutationState.CONFLICT, journal.mutation(conflict.mutationId)?.state)
        assertEquals("revision_conflict", journal.mutation(conflict.mutationId)?.receipt?.conflict?.code)
        assertEquals("rev:interval-1:remote", journal.mutation(conflict.mutationId)?.receipt?.conflict?.authoritativeRevision)
        assertEquals(JournalMutationState.REJECTED, journal.mutation(rejected.mutationId)?.state)
        assertTrue(journal.mutation(rejected.mutationId)?.receipt?.detail?.contains("invalid_mutation") == true)
        assertTrue(DurableMutationJournal(storage).replayBatch().isEmpty())
        assertNull(transport.effectCounts[conflict.mutationId])
        assertNull(transport.effectCounts[rejected.mutationId])
    }

    private fun sample(journal: DurableMutationJournal, id: String, payload: String = "{\"title\":\"New\"}") =
        journal.enqueue(
            mutationId = id,
            entityType = "asset",
            entityId = "asset-1",
            baseRevision = "sha256:old",
            operation = "asset.metadata.patch",
            payloadJson = payload,
            provenanceJson = "{\"origin\":\"android\"}",
            createdAtEpochMillis = 1234,
        )

    private class ResilienceTransport : SampleLibTransport {
        val credential = MobileCredential.issued("resilience-token")
        val offlineOnceIds = mutableSetOf<String>()
        val ambiguousAfterCommitIds = mutableSetOf<String>()
        val conflictIds = mutableSetOf<String>()
        val rejectedIds = mutableSetOf<String>()
        val receipts = mutableMapOf<String, MutationReceipt>()
        val pushAttempts = mutableMapOf<String, Int>()
        val effectCounts = mutableMapOf<String, Int>()
        val wireFingerprints = mutableMapOf<String, MutableList<String>>()

        private val capabilities = ServerCapabilities(
            pull = setOf("asset", "interval", "tag_attachment"),
            mutations = setOf(
                "asset.metadata.patch",
                "interval.editor_state.replace",
                "tag.ensure_attach",
                "tag.detach",
            ),
            playlists = "canonical",
            analysis = emptySet(),
            media = emptySet(),
        )
        private val limits = ServerLimits(100, 100, 7_776_000, 31_536_000)

        override suspend fun pair(request: PairingRequest): PairingResult =
            throw UnsupportedOperationException("pairing is outside this resilience fixture")

        override suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted = HelloAccepted(
            protocolVersion = "1",
            identity = ServerIdentity("sample-lib-resilience", "generation-1"),
            serverChangeRevision = 10,
            capabilities = capabilities,
            limits = limits,
        )

        override suspend fun pull(
            credential: MobileCredential,
            cursor: String?,
            scopes: Set<String>,
            limit: Int,
        ): PullPage = throw UnsupportedOperationException("pull is outside this resilience fixture")

        override suspend fun push(
            credential: MobileCredential,
            clientId: String,
            mutations: List<SampleLibMutation>,
        ): PushResult {
            require(mutations.size == 1) { "resilience fixture expects one mutation per recovery push" }
            val mutation = mutations.single()
            val attempt = pushAttempts.getOrDefault(mutation.mutationId, 0) + 1
            pushAttempts[mutation.mutationId] = attempt
            wireFingerprints.getOrPut(mutation.mutationId) { mutableListOf() } += mutation.wireFingerprint()

            if (mutation.mutationId in offlineOnceIds && attempt == 1) {
                throw SampleLibFailure.Offline()
            }

            receipts[mutation.mutationId]?.let { original ->
                return PushResult(listOf(original), original.serverChangeRevision)
            }

            val serverRevision = 10L + receipts.size + 1L
            val receipt = when {
                mutation.mutationId in conflictIds -> MutationReceipt(
                    mutationId = mutation.mutationId,
                    outcome = ReceiptOutcome.CONFLICT,
                    serverChangeRevision = serverRevision,
                    conflict = MutationConflict(
                        mutationId = mutation.mutationId,
                        entityId = mutation.entityId,
                        baseRevision = mutation.baseRevision,
                        authoritativeRevision = OpaqueRevision("rev:${mutation.entityId}:remote"),
                        localValue = mutation.payload,
                        remoteValue = buildJsonObject { put("authoritative", true) },
                        mergeClass = "editor_aggregate",
                        code = "revision_conflict",
                    ),
                )
                mutation.mutationId in rejectedIds -> MutationReceipt(
                    mutationId = mutation.mutationId,
                    outcome = ReceiptOutcome.REJECTED,
                    serverChangeRevision = serverRevision,
                    error = MutationError("invalid_mutation", "fixture rejection"),
                )
                else -> MutationReceipt(
                    mutationId = mutation.mutationId,
                    outcome = ReceiptOutcome.APPLIED,
                    serverChangeRevision = serverRevision,
                    entityRevision = OpaqueRevision("rev:${mutation.entityId}:$serverRevision"),
                    canonical = mutation.payload,
                )
            }
            receipts[mutation.mutationId] = receipt
            if (receipt.outcome == ReceiptOutcome.APPLIED || receipt.outcome == ReceiptOutcome.NO_OP) {
                effectCounts[mutation.mutationId] = effectCounts.getOrDefault(mutation.mutationId, 0) + 1
            }
            if (mutation.mutationId in ambiguousAfterCommitIds && attempt == 1) {
                throw SampleLibFailure.AmbiguousDelivery()
            }
            return PushResult(listOf(receipt), serverRevision)
        }

        override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt =
            receipts[mutationId] ?: throw SampleLibFailure.ReceiptNotFound(mutationId)

        override suspend fun fetchResource(
            credential: MobileCredential,
            descriptor: ResourceDescriptor,
            offset: Long,
        ): ResourceChunk = throw UnsupportedOperationException("media is outside this resilience fixture")

        private fun SampleLibMutation.wireFingerprint(): String = listOf(
            entityType,
            entityId,
            baseRevision?.value.orEmpty(),
            operation.wireName,
            payload.toString(),
            createdAt.toString(),
            provenance.toString(),
        ).joinToString("|")
    }

    private class RecordingStorage(
        var durable: JournalSnapshot = JournalSnapshot(),
        var failStores: Int = 0,
    ) : MutationJournalStorage {
        override fun load(): JournalSnapshot = durable

        override fun store(snapshot: JournalSnapshot) {
            if (failStores > 0) {
                failStores--
                throw IOException("injected durable write failure")
            }
            durable = snapshot
        }
    }
}
