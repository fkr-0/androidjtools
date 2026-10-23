package dev.androidjtools.remote.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.EditAvailability
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
import dev.androidjtools.prep.cues.CueEditorState
import dev.androidjtools.prep.cues.PreparationStageResult
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalEditOutbox
import dev.androidjtools.sync.journal.JournalMutationStager
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalReceiptOutcome
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import dev.androidjtools.core.provider.AnalysisActionResult
import dev.androidjtools.core.provider.AnalysisRequestAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.sync.analysis.SampleLibAnalysisProvider
import dev.androidjtools.sync.samplelib.ANALYSIS_SUGGESTION_ENTITY
import dev.androidjtools.sync.samplelib.AnalysisSuggestionRecord
import dev.androidjtools.sync.samplelib.AnalysisSuggestions
import dev.androidjtools.sync.samplelib.CachedEntity
import dev.androidjtools.sync.samplelib.InMemorySuggestionDismissals
import dev.androidjtools.sync.samplelib.SuggestionData
import dev.androidjtools.sync.samplelib.SuggestionWireKind
import dev.androidjtools.ui.waveform.EditBuild
import dev.androidjtools.ui.waveform.ServerEditPlanner
import dev.androidjtools.sync.samplelib.AssetFiles
import dev.androidjtools.sync.samplelib.LibraryProjection
import dev.androidjtools.sync.samplelib.SampleLibServerEditProvider
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

    private suspend fun pullAll(
        client: SampleLibClient,
        session: SampleLibSession,
        cursor: String? = null,
        scopes: Set<String> = setOf("library"),
    ): Pair<List<EntityChange>, PullPage> {
        val changes = mutableListOf<EntityChange>()
        var next = cursor
        while (true) {
            val page = client.pull(session, next, scopes, 100)
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
    fun serverPlaylistsArePulledAndProjectedReadOnlyInPositionOrder() = runBlocking {
        requireLive()
        val dir = Files.createTempDirectory("samplelib-live-playlists").toFile()
        val db = JdbcSqlDatabase()
        try {
            val cache = SqlLibraryCache(db)
            val manager = SampleLibSessionManager(SampleLibProfileStore(dir, FakeCipher()), cache)
            manager.pair(baseUrl, pairingCode)
            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)
            assertEquals("canonical_v1", manager.capabilities.value!!.playlists)

            val snap = cache.snapshot("asset", "interval", "playlist", "playlist_item")
            val projection = LibraryProjector.project(
                snap.getValue("asset"), snap.getValue("interval"),
                playlists = snap.getValue("playlist"), playlistItems = snap.getValue("playlist_item"),
            )
            val warmup = projection.playlists.single { it.name == "Warmup" }
            assertEquals(listOf("Sets"), warmup.crate)
            // The seed inserts position 1 before position 0, so only the explicit position gives this order.
            assertEquals(listOf("asset-2", "asset-1"), warmup.trackIds)
            assertTrue(warmup.readOnly)
            assertTrue("a folder is a crate, not a playlist", projection.playlists.none { it.name == "Sets" })
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    // ---- S3: editing through the journal against the real server ----

    private class MemoryJournalStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private inner class EditHarness {
        val dir: File = Files.createTempDirectory("samplelib-live-edits").toFile()
        val db = JdbcSqlDatabase()
        val cache = SqlLibraryCache(db)
        val journal = DurableMutationJournal(MemoryJournalStorage())
        val manager = SampleLibSessionManager(SampleLibProfileStore(dir, FakeCipher()), cache, outbox = JournalEditOutbox(journal))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = SampleLibServerEditProvider(
            lazyOf(journal), MutableStateFlow(LibraryProjection.EMPTY), manager.capabilities, manager.state,
            requestFlush = {}, ioDispatcher = Dispatchers.Unconfined, scope = scope,
        )

        fun library() = LibraryProjector.project(cache.entities("asset"), cache.entities("interval"))
        fun intervalRevision() = cache.entities("interval").single { it.entityId == "interval-1" }.revision

        /** A cue rename built exactly as the Preparation screen builds it: the editor, then the journal stager. */
        fun stageRename(newLabel: String, mutationId: String = "android-live-" + UUID.randomUUID()): String {
            val library = library()
            val cues = library.cues.getValue("asset-1")
            val editor = CueEditorState.fromDomain("asset-1", 10_000L, library.aggregate("interval-1")!!, cues)
                .rename(cues.first { it.id == "marker-a" }.id, newLabel).editor
            val result = editor.stage(mutationId, System.currentTimeMillis(), JournalMutationStager(journal))
            assertTrue("staging failed: $result", result is PreparationStageResult.Queued)
            return mutationId
        }

        fun cueLabel() = library().cues.getValue("asset-1").first { it.id == "marker-a" }.label

        fun close() {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    /** Another installation changes the interval behind our back, so our cached revision goes stale. */
    private suspend fun changeIntervalFromAnotherDevice(label: String) {
        val other = SampleLibClient(SampleLibHttpTransport(baseUrl), "android-dj-tools", "other-device-" + UUID.randomUUID())
        val session = other.pair(PairingCode.entered(pairingCode))
        val (changes, _) = pullAll(other, session)
        val interval = changes.last { it.entityType == "interval" && it.entityId == "interval-1" }
        val receipt = (other.pushWithRecovery(session, mutation("android-other-" + UUID.randomUUID(), interval.revision, editorPayload(interval.value, label))) as MutationDelivery.Confirmed).receipt
        assertEquals(ReceiptOutcome.APPLIED, receipt.outcome)
    }

    @Test
    fun aQueuedCueEditIsDeliveredOnRefreshAndOnlyThenShowsInTheLibrary() = runBlocking {
        requireLive()
        val h = EditHarness()
        try {
            h.manager.pair(baseUrl, pairingCode)
            assertEquals(h.manager.error.value, SyncState.ONLINE_IDLE, h.manager.state.value)
            assertEquals(EditAvailability.Available, h.provider.availability.value)
            val before = h.intervalRevision()
            val label = "live-edit-" + UUID.randomUUID().toString().take(8)

            val id = h.stageRename(label)

            // Queued is not effective: the library still shows the server's version.
            assertEquals(JournalMutationState.PENDING, h.journal.mutation(id)?.state)
            assertNotEquals(label, h.cueLabel())

            h.manager.refresh()

            assertEquals(SyncState.ONLINE_IDLE, h.manager.state.value)
            assertEquals(JournalReceiptOutcome.APPLIED, h.journal.mutation(id)?.receipt?.outcome)
            assertNotEquals("the server must have produced a new revision", before, h.intervalRevision())
            assertEquals(label, h.cueLabel())
            // The server's own vocabulary and colour format survived the round trip.
            val cue = h.library().cues.getValue("asset-1").first { it.id == "marker-a" }
            assertEquals("slice", cue.role)
            assertEquals(0xFF112233L, cue.colorArgb)
        } finally {
            h.close()
        }
    }

    @Test
    fun aStaleEditIsAConflictAndReapplyingTheDraftThenSucceeds() = runBlocking {
        requireLive()
        val h = EditHarness()
        try {
            h.manager.pair(baseUrl, pairingCode)
            val mine = "mine-" + UUID.randomUUID().toString().take(8)
            val theirs = "theirs-" + UUID.randomUUID().toString().take(8)
            val id = h.stageRename(mine)
            changeIntervalFromAnotherDevice(theirs)

            h.manager.refresh()

            val conflicted = h.journal.mutation(id)!!
            assertEquals(JournalMutationState.CONFLICT, conflicted.state)
            assertEquals("editor_aggregate", conflicted.receipt?.conflict?.mergeClass)
            // Never a silent overwrite: the library now shows the other device's version.
            assertEquals(theirs, h.cueLabel())

            val reapplied = h.provider.reapplyDraft(id)
            val newId = (reapplied as PreparationStageResult.Queued).mutationId
            assertEquals(JournalMutationState.RESOLVED, h.journal.mutation(id)?.state)
            h.manager.refresh()

            assertEquals(JournalReceiptOutcome.APPLIED, h.journal.mutation(newId)?.receipt?.outcome)
            assertEquals(mine, h.cueLabel())
        } finally {
            h.close()
        }
    }

    @Test
    fun useServerStateDropsTheLocalEditAndKeepsTheServersVersion() = runBlocking {
        requireLive()
        val h = EditHarness()
        try {
            h.manager.pair(baseUrl, pairingCode)
            val mine = "mine-" + UUID.randomUUID().toString().take(8)
            val theirs = "theirs-" + UUID.randomUUID().toString().take(8)
            val id = h.stageRename(mine)
            changeIntervalFromAnotherDevice(theirs)
            h.manager.refresh()
            assertEquals(JournalMutationState.CONFLICT, h.journal.mutation(id)?.state)

            assertTrue(h.provider.useServerState(id))
            h.manager.refresh()

            assertEquals(JournalMutationState.RESOLVED, h.journal.mutation(id)?.state)
            assertTrue(h.journal.replayBatch().isEmpty())
            assertEquals(theirs, h.cueLabel())
        } finally {
            h.close()
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

    // ---- S5: analysis suggestions against the real server ----
    //
    // Assumptions about the server (see scripts/seed_live_samplelib.py, which persists the runs):
    //  - hello advertises analysis [tempo, key, beat_grid, cue_candidates] and analysis_suggestion.accept; analysis_request
    //    is "enqueue_v1" or "unavailable" (it depends on a configured worker, so both are accepted);
    //  - the "analysis" pull scope needs analysis.read (granted by pairing) and is not part of "library";
    //  - five seeded tracks asset-{analysis,accept-a,accept-b,replace,cues} each have a full-length interval
    //    interval-<name> and the three runs described in the seed; suggestion ids are "<run>:<kind>:<asset id>";
    //  - tempo alternatives are bpm/2 and bpm*2; the key run records no confidence; tempo carries the candidate's 0.7;
    //  - an accept of a value the interval already holds is a no_op; its receipt's entity_revision is the suggestion's
    //    revision and the interval's new revision is canonical.revision (the app reads neither);
    //  - POST /v1/analysis/runs persists a completed run that replaces the suggestion (new id, old id tombstoned).

    private inner class AnalysisHarness {
        val dir: File = Files.createTempDirectory("samplelib-live-analysis").toFile()
        val db = JdbcSqlDatabase()
        val cache = SqlLibraryCache(db)
        val journal = DurableMutationJournal(MemoryJournalStorage())
        val manager = SampleLibSessionManager(SampleLibProfileStore(dir, FakeCipher()), cache, outbox = JournalEditOutbox(journal))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val projection = MutableStateFlow(LibraryProjection.EMPTY)
        val dismissals = InMemorySuggestionDismissals()
        var flushes = 0
        val provider = SampleLibAnalysisProvider(
            projection = projection,
            capabilities = manager.capabilities,
            syncState = manager.state,
            lazyJournal = lazyOf(journal),
            dismissals = dismissals,
            requester = manager,
            requestFlush = { flushes++ },
            ioDispatcher = Dispatchers.Unconfined,
            scope = scope,
        )

        fun library(): LibraryProjection = LibraryProjector.project(
            cache.entities("asset"), cache.entities("interval"),
            analysisSuggestions = if (manager.capabilities.value?.analysis.orEmpty().isNotEmpty()) cache.entities(ANALYSIS_SUGGESTION_ENTITY) else emptyList(),
        ).also { projection.value = it }

        suspend fun pair() {
            manager.pair(baseUrl, pairingCode)
            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)
            library()
        }

        /** The user's refresh: deliver the journal, then pull; the projection follows the cache only. */
        suspend fun refresh(): LibraryProjection {
            manager.refresh()
            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)
            return library()
        }

        fun suggestion(track: String, kind: SuggestionWireKind): AnalysisSuggestionRecord =
            library().suggestionsOf("asset-$track").single { it.kind == kind }

        fun trackOf(name: String) = library().tracks.single { it.id == "asset-$name" }

        fun close() {
            scope.cancel()
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun helloAdvertisesTheAnalysisKindsTheAcceptOperationAndSaysWhetherAnalysisCanBeRequested() = runBlocking {
        requireLive()
        val session = client().pair(PairingCode.entered(pairingCode))
        val caps = session.capabilities

        assertTrue("kinds: ${caps.analysis}", caps.analysis.containsAll(setOf("tempo", "key", "beat_grid", "cue_candidates")))
        assertTrue("analysis_suggestion.accept" in caps.mutations)
        assertTrue("analysis_request: ${caps.analysisRequest}", caps.analysisRequest == "enqueue_v1" || caps.analysisRequest == "unavailable")
        assertEquals(caps.analysisRequest == "enqueue_v1", caps.canRequestAnalysis)
        assertTrue("pairing grants analysis.read: ${session.grantedScopes}", session.grantedScopes.orEmpty().contains("analysis.read"))
    }

    @Test
    fun theAnalysisScopeCarriesSuggestionsInTheDocumentedShapeAndTheLibraryScopeDoesNot() = runBlocking {
        requireLive()
        val client = client()
        val session = client.pair(PairingCode.entered(pairingCode))

        val (library, _) = pullAll(client, session)
        assertTrue("the library scope carries no suggestions", library.none { it.entityType == "analysis_suggestion" })

        val (both, _) = pullAll(client, session, scopes = setOf("library", "analysis"))
        assertTrue(both.any { it.entityType == "interval" })
        val raw = both.filter { it.entityType == "analysis_suggestion" && it.value.getValue("asset_id").jsonPrimitive.content == "asset-analysis" }
        assertEquals(4, raw.size)
        raw.forEach {
            assertEquals("sample-lib.analysis-suggestion/v1", it.schema)
            assertTrue(it.revision.value.isNotBlank())
            assertEquals(it.entityId, it.value.getValue("id").jsonPrimitive.content)
        }

        val byKind = raw.mapNotNull { AnalysisSuggestions.parse(CachedEntity(it.entityType, it.entityId, it.revision.value, it.schema, it.value.toString())) }
            .associateBy { it.kind }
        assertEquals(4, byKind.size)
        assertEquals("analysis-tempo:tempo:asset-analysis", byKind.getValue(SuggestionWireKind.TEMPO).id)
        assertEquals(SuggestionData.Tempo(128.0, listOf(64.0, 256.0)), byKind.getValue(SuggestionWireKind.TEMPO).data)
        assertEquals(0.7, byKind.getValue(SuggestionWireKind.TEMPO).confidence!!, 1e-9)
        assertEquals("tempo-candidates", byKind.getValue(SuggestionWireKind.TEMPO).tool)
        assertEquals("analysis-tempo", byKind.getValue(SuggestionWireKind.TEMPO).analysisRunId)
        assertNull("asset-level, not interval-scoped", byKind.getValue(SuggestionWireKind.TEMPO).intervalId)
        assertEquals(SuggestionData.BeatGrid(128.0, 100L, listOf(100L, 569L, 1038L, 1507L)), byKind.getValue(SuggestionWireKind.BEAT_GRID).data)
        assertEquals(SuggestionData.Key("A minor", "A", "minor"), byKind.getValue(SuggestionWireKind.KEY).data)
        assertNull("the run recorded no key confidence", byKind.getValue(SuggestionWireKind.KEY).confidence)
        assertEquals(SuggestionData.CueCandidates(listOf(1000L, 2500L, 4000L)), byKind.getValue(SuggestionWireKind.CUE_CANDIDATES).data)
        assertNotNull(byKind.getValue(SuggestionWireKind.TEMPO).createdAt)
    }

    @Test
    fun suggestionsAreCachedAndProjectedPerTrackAndTheLibraryItselfIsUntouched() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()

            val suggestions = h.provider.suggestions("asset-analysis").value
            assertEquals(4, suggestions.size)
            assertTrue(h.library().suggestionsOf("asset-1").isEmpty())
            assertNull("a suggestion never changes the library", h.trackOf("analysis").bpm)
            assertNull(h.trackOf("analysis").key)
            assertEquals("interval-analysis", h.library().fullSpanIntervalOf("asset-analysis"))
            assertNull(suggestions.single { it.kind == dev.androidjtools.core.model.SuggestionKind.KEY }.confidence)
            assertTrue(h.provider.capabilities.value.all { it.available })
        } finally {
            h.close()
        }
    }

    @Test
    fun analysisCanOnlyBeRequestedAsAdvertisedAndCachedResultsStartNothing() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val offered = h.manager.capabilities.value!!.canRequestAnalysis

            assertEquals(
                if (offered) AnalysisRequestAvailability.Offered else AnalysisRequestAvailability.Unavailable("This server cannot analyse tracks on request."),
                h.provider.requestAvailability.value,
            )
            val result = h.provider.requestAnalysis("asset-analysis")
            if (offered) {
                // Every kind of this track is already persisted, so the server answers cached and starts no job.
                assertTrue(result.toString(), (result as AnalysisActionResult.Done).message.contains("already exist"))
            } else {
                assertTrue("the app asks nothing of a server that did not offer it", result is AnalysisActionResult.Refused)
            }

            // The route itself, called directly: unavailable is a normal answer, not an error; an unknown asset is a typed 404.
            val transport = SampleLibHttpTransport(baseUrl)
            val credential = (h.manager.mediaAuth() != null).let { client().pair(PairingCode.entered(pairingCode)).credential }
            // Results that already exist are answered cached whatever the mode: nothing needs a provider.
            val direct = transport.requestAnalysis(credential, "asset-analysis", null, force = false)
            assertEquals(AnalysisRequestStatus.CACHED, direct.status)
            assertTrue(direct.kinds.values.all { it == AnalysisRequestStatus.CACHED } && direct.jobId == null)
            if (!offered) {
                // A track nothing was computed for, on a server with no worker: unavailable is a normal 200 answer with
                // a message. (Not asked when the server does offer it: that would genuinely queue analysis.)
                val none = transport.requestAnalysis(credential, "asset-2", null, force = false)
                assertEquals(AnalysisRequestStatus.UNAVAILABLE, none.status)
                assertTrue(none.message.orEmpty().isNotBlank())
            }
            try {
                transport.requestAnalysis(credential, "no-such-asset", null, force = false)
                fail("an unknown asset must be a typed failure")
            } catch (expected: SampleLibFailure.EntityNotFound) {
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun acceptingATempoIsDeliveredAndTheLibraryChangesOnlyAfterThePullThenAnAliasCanBeChosen() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val tempo = h.suggestion("accept-a", SuggestionWireKind.TEMPO)

            val queued = h.provider.acceptSuggestion(tempo.id)

            assertTrue(queued.toString(), queued is AnalysisActionResult.Done)
            assertEquals(ServerEditStatus.PENDING, h.provider.acceptances.value.getValue(tempo.id).status)
            assertNull("queued is not effective", h.library().tracks.single { it.id == "asset-accept-a" }.bpm)

            val after = h.refresh()

            val mutation = h.journal.all().single { it.operation == "analysis_suggestion.accept" }
            assertEquals(JournalReceiptOutcome.APPLIED, mutation.receipt?.outcome)
            assertEquals("analysis_suggestion", mutation.entityType)
            assertEquals(128.0, after.tracks.single { it.id == "asset-accept-a" }.bpm!!, 1e-9)
            assertEquals(ServerEditStatus.SAVED, h.provider.acceptances.value.getValue(tempo.id).status)
            assertEquals(dev.androidjtools.core.model.SuggestionDecision.ACCEPTED, h.provider.suggestions("asset-accept-a").value.single { it.id == tempo.id }.decision)

            // The half-time alias, chosen by the person.
            assertTrue(h.provider.acceptSuggestion(tempo.id, 64.0) is AnalysisActionResult.Done)
            val chosen = h.refresh()
            assertEquals(64.0, chosen.tracks.single { it.id == "asset-accept-a" }.bpm!!, 1e-9)
            assertEquals(2, h.journal.all().count { it.operation == "analysis_suggestion.accept" })

            // A value outside the options never leaves the device.
            assertTrue(h.provider.acceptSuggestion(tempo.id, 99.0) is AnalysisActionResult.Refused)
        } finally {
            h.close()
        }
    }

    @Test
    fun aKeyAndABeatGridAreAcceptedAndRepeatingAnAlreadyAppliedValueIsANoOp() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val key = h.suggestion("accept-b", SuggestionWireKind.KEY)
            val grid = h.suggestion("accept-b", SuggestionWireKind.BEAT_GRID)
            val tempo = h.suggestion("accept-b", SuggestionWireKind.TEMPO)

            assertTrue(h.provider.acceptSuggestion(key.id) is AnalysisActionResult.Done)
            assertTrue(h.provider.acceptSuggestion(grid.id) is AnalysisActionResult.Done)
            val after = h.refresh()

            assertEquals("A minor", after.tracks.single { it.id == "asset-accept-b" }.key)
            assertEquals(128.0, after.tracks.single { it.id == "asset-accept-b" }.bpm!!, 1e-9)
            assertEquals(ServerEditStatus.SAVED, h.provider.acceptances.value.getValue(key.id).status)
            assertEquals(ServerEditStatus.SAVED, h.provider.acceptances.value.getValue(grid.id).status)

            // The beat grid already set bpm 128: accepting the tempo changes nothing.
            assertTrue(h.provider.acceptSuggestion(tempo.id) is AnalysisActionResult.Done)
            h.refresh()
            val noOp = h.journal.all().single { it.entityId == tempo.id }
            assertEquals(JournalReceiptOutcome.NO_OP, noOp.receipt?.outcome)
        } finally {
            h.close()
        }
    }

    @Test
    fun aSuggestionTheServerReplacedIsAConflictWithOnlyDismissAndTheReplacementAppearsAfterTheRefresh() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val old = h.suggestion("replace", SuggestionWireKind.TEMPO)
            assertTrue(h.provider.acceptSuggestion(old.id) is AnalysisActionResult.Done)

            // Another client re-ran tempo analysis for this track before our accept was delivered.
            val newRunId = postCompletedTempoRun("asset-replace", bpm = 140)

            h.refresh()

            val conflicted = h.journal.all().single { it.entityId == old.id }
            assertEquals(JournalMutationState.CONFLICT, conflicted.state)
            assertEquals("suggestion_changed", conflicted.receipt?.conflict?.code)
            assertEquals(ServerEditStatus.CONFLICT, h.provider.acceptances.value[old.id]?.status ?: ServerEditStatus.CONFLICT)
            assertNull("nothing was applied", h.trackOf("replace").bpm)
            // The refresh already carried the replacement: new id, old id tombstoned.
            assertTrue(h.library().suggestionsOf("asset-replace").none { it.id == old.id })
            val replacement = h.library().suggestionsOf("asset-replace").single { it.kind == SuggestionWireKind.TEMPO }
            assertEquals("$newRunId:tempo:asset-replace", replacement.id)
            assertEquals(SuggestionData.Tempo(140.0, listOf(70.0, 280.0)), replacement.data)

            h.provider.dismissSuggestion(old.id) // the old record is gone from the projection, so only the journal entry matters
            assertEquals(JournalMutationState.CONFLICT, h.journal.mutation(conflicted.mutationId)?.state)
        } finally {
            h.close()
        }
    }

    @Test
    fun aConflictedAcceptIsDismissedWhileTheOldSuggestionIsStillShownAndTheReplacementIsNotHidden() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val old = h.suggestion("replace", SuggestionWireKind.KEY)
            // Record the conflict directly so the old suggestion is still in the projection when it is dismissed.
            assertTrue(h.provider.acceptSuggestion(old.id) is AnalysisActionResult.Done)
            val id = h.journal.all().single { it.entityId == old.id }.mutationId
            h.journal.markApplying(id)
            h.journal.recordReceipt(
                dev.androidjtools.sync.journal.JournalReceipt(
                    id, JournalReceiptOutcome.CONFLICT,
                    conflict = dev.androidjtools.sync.journal.JournalConflict("suggestion_changed", "analysis_suggestion", null, null, null),
                ),
            )
            assertEquals(ServerEditStatus.CONFLICT, h.provider.acceptances.value.getValue(old.id).status)

            h.provider.dismissSuggestion(old.id)

            assertEquals(JournalMutationState.RESOLVED, h.journal.mutation(id)?.state)
            assertTrue(h.provider.suggestions("asset-replace").value.none { it.id == old.id })
            assertEquals("a refresh was asked for", 2, h.flushes)
        } finally {
            h.close()
        }
    }

    @Test
    fun cueCandidatesBecomeCuesInOneEditorStateEditAndAreNotAddedTwice() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            val candidates = h.suggestion("cues", SuggestionWireKind.CUE_CANDIDATES)
            val points = (candidates.data as SuggestionData.CueCandidates).pointsMs

            fun planner(library: LibraryProjection) = ServerEditPlanner(
                track = library.tracks.single { it.id == "asset-cues" },
                cues = library.cues["asset-cues"].orEmpty(),
                loops = library.loops["asset-cues"].orEmpty(),
                aggregates = library.aggregatesOfTrack("asset-cues"),
                nowMs = System::currentTimeMillis,
                newMutationId = { "android-live-" + UUID.randomUUID() },
            )

            val build = planner(h.library()).addCues(points, candidates.analysisRunId) as EditBuild.Ready
            assertEquals("analysis", build.intent.provenance["source"])
            assertEquals("cues-chop", build.intent.provenance["analysis_run_id"])
            assertTrue(JournalMutationStager(h.journal).stage(build.intent) is PreparationStageResult.Queued)
            val after = h.refresh()

            assertEquals(JournalReceiptOutcome.APPLIED, h.journal.mutation(build.intent.mutationId)?.receipt?.outcome)
            assertEquals(points, after.cues.getValue("asset-cues").map { it.positionMs }.sorted())
            assertEquals(points.size, after.cues.getValue("asset-cues").size)

            assertTrue((planner(after).addCues(points, candidates.analysisRunId) as EditBuild.Refused).reason.contains("already"))
        } finally {
            h.close()
        }
    }

    @Test
    fun forgettingTheServerLeavesNoAcceptanceOrSuggestionBehind() = runBlocking {
        requireLive()
        val h = AnalysisHarness()
        try {
            h.pair()
            h.provider.acceptSuggestion(h.suggestion("accept-b", SuggestionWireKind.KEY).id)
            h.provider.dismissSuggestion(h.suggestion("accept-b", SuggestionWireKind.TEMPO).id)
            assertTrue(h.dismissals.keys.value.isNotEmpty())

            val cleared = dev.androidjtools.sync.samplelib.discardServerBoundState(lazyOf(h.journal), h.dismissals)
            h.manager.forget()

            assertTrue(cleared)
            assertTrue(h.journal.all().isEmpty())
            assertTrue(h.dismissals.keys.value.isEmpty())
            assertTrue(h.cache.entities(ANALYSIS_SUGGESTION_ENTITY).isEmpty())
        } finally {
            h.close()
        }
    }

    /** Another client (or a worker) persists a newer completed tempo run; returns the new run id. */
    private fun postCompletedTempoRun(assetId: String, bpm: Int): String {
        val body = buildJsonObject {
            put("asset_id", assetId)
            put("tool_name", "tempo-candidates")
            put("operation", "mir.tempo_candidates")
            put("status", "completed")
            put("output_json", buildJsonObject {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("bpm", bpm)
                        put("confidence", 0.6)
                        put("beat_times_ms", buildJsonArray { add(JsonPrimitive(50)); add(JsonPrimitive(500)) })
                    })
                })
            })
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/analysis/runs")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("POST /v1/analysis/runs: ${response.code}", response.isSuccessful)
            return kotlinx.serialization.json.Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("id").jsonPrimitive.content
        }
    }
}
