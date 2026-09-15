package dev.androidjtools.sync.journal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class JournalMutationState { PENDING, APPLYING, RECEIPT_RECORDED, REJECTED, CONFLICT }

enum class JournalReceiptOutcome { APPLIED, NO_OP, REJECTED, CONFLICT }

data class JournalConflict(
    val code: String,
    val mergeClass: String,
    val authoritativeRevision: String? = null,
    val localValueJson: String? = null,
    val remoteValueJson: String? = null,
)

data class JournalReceipt(
    val mutationId: String,
    val outcome: JournalReceiptOutcome,
    val entityRevision: String? = null,
    val detail: String? = null,
    val conflict: JournalConflict? = null,
)

data class JournalMutation(
    val sequence: Long,
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val baseRevision: String?,
    val operation: String,
    val canonicalPayload: String,
    val canonicalProvenance: String,
    val canonicalRequestBody: String,
    val createdAtEpochMillis: Long,
    val state: JournalMutationState = JournalMutationState.PENDING,
    val receipt: JournalReceipt? = null,
)

data class JournalSnapshot(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val mutations: List<JournalMutation> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2
    }
}

interface MutationJournalStorage {
    fun load(): JournalSnapshot
    fun store(snapshot: JournalSnapshot)
}

class IdempotencyKeyReuseException(mutationId: String) :
    IllegalArgumentException("mutation_id $mutationId was already used with a different canonical body")

class DurableMutationJournal(
    private val storage: MutationJournalStorage,
) {
    private var snapshot: JournalSnapshot = normalizeForRecovery(storage.load())

    fun all(): List<JournalMutation> = snapshot.mutations.toList()

    fun mutation(mutationId: String): JournalMutation? =
        snapshot.mutations.firstOrNull { it.mutationId == mutationId }

    fun enqueue(
        mutationId: String,
        entityType: String,
        entityId: String,
        baseRevision: String?,
        operation: String,
        payloadJson: String,
        provenanceJson: String = "{}",
        createdAtEpochMillis: Long,
    ): JournalMutation {
        require(mutationId.length >= 8) { "mutation_id must be at least 8 characters" }
        require(entityId.isNotBlank()) { "entity_id must be non-empty" }
        ProtocolOperationPolicy.validate(operation, entityType, baseRevision)

        val payload = canonicalJsonObject(payloadJson, "payload")
        val provenance = canonicalJsonObject(provenanceJson, "provenance")
        val canonicalBody = canonicalRequestBody(
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            baseRevision = baseRevision,
            operation = operation,
            payload = payload,
            provenance = provenance,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        snapshot.mutations.firstOrNull { it.mutationId == mutationId }?.let { existing ->
            if (existing.canonicalRequestBody != canonicalBody) throw IdempotencyKeyReuseException(mutationId)
            return existing
        }

        val next = JournalMutation(
            sequence = (snapshot.mutations.maxOfOrNull { it.sequence } ?: 0L) + 1L,
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            baseRevision = baseRevision,
            operation = operation,
            canonicalPayload = payload,
            canonicalProvenance = provenance,
            canonicalRequestBody = canonicalBody,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        // Persist before returning success. If storage throws, the in-memory view is unchanged.
        persist(snapshot.copy(mutations = snapshot.mutations + next))
        return next
    }

    fun replayBatch(limit: Int = Int.MAX_VALUE): List<JournalMutation> {
        require(limit > 0)
        return snapshot.mutations
            .asSequence()
            .filter { it.state == JournalMutationState.PENDING || it.state == JournalMutationState.APPLYING }
            .sortedBy { it.sequence }
            .take(limit)
            .map { if (it.state == JournalMutationState.APPLYING) it.copy(state = JournalMutationState.PENDING) else it }
            .toList()
    }

    fun markApplying(mutationId: String): JournalMutation = update(mutationId) { current ->
        require(current.receipt == null) { "terminal mutation cannot return to applying" }
        current.copy(state = JournalMutationState.APPLYING)
    }

    fun recordReceipt(receipt: JournalReceipt): JournalMutation = update(receipt.mutationId) { current ->
        current.receipt?.let { existing ->
            require(existing == receipt) { "authoritative receipt changed for ${receipt.mutationId}" }
            return@update current
        }
        require(receipt.entityRevision == null || receipt.entityRevision.isNotBlank()) {
            "entity_revision must be opaque and non-empty when present"
        }
        if (receipt.outcome == JournalReceiptOutcome.CONFLICT) {
            requireNotNull(receipt.conflict) { "conflict receipt requires conflict evidence" }
        }
        val terminalState = when (receipt.outcome) {
            JournalReceiptOutcome.APPLIED, JournalReceiptOutcome.NO_OP -> JournalMutationState.RECEIPT_RECORDED
            JournalReceiptOutcome.REJECTED -> JournalMutationState.REJECTED
            JournalReceiptOutcome.CONFLICT -> JournalMutationState.CONFLICT
        }
        // Receipt and terminal state become visible together only after one durable snapshot write.
        current.copy(state = terminalState, receipt = receipt)
    }

    fun compactAcknowledged(): Int {
        val survivors = snapshot.mutations.filterNot { mutation ->
            mutation.state == JournalMutationState.RECEIPT_RECORDED &&
                mutation.receipt?.outcome in setOf(JournalReceiptOutcome.APPLIED, JournalReceiptOutcome.NO_OP)
        }
        val removed = snapshot.mutations.size - survivors.size
        if (removed > 0) persist(snapshot.copy(mutations = survivors))
        return removed
    }

    private fun update(mutationId: String, transform: (JournalMutation) -> JournalMutation): JournalMutation {
        val index = snapshot.mutations.indexOfFirst { it.mutationId == mutationId }
        require(index >= 0) { "unknown mutation_id $mutationId" }
        val updated = transform(snapshot.mutations[index])
        if (updated == snapshot.mutations[index]) return updated
        val list = snapshot.mutations.toMutableList().also { it[index] = updated }
        persist(snapshot.copy(mutations = list))
        return updated
    }

    private fun persist(next: JournalSnapshot) {
        val versioned = next.copy(formatVersion = JournalSnapshot.CURRENT_FORMAT_VERSION)
        storage.store(versioned)
        snapshot = versioned
    }

    private fun normalizeForRecovery(loaded: JournalSnapshot): JournalSnapshot = loaded.copy(
        formatVersion = JournalSnapshot.CURRENT_FORMAT_VERSION,
        mutations = loaded.mutations.map { mutation ->
            // APPLYING can mean request bytes left the process before it died. Exact replay with the same
            // immutable mutation_id/body is the protocol recovery mechanism.
            if (mutation.state == JournalMutationState.APPLYING && mutation.receipt == null) {
                mutation.copy(state = JournalMutationState.PENDING)
            } else {
                mutation
            }
        },
    )
}

object ProtocolOperationPolicy {
    fun validate(operation: String, entityType: String, baseRevision: String?) {
        val requiredExisting = when (operation) {
            "interval.editor_state.replace" -> setOf("interval")
            "asset.metadata.patch" -> setOf("asset")
            "tag.detach" -> setOf("tag_attachment")
            "analysis.suggestion.accept" -> setOf("asset", "interval")
            "tag.ensure_attach" -> emptySet()
            else -> throw IllegalArgumentException("unsupported mutation operation $operation")
        }
        if (operation == "tag.ensure_attach") {
            require(entityType == "tag_attachment") { "tag.ensure_attach requires tag_attachment" }
            require(baseRevision == null) { "tag.ensure_attach requires null base_revision" }
        } else {
            require(entityType in requiredExisting) { "$operation does not allow entity_type $entityType" }
            require(!baseRevision.isNullOrBlank()) { "$operation requires an opaque base_revision" }
        }
    }
}

class FileMutationJournalStorage(private val file: File) : MutationJournalStorage {
    override fun load(): JournalSnapshot {
        if (!file.exists()) return JournalSnapshot()
        val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return JournalSnapshot()
        val header = lines.first().split('\t')
        require(header.size == 2 && header[0] == MAGIC) { "invalid mutation journal header" }
        val version = header[1].toInt()
        require(version in 1..JournalSnapshot.CURRENT_FORMAT_VERSION) { "unsupported mutation journal version $version" }
        val mutations = lines.drop(1).map { line ->
            when (version) {
                1 -> decodeV1(line)
                else -> decodeV2(line)
            }
        }
        return JournalSnapshot(version, mutations)
    }

    override fun store(snapshot: JournalSnapshot) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), ".${file.name}.tmp")
        FileOutputStream(temp, false).use { output ->
            val content = buildString {
                append(MAGIC).append('\t').append(JournalSnapshot.CURRENT_FORMAT_VERSION).append('\n')
                snapshot.mutations.sortedBy { it.sequence }.forEach { append(encodeV2(it)).append('\n') }
            }
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            temp.delete()
            throw IOException("atomic mutation journal replace is not supported", error)
        }
    }

    private fun encodeV2(m: JournalMutation): String = listOf(
        "M",
        m.sequence.toString(),
        enc(m.mutationId),
        enc(m.entityType),
        enc(m.entityId),
        encNullable(m.baseRevision),
        enc(m.operation),
        enc(m.canonicalPayload),
        enc(m.canonicalProvenance),
        enc(m.canonicalRequestBody),
        m.createdAtEpochMillis.toString(),
        m.state.name,
        m.receipt?.outcome?.name ?: "-",
        encNullable(m.receipt?.entityRevision),
        encNullable(m.receipt?.detail),
        encNullable(m.receipt?.conflict?.code),
        encNullable(m.receipt?.conflict?.mergeClass),
        encNullable(m.receipt?.conflict?.authoritativeRevision),
        encNullable(m.receipt?.conflict?.localValueJson),
        encNullable(m.receipt?.conflict?.remoteValueJson),
    ).joinToString("\t")

    private fun decodeV2(line: String): JournalMutation {
        val f = line.split('\t')
        require(f.size == 20 && f[0] == "M") { "invalid v2 mutation record" }
        val mutationId = dec(f[2])
        val entityType = dec(f[3])
        val entityId = dec(f[4])
        val baseRevision = decNullable(f[5])
        val operation = dec(f[6])
        val payload = canonicalJsonObject(dec(f[7]), "payload")
        val provenance = canonicalJsonObject(dec(f[8]), "provenance")
        val createdAt = f[10].toLong()
        val receiptOutcome = f[12].takeUnless { it == "-" }?.let(JournalReceiptOutcome::valueOf)
        val conflictCode = decNullable(f[15])
        val receipt = receiptOutcome?.let { outcome ->
            JournalReceipt(
                mutationId = mutationId,
                outcome = outcome,
                entityRevision = decNullable(f[13]),
                detail = decNullable(f[14]),
                conflict = conflictCode?.let {
                    JournalConflict(
                        code = it,
                        mergeClass = requireNotNull(decNullable(f[16])),
                        authoritativeRevision = decNullable(f[17]),
                        localValueJson = decNullable(f[18]),
                        remoteValueJson = decNullable(f[19]),
                    )
                },
            )
        }
        return JournalMutation(
            sequence = f[1].toLong(),
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            baseRevision = baseRevision,
            operation = operation,
            canonicalPayload = payload,
            canonicalProvenance = provenance,
            // v2 persisted an internal epoch-millis request shape. Recompute the accepted v1 wire body
            // so unresolved intents replay with the protocol schema rather than preserving an invalid body.
            canonicalRequestBody = canonicalRequestBody(
                mutationId,
                entityType,
                entityId,
                baseRevision,
                operation,
                payload,
                provenance,
                createdAt,
            ),
            createdAtEpochMillis = createdAt,
            state = JournalMutationState.valueOf(f[11]),
            receipt = receipt,
        )
    }

    // v1 existed before receipt/state persistence. Loading it preserves unresolved intent; the next write upgrades
    // the file to v2 without changing mutation identity or canonical request bytes.
    private fun decodeV1(line: String): JournalMutation {
        val f = line.split('\t')
        require(f.size == 10 && f[0] == "M") { "invalid v1 mutation record" }
        val mutationId = dec(f[2])
        val entityType = dec(f[3])
        val entityId = dec(f[4])
        val baseRevision = decNullable(f[5])
        val operation = dec(f[6])
        val payload = dec(f[7])
        val provenance = dec(f[8])
        val createdAt = f[9].toLong()
        return JournalMutation(
            sequence = f[1].toLong(),
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            baseRevision = baseRevision,
            operation = operation,
            canonicalPayload = payload,
            canonicalProvenance = provenance,
            canonicalRequestBody = canonicalRequestBody(
                mutationId,
                entityType,
                entityId,
                baseRevision,
                operation,
                payload,
                provenance,
                createdAt,
            ),
            createdAtEpochMillis = createdAt,
        )
    }

    companion object {
        private const val MAGIC = "androidjtools-mutation-journal"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        internal fun encodeV1ForMigrationTest(m: JournalMutation): String = listOf(
            "$MAGIC\t1",
            listOf(
                "M",
                m.sequence.toString(),
                enc(m.mutationId),
                enc(m.entityType),
                enc(m.entityId),
                encNullable(m.baseRevision),
                enc(m.operation),
                enc(m.canonicalPayload),
                enc(m.canonicalProvenance),
                m.createdAtEpochMillis.toString(),
            ).joinToString("\t"),
        ).joinToString("\n", postfix = "\n")

        private fun enc(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        private fun encNullable(value: String?): String = value?.let(::enc) ?: "-"
        private fun dec(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)
        private fun decNullable(value: String): String? = value.takeUnless { it == "-" }?.let(::dec)
    }
}

private fun canonicalJsonObject(raw: String, fieldName: String): String {
    val parsed = Json.parseToJsonElement(raw)
    require(parsed is JsonObject) { "$fieldName must be a JSON object" }
    return canonicalize(parsed).toString()
}

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}

private fun canonicalRequestBody(
    mutationId: String,
    entityType: String,
    entityId: String,
    baseRevision: String?,
    operation: String,
    payload: String,
    provenance: String,
    createdAtEpochMillis: Long,
): String {
    val body = JsonObject(
        mapOf(
            "mutation_id" to JsonPrimitive(mutationId),
            "entity_type" to JsonPrimitive(entityType),
            "entity_id" to JsonPrimitive(entityId),
            "base_revision" to (baseRevision?.let(::JsonPrimitive) ?: JsonNull),
            "operation" to JsonPrimitive(operation),
            "payload" to Json.parseToJsonElement(payload),
            "created_at" to JsonPrimitive(Instant.ofEpochMilli(createdAtEpochMillis).toString()),
            "provenance" to Json.parseToJsonElement(provenance),
        ),
    )
    return canonicalize(body).toString()
}
