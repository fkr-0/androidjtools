package dev.androidjtools.testing

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Header names are lower-cased. */
data class RecordedRequest(val method: String, val path: String, val headers: Map<String, String>)

class StubResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/** Minimal loopback HTTP/1.1 server for unit tests. Every response closes the connection. */
class LoopbackHttpServer(private val handler: (RecordedRequest) -> StubResponse) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    val port: Int get() = server.localPort
    val requests: List<RecordedRequest> get() = seen.toList()

    /** [prefix] is a reverse-proxy style path prefix such as "/samplelab" (or empty). */
    fun baseUrl(prefix: String = "") = "http://127.0.0.1:$port$prefix"

    init {
        thread(isDaemon = true, name = "loopback-http-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@thread
                }
                thread(isDaemon = true, name = "loopback-http-conn") { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
            }
        }
        val requestLine = line() ?: return
        val parts = requestLine.split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line() ?: break
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val request = RecordedRequest(parts[0], parts[1], headers)
        seen += request
        val response = handler(request)
        val head = StringBuilder("HTTP/1.1 ${response.status} X\r\n")
        response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (response.headers.keys.none { it.equals("Content-Length", true) }) head.append("Content-Length: ${response.body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        socket.getOutputStream().apply {
            write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method != "HEAD") write(response.body)
            flush()
        }
    }

    override fun close() = server.close()
}
