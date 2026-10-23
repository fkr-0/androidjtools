package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibProfileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val profile = SampleLibProfile("https://samplelab.example/prefix", "server-1", "principal-1", "install-1")
    private val token = "super-secret-token-value"

    private fun store(cipher: CredentialCipher = FakeCipher()) = SampleLibProfileStore(File(tmp.root, "samplelib"), cipher)

    @Test
    fun emptyDirectoryIsAbsent() {
        assertEquals(StoredSessionResult.Absent, store().load())
    }

    @Test
    fun saveThenLoadRoundTripsProfileAndCredential() {
        store().save(profile, MobileCredential.issued(token))
        val loaded = store().load() as StoredSessionResult.Loaded
        assertEquals(profile, loaded.session.profile)
        assertEquals(token, loaded.session.credential.bearerValue())
    }

    @Test
    fun theGrantedScopesRoundTripAndAnOlderProfileWithoutThemLoadsAsUnknown() {
        val withScopes = profile.copy(scopes = setOf("sync.read", "analysis.read"))
        store().save(withScopes, MobileCredential.issued(token))
        assertEquals(setOf("sync.read", "analysis.read"), (store().load() as StoredSessionResult.Loaded).session.profile.scopes)

        File(tmp.root, "samplelib/samplelib-profile.json").writeText(
            """{"base_url":"https://samplelab.example/prefix","server_id":"server-1","principal_id":null,"installation_id":"install-1"}""",
        )
        assertEquals(null, (store().load() as StoredSessionResult.Loaded).session.profile.scopes)
    }

    @Test
    fun theCredentialNeverAppearsInPlaintextOnDisk() {
        store().save(profile, MobileCredential.issued(token))
        val all = File(tmp.root, "samplelib").walkTopDown().filter { it.isFile }.map { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(all.none { it.contains(token) })
        assertTrue(all.any { it.contains("server-1") })
    }

    @Test
    fun savingAgainReplacesTheCredentialAndLeavesNoTempFiles() {
        store().save(profile, MobileCredential.issued("old-token"))
        store().save(profile.copy(principalId = null), MobileCredential.issued("new-token"))
        val loaded = store().load() as StoredSessionResult.Loaded
        assertEquals("new-token", loaded.session.credential.bearerValue())
        assertEquals(null, loaded.session.profile.principalId)
        assertTrue(File(tmp.root, "samplelib").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun clearRemovesProfileAndCredential() {
        val store = store()
        store.save(profile, MobileCredential.issued(token))
        store.clear()
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(File(tmp.root, "samplelib").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun clearOnAnEmptyStoreIsHarmless() {
        store().clear()
        assertEquals(StoredSessionResult.Absent, store().load())
    }

    @Test
    fun aCredentialThatCannotBeDecryptedIsReportedWithTheProfileKept() {
        store().save(profile, MobileCredential.issued(token))
        val result = store(FakeCipher(failDecrypt = true)).load()
        val unreadable = result as StoredSessionResult.Unreadable
        assertEquals(profile, unreadable.profile)
        assertFalse(unreadable.reason.contains(token))
    }

    @Test
    fun aCorruptCredentialFileIsUnreadableNotACrash() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-credential.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store().load() is StoredSessionResult.Unreadable)
    }

    @Test
    fun aMissingCredentialFileIsUnreadableWithTheProfileKept() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-credential.bin").delete()
        val unreadable = store().load() as StoredSessionResult.Unreadable
        assertEquals(profile, unreadable.profile)
    }

    @Test
    fun aTruncatedProfileFileIsUnreadableNotACrash() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-profile.json").writeText("{\"base_url\":")
        val unreadable = store().load() as StoredSessionResult.Unreadable
        assertEquals(null, unreadable.profile)
    }

    private fun blockCredentialDeletion() {
        val credential = File(tmp.root, "samplelib/samplelib-credential.bin")
        credential.delete()
        credential.mkdirs()
        File(credential, "blocker").writeText("x") // a non-empty directory cannot be deleted by File.delete()
    }

    @Test
    fun clearDestroysTheCipherKeyExactlyOnce() {
        val cipher = FakeCipher()
        val store = store(cipher)
        store.save(profile, MobileCredential.issued(token))
        assertTrue(store.clear())
        assertEquals(1, cipher.destroyCalls)
    }

    @Test
    fun clearStillDestroysTheKeyWhenTheCredentialFileCannotBeDeleted() {
        val cipher = FakeCipher()
        val store = store(cipher)
        store.save(profile, MobileCredential.issued(token))
        blockCredentialDeletion()
        assertFalse(store.clear())
        assertEquals(1, cipher.destroyCalls)
    }

    @Test
    fun aFailingKeyDestroyNeitherThrowsNorChangesTheResult() {
        val cipher = FakeCipher(failDestroy = true)
        val store = store(cipher)
        store.save(profile, MobileCredential.issued(token))
        assertTrue(store.clear())
        assertEquals(1, cipher.destroyCalls)
        assertEquals(StoredSessionResult.Absent, store.load())

        store.save(profile, MobileCredential.issued(token))
        blockCredentialDeletion()
        assertFalse(store.clear())
        assertEquals(2, cipher.destroyCalls)
    }

    @Test
    fun savingAfterClearWorksAgain() {
        val store = store()
        store.save(profile, MobileCredential.issued("first"))
        store.clear()
        store.save(profile, MobileCredential.issued("second"))
        val loaded = store.load() as StoredSessionResult.Loaded
        assertEquals("second", loaded.session.credential.bearerValue())
    }

    @Test
    fun clearReportsWhetherEverythingWasRemoved() {
        assertTrue(store().clear())
        store().save(profile, MobileCredential.issued(token))
        assertTrue(store().clear())
        assertEquals(StoredSessionResult.Absent, store().load())

        store().save(profile, MobileCredential.issued(token))
        val credential = File(tmp.root, "samplelib/samplelib-credential.bin")
        credential.delete()
        credential.mkdirs()
        File(credential, "blocker").writeText("x") // a non-empty directory cannot be deleted by File.delete()
        assertFalse(store().clear())
    }
}
