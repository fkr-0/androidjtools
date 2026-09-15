package dev.androidjtools.remote.samplelib

import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.JsonObject

@JvmInline
value class OpaqueRevision(val value: String) {
    init {
        require(value.isNotBlank()) { "opaque revision must not be blank" }
    }

    override fun toString(): String = value
}

/** Secret wrapper whose string representation is always safe for diagnostics. */
class MobileCredential private constructor(private val value: String) {
    init {
        require(value.isNotBlank()) { "mobile credential must not be blank" }
    }

    internal fun bearerValue(): String = value

    override fun toString(): String = "MobileCredential(<redacted>)"

    companion object {
        fun issued(value: String): MobileCredential = MobileCredential(value)
    }
}

/** Pairing secrets are kept out of data-class/string rendering for the same reason as tokens. */
class PairingCode private constructor(private val value: String) {
    init {
        require(value.isNotBlank()) { "pairing code must not be blank" }
    }

    internal fun reveal(): String = value

    override fun toString(): String = "PairingCode(<redacted>)"

    companion object {
        fun entered(value: String): PairingCode = PairingCode(value)
    }
}

data class PairingRequest(
    val clientId: String,
    val installationId: String,
    val pairingCode: PairingCode,
)

data class PairingResult(
    val serverId: String,
    val principalId: String,
    val installationId: String,
    val credential: MobileCredential,
    val scopes: Set<String>,
)

data class ServerIdentity(
    val serverId: String,
    val authorityGeneration: String,
)

data class ServerCapabilities(
    val pull: Set<String>,
    val mutations: Set<String>,
    val playlists: String,
    val analysis: Set<String>,
    val media: Set<String>,
)

data class ServerLimits(
    val pullPageMax: Int,
    val pushBatchMax: Int,
    val tombstoneRetentionSeconds: Long,
    val mutationReplayHorizonSeconds: Long,
)

data class HelloRequest(
    val clientId: String,
    val installationId: String,
    val protocolVersions: List<String> = listOf("1"),
    val capabilities: Set<String> = emptySet(),
)

data class HelloAccepted(
    val protocolVersion: String,
    val identity: ServerIdentity,
    val serverChangeRevision: Long,
    val capabilities: ServerCapabilities,
    val limits: ServerLimits,
)

data class EntityChange(
    val entityType: String,
    val entityId: String,
    val revision: OpaqueRevision,
    val schema: String,
    val value: JsonObject,
)

data class Tombstone(
    val entityType: String,
    val entityId: String,
    val revision: OpaqueRevision,
    val deletedAt: Instant,
)

enum class PullMode { SNAPSHOT, INCREMENTAL }

data class PullPage(
    val mode: PullMode,
    val nextCursor: String,
    val hasMore: Boolean,
    val changes: List<EntityChange>,
    val tombstones: List<Tombstone>,
    val authorityGeneration: String,
    val serverChangeRevision: Long,
)

enum class MutationOperation(val wireName: String) {
    INTERVAL_EDITOR_STATE_REPLACE("interval.editor_state.replace"),
    ASSET_METADATA_PATCH("asset.metadata.patch"),
    TAG_ENSURE_ATTACH("tag.ensure_attach"),
    TAG_DETACH("tag.detach"),
    ANALYSIS_SUGGESTION_ACCEPT("analysis.suggestion.accept"),
}

data class SampleLibMutation(
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val baseRevision: OpaqueRevision?,
    val operation: MutationOperation,
    val payload: JsonObject,
    val createdAt: Instant,
    val provenance: JsonObject,
)

enum class ReceiptOutcome { APPLIED, NO_OP, REJECTED, CONFLICT }

data class MutationError(val code: String, val message: String)

data class MutationConflict(
    val mutationId: String,
    val entityId: String,
    val baseRevision: OpaqueRevision?,
    val authoritativeRevision: OpaqueRevision?,
    val localValue: JsonObject?,
    val remoteValue: JsonObject?,
    val mergeClass: String,
    val code: String,
)

data class MutationReceipt(
    val mutationId: String,
    val outcome: ReceiptOutcome,
    val serverChangeRevision: Long,
    val entityRevision: OpaqueRevision? = null,
    val canonical: JsonObject? = null,
    val error: MutationError? = null,
    val conflict: MutationConflict? = null,
)

data class PushResult(
    val receipts: List<MutationReceipt>,
    val serverChangeRevision: Long,
)

data class ResourceDescriptor(
    /** Stable Sample Lib identity. Never a filesystem path or credential-bearing URL. */
    val resourceUri: String,
    /** Refreshable authenticated fetch location. Relative paths are preferred. */
    val fetchPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val etag: String? = null,
)

data class ResourceChunk(
    val statusCode: Int,
    val bytes: ByteArray,
    val contentRangeStart: Long?,
    val totalSize: Long?,
    val etag: String?,
)

data class DownloadedResource(
    val descriptor: ResourceDescriptor,
    val bytes: ByteArray,
    val resumed: Boolean,
)

data class SampleLibSession(
    val identity: ServerIdentity,
    val principalId: String?,
    val installationId: String,
    val credential: MobileCredential,
    val capabilities: ServerCapabilities,
    val limits: ServerLimits,
    /** True when a known authority generation changed and only cursor=null bootstrap is safe. */
    val requiresReset: Boolean,
)

sealed interface MutationDelivery {
    data class Confirmed(val receipt: MutationReceipt) : MutationDelivery
    data class Pending(val mutation: SampleLibMutation, val reason: String) : MutationDelivery
}

sealed class SampleLibFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PairingRejected(message: String = "Pairing was rejected") : SampleLibFailure(message)
    class AuthRequired(message: String = "Sample Lib authorization is required or was revoked") : SampleLibFailure(message)
    class Incompatible(val requested: List<String>, val supported: List<String>) :
        SampleLibFailure("No mutually supported Sample Lib sync protocol version")
    class CapabilityUnavailable(val capability: String) :
        SampleLibFailure("Sample Lib capability is unavailable: $capability")
    class CursorExpired(val authorityGeneration: String) :
        SampleLibFailure("Sample Lib pull cursor expired; a snapshot reset is required")
    class ResetRequired(val authorityGeneration: String) :
        SampleLibFailure("Sample Lib authority generation changed; cursor=null reset is required")
    class ServerIdentityMismatch(expected: String, actual: String) :
        SampleLibFailure("Sample Lib server identity mismatch: expected $expected, received $actual")
    class InsecureTransport(host: String) :
        SampleLibFailure("Non-loopback Sample Lib transport must use authenticated TLS: $host")
    class Unavailable(message: String = "Sample Lib is unavailable") : SampleLibFailure(message)
    class Offline(cause: Throwable? = null) : SampleLibFailure("Sample Lib transport is offline", cause)
    class AmbiguousDelivery(cause: Throwable? = null) :
        SampleLibFailure("Mutation delivery became ambiguous before a receipt was received", cause)
    class ReceiptNotFound(val mutationId: String) :
        SampleLibFailure("No authoritative receipt exists for mutation $mutationId")
    class IntegrityMismatch(message: String) : SampleLibFailure(message)
    class InvalidResponse(message: String) : SampleLibFailure(message)
}

interface SampleLibTransport {
    suspend fun pair(request: PairingRequest): PairingResult
    suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted
    suspend fun pull(
        credential: MobileCredential,
        cursor: String?,
        scopes: Set<String>,
        limit: Int,
    ): PullPage
    suspend fun push(credential: MobileCredential, clientId: String, mutations: List<SampleLibMutation>): PushResult
    suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt
    suspend fun fetchResource(
        credential: MobileCredential,
        descriptor: ResourceDescriptor,
        offset: Long,
    ): ResourceChunk
}
