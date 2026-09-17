package dev.androidjtools.sync.journal

import dev.androidjtools.remote.samplelib.AnalysisCandidateDecision
import dev.androidjtools.remote.samplelib.AnalysisCandidateFreshness
import dev.androidjtools.remote.samplelib.AnalysisCandidateValue
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.ReceiptOutcome
import dev.androidjtools.remote.samplelib.SampleLibAnalysisCandidate
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AnalysisAcceptanceState { PROPOSED, QUEUED, ACCEPTED, REJECTED, CONFLICT }

data class AnalysisAcceptanceResolution(
    val state: AnalysisAcceptanceState,
    /** Canonical projection is usable only after applied/no_op authority evidence. */
    val canonical: JsonObject? = null,
    val entityRevision: String? = null,
)

class StaleAnalysisCandidateException(message: String) : IllegalStateException(message)

/**
 * Bridges explicit user acceptance into the ordinary durable sync journal.
 * It never promotes a proposal by itself; authoritative receipts are the only success boundary.
 */
class AnalysisSuggestionAcceptanceCoordinator(
    private val journal: DurableMutationJournal,
) {
    fun queue(candidate: SampleLibAnalysisCandidate, createdAtEpochMillis: Long): JournalMutation {
        require(candidate.decision == AnalysisCandidateDecision.PROPOSED) {
            "only proposed analysis candidates can be queued"
        }
        if (candidate.freshness != AnalysisCandidateFreshness.FRESH) {
            throw StaleAnalysisCandidateException("analysis candidate is not fresh")
        }
        validateTargetIdentity(candidate)
        val mutationId = mutationIdFor(candidate.candidateId)
        return journal.enqueue(
            mutationId = mutationId,
            entityType = candidate.inputIdentity.entityType,
            entityId = candidate.inputIdentity.entityId,
            baseRevision = candidate.inputIdentity.revision.value,
            operation = "analysis.suggestion.accept",
            payloadJson = candidatePayload(candidate).toString(),
            provenanceJson = candidateProvenance(candidate).toString(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    fun state(candidate: SampleLibAnalysisCandidate): AnalysisAcceptanceState {
        val mutation = journal.mutation(mutationIdFor(candidate.candidateId)) ?: return when (candidate.decision) {
            AnalysisCandidateDecision.ACCEPTED -> AnalysisAcceptanceState.ACCEPTED
            AnalysisCandidateDecision.REJECTED -> AnalysisAcceptanceState.REJECTED
            else -> AnalysisAcceptanceState.PROPOSED
        }
        return when (mutation.state) {
            JournalMutationState.PENDING, JournalMutationState.APPLYING -> AnalysisAcceptanceState.QUEUED
            JournalMutationState.RECEIPT_RECORDED -> AnalysisAcceptanceState.ACCEPTED
            JournalMutationState.REJECTED -> AnalysisAcceptanceState.REJECTED
            JournalMutationState.CONFLICT -> AnalysisAcceptanceState.CONFLICT
        }
    }

    fun recordAuthoritativeReceipt(
        candidate: SampleLibAnalysisCandidate,
        receipt: MutationReceipt,
    ): AnalysisAcceptanceResolution {
        val expectedMutationId = mutationIdFor(candidate.candidateId)
        require(receipt.mutationId == expectedMutationId) { "receipt does not belong to analysis candidate" }
        val journalReceipt = when (receipt.outcome) {
            ReceiptOutcome.APPLIED -> JournalReceipt(
                receipt.mutationId,
                JournalReceiptOutcome.APPLIED,
                entityRevision = receipt.entityRevision?.value,
            )
            ReceiptOutcome.NO_OP -> JournalReceipt(
                receipt.mutationId,
                JournalReceiptOutcome.NO_OP,
                entityRevision = receipt.entityRevision?.value,
            )
            ReceiptOutcome.REJECTED -> JournalReceipt(
                receipt.mutationId,
                JournalReceiptOutcome.REJECTED,
                detail = receipt.error?.let { "${it.code}: ${it.message}" },
            )
            ReceiptOutcome.CONFLICT -> JournalReceipt(
                receipt.mutationId,
                JournalReceiptOutcome.CONFLICT,
                conflict = receipt.conflict?.let { conflict ->
                    JournalConflict(
                        code = conflict.code,
                        mergeClass = conflict.mergeClass,
                        authoritativeRevision = conflict.authoritativeRevision?.value,
                        localValueJson = conflict.localValue?.toString(),
                        remoteValueJson = conflict.remoteValue?.toString(),
                    )
                } ?: throw IllegalArgumentException("conflict receipt requires conflict evidence"),
            )
        }
        journal.recordReceipt(journalReceipt)
        return when (receipt.outcome) {
            ReceiptOutcome.APPLIED, ReceiptOutcome.NO_OP -> AnalysisAcceptanceResolution(
                state = AnalysisAcceptanceState.ACCEPTED,
                canonical = receipt.canonical,
                entityRevision = receipt.entityRevision?.value,
            )
            ReceiptOutcome.REJECTED -> AnalysisAcceptanceResolution(AnalysisAcceptanceState.REJECTED)
            ReceiptOutcome.CONFLICT -> AnalysisAcceptanceResolution(AnalysisAcceptanceState.CONFLICT)
        }
    }

    companion object {
        fun mutationIdFor(candidateId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(candidateId.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return "analysis-accept-${digest.take(24)}"
        }
    }
}

private fun validateTargetIdentity(candidate: SampleLibAnalysisCandidate) {
    when (candidate.inputIdentity.entityType) {
        "asset" -> require(candidate.inputIdentity.entityId == candidate.assetId) {
            "analysis candidate asset identity mismatch"
        }
        "interval" -> require(candidate.inputIdentity.entityId == candidate.intervalId) {
            "analysis candidate interval identity mismatch"
        }
        else -> throw IllegalArgumentException("unsupported analysis candidate target")
    }
}

private fun candidatePayload(candidate: SampleLibAnalysisCandidate): JsonObject = buildJsonObject {
    put("candidate_id", candidate.candidateId)
    put("analysis_run_id", candidate.analysisRunId)
    put("kind", candidate.kind.wireName)
    put("value", candidate.value.toWireElement())
    put("input_identity", buildJsonObject {
        put("entity_type", candidate.inputIdentity.entityType)
        put("entity_id", candidate.inputIdentity.entityId)
        put("revision", candidate.inputIdentity.revision.value)
        candidate.inputIdentity.contentSha256?.let { put("content_sha256", it) }
    })
}

private fun candidateProvenance(candidate: SampleLibAnalysisCandidate): JsonObject = buildJsonObject {
    put("source", "analysis_suggestion")
    put("candidate_id", candidate.candidateId)
    put("analysis_run_id", candidate.analysisRunId)
    put("generated_at", candidate.generatedAt.toString())
    candidate.confidence?.let { put("confidence", it) }
    put("freshness", candidate.freshness.name.lowercase())
    put("input_identity", buildJsonObject {
        put("entity_type", candidate.inputIdentity.entityType)
        put("entity_id", candidate.inputIdentity.entityId)
        put("revision", candidate.inputIdentity.revision.value)
        candidate.inputIdentity.contentSha256?.let { put("content_sha256", it) }
    })
    put("candidate_source", buildJsonObject {
        put("service", candidate.source.service)
        candidate.source.model?.let { put("model", it) }
        candidate.source.version?.let { put("version", it) }
        candidate.source.pipelineVersion?.let { put("pipeline_version", it) }
    })
}
