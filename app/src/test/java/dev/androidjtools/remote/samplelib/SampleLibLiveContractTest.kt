package dev.androidjtools.remote.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.offline.download.DownloadAttemptResult
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheQuotaManager
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadEngine
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.offline.download.RangeResponse
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.sync.samplelib.AssetFiles
import dev.androidjtools.sync.samplelib.FakeCipher
import dev.androidjtools.sync.samplelib.JdbcSqlDatabase
import dev.androidjtools.sync.samplelib.LibraryProjector
import dev.androidjtools.sync.samplelib.SampleLibProfileStore
import dev.androidjtools.sync.samplelib.SampleLibRangeTransport
import dev.androidjtools.sync.samplelib.SampleLibSessionManager
import dev.androidjtools.sync.samplelib.serverAudioSupport
import dev.androidjtools.sync.samplelib.SqlLibraryCache
import dev.androidjtools.sync.samplelib.StoredSessionResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Contract test of the real client code against a real Sample Lib server.
 *
 * Skipped unless SAMPLE_LIB_LIVE_URL and SAMPLE_LIB_LIVE_PAIRING_CODE are set. The server must be
 * a disposable instance seeded with asset-1 / interval-1 / marker-a (the Sample Lib sync fixture),
 * because this test pushes editor mutations. Use scripts/run_live_samplelib_contract.sh, which builds
 * such a server from a clean export of the Sample Lib checkout and tears it down afterwards.
 */
class SampleLibLiveContractTest {
    private val baseUrl = System.getenv("SAMPLE_LIB_LIVE_URL").orEmpty()
    private val pairingCode = System.getenv("SAMPLE_LIB_LIVE_PAIRING_CODE").orEmpty()
    private val installationId = "live-contract-" + UUID.randomUUID()

    private fun client() = SampleLibClient(SampleLibHttpTransport(baseUrl), "android-dj-tools", installationId)

    private fun requireLive() =
        assumeTrue("set SAMPLE_LIB_LIVE_URL and SAMPLE_LIB_LIVE_PAIRING_CODE to run", baseUrl.isNotBlank() && pairingCode.isNotBlank())

    private suspend fun pullAll(client: SampleLibClient, session: SampleLibSession, cursor: String? = null): Pair<List<EntityChange>, PullPage> {
        val changes = mutableListOf<EntityChange>()
        var next = cursor
        while (true) {
            val page = client.pull(session, next, setOf("library"), 100)
            changes += page.changes
            next = page.nextCursor
            if (!page.hasMore) return changes to page
        }
    }

    private fun editorPayload(interval: JsonObject, label: String): JsonObject = buildJsonObject {
        val iv = interval.getValue("interval").jsonObject
        put("start_ms", iv.getValue("start_ms").jsonPrimitive.int)
        put("end_ms", iv.getValue("end_ms").jsonPrimitive.int)
        put("markers", buildJsonArray {
            interval.getValue("markers").jsonArray.forEach { marker ->
                val m = marker.jsonObject
                add(buildJsonObject {
                    put("id", m.getValue("id").jsonPrimitive.content)
                    put("position_ms", m.getValue("position_ms").jsonPrimitive.int)
                    put("label", label)
                    put("marker_type", m.getValue("marker_type").jsonPrimitive.content)
                    put("color", m["color"]?.jsonPrimitive?.content?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                })
            }
        })
        put("comment", "androidjtools live contract test")
    }

    private fun mutation(id: String, base: OpaqueRevision, payload: JsonObject) = SampleLibMutation(
        mutationId = id,
        entityType = "interval",
        entityId = "interval-1",
        baseRevision = base,
        operation = MutationOperation.INTERVAL_EDITOR_STATE_REPLACE,
        payload = payload,
        createdAt = Instant.now(),
        provenance = buildJsonObject { put("source", "androidjtools-live-contract") },
    )

    @Test
    fun pairingNegotiatesCapabilitiesAndPinsServerIdentity() = runBlocking {
        requireLive()
        val client = client()
        val session = client.pair(PairingCode.entered(pairingCode))

        assertTrue(session.identity.serverId.isNotBlank())
        assertTrue(session.identity.authorityGeneration.isNotBlank())
        assertTrue("pull must advertise library families", session.capabilities.pull.containsAll(setOf("asset", "interval", "marker")))
        assertTrue("interval.editor_state.replace" in session.capabilities.mutations)
        assertEquals("canonical_v1", session.capabilities.playlists)
        assertTrue(session.limits.pullPageMax > 0 && session.limits.pushBatchMax > 0)

        val reconnect = client.connect(session.credential, session.identity.serverId, session.identity.authorityGeneration, session.principalId)
        assertEquals(session.identity, reconnect.identity)

        try {
            client.connect(session.credential, "some-other-server")
            fail("a different server identity must be refused")
        } catch (expected: SampleLibFailure.ServerIdentityMismatch) {
        }
    }

    @Test
    fun invalidCredentialIsReportedAsAuthRequired() = runBlocking {
        requireLive()
        try {
            client().connect(MobileCredential.issued("not-a-real-credential"), "irrelevant")
            fail("garbage credential must be rejected")
        } catch (expected: SampleLibFailure.AuthRequired) {
        }
    }

    @Test
    fun snapshotThenIncrementalPullReturnsCanonicalEntities() = runBlocking {
        requireLive()
        val client = client()
        val session = client.pair(PairingCode.entered(pairingCode))

        val (changes, last) = pullAll(client, session)
        val byType = changes.groupBy { it.entityType }
        assertTrue("asset missing: ${byType.keys}", "asset" in byType)
        assertTrue("interval missing: ${byType.keys}", "interval" in byType)
        assertTrue("marker missing: ${byType.keys}", "marker" in byType)
        changes.forEach { assertTrue("${it.entityType} revision must be opaque non-blank", it.revision.value.isNotBlank()) }
        assertEquals(PullMode.SNAPSHOT, client.pull(session, null, setOf("library"), 100).mode)

        val (delta, page) = pullAll(client, session, last.nextCursor)
        assertEquals(PullMode.INCREMENTAL, page.mode)
        assertTrue("nothing changed, so the delta must be empty: ${delta.map { it.entityId }}", delta.isEmpty())
        assertTrue(page.tombstones.isEmpty())
    }

    @Test
    fun editorMutationIsIdempotentRecoverableAndConflictSafe() = runBlocking {
        requireLive()
        val client = client()
        val session = client.pair(PairingCode.entered(pairingCode))
        val (changes, snapshotEnd) = pullAll(client, session)
        val interval = changes.first { it.entityType == "interval" && it.entityId == "interval-1" }
        val label = "live-" + UUID.randomUUID().toString().take(8)

        val first = mutation("android-live-" + UUID.randomUUID(), interval.revision, editorPayload(interval.value, label))
        val applied = (client.pushWithRecovery(session, first) as MutationDelivery.Confirmed).receipt
        assertEquals(ReceiptOutcome.APPLIED, applied.outcome)
        assertEquals(first.mutationId, applied.mutationId)
        val newRevision = applied.entityRevision
        assertTrue("applied receipt must carry the new entity revision", newRevision != null && newRevision != interval.revision)

        val replay = (client.pushWithRecovery(session, first) as MutationDelivery.Confirmed).receipt
        assertEquals("replaying the same mutation must return the original receipt", applied, replay)
        assertEquals(applied, client.lookupReceipt(session, first.mutationId))

        val (delta, _) = pullAll(client, session, snapshotEnd.nextCursor)
        val updated = delta.single { it.entityType == "interval" }
        assertEquals(newRevision, updated.revision)

        val stale = mutation("android-live-" + UUID.randomUUID(), interval.revision, editorPayload(interval.value, "stale-" + label))
        val conflict = (client.pushWithRecovery(session, stale) as MutationDelivery.Confirmed).receipt
        assertEquals(ReceiptOutcome.CONFLICT, conflict.outcome)
        assertEquals("editor_aggregate", conflict.conflict?.mergeClass)
        assertEquals(newRevision, conflict.conflict?.authoritativeRevision)
        assertNotEquals("conflict must not silently overwrite", "stale-$label", updated.value.getValue("markers").jsonArray.first().jsonObject.getValue("label").jsonPrimitive.content)
    }

    @Test
    fun operationsTheServerDoesNotAdvertiseAreRefusedBeforeSending() = runBlocking {
        requireLive()
        val client = client()
        val session = client.pair(PairingCode.entered(pairingCode))
        assertTrue("asset.metadata.patch" !in session.capabilities.mutations)
        val unsupported = SampleLibMutation(
            mutationId = "android-live-" + UUID.randomUUID(),
            entityType = "asset",
            entityId = "asset-1",
            baseRevision = OpaqueRevision("x"),
            operation = MutationOperation.ASSET_METADATA_PATCH,
            payload = buildJsonObject { put("title", "x") },
            createdAt = Instant.now(),
            provenance = JsonObject(emptyMap()),
        )
        try {
            client.push(session, listOf(unsupported))
            fail("unadvertised operation must not be sent")
        } catch (expected: SampleLibFailure.CapabilityUnavailable) {
        }
    }

    @Test
    fun managerPairsSyncsAndProjectsTheRealLibraryThenForgets() = runBlocking {
        requireLive()
        val dir = Files.createTempDirectory("samplelib-live-store").toFile()
        val db = JdbcSqlDatabase()
        try {
            val cache = SqlLibraryCache(db)
            val store = SampleLibProfileStore(dir, FakeCipher())
            val manager = SampleLibSessionManager(store, cache)

            manager.pair(baseUrl, pairingCode)

            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)
            assertTrue("interval.editor_state.replace" in manager.capabilities.value!!.mutations)
            val projection = LibraryProjector.project(cache.entities("asset"), cache.entities("interval"))
            val track = projection.tracks.single { it.id == "asset-1" }
            assertEquals("A", track.title)
            assertEquals("Artist", track.artist)
            assertEquals(3, track.rating)
            val cue = projection.cues.getValue("asset-1").single { it.id == "marker-a" }
            assertEquals(1500L, cue.positionMs)
            assertEquals(0xFF112233L, cue.colorArgb)

            // A second refresh reconnects with the pinned identity and finds nothing new.
            val before = cache.entities("asset").map { it.revision }
            manager.refresh()
            assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
            assertEquals(before, cache.entities("asset").map { it.revision })

            manager.forget()
            assertEquals(StoredSessionResult.Absent, store.load())
            assertTrue(cache.entities("asset").isEmpty())
            assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private class RecordingTransport(private val inner: RangeTransport) : RangeTransport {
        val statuses = mutableListOf<Int>()
        override suspend fun open(request: RangeRequest): RangeResponse = inner.open(request).also { statuses += it.statusCode }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun serverAudioIsAdvertisedProjectedAndDownloadsThroughTheRealMediaRoute() = runBlocking {
        requireLive()
        val dir = Files.createTempDirectory("samplelib-live-audio").toFile()
        val db = JdbcSqlDatabase()
        try {
            val cache = SqlLibraryCache(db)
            val manager = SampleLibSessionManager(SampleLibProfileStore(File(dir, "profile"), FakeCipher()), cache)
            manager.pair(baseUrl, pairingCode)
            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)

            val support = serverAudioSupport(manager.capabilities.value)
            assertTrue("server must advertise asset_file with resource_uri + content_hash", support.advertised)
            assertTrue("server must advertise range", support.range)

            // Only original and preview are exposed; the seeded stem row must not leak.
            val rows = cache.entities("asset_file")
            assertEquals(setOf("file-original", "file-preview"), rows.map { it.entityId }.toSet())
            rows.forEach { assertNotNull("descriptor parses: ${it.entityId}", AssetFiles.parse(it)) }

            val projection = LibraryProjector.project(
                cache.entities("asset"), cache.entities("interval"), rows, audioAdvertised = true,
            )
            val track = projection.tracks.single { it.id == "asset-1" }
            assertTrue(track.playable)
            assertNotNull("streamed tracks are not deck-ready", track.deckBlockedReason)
            val audio = projection.audio.getValue("asset-1")
            assertEquals("file-preview", audio.stream.id)
            assertEquals("file-original", audio.download.id)

            // The media route: 401 without a credential, 404 for the hidden stem and for unknown ids, 416 for an
            // oversize suffix, and never a redirect.
            val authed = newMediaHttpClient(manager)
            val plain = okhttp3.OkHttpClient.Builder().followRedirects(false).build()
            val originalUrl = mediaUrl(baseUrl, "file-original")!!
            plain.newCall(Request.Builder().url(originalUrl).build()).execute().use { assertEquals(401, it.code) }
            authed.newCall(Request.Builder().url(mediaUrl(baseUrl, "file-stem")!!).build()).execute().use { assertEquals(404, it.code) }
            authed.newCall(Request.Builder().url(mediaUrl(baseUrl, "nope")!!).build()).execute().use { assertEquals(404, it.code) }
            authed.newCall(Request.Builder().url(originalUrl).header("Range", "bytes=-99999999").build()).execute()
                .use { assertEquals(416, it.code) }

            // Download the original with the real transport and engine; the hash is re-checked independently here.
            val recording = RecordingTransport(SampleLibRangeTransport(manager, authed))
            val store = OfflineDownloadStore(File(dir, "state"))
            val storage = OfflineCacheStorage(File(dir, "media"))
            val engine = OfflineDownloadEngine(store, storage, OfflineCacheQuotaManager(store, storage, 1L shl 24), recording)
            val d = audio.download
            val asset = OfflineAsset(d.id, "original://${d.id}", d.sha256, d.sizeBytes, advertisedEtag = d.etag)

            assertTrue(engine.download(asset) is DownloadAttemptResult.Success)
            val downloaded = storage.finalFile(d.id).readBytes()
            assertEquals(d.sizeBytes, downloaded.size.toLong())
            assertEquals(d.sha256, sha256(downloaded))
            assertEquals(OfflineDownloadPhase.AVAILABLE, store.get(d.id)?.phase)

            // Resume: a half copy continues with Range + If-Range (the quoted etag round-trips) and verifies.
            store.clear()
            storage.deleteAll()
            val half = downloaded.size / 2
            store.ensure(asset)
            storage.partialFile(d.id).writeBytes(downloaded.copyOfRange(0, half))
            store.update(d.id) { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = half.toLong(), currentEtag = d.etag) }
            recording.statuses.clear()
            assertTrue(engine.download(asset) is DownloadAttemptResult.Success)
            assertEquals(listOf(206), recording.statuses)
            assertArrayEquals(downloaded, storage.finalFile(d.id).readBytes())

            // With the copy verified, the track is offline and deck-ready.
            val offline = LibraryProjector.project(
                cache.entities("asset"), cache.entities("interval"), rows, audioAdvertised = true, downloaded = mapOf(d.id to d.sha256),
            ).tracks.single { it.id == "asset-1" }
            assertTrue(offline.offlineAvailable)
            assertNull(offline.deckBlockedReason)

            // After Forget the credential is gone: the transport refuses before any request.
            manager.forget()
            assertNull(manager.mediaAuth())
            try {
                recording.open(RangeRequest(asset))
                org.junit.Assert.fail("expected IOException after forget")
            } catch (_: java.io.IOException) {
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
