package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.HelloAccepted
import dev.androidjtools.remote.samplelib.HelloRequest
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.PairingRequest
import dev.androidjtools.remote.samplelib.PairingResult
import dev.androidjtools.remote.samplelib.PullMode
import dev.androidjtools.remote.samplelib.PullPage
import dev.androidjtools.remote.samplelib.PushResult
import dev.androidjtools.remote.samplelib.ResourceChunk
import dev.androidjtools.remote.samplelib.ResourceDescriptor
import dev.androidjtools.remote.samplelib.SampleLibMutation
import dev.androidjtools.remote.samplelib.SampleLibTransport
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.ServerIdentity
import dev.androidjtools.remote.samplelib.ServerLimits
import dev.androidjtools.remote.samplelib.Tombstone
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deterministic in-memory server for sync tests: pull pages and failures are queued by the test. */
class ScriptedTransport : SampleLibTransport {
    var serverId = "server-1"
    var generation = "gen-1"
    var capabilities = ServerCapabilities(
        pull = setOf("asset", "interval", "marker"),
        mutations = setOf("interval.editor_state.replace"),
        playlists = "canonical_v1",
        analysis = emptySet(),
        media = setOf("resource_uri", "content_hash", "range"),
    )
    var limits = ServerLimits(500, 100, 7_776_000, 31_536_000)
    var pairFailure: Throwable? = null
    var helloFailure: Throwable? = null
    var pairCalls = 0
    var helloCalls = 0
    val pullCursors = mutableListOf<String?>()
    var onPull: (suspend () -> Unit)? = null
    private val script = ArrayDeque<() -> PullPage>()

    fun enqueuePage(page: PullPage) {
        script.addLast { page }
    }

    fun enqueueFailure(failure: Throwable) {
        script.addLast { throw failure }
    }

    override suspend fun pair(request: PairingRequest): PairingResult {
        pairCalls++
        pairFailure?.let { throw it }
        return PairingResult(serverId, "principal-1", request.installationId, MobileCredential.issued("token-secret"), setOf("sync.read"))
    }

    override suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted {
        helloCalls++
        helloFailure?.let { throw it }
        return HelloAccepted("1", ServerIdentity(serverId, generation), 1L, capabilities, limits)
    }

    override suspend fun pull(credential: MobileCredential, cursor: String?, scopes: Set<String>, limit: Int): PullPage {
        pullCursors += cursor
        onPull?.invoke()
        val next = script.removeFirstOrNull() ?: error("no scripted pull page")
        return next()
    }

    override suspend fun push(credential: MobileCredential, clientId: String, mutations: List<SampleLibMutation>): PushResult =
        error("push is not scripted")

    override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt =
        error("receipt is not scripted")

    override suspend fun fetchResource(credential: MobileCredential, descriptor: ResourceDescriptor, offset: Long): ResourceChunk =
        error("fetchResource is not scripted")
}

fun page(
    next: String,
    hasMore: Boolean = false,
    changes: List<EntityChange> = emptyList(),
    tombstones: List<Tombstone> = emptyList(),
    mode: PullMode = PullMode.SNAPSHOT,
    generation: String = "gen-1",
) = PullPage(mode, next, hasMore, changes, tombstones, generation, 1L)

fun assetChange(id: String, title: String = id, revision: String = "r1") =
    EntityChange(
        "asset",
        id,
        OpaqueRevision(revision),
        "sample-lib.asset/v1",
        buildJsonObject {
            put("title", title)
            put("duration_ms", 1000)
        },
    )

fun tombstoneOf(type: String, id: String) = Tombstone(type, id, OpaqueRevision("t"), Instant.EPOCH)
