package dev.androidjtools.sync.samplelib

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class AesGcmKeystoreCipherTest {
    private val cipher = AesGcmKeystoreCipher("androidjtools.samplelib.test")

    @After
    fun cleanUp() = cipher.deleteKey()

    @Test
    fun roundTripsAndHidesThePlaintext() {
        val plain = "secret-token".toByteArray()
        val encrypted = cipher.encrypt(plain)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("secret-token"))
        assertArrayEquals(plain, cipher.decrypt(encrypted))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val encrypted = cipher.encrypt("secret-token".toByteArray())
        encrypted[encrypted.size - 1] = (encrypted.last().toInt() xor 1).toByte()
        try {
            cipher.decrypt(encrypted)
            fail("tampered ciphertext must not decrypt")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun aDeletedKeyMakesOldCiphertextUnreadable() {
        val encrypted = cipher.encrypt("secret-token".toByteArray())
        cipher.deleteKey()
        try {
            cipher.decrypt(encrypted)
            fail("a regenerated key must not decrypt old data")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun destroyMakesOldCiphertextUnreadableAndANewKeyWorksAfterwards() {
        val encrypted = cipher.encrypt("secret-token".toByteArray())
        cipher.destroy()
        try {
            cipher.decrypt(encrypted)
            fail("destroy() must make earlier ciphertext undecryptable")
        } catch (expected: Exception) {
        }
        val fresh = "next-token".toByteArray()
        assertArrayEquals(fresh, cipher.decrypt(cipher.encrypt(fresh)))
    }
}
