package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAudioTest {
    private val digest = "ab".repeat(32)

    private fun file(
        id: String = "file-1",
        assetId: String = "asset-1",
        role: String = "original",
        sha: String = digest,
        size: String = "2688",
        mime: String = "\"audio/wav\"",
        uri: String = "$role://$id",
    ) = CachedEntity(
        "asset_file", id, "r", "sample-lib.asset-file/v1",
        """{"id":"$id","asset_id":"$assetId","file_role":"$role","resource_uri":"$uri","sha256":"$sha","size_bytes":$size,"mime_type":$mime,"etag":"\"$sha\""}""",
    )

    @Test
    fun aWellFormedRowKeepsTheEtagVerbatimWithItsQuotes() {
        val parsed = AssetFiles.parse(file())!!
        assertEquals("file-1", parsed.id)
        assertEquals("asset-1", parsed.assetId)
        assertEquals("original", parsed.role)
        assertEquals(digest, parsed.sha256)
        assertEquals(2688L, parsed.sizeBytes)
        assertEquals("audio/wav", parsed.mimeType)
        assertEquals("\"$digest\"", parsed.etag)
    }

    @Test
    fun theDigestIsNormalisedToLowercase() {
        assertEquals(digest, AssetFiles.parse(file(sha = digest.uppercase()))!!.sha256)
    }

    @Test
    fun malformedRowsAreDroppedNotGuessedAt() {
        assertNull("role outside original/preview", AssetFiles.parse(file(role = "stem", uri = "stem://file-1")))
        assertNull("uri that disagrees with role and id", AssetFiles.parse(file(uri = "original://other")))
        assertNull("short digest", AssetFiles.parse(file(sha = "abc")))
        assertNull("non-hex digest", AssetFiles.parse(file(sha = "zz".repeat(32))))
        assertNull("zero size", AssetFiles.parse(file(size = "0")))
        assertNull("negative size", AssetFiles.parse(file(size = "-5")))
        assertNull("string size", AssetFiles.parse(file(size = "\"2688\"")))
        assertNull("blank mime", AssetFiles.parse(file(mime = "\"\"")))
        assertNull("entity id differs from value id", AssetFiles.parse(file().copy(entityId = "file-9")))
        assertNull("not json", AssetFiles.parse(file().copy(valueJson = "{")))
        assertNull("not an object", AssetFiles.parse(file().copy(valueJson = "[]")))
    }

    @Test
    fun streamingPrefersThePreviewAndDownloadingPrefersTheOriginal() {
        val audio = AssetFiles.audioByAsset(
            listOf(file("f-orig", role = "original"), file("f-prev", role = "preview")),
        ).getValue("asset-1")
        assertEquals("f-prev", audio.stream.id)
        assertEquals("f-orig", audio.download.id)
    }

    @Test
    fun aLoneFileServesBothPurposes() {
        val onlyPreview = AssetFiles.audioByAsset(listOf(file("f-prev", role = "preview"))).getValue("asset-1")
        assertEquals("f-prev", onlyPreview.stream.id)
        assertEquals("f-prev", onlyPreview.download.id)
        val onlyOriginal = AssetFiles.audioByAsset(listOf(file("f-orig", role = "original"))).getValue("asset-1")
        assertEquals("f-orig", onlyOriginal.stream.id)
        assertEquals("f-orig", onlyOriginal.download.id)
    }

    @Test
    fun filesAreGroupedPerAssetAndBadRowsDoNotHideGoodOnes() {
        val audio = AssetFiles.audioByAsset(
            listOf(file("f1", assetId = "a1"), file("f2", assetId = "a2"), file("bad", assetId = "a3", sha = "x")),
        )
        assertEquals(setOf("a1", "a2"), audio.keys)
    }

    @Test
    fun twoOriginalsForOneAssetResolveDeterministicallyToTheLowestId() {
        val audio = AssetFiles.audioByAsset(listOf(file("f-b"), file("f-a"))).getValue("asset-1")
        assertEquals("f-a", audio.download.id)
    }

    private fun caps(pull: Set<String>, media: Set<String>) = ServerCapabilities(pull, emptySet(), "canonical_v1", emptySet(), media)

    @Test
    fun audioIsAdvertisedOnlyWithTheFamilyAndTheMediaFeatures() {
        val all = setOf("resource_uri", "content_hash", "range")
        assertEquals(ServerAudioSupport(true, true), serverAudioSupport(caps(setOf("asset", "asset_file"), all)))
        assertEquals(ServerAudioSupport(true, false), serverAudioSupport(caps(setOf("asset_file"), setOf("resource_uri", "content_hash"))))
        assertFalse(serverAudioSupport(caps(setOf("asset"), all)).advertised)
        assertFalse(serverAudioSupport(caps(setOf("asset_file"), setOf("resource_uri"))).advertised)
        assertFalse(serverAudioSupport(caps(setOf("asset_file"), setOf("content_hash"))).advertised)
        assertFalse(serverAudioSupport(null).advertised)
        assertTrue(serverAudioSupport(caps(setOf("asset_file"), all)).range)
    }
}
