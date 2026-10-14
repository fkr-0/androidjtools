package dev.androidjtools.offline.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaResolverQuickTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = "0123456789".toByteArray()
    private val sha = sha256Hex(bytes)
    private val asset = OfflineAsset("f1", "original://f1", sha, bytes.size.toLong())

    private fun setup(phase: OfflineDownloadPhase = OfflineDownloadPhase.AVAILABLE, write: ByteArray? = bytes):
        Triple<OfflineDownloadStore, OfflineCacheStorage, OfflineMediaResolver> {
        val root = tmp.newFolder()
        val store = OfflineDownloadStore(File(root, "state"))
        val storage = OfflineCacheStorage(File(root, "media"))
        store.ensure(asset)
        store.update("f1") { it.copy(phase = phase) }
        write?.let { storage.finalFile("f1").writeBytes(it) }
        return Triple(store, storage, OfflineMediaResolver(store, storage))
    }

    @Test
    fun anAvailableFileOfTheRightSizeResolvesWithoutHashing() {
        val (_, storage, resolver) = setup()
        val media = resolver.resolveQuick("f1", sha)!!
        assertEquals(storage.finalFile("f1").toURI().toString(), media.uri)
        assertEquals(10L, media.sizeBytes)
    }

    @Test
    fun quickResolutionDoesNotRehashSoItCannotBlockTheMainThread() {
        // Same length, different content: resolve() would catch it by hashing, resolveQuick() deliberately does not.
        val (_, _, resolver) = setup(write = "ABCDEFGHIJ".toByteArray())
        assertNotNull(resolver.resolveQuick("f1", sha))
        assertNull(resolver.resolve("f1"))
    }

    @Test
    fun aWrongSizeOrMissingFileIsNotAvailable() {
        assertNull(setup(write = "short".toByteArray()).third.resolveQuick("f1", sha))
        assertNull(setup(write = null).third.resolveQuick("f1", sha))
    }

    @Test
    fun onlyTheAvailablePhaseResolves() {
        for (phase in OfflineDownloadPhase.values().filter { it != OfflineDownloadPhase.AVAILABLE }) {
            assertNull(phase.name, setup(phase).third.resolveQuick("f1", sha))
        }
    }

    @Test
    fun aDescriptorForADifferentRevisionDoesNotResolve() {
        val (_, _, resolver) = setup()
        assertNull(resolver.resolveQuick("f1", "ee".repeat(32)))
        assertNotNull(resolver.resolveQuick("f1", sha.uppercase()))
        assertNotNull(resolver.resolveQuick("f1"))
    }

    @Test
    fun anUnknownIdResolvesToNothing() {
        assertNull(setup().third.resolveQuick("nope", sha))
    }
}
