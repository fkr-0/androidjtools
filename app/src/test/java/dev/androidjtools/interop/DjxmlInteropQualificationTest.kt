package dev.androidjtools.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjxmlInteropQualificationTest {
    @Test
    fun `supported adapter semantics remain blocked until production sync exists`() {
        val result = DjxmlInteropPolicy.evaluate(
            DjxmlInteropEnvironment(
                djxmlMajorVersion = 2,
                realSyncFacadeAvailable = false,
                canonicalPlaylistAuthorityAvailable = false,
            ),
        )

        listOf(
            DjxmlSemantic.STABLE_ASSET_IDENTITY,
            DjxmlSemantic.CUE,
            DjxmlSemantic.LOOP,
            DjxmlSemantic.REGION,
            DjxmlSemantic.NAMESPACED_TAG,
            DjxmlSemantic.CRATE_MEMBERSHIP,
        ).forEach { semantic ->
            assertEquals(
                DjxmlQualificationState.ADAPTER_SUPPORTED_BUT_REAL_SYNC_BLOCKED,
                result.stateOf(semantic),
            )
        }
        assertFalse(result.fullyQualified)
    }

    @Test
    fun `crate compatibility never masquerades as playlist identity or order`() {
        val result = DjxmlInteropPolicy.evaluate(
            DjxmlInteropEnvironment(
                djxmlMajorVersion = 2,
                realSyncFacadeAvailable = true,
                canonicalPlaylistAuthorityAvailable = false,
            ),
        )

        assertEquals(DjxmlQualificationState.QUALIFIED, result.stateOf(DjxmlSemantic.CRATE_MEMBERSHIP))
        assertEquals(
            DjxmlQualificationState.BLOCKED_NO_CANONICAL_PLAYLIST_AUTHORITY,
            result.stateOf(DjxmlSemantic.PLAYLIST_IDENTITY),
        )
        assertEquals(
            DjxmlQualificationState.BLOCKED_NO_CANONICAL_PLAYLIST_AUTHORITY,
            result.stateOf(DjxmlSemantic.PLAYLIST_ORDER),
        )
    }

    @Test
    fun `full playlist qualification requires both canonical authority and real sync`() {
        val withoutSync = DjxmlInteropPolicy.evaluate(
            DjxmlInteropEnvironment(2, realSyncFacadeAvailable = false, canonicalPlaylistAuthorityAvailable = true),
        )
        assertEquals(
            DjxmlQualificationState.ADAPTER_SUPPORTED_BUT_REAL_SYNC_BLOCKED,
            withoutSync.stateOf(DjxmlSemantic.PLAYLIST_ORDER),
        )

        val complete = DjxmlInteropPolicy.evaluate(
            DjxmlInteropEnvironment(2, realSyncFacadeAvailable = true, canonicalPlaylistAuthorityAvailable = true),
        )
        assertTrue(complete.fullyQualified)
    }

    @Test
    fun `stable identity is Sample Lib asset identity not a path or DJXML track number`() {
        assertEquals("sample-lib:asset-123", DjxmlInteropPolicy.assetGrouping("asset-123"))
        assertEquals("asset-123", DjxmlInteropPolicy.assetIdFromGrouping("sample-lib:asset-123"))
        assertNull(DjxmlInteropPolicy.assetIdFromGrouping("file:///music/night-bus.wav"))
        assertNull(DjxmlInteropPolicy.assetIdFromGrouping("42"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported DJXML major version fails closed`() {
        DjxmlInteropPolicy.evaluate(
            DjxmlInteropEnvironment(1, realSyncFacadeAvailable = true, canonicalPlaylistAuthorityAvailable = true),
        )
    }
}
