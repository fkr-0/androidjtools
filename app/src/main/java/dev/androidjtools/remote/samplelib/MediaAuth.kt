package dev.androidjtools.remote.samplelib

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

private const val MEDIA_SEGMENTS = "v1/sync/media"

/**
 * The paired server's media origin plus its bearer. It can only be created for an https server or a loopback
 * address, mirrors [SampleLibHttpTransport]'s own rule, and never renders the secret.
 */
class MediaAuth private constructor(val base: HttpUrl, private val credential: MobileCredential) {
    private val mediaPrefix: String = base.encodedPath.trimEnd('/') + "/$MEDIA_SEGMENTS/"

    /** True only for the same scheme, host and port, under `<base path>/v1/sync/media/`. */
    fun accepts(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.encodedPath.startsWith(mediaPrefix) && url.encodedPath.length > mediaPrefix.length

    internal fun authorizationHeader(): String = "Bearer ${credential.bearerValue()}"

    fun mediaUrl(fileId: String): HttpUrl = buildMediaUrl(base, fileId)

    override fun toString(): String = "MediaAuth(${base.host}, <redacted>)"

    companion object {
        fun create(baseUrl: String, credential: MobileCredential): MediaAuth? {
            val parsed = parseBase(baseUrl) ?: return null
            return MediaAuth(parsed, credential)
        }
    }
}

fun interface MediaAuthProvider {
    /** Blocking (may read the Keystore-backed store): call off the main thread. Null when no credential is usable. */
    fun mediaAuth(): MediaAuth?
}

/** The streaming/download URL of an asset_file under [baseUrl], or null when [baseUrl] is not acceptable. */
fun mediaUrl(baseUrl: String, fileId: String): HttpUrl? = parseBase(baseUrl)?.let { buildMediaUrl(it, fileId) }

private fun parseBase(baseUrl: String): HttpUrl? {
    val parsed = (if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/").toHttpUrlOrNull() ?: return null
    val loopback = parsed.host == "127.0.0.1" || parsed.host == "localhost"
    return parsed.takeIf { loopback || it.isHttps }
}

private fun buildMediaUrl(base: HttpUrl, fileId: String): HttpUrl =
    base.newBuilder().addPathSegments(MEDIA_SEGMENTS).addPathSegment(fileId).build()

/** Adds the bearer only for [MediaAuth.accepts] URLs and strips any caller-supplied Authorization otherwise. */
class MediaAuthInterceptor(private val provider: MediaAuthProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Cheap pre-check so unrelated requests never trigger a credential load.
        val mayBeMedia = request.url.encodedPath.contains("/$MEDIA_SEGMENTS/")
        val auth = if (mayBeMedia) provider.mediaAuth()?.takeIf { it.accepts(request.url) } else null
        val outgoing = request.newBuilder().removeHeader("Authorization").apply {
            if (auth != null) header("Authorization", auth.authorizationHeader())
        }.build()
        return chain.proceed(outgoing)
    }
}

/** One client for Media3 streaming and for offline downloads: never follows redirects. */
fun newMediaHttpClient(provider: MediaAuthProvider, base: OkHttpClient = OkHttpClient()): OkHttpClient =
    base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(MediaAuthInterceptor(provider))
        .build()
