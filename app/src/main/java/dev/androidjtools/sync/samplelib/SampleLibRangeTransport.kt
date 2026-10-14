package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OkHttpRangeTransport
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.offline.download.RangeResponse
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Downloads `original://<asset_file id>` / `preview://<asset_file id>` from the paired server. The persisted
 * descriptor keeps only the role reference; the URL is rebuilt from the current pairing for every request, so
 * no host or credential is ever stored with the download.
 */
class SampleLibRangeTransport(
    private val auth: MediaAuthProvider,
    client: OkHttpClient,
) : RangeTransport {
    private val delegate = OkHttpRangeTransport(client)

    override suspend fun open(request: RangeRequest): RangeResponse {
        val fileId = fileIdOf(request.asset.resourceUri) ?: throw IOException("Unsupported media reference.")
        // May read the Keystore-backed store the first time after process start.
        val credential = withContext(Dispatchers.IO) { auth.mediaAuth() } ?: throw IOException("No paired Sample Lib server.")
        val url = credential.mediaUrl(fileId).toString()
        return try {
            delegate.open(request.copy(asset = request.asset.copy(resourceUri = url)))
        } catch (e: IOException) {
            // OkHttp messages embed the server address; this text ends up in the download record and the UI.
            throw IOException(describe(e))
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is UnknownHostException -> "The Sample Lib server could not be found."
        is SSLException -> "A secure connection to the Sample Lib server could not be made."
        is SocketTimeoutException, is ConnectException -> "The Sample Lib server could not be reached."
        else -> "The connection to the Sample Lib server failed."
    }

    private companion object {
        private val reference = Regex("^(?:original|preview)://([^/?#\\s]+)$")

        fun fileIdOf(resourceUri: String): String? = reference.matchEntire(resourceUri)?.groupValues?.get(1)
    }
}
