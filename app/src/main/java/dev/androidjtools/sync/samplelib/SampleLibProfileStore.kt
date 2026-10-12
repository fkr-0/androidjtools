package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SampleLibProfile(
    val baseUrl: String,
    val serverId: String,
    val principalId: String?,
    val installationId: String,
)

data class StoredSession(val profile: SampleLibProfile, val credential: MobileCredential)

sealed interface StoredSessionResult {
    data object Absent : StoredSessionResult
    data class Loaded(val session: StoredSession) : StoredSessionResult
    data class Unreadable(val profile: SampleLibProfile?, val reason: String) : StoredSessionResult
}

interface CredentialCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Persists the (non-secret) server profile and the encrypted credential in two separate files. */
class SampleLibProfileStore(private val dir: File, private val cipher: CredentialCipher) {
    private val profileFile = File(dir, "samplelib-profile.json")
    private val credentialFile = File(dir, "samplelib-credential.bin")

    fun save(profile: SampleLibProfile, credential: MobileCredential) {
        // Credential first: a profile without a credential is recoverable, the reverse never exists.
        AtomicFiles.write(credentialFile, cipher.encrypt(credential.bearerValue().toByteArray(Charsets.UTF_8)))
        AtomicFiles.write(profileFile, encode(profile).toString().toByteArray(Charsets.UTF_8))
    }

    fun load(): StoredSessionResult {
        if (!profileFile.exists()) return StoredSessionResult.Absent
        val profile = try {
            decode(Json.parseToJsonElement(profileFile.readText(Charsets.UTF_8)) as JsonObject)
        } catch (_: Exception) {
            return StoredSessionResult.Unreadable(null, "The stored server profile could not be read.")
        }
        if (!credentialFile.exists()) {
            return StoredSessionResult.Unreadable(profile, "The stored server credential is missing.")
        }
        val credential = try {
            MobileCredential.issued(String(cipher.decrypt(credentialFile.readBytes()), Charsets.UTF_8))
        } catch (_: Exception) {
            return StoredSessionResult.Unreadable(profile, "The stored server credential could not be decrypted.")
        }
        return StoredSessionResult.Loaded(StoredSession(profile, credential))
    }

    fun clear() {
        profileFile.delete()
        credentialFile.delete()
    }

    private fun encode(profile: SampleLibProfile): JsonObject = buildJsonObject {
        put("base_url", profile.baseUrl)
        put("server_id", profile.serverId)
        put("principal_id", profile.principalId?.let(::JsonPrimitive) ?: JsonNull)
        put("installation_id", profile.installationId)
    }

    private fun decode(obj: JsonObject): SampleLibProfile = SampleLibProfile(
        baseUrl = obj.getValue("base_url").jsonPrimitive.content,
        serverId = obj.getValue("server_id").jsonPrimitive.content,
        principalId = obj["principal_id"]?.jsonPrimitive?.contentOrNull,
        installationId = obj.getValue("installation_id").jsonPrimitive.content,
    )
}
