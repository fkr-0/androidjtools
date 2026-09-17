package dev.androidjtools.remote.samplelib

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class SampleLibClient(
    private val transport: SampleLibTransport,
    private val clientId: String,
    private val installationId: String,
) {
    init {
        require(clientId.isNotBlank())
        require(installationId.isNotBlank())
    }

    suspend fun pair(code: PairingCode): SampleLibSession {
        val paired = transport.pair(PairingRequest(clientId, installationId, code))
        if (paired.installationId != installationId) {
            throw SampleLibFailure.InvalidResponse("Pairing response was issued for a different installation")
        }
        val hello = transport.hello(paired.credential, HelloRequest(clientId, installationId))
        if (paired.serverId != hello.identity.serverId) {
            throw SampleLibFailure.ServerIdentityMismatch(paired.serverId, hello.identity.serverId)
        }
        return hello.toSession(
            credential = paired.credential,
            principalId = paired.principalId,
            previousGeneration = null,
        )
    }

    /**
     * Reconnect using a previously issued credential and pinned server identity.
     * A generation change is not accepted as an empty delta: requiresReset becomes true.
     */
    suspend fun connect(
        credential: MobileCredential,
        pinnedServerId: String,
        previousAuthorityGeneration: String? = null,
        principalId: String? = null,
    ): SampleLibSession {
        val hello = transport.hello(credential, HelloRequest(clientId, installationId))
        if (hello.identity.serverId != pinnedServerId) {
            throw SampleLibFailure.ServerIdentityMismatch(pinnedServerId, hello.identity.serverId)
        }
        return hello.toSession(credential, principalId, previousAuthorityGeneration)
    }

    /** Hello is the protocol health diagnostic: auth, version, identity and capabilities are checked together. */
    suspend fun health(session: SampleLibSession): HelloAccepted {
        val hello = transport.hello(session.credential, HelloRequest(clientId, installationId))
        if (hello.identity.serverId != session.identity.serverId) {
            throw SampleLibFailure.ServerIdentityMismatch(session.identity.serverId, hello.identity.serverId)
        }
        return hello
    }

    suspend fun pull(
        session: SampleLibSession,
        cursor: String?,
        scopes: Set<String> = setOf("library"),
        limit: Int = session.limits.pullPageMax,
    ): PullPage {
        if (cursor != null && session.requiresReset) {
            throw SampleLibFailure.ResetRequired(session.identity.authorityGeneration)
        }
        require(scopes.isNotEmpty()) { "at least one pull scope is required" }
        val boundedLimit = limit.coerceIn(1, session.limits.pullPageMax)
        val page = transport.pull(session.credential, cursor, scopes, boundedLimit)
        if (page.authorityGeneration != session.identity.authorityGeneration) {
            throw SampleLibFailure.ResetRequired(page.authorityGeneration)
        }
        return page
    }

    suspend fun push(session: SampleLibSession, mutations: List<SampleLibMutation>): PushResult {
        require(mutations.isNotEmpty()) { "at least one mutation is required" }
        require(mutations.size <= session.limits.pushBatchMax) { "push exceeds negotiated batch limit" }
        mutations.forEach { validateMutation(session, it) }
        return transport.push(session.credential, clientId, mutations)
    }

    suspend fun pushWithRecovery(session: SampleLibSession, mutation: SampleLibMutation): MutationDelivery {
        return try {
            val result = push(session, listOf(mutation))
            val receipt = result.receipts.singleOrNull { it.mutationId == mutation.mutationId }
                ?: throw SampleLibFailure.InvalidResponse("Push response omitted the requested mutation receipt")
            MutationDelivery.Confirmed(receipt)
        } catch (ambiguous: SampleLibFailure.AmbiguousDelivery) {
            recoverReceiptOrKeepPending(session, mutation, ambiguous.message ?: "ambiguous delivery")
        } catch (offline: SampleLibFailure.Offline) {
            // Keep the immutable mutation pending. A transport can know it is offline before
            // sending bytes, while journal replay remains safe because mutation_id/body are stable.
            recoverReceiptOrKeepPending(session, mutation, offline.message ?: "offline delivery")
        } catch (unavailable: SampleLibFailure.Unavailable) {
            // A 5xx does not authorize minting a replacement intent. Receipt lookup first in
            // case the server committed before failing its response; otherwise retry this ID.
            recoverReceiptOrKeepPending(session, mutation, unavailable.message ?: "server unavailable")
        }
    }

    suspend fun lookupReceipt(session: SampleLibSession, mutationId: String): MutationReceipt =
        transport.receipt(session.credential, mutationId)

    private suspend fun recoverReceiptOrKeepPending(
        session: SampleLibSession,
        mutation: SampleLibMutation,
        reason: String,
    ): MutationDelivery = try {
        MutationDelivery.Confirmed(lookupReceipt(session, mutation.mutationId))
    } catch (_: SampleLibFailure.ReceiptNotFound) {
        MutationDelivery.Pending(mutation, reason)
    } catch (_: SampleLibFailure.Offline) {
        MutationDelivery.Pending(mutation, reason)
    } catch (_: SampleLibFailure.Unavailable) {
        MutationDelivery.Pending(mutation, reason)
    }

    suspend fun downloadResource(
        session: SampleLibSession,
        descriptor: ResourceDescriptor,
        existingPrefix: ByteArray = ByteArray(0),
    ): DownloadedResource {
        require(descriptor.resourceUri.isNotBlank()) { "resource_uri must be stable and non-empty" }
        require(descriptor.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "resource sha256 must be 64 hex characters" }
        require(descriptor.sizeBytes >= 0) { "resource size must not be negative" }
        requireMedia(session, "resource_uri")
        requireMedia(session, "content_hash")

        val mayResume = existingPrefix.isNotEmpty() && "range" in session.capabilities.media
        val offset = if (mayResume) existingPrefix.size.toLong() else 0L
        val chunk = transport.fetchResource(session.credential, descriptor, offset)
        val resumed = offset > 0 && chunk.statusCode == 206
        val bytes = when {
            resumed -> {
                if (chunk.contentRangeStart != offset) {
                    throw SampleLibFailure.IntegrityMismatch("Range response started at the wrong byte offset")
                }
                if (descriptor.etag != null && chunk.etag != descriptor.etag) {
                    throw SampleLibFailure.IntegrityMismatch("Range response validator no longer matches the canonical resource")
                }
                existingPrefix + chunk.bytes
            }
            chunk.statusCode == 200 -> chunk.bytes
            else -> throw SampleLibFailure.InvalidResponse("Unexpected media HTTP status ${chunk.statusCode}")
        }

        if (bytes.size.toLong() != descriptor.sizeBytes) {
            throw SampleLibFailure.IntegrityMismatch(
                "Downloaded resource size ${bytes.size} does not match expected ${descriptor.sizeBytes}"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(descriptor.sha256, ignoreCase = true)) {
            throw SampleLibFailure.IntegrityMismatch("Downloaded resource SHA-256 does not match canonical metadata")
        }
        return DownloadedResource(descriptor, bytes, resumed)
    }

    private fun requireMedia(session: SampleLibSession, capability: String) {
        if (capability !in session.capabilities.media) {
            throw SampleLibFailure.CapabilityUnavailable("media.$capability")
        }
    }

    private fun validateMutation(session: SampleLibSession, mutation: SampleLibMutation) {
        if (mutation.operation.wireName !in session.capabilities.mutations) {
            throw SampleLibFailure.CapabilityUnavailable("mutation.${mutation.operation.wireName}")
        }
        val expectedTypes = when (mutation.operation) {
            MutationOperation.INTERVAL_EDITOR_STATE_REPLACE -> setOf("interval")
            MutationOperation.ASSET_METADATA_PATCH -> setOf("asset")
            MutationOperation.TAG_ENSURE_ATTACH, MutationOperation.TAG_DETACH -> setOf("tag_attachment")
            MutationOperation.ANALYSIS_SUGGESTION_ACCEPT -> setOf("asset", "interval")
        }
        if (mutation.entityType !in expectedTypes) {
            throw IllegalArgumentException("${mutation.operation.wireName} is invalid for ${mutation.entityType}")
        }
        if (mutation.operation == MutationOperation.TAG_ENSURE_ATTACH) {
            require(mutation.baseRevision == null) { "tag.ensure_attach requires base_revision=null" }
        } else {
            require(mutation.baseRevision != null) { "${mutation.operation.wireName} requires an opaque base_revision" }
        }
        validateSecretFreeProvenance(session.credential, mutation.provenance)
    }

    private fun validateSecretFreeProvenance(credential: MobileCredential, provenance: JsonObject) {
        val forbiddenKeyFragments = setOf(
            "authorization", "credential", "pairing_code", "password", "secret", "token",
        )
        fun inspect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val normalized = key.lowercase()
                    require(forbiddenKeyFragments.none { it in normalized }) {
                        "mutation provenance must not contain credential-like fields"
                    }
                    inspect(value)
                }
                is JsonArray -> element.forEach(::inspect)
                is JsonPrimitive -> require(element.contentOrNull != credential.bearerValue()) {
                    "mutation provenance must not contain the mobile credential"
                }
            }
        }
        inspect(provenance)
    }

    private fun HelloAccepted.toSession(
        credential: MobileCredential,
        principalId: String?,
        previousGeneration: String?,
    ): SampleLibSession = SampleLibSession(
        identity = identity,
        principalId = principalId,
        installationId = installationId,
        credential = credential,
        capabilities = capabilities,
        limits = limits,
        requiresReset = previousGeneration != null && previousGeneration != identity.authorityGeneration,
    )
}
