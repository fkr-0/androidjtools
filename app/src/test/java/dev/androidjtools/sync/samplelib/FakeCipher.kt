package dev.androidjtools.sync.samplelib

/** Reversible stand-in for the Keystore cipher; output never equals the plaintext. */
class FakeCipher(
    private val failDecrypt: Boolean = false,
    private val failDestroy: Boolean = false,
) : CredentialCipher {
    /** How many times [destroy] was called. */
    var destroyCalls = 0
        private set

    override fun destroy() {
        destroyCalls++
        check(!failDestroy) { "keystore unavailable" }
    }

    override fun encrypt(plain: ByteArray): ByteArray =
        ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() } + byteArrayOf(0x7F)

    override fun decrypt(encrypted: ByteArray): ByteArray {
        check(!failDecrypt) { "key invalidated" }
        require(encrypted.isNotEmpty() && encrypted.last() == 0x7F.toByte()) { "bad ciphertext" }
        return ByteArray(encrypted.size - 1) { (encrypted[it].toInt() xor 0x5A).toByte() }
    }
}
