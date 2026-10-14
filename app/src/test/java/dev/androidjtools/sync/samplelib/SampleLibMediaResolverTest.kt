package dev.androidjtools.sync.samplelib

import dev.androidjtools.offline.download.OfflineAsset
import dev.androidjtools.offline.download.OfflineCacheStorage
import dev.androidjtools.offline.download.OfflineDownloadPhase
import dev.androidjtools.offline.download.OfflineDownloadStore
import dev.androidjtools.offline.download.OfflineMediaResolver
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibMediaResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun descriptor(id: String, role: String, sha: String = this.sha) =
        AssetFileDescriptor(id, "a1", role, sha, 10, "audio/wav", "\"$sha\"")

    private val audio = ServerAudio(stream = descriptor("f-prev", "preview"), download = descriptor("f-orig", "original"))

    private class Fixture(val offline: OfflineMediaResolver, val store: OfflineDownloadStore, val storage: OfflineCacheStorage)

    private fun fixture(): Fixture {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        return Fixture(OfflineMediaResolver(store, storage), store, storage)
    }

    private fun Fixture.download(id: String = "f-orig", sha: String = this@SampleLibMediaResolverTest.sha) {
        store.ensure(OfflineAsset(id, "original://$id", sha, 10))
        store.update(id) { it.copy(phase = OfflineDownloadPhase.AVAILABLE) }
        storage.finalFile(id).writeBytes(bytes)
    }

    private fun projection(advertised: Boolean = true, withAudio: Boolean = true) = LibraryProjection(
        tracks = emptyList(), cues = emptyMap(), loops = emptyMap(),
        audio = if (withAudio) mapOf("a1" to audio) else emptyMap(), audioAdvertised = advertised,
    )

    private fun resolver(f: Fixture, projection: LibraryProjection = projection(), base: String? = "https://lab.example/samplelab") =
        SampleLibMediaResolver({ projection }, { base }, { f.offline })

    @Test
    fun withoutALocalCopyTheStreamUrlIsThePreviewUnderTheProxyPrefixAndCarriesNoCredential() {
        val r = resolver(fixture())
        assertEquals("https://lab.example/samplelab/v1/sync/media/f-prev", r.playbackUri("a1"))
        assertEquals("https://lab.example/samplelab/v1/sync/media/f-prev", r.streamUri("a1"))
        assertNull(r.localUri("a1"))
    }

    @Test
    fun aVerifiedLocalCopyWinsOverStreaming() {
        val f = fixture().also { it.download() }
        val r = resolver(f)
        val local = r.localUri("a1")!!
        assertTrue(local.startsWith("file:"))
        assertEquals(local, r.playbackUri("a1"))
    }

    @Test
    fun decksOnlyEverGetTheLocalCopy() {
        val none = resolver(fixture())
        assertNull(none.deckUri("a1"))
        val f = fixture().also { it.download() }
        assertEquals(resolver(f).localUri("a1"), resolver(f).deckUri("a1"))
    }

    @Test
    fun aLocalCopyOfAnOlderRevisionIsIgnored() {
        val f = fixture().also { it.download(sha = "ee".repeat(32)) }
        assertNull(resolver(f).localUri("a1"))
    }

    @Test
    fun streamingNeedsAdvertisedAudioAndAPairedBase() {
        assertNull(resolver(fixture(), projection(advertised = false)).streamUri("a1"))
        assertNull(resolver(fixture(), base = null).streamUri("a1"))
        assertNull(resolver(fixture(), base = "http://example.com").streamUri("a1"))
        // ...but a local copy still plays when the server is not advertising audio or no base is known.
        val f = fixture().also { it.download() }
        assertTrue(resolver(f, projection(advertised = false), base = null).playbackUri("a1")!!.startsWith("file:"))
    }

    @Test
    fun unknownTracksResolveToNothing() {
        val r = resolver(fixture(), projection(withAudio = false))
        assertNull(r.playbackUri("a1"))
        assertNull(r.deckUri("a1"))
        assertNull(resolver(fixture()).playbackUri("device-media:7"))
    }
}
