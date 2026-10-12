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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SampleLibHttpTransportPathTest {
    // Plain loopback socket server: the JDK-internal com.sun.net.httpserver is not on the Android unit-test compile classpath.
    private lateinit var server: ServerSocket
    private val seenPaths = CopyOnWriteArrayList<String>()

    private val hello = """{"type":"hello_accepted","protocol_version":"1","server_id":"s","authority_generation":"g","server_change_revision":1,"capabilities":{"pull":["asset"],"mutations":[],"playlists":"canonical_v1","analysis":[],"media":[]},"limits":{"pull_page_max":10,"push_batch_max":10,"tombstone_retention_seconds":1,"mutation_replay_horizon_seconds":1}}"""
    private val pair = """{"type":"pairing_accepted","server_id":"s","principal_id":"p","installation_id":"i","access_token":"tok","scopes":["sync.read"]}"""
    private val pull = """{"type":"pull_response","mode":"snapshot","next_cursor":"c","has_more":false,"changes":[],"tombstones":[],"authority_generation":"g","server_change_revision":1}"""

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "samplelib-test-server") {
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

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val path = reader.readLine().split(' ')[1]
        var contentLength = 0
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
        }
        repeat(contentLength) { reader.read() }
        seenPaths += path
        val body = when {
            path.endsWith("/hello") -> hello
            path.endsWith("/pair") -> pair
            else -> pull
        }.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    @After
    fun stop() = server.close()

    private fun base() = "http://127.0.0.1:${server.localPort}"
    private val credential = MobileCredential.issued("tok")

    @Test
    fun everyEndpointKeepsAReverseProxyPathPrefix() = runBlocking {
        val transport = SampleLibHttpTransport(base() + "/samplelab")
        transport.pair(PairingRequest("c", "i", PairingCode.entered("123456")))
        transport.hello(credential, HelloRequest("c", "i"))
        transport.pull(credential, null, setOf("library"), 10)
        assertEquals(
            listOf("/samplelab/v1/sync/pair", "/samplelab/v1/sync/hello", "/samplelab/v1/sync/pull"),
            seenPaths.toList(),
        )
    }

    @Test
    fun withoutAPrefixEndpointsStayAtTheRoot() = runBlocking {
        SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i"))
        assertEquals(listOf("/v1/sync/hello"), seenPaths.toList())
    }

    @Test
    fun redirectsAreNotFollowed() {
        // Replace the server with one that sends 302
        server.close()
        val redirectServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "redirect-test-server") {
            while (!redirectServer.isClosed) {
                val socket = try {
                    redirectServer.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    val path = reader.readLine().split(' ')[1]
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    repeat(contentLength) { reader.read() }
                    seenPaths += path
                    val head = "HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); flush() }
                }
            }
        }

        val redirectBase = "http://127.0.0.1:${redirectServer.localPort}"
        val transport = SampleLibHttpTransport(redirectBase)
        try {
            runBlocking { transport.hello(credential, HelloRequest("c", "i")) }
            throw AssertionError("Expected SampleLibFailure.InvalidResponse")
        } catch (expected: SampleLibFailure.InvalidResponse) {
            // Expected: non-2xx treated as error
        } finally {
            redirectServer.close()
        }
        // Only the original path was requested, not /other
        assertEquals(listOf("/v1/sync/hello"), seenPaths.filter { it != "/other" })
        assertTrue(seenPaths.none { it == "/other" })
    }

    @Test
    fun ipv6LoopbackIsRefused() {
        try {
            SampleLibHttpTransport("http://[::1]:8017")
            throw AssertionError("Expected InsecureTransport")
        } catch (expected: SampleLibFailure.InsecureTransport) {
            // Expected
        }
    }
}
