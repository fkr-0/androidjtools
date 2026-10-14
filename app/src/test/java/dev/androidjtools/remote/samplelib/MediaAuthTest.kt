package dev.androidjtools.remote.samplelib

import dev.androidjtools.testing.LoopbackHttpServer
import dev.androidjtools.testing.StubResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAuthTest {
    private val servers = mutableListOf<LoopbackHttpServer>()

    private fun server(handler: (dev.androidjtools.testing.RecordedRequest) -> StubResponse = { StubResponse(200, body = "ok".toByteArray()) }) =
        LoopbackHttpServer(handler).also { servers += it }

    @After
    fun stop() = servers.forEach { it.close() }

    private fun auth(baseUrl: String, token: String = "tok-secret") =
        MediaAuth.create(baseUrl, MobileCredential.issued(token))!!

    private fun client(auth: MediaAuth?) = newMediaHttpClient(MediaAuthProvider { auth })

    private fun get(client: OkHttpClient, url: String, vararg headers: Pair<String, String>): Int =
        client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.code }

    @Test
    fun theBearerIsSentOnlyToThePairedOriginsMediaPath() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        assertEquals(200, get(client, "${paired.baseUrl()}/v1/sync/media/file-1"))
        assertEquals("Bearer tok-secret", paired.requests.single().headers["authorization"])
    }

    @Test
    fun nothingElseOnThePairedHostGetsTheBearer() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${paired.baseUrl()}/v1/sync/pull")
        get(client, "${paired.baseUrl()}/v1/sync/media")
        get(client, "${paired.baseUrl()}/other/v1/sync/media/file-1")
        // Dot segments are normalised by HttpUrl before matching, so traversal cannot widen the match.
        get(client, "${paired.baseUrl()}/v1/sync/media/%2e%2e/pull")
        get(client, "${paired.baseUrl()}/v1/sync/media/../pull")

        assertEquals(5, paired.requests.size)
        assertTrue(paired.requests.all { "authorization" !in it.headers })
    }

    @Test
    fun aDifferentHostOrPortNeverGetsTheBearer() {
        val paired = server()
        val other = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${other.baseUrl()}/v1/sync/media/file-1") // different port
        get(client, "http://localhost:${paired.port}/v1/sync/media/file-1") // different host name

        assertTrue(other.requests.all { "authorization" !in it.headers })
        assertEquals(1, paired.requests.size)
        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun aReverseProxyPrefixIsPartOfTheMatch() {
        val paired = server()
        val client = client(auth(paired.baseUrl("/samplelab")))

        get(client, "${paired.baseUrl("/samplelab")}/v1/sync/media/file-1")
        get(client, "${paired.baseUrl()}/v1/sync/media/file-1")

        assertEquals("Bearer tok-secret", paired.requests[0].headers["authorization"])
        assertNull(paired.requests[1].headers["authorization"])
    }

    @Test
    fun redirectsAreNeverFollowedSoTheBearerCannotLeakThroughOne() {
        val target = server()
        val paired = server {
            StubResponse(302, headers = mapOf("Location" to "http://127.0.0.1:${target.port}/v1/sync/media/file-1"))
        }
        val client = client(auth(paired.baseUrl()))

        assertEquals(302, get(client, "${paired.baseUrl()}/v1/sync/media/file-1"))
        assertTrue(target.requests.isEmpty())
    }

    @Test
    fun aCallerSuppliedAuthorizationHeaderIsRemovedWhenTheUrlIsNotTheMediaRoute() {
        val paired = server()
        val client = client(auth(paired.baseUrl()))

        get(client, "${paired.baseUrl()}/elsewhere", "Authorization" to "Bearer injected")

        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun withNoCredentialNothingIsSent() {
        val paired = server()
        val client = client(null)

        get(client, "${paired.baseUrl()}/v1/sync/media/file-1")

        assertNull(paired.requests.single().headers["authorization"])
    }

    @Test
    fun theCredentialNeverAppearsInTheStringForm() {
        val rendered = auth("http://127.0.0.1:8765").toString()
        assertFalse(rendered.contains("tok-secret"))
    }

    @Test
    fun aRemoteHostWithoutTlsCannotHoldACredential() {
        assertNull(MediaAuth.create("http://example.com", MobileCredential.issued("t")))
        assertNull(MediaAuth.create("not a url", MobileCredential.issued("t")))
        assertNotNull(MediaAuth.create("https://example.com/samplelab", MobileCredential.issued("t")))
        assertNotNull(MediaAuth.create("http://localhost:8765", MobileCredential.issued("t")))
    }

    @Test
    fun mediaUrlsKeepTheProxyPrefixAndEncodeTheId() {
        assertEquals(
            "http://127.0.0.1:1/samplelab/v1/sync/media/file-1",
            mediaUrl("http://127.0.0.1:1/samplelab", "file-1").toString(),
        )
        assertEquals("http://127.0.0.1:1/v1/sync/media/file-1", mediaUrl("http://127.0.0.1:1", "file-1").toString())
        assertEquals("http://127.0.0.1:1/v1/sync/media/a%2Fb", mediaUrl("http://127.0.0.1:1/", "a/b").toString())
        assertNull(mediaUrl("nope", "file-1"))
    }
}
