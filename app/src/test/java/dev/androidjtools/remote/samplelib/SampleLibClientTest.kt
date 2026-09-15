package dev.androidjtools.remote.samplelib

import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SampleLibClientTest {
    private val credential = MobileCredential.issued("super-secret-token")
    private val capabilities = ServerCapabilities(
        pull = setOf("asset", "interval"),
        mutations = setOf("asset.metadata.patch", "interval.editor_state.replace"),
        playlists = "unsupported_until_canonical_playlist_migration",
        analysis = setOf("suggestions.read"),
        media = setOf("resource_uri", "content_hash", "range"),
    )
    private val limits = ServerLimits(1000, 100, 7_776_000, 31_536_000)

    @Test
    fun credentialsAndPairingCodesAreRedacted() {
        assertFalse(credential.toString().contains("super-secret-token"))
        val code = PairingCode.entered("482913")
        assertFalse(code.toString().contains("482913"))
    }

    @Test
    fun pairingPinsServerIdentityAndUsesIssuedAuthorization() = runTest {
        val transport = FakeTransport(credential, capabilities, limits)
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.pair(PairingCode.entered("482913"))
        assertEquals("sample-lib-a", session.identity.serverId)
        assertEquals("generation-1", session.identity.authorityGeneration)
        assertEquals("principal-1", session.principalId)
        assertFalse(session.requiresReset)
    }

    @Test
    fun reconnectMarksGenerationChangeAsResetAndRejectsIncrementalCursor() = runTest {
        val transport = FakeTransport(credential, capabilities, limits).apply { generation = "generation-2" }
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a", previousAuthorityGeneration = "generation-1")
        assertTrue(session.requiresReset)
        try {
            client.pull(session, cursor = "opaque:old")
            fail("expected reset requirement")
        } catch (_: SampleLibFailure.ResetRequired) {
            // expected
        }
        val snapshot = client.pull(session, cursor = null)
        assertEquals(PullMode.SNAPSHOT, snapshot.mode)
    }

    @Test
    fun mutationAmbiguityRecoversAuthoritativeReceiptWithoutReplay() = runTest {
        val transport = FakeTransport(credential, capabilities, limits).apply { ambiguousPush = true }
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val mutation = assetMutation("mutation-1")
        transport.receipts[mutation.mutationId] = appliedReceipt(mutation.mutationId)

        val delivery = client.pushWithRecovery(session, mutation)
        assertTrue(delivery is MutationDelivery.Confirmed)
        assertEquals(1, transport.pushCalls)
        assertEquals(1, transport.receiptCalls)
    }

    @Test
    fun mutationAmbiguityWithoutReceiptRemainsPending() = runTest {
        val transport = FakeTransport(credential, capabilities, limits).apply { ambiguousPush = true }
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val mutation = assetMutation("mutation-pending")
        val delivery = client.pushWithRecovery(session, mutation)
        assertTrue(delivery is MutationDelivery.Pending)
        assertEquals(mutation.mutationId, (delivery as MutationDelivery.Pending).mutation.mutationId)

        // A later reconnect reuses the exact stable mutation identity; no replacement intent is minted.
        transport.ambiguousPush = false
        val reconnected = client.connect(credential, "sample-lib-a", previousAuthorityGeneration = "generation-1")
        val retried = client.pushWithRecovery(reconnected, delivery.mutation)
        assertTrue(retried is MutationDelivery.Confirmed)
        assertEquals(mutation.mutationId, (retried as MutationDelivery.Confirmed).receipt.mutationId)
    }

    @Test
    fun unsupportedPlaylistCapabilityFailsExplicitly() = runTest {
        val transport = FakeTransport(credential, capabilities, limits)
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val unsupported = assetMutation("playlist-ish").copy(
            operation = MutationOperation.TAG_ENSURE_ATTACH,
            entityType = "tag_attachment",
            baseRevision = null,
        )
        try {
            client.push(session, listOf(unsupported))
            fail("expected capability error")
        } catch (failure: SampleLibFailure.CapabilityUnavailable) {
            assertTrue(failure.message!!.contains("tag.ensure_attach"))
        }
    }

    @Test
    fun credentialsCannotEnterMutationProvenance() = runTest {
        val transport = FakeTransport(credential, capabilities, limits)
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val leaked = assetMutation("secret-provenance").copy(
            provenance = buildJsonObject { put("note", "super-secret-token") },
        )
        try {
            client.push(session, listOf(leaked))
            fail("expected provenance secret rejection")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.contains("credential"))
        }
        assertEquals(0, transport.pushCalls)
    }

    @Test
    fun rangeResumeVerifiesFinalSizeAndSha256() = runTest {
        val bytes = "deterministic-media-payload".repeat(20).encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val transport = FakeTransport(credential, capabilities, limits).apply { media = bytes }
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val prefix = bytes.copyOfRange(0, 37)
        val result = client.downloadResource(
            session,
            ResourceDescriptor("asset://asset-1/audio", "/v1/resources/audio", hash, bytes.size.toLong(), "audio/wav", "fixture-etag"),
            existingPrefix = prefix,
        )
        assertTrue(result.resumed)
        assertTrue(bytes.contentEquals(result.bytes))
        assertEquals(37L, transport.lastMediaOffset)
    }

    @Test
    fun staleRangeValidatorFallsBackToFullResourceAndStillVerifiesIntegrity() = runTest {
        val bytes = "full-restart-media".repeat(32).encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val transport = FakeTransport(credential, capabilities, limits).apply {
            media = bytes
            returnFullOnRange = true
        }
        val client = SampleLibClient(transport, "android", "install-1")
        val session = client.connect(credential, "sample-lib-a")
        val result = client.downloadResource(
            session,
            ResourceDescriptor("asset://asset-1/audio", "/v1/resources/audio", hash, bytes.size.toLong(), etag = "new-etag"),
            existingPrefix = bytes.copyOfRange(0, 20),
        )
        assertFalse(result.resumed)
        assertTrue(bytes.contentEquals(result.bytes))
    }

    @Test
    fun nonLoopbackPlainHttpIsRejectedBeforeAnyCredentialCanBeSent() {
        try {
            SampleLibHttpTransport("http://192.0.2.10:8765")
            fail("expected insecure transport rejection")
        } catch (_: SampleLibFailure.InsecureTransport) {
            // expected
        }
    }

    private fun assetMutation(id: String) = SampleLibMutation(
        mutationId = id,
        entityType = "asset",
        entityId = "asset-1",
        baseRevision = OpaqueRevision("rev:asset-1:7"),
        operation = MutationOperation.ASSET_METADATA_PATCH,
        payload = buildJsonObject { put("rating", 4) },
        createdAt = Instant.parse("2026-09-15T12:00:00Z"),
        provenance = buildJsonObject { put("source", "unit-test") },
    )

    private fun appliedReceipt(id: String) = MutationReceipt(
        mutationId = id,
        outcome = ReceiptOutcome.APPLIED,
        serverChangeRevision = 8,
        entityRevision = OpaqueRevision("rev:asset-1:8"),
    )

    private class FakeTransport(
        private val credential: MobileCredential,
        private val capabilities: ServerCapabilities,
        private val limits: ServerLimits,
    ) : SampleLibTransport {
        var generation = "generation-1"
        var ambiguousPush = false
        var pushCalls = 0
        var receiptCalls = 0
        val receipts = mutableMapOf<String, MutationReceipt>()
        var media = ByteArray(0)
        var lastMediaOffset = -1L
        var returnFullOnRange = false

        override suspend fun pair(request: PairingRequest): PairingResult = PairingResult(
            "sample-lib-a", "principal-1", request.installationId, credential,
            setOf("sync.read", "sync.write", "media.read"),
        )

        override suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted = HelloAccepted(
            "1", ServerIdentity("sample-lib-a", generation), 7, capabilities, limits,
        )

        override suspend fun pull(
            credential: MobileCredential,
            cursor: String?,
            scopes: Set<String>,
            limit: Int,
        ): PullPage = PullPage(
            if (cursor == null) PullMode.SNAPSHOT else PullMode.INCREMENTAL,
            "opaque:$generation:7", false, emptyList(), emptyList(), generation, 7,
        )

        override suspend fun push(
            credential: MobileCredential,
            clientId: String,
            mutations: List<SampleLibMutation>,
        ): PushResult {
            pushCalls++
            if (ambiguousPush) throw SampleLibFailure.AmbiguousDelivery()
            return PushResult(
                mutations.map {
                    receipts[it.mutationId] ?: MutationReceipt(
                        mutationId = it.mutationId,
                        outcome = ReceiptOutcome.APPLIED,
                        serverChangeRevision = 8,
                        entityRevision = OpaqueRevision("rev:${it.entityType}:${it.entityId}:8"),
                    )
                },
                8,
            )
        }

        override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt {
            receiptCalls++
            return receipts[mutationId] ?: throw SampleLibFailure.ReceiptNotFound(mutationId)
        }

        override suspend fun fetchResource(
            credential: MobileCredential,
            descriptor: ResourceDescriptor,
            offset: Long,
        ): ResourceChunk {
            lastMediaOffset = offset
            return if (offset > 0 && !returnFullOnRange) {
                ResourceChunk(206, media.copyOfRange(offset.toInt(), media.size), offset, media.size.toLong(), descriptor.etag)
            } else {
                ResourceChunk(200, media, null, media.size.toLong(), descriptor.etag)
            }
        }
    }
}
