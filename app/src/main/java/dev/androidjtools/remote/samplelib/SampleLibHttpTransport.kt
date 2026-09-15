package dev.androidjtools.remote.samplelib

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class SampleLibHttpTransport(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) : SampleLibTransport {
    private val base: HttpUrl = baseUrl.ensureTrailingSlash().toHttpUrl()
    private val json = Json { ignoreUnknownKeys = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    init {
        val loopback = base.host == "127.0.0.1" || base.host == "localhost" || base.host == "::1"
        if (!loopback && !base.isHttps) throw SampleLibFailure.InsecureTransport(base.host)
    }

    override suspend fun pair(request: PairingRequest): PairingResult {
        val body = buildJsonObject {
            put("type", "pairing_request")
            put("client_id", request.clientId)
            put("installation_id", request.installationId)
            put("pairing_code", request.pairingCode.reveal())
        }
        val response = postJson("/v1/sync/pair", body, credential = null, ambiguousOnIo = false)
        val obj = response.requireObject()
        if (obj.string("type") != "pairing_accepted") {
            throw SampleLibFailure.InvalidResponse("Pairing endpoint returned an unexpected response type")
        }
        return PairingResult(
            serverId = obj.string("server_id"),
            principalId = obj.string("principal_id"),
            installationId = obj.string("installation_id"),
            credential = MobileCredential.issued(obj.string("access_token")),
            scopes = obj.stringSet("scopes"),
        )
    }

    override suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted {
        val body = buildJsonObject {
            put("type", "hello_request")
            put("protocol_versions", strings(request.protocolVersions))
            put("client_id", request.clientId)
            put("installation_id", request.installationId)
            put("capabilities", strings(request.capabilities.sorted()))
        }
        val obj = postJson("/v1/sync/hello", body, credential, ambiguousOnIo = false).requireObject()
        if (obj.string("type") != "hello_accepted") {
            throw SampleLibFailure.InvalidResponse("Hello endpoint did not return hello_accepted")
        }
        val caps = obj.objectValue("capabilities")
        val limits = obj.objectValue("limits")
        return HelloAccepted(
            protocolVersion = obj.string("protocol_version"),
            identity = ServerIdentity(obj.string("server_id"), obj.string("authority_generation")),
            serverChangeRevision = obj.longValue("server_change_revision"),
            capabilities = ServerCapabilities(
                pull = caps.stringSet("pull"),
                mutations = caps.stringSet("mutations"),
                playlists = caps.string("playlists"),
                analysis = caps.stringSet("analysis"),
                media = caps.stringSet("media"),
            ),
            limits = ServerLimits(
                pullPageMax = limits.intValue("pull_page_max"),
                pushBatchMax = limits.intValue("push_batch_max"),
                tombstoneRetentionSeconds = limits.longValue("tombstone_retention_seconds"),
                mutationReplayHorizonSeconds = limits.longValue("mutation_replay_horizon_seconds"),
            ),
        )
    }

    override suspend fun pull(
        credential: MobileCredential,
        cursor: String?,
        scopes: Set<String>,
        limit: Int,
    ): PullPage {
        val body = buildJsonObject {
            put("type", "pull_request")
            put("protocol_version", "1")
            put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull)
            put("scopes", strings(scopes.sorted()))
            put("limit", limit)
        }
        val obj = postJson("/v1/sync/pull", body, credential, ambiguousOnIo = false).requireObject()
        if (obj.string("type") != "pull_response") {
            throw SampleLibFailure.InvalidResponse("Pull endpoint did not return pull_response")
        }
        val changes = obj.arrayValue("changes").map { element ->
            val item = element.jsonObject
            EntityChange(
                entityType = item.string("entity_type"),
                entityId = item.string("entity_id"),
                revision = OpaqueRevision(item.string("revision")),
                schema = item.string("schema"),
                value = item.objectValue("value"),
            )
        }
        val tombstones = obj.arrayValue("tombstones").map { element ->
            val item = element.jsonObject
            Tombstone(
                entityType = item.string("entity_type"),
                entityId = item.string("entity_id"),
                revision = OpaqueRevision(item.string("revision")),
                deletedAt = Instant.parse(item.string("deleted_at")),
            )
        }
        return PullPage(
            mode = when (obj.string("mode")) {
                "snapshot" -> PullMode.SNAPSHOT
                "incremental" -> PullMode.INCREMENTAL
                else -> throw SampleLibFailure.InvalidResponse("Unknown pull mode")
            },
            nextCursor = obj.string("next_cursor"),
            hasMore = obj.booleanValue("has_more"),
            changes = changes,
            tombstones = tombstones,
            authorityGeneration = obj.string("authority_generation"),
            serverChangeRevision = obj.longValue("server_change_revision"),
        )
    }

    override suspend fun push(
        credential: MobileCredential,
        clientId: String,
        mutations: List<SampleLibMutation>,
    ): PushResult {
        val body = buildJsonObject {
            put("type", "push_request")
            put("protocol_version", "1")
            put("client_id", clientId)
            put("mutations", buildJsonArray { mutations.forEach { add(encodeMutation(it)) } })
        }
        val obj = postJson("/v1/sync/push", body, credential, ambiguousOnIo = true).requireObject()
        if (obj.string("type") != "push_response") {
            throw SampleLibFailure.InvalidResponse("Push endpoint did not return push_response")
        }
        return PushResult(
            receipts = obj.arrayValue("receipts").map(::decodeReceipt),
            serverChangeRevision = obj.longValue("server_change_revision"),
        )
    }

    override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt {
        val url = base.newBuilder()
            .addPathSegments("v1/sync/receipts")
            .addPathSegment(mutationId)
            .build()
        val obj = executeJson(
            Request.Builder().url(url).header("Authorization", "Bearer ${credential.bearerValue()}").get().build(),
            ambiguousOnIo = false,
        ).requireObject()
        if (obj.string("type") != "receipt_lookup_response") {
            throw SampleLibFailure.InvalidResponse("Receipt endpoint returned an unexpected response type")
        }
        return decodeReceipt(obj.objectValue("receipt"))
    }

    override suspend fun fetchResource(
        credential: MobileCredential,
        descriptor: ResourceDescriptor,
        offset: Long,
    ): ResourceChunk = withContext(Dispatchers.IO) {
        val url = resolveResource(descriptor.fetchPath)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.bearerValue()}")
            .apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    descriptor.etag?.let { header("If-Range", it) }
                }
            }
            .get()
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (exc: IOException) {
            throw SampleLibFailure.Offline(exc)
        }
        response.use {
            mapHttpFailure(it, isPairing = false)
            val bytes = it.body?.bytes() ?: ByteArray(0)
            ResourceChunk(
                statusCode = it.code,
                bytes = bytes,
                contentRangeStart = parseContentRangeStart(it.header("Content-Range")),
                totalSize = parseTotalSize(it),
                etag = it.header("ETag"),
            )
        }
    }

    private suspend fun postJson(
        path: String,
        payload: JsonObject,
        credential: MobileCredential?,
        ambiguousOnIo: Boolean,
    ): JsonElement {
        val url = base.resolve(path) ?: throw SampleLibFailure.InvalidResponse("Invalid Sample Lib endpoint path")
        val request = Request.Builder()
            .url(url)
            .apply { credential?.let { header("Authorization", "Bearer ${it.bearerValue()}") } }
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return executeJson(request, ambiguousOnIo, isPairing = credential == null && path.endsWith("/pair"))
    }

    private suspend fun executeJson(
        request: Request,
        ambiguousOnIo: Boolean,
        isPairing: Boolean = false,
    ): JsonElement = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (exc: IOException) {
            if (ambiguousOnIo) throw SampleLibFailure.AmbiguousDelivery(exc)
            throw SampleLibFailure.Offline(exc)
        }
        response.use {
            val raw = it.body?.string().orEmpty()
            val parsed = raw.takeIf(String::isNotBlank)?.let { body ->
                try {
                    json.parseToJsonElement(body)
                } catch (exc: Exception) {
                    throw SampleLibFailure.InvalidResponse("Sample Lib returned malformed JSON")
                }
            }
            if (!it.isSuccessful) {
                mapHttpFailure(it, parsed, isPairing)
            }
            parsed ?: throw SampleLibFailure.InvalidResponse("Sample Lib returned an empty JSON response")
        }
    }

    private fun mapHttpFailure(response: Response, parsed: JsonElement? = null, isPairing: Boolean = false) {
        if (response.isSuccessful) return
        val obj = parsed as? JsonObject
        val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
        val error = obj?.get("error") as? JsonObject
            ?: (obj?.get("detail") as? JsonObject)?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        when {
            response.code == 401 && isPairing -> throw SampleLibFailure.PairingRejected()
            response.code == 401 || response.code == 403 -> throw SampleLibFailure.AuthRequired()
            response.code == 404 && response.request.url.encodedPath.contains("/receipts/") -> {
                throw SampleLibFailure.ReceiptNotFound(response.request.url.pathSegments.last())
            }
            response.code == 426 || type == "hello_incompatible" -> {
                throw SampleLibFailure.Incompatible(
                    requested = obj?.stringListOrEmpty("requested_versions") ?: emptyList(),
                    supported = obj?.stringListOrEmpty("supported_versions") ?: emptyList(),
                )
            }
            response.code == 409 && (type == "pull_cursor_expired" || code == "cursor_expired") -> {
                throw SampleLibFailure.CursorExpired(obj?.get("authority_generation")?.jsonPrimitive?.contentOrNull.orEmpty())
            }
            response.code >= 500 -> throw SampleLibFailure.Unavailable()
            else -> throw SampleLibFailure.InvalidResponse("Sample Lib HTTP ${response.code}${code?.let { ": $it" }.orEmpty()}")
        }
    }

    private fun encodeMutation(mutation: SampleLibMutation): JsonObject = buildJsonObject {
        put("mutation_id", mutation.mutationId)
        put("entity_type", mutation.entityType)
        put("entity_id", mutation.entityId)
        put("base_revision", mutation.baseRevision?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("operation", mutation.operation.wireName)
        put("payload", mutation.payload)
        put("created_at", mutation.createdAt.toString())
        put("provenance", mutation.provenance)
    }

    private fun decodeReceipt(element: JsonElement): MutationReceipt {
        val obj = element.jsonObject
        val outcome = when (obj.string("outcome")) {
            "applied" -> ReceiptOutcome.APPLIED
            "no_op" -> ReceiptOutcome.NO_OP
            "rejected" -> ReceiptOutcome.REJECTED
            "conflict" -> ReceiptOutcome.CONFLICT
            else -> throw SampleLibFailure.InvalidResponse("Unknown mutation receipt outcome")
        }
        val error = (obj["error"] as? JsonObject)?.let { MutationError(it.string("code"), it.string("message")) }
        val conflict = (obj["conflict"] as? JsonObject)?.let {
            MutationConflict(
                mutationId = it.string("mutation_id"),
                entityId = it.string("entity_id"),
                baseRevision = it.optionalRevision("base_revision"),
                authoritativeRevision = it.optionalRevision("authoritative_revision"),
                localValue = it["local_value"] as? JsonObject,
                remoteValue = it["remote_value"] as? JsonObject,
                mergeClass = it.string("merge_class"),
                code = it.string("code"),
            )
        }
        return MutationReceipt(
            mutationId = obj.string("mutation_id"),
            outcome = outcome,
            serverChangeRevision = obj.longValue("server_change_revision"),
            entityRevision = obj.optionalRevision("entity_revision"),
            canonical = obj["canonical"] as? JsonObject,
            error = error,
            conflict = conflict,
        )
    }

    private fun resolveResource(fetchPath: String): HttpUrl {
        val candidate = fetchPath.toHttpUrlOrNullCompat()
        val resolved = candidate ?: base.resolve(fetchPath)
            ?: throw SampleLibFailure.InvalidResponse("Invalid Sample Lib resource fetch path")
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) {
            throw SampleLibFailure.InvalidResponse("Resource fetch URL must not contain credentials")
        }
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port) {
            throw SampleLibFailure.InvalidResponse("Resource fetch URL must remain on the paired Sample Lib origin")
        }
        return resolved
    }

    private fun parseContentRangeStart(value: String?): Long? {
        if (value == null || !value.startsWith("bytes ")) return null
        return value.removePrefix("bytes ").substringBefore('-').toLongOrNull()
    }

    private fun parseTotalSize(response: Response): Long? {
        val range = response.header("Content-Range")
        val fromRange = range?.substringAfterLast('/')?.toLongOrNull()
        return fromRange ?: response.header("Content-Length")?.toLongOrNull()
    }

    private fun strings(values: Iterable<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun JsonElement.requireObject(): JsonObject = this as? JsonObject
        ?: throw SampleLibFailure.InvalidResponse("Sample Lib response must be a JSON object")

    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: throw SampleLibFailure.InvalidResponse("Missing or blank field: $name")

    private fun JsonObject.longValue(name: String): Long = try {
        this[name]?.jsonPrimitive?.long ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        throw SampleLibFailure.InvalidResponse("Invalid integer field: $name")
    }

    private fun JsonObject.intValue(name: String): Int = try {
        this[name]?.jsonPrimitive?.int ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        throw SampleLibFailure.InvalidResponse("Invalid integer field: $name")
    }

    private fun JsonObject.booleanValue(name: String): Boolean = when (this[name]?.jsonPrimitive?.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> throw SampleLibFailure.InvalidResponse("Invalid boolean field: $name")
    }

    private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject
        ?: throw SampleLibFailure.InvalidResponse("Missing object field: $name")

    private fun JsonObject.arrayValue(name: String): JsonArray = this[name] as? JsonArray
        ?: throw SampleLibFailure.InvalidResponse("Missing array field: $name")

    private fun JsonObject.stringSet(name: String): Set<String> = stringListOrEmpty(name).toSet()

    private fun JsonObject.stringListOrEmpty(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonObject.optionalRevision(name: String): OpaqueRevision? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val value = element.jsonPrimitive.contentOrNull ?: return null
        return OpaqueRevision(value)
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

private fun String.toHttpUrlOrNullCompat(): HttpUrl? = try {
    if (startsWith("http://") || startsWith("https://")) toHttpUrl() else null
} catch (_: IllegalArgumentException) {
    null
}
