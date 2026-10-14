package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OfflineMediaResolver
import dev.androidjtools.remote.samplelib.mediaUrl

/**
 * The only place that decides where a server track's audio comes from. A verified local copy always wins;
 * streaming needs the server to advertise audio; Live decks get local copies only. Every method is safe on the
 * main thread: no hashing, no credentials, no network.
 */
class SampleLibMediaResolver(
    private val projection: () -> LibraryProjection,
    private val baseUrl: () -> String?,
    private val offline: () -> OfflineMediaResolver,
) {
    fun localUri(trackId: String): String? {
        val download = projection().audio[trackId]?.download ?: return null
        return offline().resolveQuick(download.id, download.sha256)?.uri
    }

    fun streamUri(trackId: String): String? {
        val current = projection()
        if (!current.audioAdvertised) return null
        val stream = current.audio[trackId]?.stream ?: return null
        return mediaUrl(baseUrl() ?: return null, stream.id)?.toString()
    }

    fun playbackUri(trackId: String): String? = localUri(trackId) ?: streamUri(trackId)

    fun deckUri(trackId: String): String? = localUri(trackId)
}
