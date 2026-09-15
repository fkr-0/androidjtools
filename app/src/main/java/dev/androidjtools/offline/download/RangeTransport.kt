package dev.androidjtools.offline.download

import java.io.Closeable
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

fun interface OfflineRequestDecorator {
    /** Authentication/transport policy can be supplied by the remote integration lane. */
    fun decorate(builder: Request.Builder, asset: OfflineAsset)
}

data class RangeRequest(
    val asset: OfflineAsset,
    val startByte: Long? = null,
    val ifRange: String? = null,
)

class RangeResponse(
    val statusCode: Int,
    val body: InputStream,
    val contentLength: Long?,
    val contentRangeStart: Long?,
    val etag: String?,
    val lastModified: String?,
    private val closeAction: () -> Unit = { body.close() },
) : Closeable {
    override fun close() = closeAction()
}

interface RangeTransport {
    suspend fun open(request: RangeRequest): RangeResponse
}

class OkHttpRangeTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val decorator: OfflineRequestDecorator = OfflineRequestDecorator { _, _ -> },
) : RangeTransport {
    override suspend fun open(request: RangeRequest): RangeResponse {
        val builder = Request.Builder().url(request.asset.resourceUri)
        request.startByte?.let { builder.header("Range", "bytes=$it-") }
        request.ifRange?.let { builder.header("If-Range", it) }
        decorator.decorate(builder, request.asset)
        val call = client.newCall(builder.build())
        val response = call.await()
        val body = response.body ?: run {
            response.close()
            throw IOException("HTTP ${response.code} response has no body")
        }
        return RangeResponse(
            statusCode = response.code,
            body = body.byteStream(),
            contentLength = body.contentLength().takeIf { it >= 0L },
            contentRangeStart = parseContentRangeStart(response.header("Content-Range")),
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            closeAction = response::close,
        )
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun parseContentRangeStart(value: String?): Long? {
        // RFC 7233: bytes first-last/complete-length
        if (value == null || !value.startsWith("bytes ")) return null
        return value.removePrefix("bytes ").substringBefore('-').toLongOrNull()
    }
}
