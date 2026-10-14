package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.ServerCapabilities

const val ASSET_FILE_ENTITY = "asset_file"

const val NO_SERVER_AUDIO_NOTICE = "This track has no audio file on your Sample Lib server yet."
const val PAIR_AGAIN_NOTICE = "The server no longer accepts this device. Pair again to play its tracks."
const val DECK_NEEDS_DOWNLOAD_NOTICE = "Download this track to load it on a deck."

/** One audio file the server can serve. [sha256] is lowercase; [etag] is the server's quoted validator, verbatim. */
data class AssetFileDescriptor(
    val id: String,
    val assetId: String,
    val role: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val etag: String,
)

/** [stream] is what the player streams (the small preview when there is one); [download] is what a pin stores. */
data class ServerAudio(val stream: AssetFileDescriptor, val download: AssetFileDescriptor)

data class ServerAudioSupport(val advertised: Boolean, val range: Boolean)

fun serverAudioSupport(capabilities: ServerCapabilities?): ServerAudioSupport {
    if (capabilities == null) return ServerAudioSupport(advertised = false, range = false)
    val advertised = ASSET_FILE_ENTITY in capabilities.pull &&
        "resource_uri" in capabilities.media &&
        "content_hash" in capabilities.media
    return ServerAudioSupport(advertised, range = advertised && "range" in capabilities.media)
}

object AssetFiles {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val roles = setOf("original", "preview")

    /** Returns null for anything that does not match the documented descriptor exactly. */
    fun parse(entity: CachedEntity): AssetFileDescriptor? {
        val value = parseJsonObject(entity.valueJson) ?: return null
        val id = value.string("id")?.takeIf { it.isNotBlank() && it == entity.entityId } ?: return null
        val assetId = value.string("asset_id")?.takeIf { it.isNotBlank() } ?: return null
        val role = value.string("file_role")?.takeIf { it in roles } ?: return null
        if (value.string("resource_uri") != "$role://$id") return null
        val digest = value.string("sha256")?.takeIf { sha256.matches(it) } ?: return null
        val size = value.long("size_bytes")?.takeIf { it > 0L } ?: return null
        val mime = value.string("mime_type")?.takeIf { it.isNotBlank() } ?: return null
        val etag = value.string("etag")?.takeIf { it.isNotBlank() } ?: return null
        return AssetFileDescriptor(id, assetId, role, digest.lowercase(), size, mime, etag)
    }

    fun audioByAsset(entities: List<CachedEntity>): Map<String, ServerAudio> =
        entities.mapNotNull(::parse)
            .groupBy { it.assetId }
            .mapValues { (_, files) ->
                val sorted = files.sortedBy { it.id }
                val preview = sorted.firstOrNull { it.role == "preview" }
                val original = sorted.firstOrNull { it.role == "original" }
                // Both roles are non-empty here: parse() only yields original or preview.
                ServerAudio(stream = preview ?: original!!, download = original ?: preview!!)
            }
}
