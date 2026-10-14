package dev.androidjtools.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorTextTest {
    @Test
    fun serverAddressesAreRemovedFromPlayerMessages() {
        val cleaned = scrubUrls("Unable to connect to http://192.168.1.5:8765/samplelab/v1/sync/media/f-prev")
        assertEquals("Unable to connect to <address>", cleaned)
        assertFalse(scrubUrls("https://lab.example/v1/sync/media/x failed").contains("lab.example"))
    }

    @Test
    fun localUrisAreScrubbedToo() {
        assertEquals("Open failed: <address>", scrubUrls("Open failed: file:///data/user/0/app/x.media"))
        assertEquals("Open failed: <address> now", scrubUrls("Open failed: content://media/external/audio/media/7 now"))
    }

    @Test
    fun plainMessagesPassThroughAndEmptyOnesGetADefault() {
        assertEquals("Response code: 401", scrubUrls("Response code: 401"))
        assertEquals("Playback failed.", scrubUrls(null))
        assertEquals("Playback failed.", scrubUrls("   "))
    }
}
