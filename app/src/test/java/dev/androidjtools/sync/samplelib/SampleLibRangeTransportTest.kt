package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.DownloadAttemptResult
import dev.androidjtools.offline.download.DownloadFailureKind
import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheQuotaManager
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadEngine
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.remote.samplelib.MediaAuth
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.newMediaHttpClient
import dev.androidjtools.testing.LoopbackHttpServer
import dev.androidjtools.testing.RecordedRequest
import dev.androidjtools.testing.StubResponse
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibRangeTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(5000) { (it * 31 % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val servers = mutableListOf<LoopbackHttpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun media(respond: (RecordedRequest) -> StubResponse): LoopbackHttpServer =
        LoopbackHttpServer(respond).also { servers += it }

    /** Mirrors the real route for original://file-1: strong etag, Range N-, If-Range. */
    private fun fileServer(body: ByteArray = content) = media { request ->
        val etag = "\"$digest\""
        val range = request.headers["range"]
        val ifRange = request.headers["if-range"]
        if (!request.path.endsWith("/v1/sync/media/file-1")) {
            StubResponse(404)
        } else if (range != null && (ifRange == null || ifRange == etag)) {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            StubResponse(
                206,
                mapOf("ETag" to etag, "Content-Range" to "bytes $start-${body.size - 1}/${body.size}"),
                body.copyOfRange(start, body.size),
            )
        } else {
            StubResponse(200, mapOf("ETag" to etag), body)
        }
    }

    private fun transport(server: LoopbackHttpServer, prefix: String = "", token: String? = "tok-secret"): SampleLibRangeTransport {
        val auth = token?.let { MediaAuth.create(server.baseUrl(prefix), MobileCredential.issued(it)) }
        val provider = MediaAuthProvider { auth }
        return SampleLibRangeTransport(provider, newMediaHttpClient(provider))
    }

    private fun asset(uri: String = "original://file-1", sha: String = digest, length: Long? = content.size.toLong()) =
        OfflineAsset("file-1", uri, sha, length, advertisedEtag = "\"$sha\"")

    private fun engine(transport: SampleLibRangeTransport, root: File = tmp.newFolder()): Triple<OfflineDownloadEngine, OfflineDownloadStore, OfflineCacheStorage> {
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        return Triple(OfflineDownloadEngine(store, storage, OfflineCacheQuotaManager(store, storage, 1L shl 20), transport), store, storage)
    }

    @Test
    fun aRoleUriIsMappedToTheAuthenticatedMediaUrlUnderTheProxyPrefix() = runBlocking {
        val server = fileServer()
        val response = transport(server, "/samplelab").open(RangeRequest(asset()))

        response.use { assertArrayEquals(content, it.body.readBytes()) }
        val seen = server.requests.single()
        assertEquals("/samplelab/v1/sync/media/file-1", seen.path)
        assertEquals("Bearer tok-secret", seen.headers["authorization"])
    }

    @Test
    fun rangeAndIfRangeArePassedThrough() = runBlocking {
        val server = fileServer()
        val response = transport(server).open(RangeRequest(asset(), startByte = 100, ifRange = "\"$digest\""))

        response.use {
            assertEquals(206, it.statusCode)
            assertEquals(100L, it.contentRangeStart)
            assertEquals("\"$digest\"", it.etag)
            assertArrayEquals(content.copyOfRange(100, content.size), it.body.readBytes())
        }
        assertEquals("bytes=100-", server.requests.single().headers["range"])
    }

    @Test
    fun onlyRoleUrisOfThePairedServerAreAccepted() = runBlocking {
        val server = fileServer()
        val subject = transport(server)
        for (uri in listOf("https://evil.example/x", "file:///etc/passwd", "original://a/b", "original://", "stem://file-1", "file-1", "original://file-1?x=1")) {
            try {
                subject.open(RangeRequest(asset(uri)))
                fail("accepted $uri")
            } catch (expected: IOException) {
                assertFalse(expected.message.orEmpty().contains("tok-secret"))
            }
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun withoutAPairedCredentialNoRequestIsMade() = runBlocking {
        val server = fileServer()
        try {
            transport(server, token = null).open(RangeRequest(asset()))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theEngineDownloadsVerifiesAndPublishesTheFile() = runBlocking {
        val server = fileServer()
        val (engine, store, storage) = engine(transport(server))

        assertTrue(engine.download(asset()) is DownloadAttemptResult.Success)

        assertEquals(OfflineDownloadPhase.AVAILABLE, store.get("file-1")?.phase)
        assertArrayEquals(content, storage.finalFile("file-1").readBytes())
        assertEquals("Bearer tok-secret", server.requests.single().headers["authorization"])
    }

    @Test
    fun theEngineResumesAPartialDownloadWithTheStoredValidator() = runBlocking {
        val server = fileServer()
        val (engine, store, storage) = engine(transport(server))
        store.ensure(asset())
        storage.partialFile("file-1").writeBytes(content.copyOfRange(0, 1200))
        store.update("file-1") { it.copy(phase = OfflineDownloadPhase.DOWNLOADING, bytesDownloaded = 1200, currentEtag = "\"$digest\"") }

        assertTrue(engine.download(asset()) is DownloadAttemptResult.Success)

        assertEquals("bytes=1200-", server.requests.single().headers["range"])
        assertEquals("\"$digest\"", server.requests.single().headers["if-range"])
        assertArrayEquals(content, storage.finalFile("file-1").readBytes())
    }

    @Test
    fun aTruncatedBodyNeverBecomesAvailable() = runBlocking {
        val server = media { StubResponse(200, mapOf("Content-Length" to "${content.size}"), content.copyOfRange(0, 1000)) }
        val (engine, store, storage) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Retry)
        assertFalse(storage.finalFile("file-1").exists())
        assertEquals(OfflineDownloadPhase.QUEUED, store.get("file-1")?.phase)
        assertNull(OfflineMediaResolver(store, storage).resolve("file-1"))
    }

    @Test
    fun aBodyWithTheWrongHashIsDiscardedAndReportedAsIntegrity() = runBlocking {
        val corrupted = content.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val server = fileServer(corrupted)
        val (engine, store, storage) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Retry)
        assertEquals(DownloadFailureKind.INTEGRITY, (result as DownloadAttemptResult.Retry).error.kind)
        assertFalse(storage.finalFile("file-1").exists())
        assertFalse(storage.partialFile("file-1").exists())
    }

    @Test
    fun aRevokedCredentialIsAHardFailureNotARetryLoop() = runBlocking {
        val server = media { StubResponse(401) }
        val (engine, store, _) = engine(transport(server))

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Failed)
        assertEquals(OfflineDownloadPhase.FAILED, store.get("file-1")?.phase)
    }

    @Test
    fun anUnreachableServerIsReportedWithoutItsAddress() = runBlocking {
        val closed = media { StubResponse(200) }
        val base = closed.baseUrl()
        val port = closed.port
        closed.close() // nothing listens on this port any more
        val provider = MediaAuthProvider { MediaAuth.create(base, MobileCredential.issued("tok-secret")) }
        val subject = SampleLibRangeTransport(provider, newMediaHttpClient(provider))
        val (engine, store, _) = engine(subject)

        val result = engine.download(asset())

        assertTrue(result is DownloadAttemptResult.Retry)
        val message = store.get("file-1")!!.error!!.message
        assertFalse(message, message.contains("127.0.0.1") || message.contains(port.toString()) || message.contains("localhost"))
        assertTrue(message, message.isNotBlank())
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}
