package dev.androidjtools.sync.journal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
