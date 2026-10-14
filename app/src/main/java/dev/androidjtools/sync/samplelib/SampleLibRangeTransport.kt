package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OkHttpRangeTransport
import dev.androidjtools.offline.download.RangeRequest
import dev.androidjtools.offline.download.RangeResponse
import dev.androidjtools.offline.download.RangeTransport
import dev.androidjtools.remote.samplelib.MediaAuthProvider
import java.io.IOException
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
        return delegate.open(request.copy(asset = request.asset.copy(resourceUri = url)))
    }

    private companion object {
        private val reference = Regex("^(?:original|preview)://([^/?#\\s]+)$")

        fun fileIdOf(resourceUri: String): String? = reference.matchEntire(resourceUri)?.groupValues?.get(1)
    }
}
