package dev.androidjtools.remote.samplelib

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The S5 wire contract as the client sees it: hello capabilities, the analysis pull scope and the request route. */
class SampleLibAnalysisProtocolTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: ServerSocket
    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile private var helloCaps = """{"pull":["asset"],"mutations":[],"playlists":"canonical_v1","analysis":[],"media":[]}"""
    @Volatile private var requestStatus = 200
    @Volatile private var requestBody = ""

    private fun hello() =
        """{"type":"hello_accepted","protocol_version":"1","server_id":"s","authority_generation":"g","server_change_revision":1,"capabilities":$helloCaps,"limits":{"pull_page_max":10,"push_batch_max":10,"tombstone_retention_seconds":1,"mutation_replay_horizon_seconds":1}}"""

    private val pull =
        """{"type":"pull_response","mode":"snapshot","next_cursor":"c","has_more":false,"changes":[],"tombstones":[],"authority_generation":"g","server_change_revision":1}"""

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "samplelib-analysis-test-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                socket.use { serve(it) }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val (method, path) = reader.readLine().split(' ').let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = CharArray(length).also { reader.read(it, 0, length) }.concatToString()
        seen += Seen(method, path, headers, body)
        val (status, payload) = when {
            path.endsWith("/hello") -> 200 to hello()
            path.endsWith("/analysis/request") -> requestStatus to requestBody
            else -> 200 to pull
        }
        val bytes = payload.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun base() = "http://127.0.0.1:${server.localPort}"
    private val credential = MobileCredential.issued("tok")

    // ---- capabilities ----

    @Test
    fun helloReadsTheAdvertisedAnalysisKindsAndTheRequestMode() = runBlocking {
        helloCaps =
            """{"pull":["asset"],"mutations":["analysis_suggestion.accept"],"playlists":"canonical_v1","analysis":["tempo","key","beat_grid","cue_candidates"],"analysis_request":"enqueue_v1","media":[]}"""
        val caps = SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i")).capabilities

        assertEquals(setOf("tempo", "key", "beat_grid", "cue_candidates"), caps.analysis)
        assertEquals("enqueue_v1", caps.analysisRequest)
        assertTrue(caps.canRequestAnalysis)
        assertTrue("analysis_suggestion.accept" in caps.mutations)
    }

    @Test
    fun anOlderServerWithoutTheKeyOffersNoRequestAndNothingFails() = runBlocking {
        val caps = SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i")).capabilities

        assertTrue(caps.analysis.isEmpty())
        assertNull(caps.analysisRequest)
        assertFalse(caps.canRequestAnalysis)
    }

    @Test
    fun unavailableAndUnknownRequestModesAreNeverOffered() = runBlocking {
        helloCaps = """{"pull":[],"mutations":[],"playlists":"canonical_v1","analysis":["tempo"],"analysis_request":"unavailable","media":[]}"""
        val unavailable = SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i")).capabilities
        assertEquals("unavailable", unavailable.analysisRequest)
        assertFalse(unavailable.canRequestAnalysis)

        helloCaps = """{"pull":[],"mutations":[],"playlists":"canonical_v1","analysis":["tempo"],"analysis_request":"enqueue_v2","media":[]}"""
        assertFalse(SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i")).capabilities.canRequestAnalysis)
    }

    // ---- pull scopes ----

    @Test
    fun theAnalysisScopeIsSentNextToLibraryWhenTheCallerAsksForIt() = runBlocking {
        SampleLibHttpTransport(base()).pull(credential, null, setOf("library", "analysis"), 10)

        val scopes = Json.parseToJsonElement(seen.single().body).jsonObject.getValue("scopes").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("analysis", "library"), scopes)
    }

    // ---- request route ----

    @Test
    fun requestPostsTheAssetKindsAndForceToTheRequestRouteWithTheBearer() = runBlocking {
        requestBody =
            """{"asset_id":"a1","status":"queued","kinds":{"tempo":"queued","key":"cached"},"job_id":"job-9","message":null}"""
        val result = SampleLibHttpTransport(base() + "/samplelab")
            .requestAnalysis(credential, "a1", listOf("tempo", "key"), force = false)

        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals("/samplelab/v1/sync/analysis/request", call.path)
        assertEquals("Bearer tok", call.headers["authorization"])
        val body = Json.parseToJsonElement(call.body).jsonObject
        assertEquals("a1", body.getValue("asset_id").jsonPrimitive.content)
        assertEquals(listOf("tempo", "key"), body.getValue("kinds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", body.getValue("force").jsonPrimitive.content)

        assertEquals("a1", result.assetId)
        assertEquals(AnalysisRequestStatus.QUEUED, result.status)
        assertEquals(AnalysisRequestStatus.CACHED, result.kinds.getValue("key"))
        assertEquals("job-9", result.jobId)
        assertNull(result.message)
    }

    @Test
    fun omittedKindsMeanTheServersDefaultAndAreNotSent() = runBlocking {
        requestBody = """{"asset_id":"a1","status":"cached","kinds":{},"job_id":null,"message":null}"""
        SampleLibHttpTransport(base()).requestAnalysis(credential, "a1", null, force = false)

        assertFalse("kinds" in Json.parseToJsonElement(seen.single().body).jsonObject)
    }

    @Test
    fun anUnavailableAnswerCarriesTheServersMessage() = runBlocking {
        requestBody =
            """{"asset_id":"a1","status":"unavailable","kinds":{"tempo":"unavailable"},"job_id":null,"message":"No analysis worker is configured."}"""
        val result = SampleLibHttpTransport(base()).requestAnalysis(credential, "a1", null, force = false)

        assertEquals(AnalysisRequestStatus.UNAVAILABLE, result.status)
        assertEquals("No analysis worker is configured.", result.message)
    }

    @Test
    fun anUnknownStatusWordIsKeptAsUnknownNotFatal() = runBlocking {
        requestBody = """{"asset_id":"a1","status":"sleeping","kinds":{"tempo":"later"},"job_id":null,"message":null}"""
        val result = SampleLibHttpTransport(base()).requestAnalysis(credential, "a1", null, force = false)

        assertEquals(AnalysisRequestStatus.UNKNOWN, result.status)
        assertEquals(AnalysisRequestStatus.UNKNOWN, result.kinds.getValue("tempo"))
    }

    @Test
    fun anUnknownAssetIsATypedFailure() = runBlocking {
        requestStatus = 404
        requestBody = """{"detail":{"error":{"code":"entity_not_found","message":"No such asset."}}}"""
        try {
            SampleLibHttpTransport(base()).requestAnalysis(credential, "nope", null, force = false)
            fail("expected EntityNotFound")
        } catch (expected: SampleLibFailure.EntityNotFound) {
        }
    }

    @Test
    fun aMissingWriteScopeIsAuthRequiredAndARejectedCredentialToo() = runBlocking {
        requestStatus = 403
        requestBody = """{"detail":{"error":{"code":"scope_forbidden","message":"Credential does not grant sync.write."}}}"""
        try {
            SampleLibHttpTransport(base()).requestAnalysis(credential, "a1", null, force = false)
            fail("expected a scope failure")
        } catch (expected: SampleLibFailure.ScopeForbidden) {
            assertTrue("a scope failure is still an auth failure for every existing caller", expected is SampleLibFailure.AuthRequired)
        }
    }

    // ---- client gate ----

    private class RecordingTransport(private val caps: ServerCapabilities) : SampleLibTransport {
        var requests = 0
        override suspend fun pair(request: PairingRequest) = error("unused")
        override suspend fun hello(credential: MobileCredential, request: HelloRequest) =
            HelloAccepted("1", ServerIdentity("s", "g"), 1, caps, ServerLimits(10, 10, 1, 1))
        override suspend fun pull(credential: MobileCredential, cursor: String?, scopes: Set<String>, limit: Int): PullPage = error("unused")
        override suspend fun push(credential: MobileCredential, clientId: String, mutations: List<SampleLibMutation>): PushResult = error("unused")
        override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt = error("unused")
        override suspend fun fetchResource(credential: MobileCredential, descriptor: ResourceDescriptor, offset: Long): ResourceChunk = error("unused")
        override suspend fun requestAnalysis(
            credential: MobileCredential,
            assetId: String,
            kinds: List<String>?,
            force: Boolean,
        ): AnalysisRequestResult {
            requests++
            return AnalysisRequestResult(assetId, AnalysisRequestStatus.QUEUED, emptyMap(), "job", null)
        }
    }

    private fun caps(mode: String?) = ServerCapabilities(
        pull = setOf("asset"), mutations = emptySet(), playlists = "canonical_v1",
        analysis = setOf("tempo"), media = emptySet(), analysisRequest = mode,
    )

    @Test
    fun theClientNeverSendsARequestTheServerDidNotOffer() = runTest {
        for (mode in listOf(null, "unavailable", "enqueue_v2")) {
            val transport = RecordingTransport(caps(mode))
            val client = SampleLibClient(transport, "c", "i")
            val session = client.connect(credential, "s")
            try {
                client.requestAnalysis(session, "a1")
                fail("expected CapabilityUnavailable for $mode")
            } catch (expected: SampleLibFailure.CapabilityUnavailable) {
            }
            assertEquals(0, transport.requests)
        }
    }

    @Test
    fun anOfferedRequestGoesThrough() = runTest {
        val transport = RecordingTransport(caps("enqueue_v1"))
        val client = SampleLibClient(transport, "c", "i")
        val session = client.connect(credential, "s")

        assertEquals(AnalysisRequestStatus.QUEUED, client.requestAnalysis(session, "a1").status)
        assertEquals(1, transport.requests)
    }

    @Test
    fun theAcceptOperationHasTheServersWireNameAndEntityType() {
        assertEquals("analysis_suggestion.accept", MutationOperation.ANALYSIS_SUGGESTION_ACCEPT.wireName)
    }
}
