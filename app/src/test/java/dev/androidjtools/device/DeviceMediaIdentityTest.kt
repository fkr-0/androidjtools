package dev.androidjtools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceMediaIdentityTest {
    @Test
    fun deviceTrackIdentityRoundTripsToPlayableMediaStoreUri() {
        val trackId = deviceTrackId(4242L)

        assertEquals("device-media:4242", trackId)
        assertEquals(4242L, mediaStoreIdFromTrackId(trackId))
        assertEquals("content://media/external/audio/media/4242", mediaUriForDeviceTrack(trackId))
    }

    @Test
    fun foreignTrackIdentityDoesNotResolveToDeviceMedia() {
        assertNull(mediaStoreIdFromTrackId("trk-001"))
        assertNull(mediaUriForDeviceTrack("trk-001"))
    }
}
