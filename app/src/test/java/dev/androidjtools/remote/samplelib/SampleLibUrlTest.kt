package dev.androidjtools.remote.samplelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SampleLibUrlTest {
    @Test
    fun trimsWhitespaceLowercasesSchemeAndHostAndDropsTrailingSlash() {
        assertEquals("https://samplelab.example", normalizeBaseUrl("  HTTPS://SampleLab.Example/  "))
    }

    @Test
    fun keepsPortAndReverseProxyPathPrefix() {
        assertEquals("https://host:8443/samplelab", normalizeBaseUrl("https://host:8443/samplelab/"))
        assertEquals("http://127.0.0.1:8017", normalizeBaseUrl("http://127.0.0.1:8017"))
    }

    @Test
    fun dropsQueryAndFragment() {
        assertEquals("https://host/p", normalizeBaseUrl("https://host/p?token=abc#frag"))
    }

    @Test
    fun rejectsEmbeddedCredentials() = expectMessage("username or password") { normalizeBaseUrl("https://user:pw@host") }

    @Test
    fun rejectsBlankInput() = expectMessage("Enter the server address") { normalizeBaseUrl("   ") }

    @Test
    fun rejectsAddressesWithoutAScheme() = expectMessage("full address") { normalizeBaseUrl("samplelab.local") }

    @Test
    fun rejectsOtherSchemes() = expectMessage("full address") { normalizeBaseUrl("ftp://host") }

    private fun expectMessage(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains(fragment))
        }
    }
}
