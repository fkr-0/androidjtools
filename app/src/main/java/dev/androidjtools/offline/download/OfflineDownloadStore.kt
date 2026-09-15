package dev.androidjtools.offline.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small durable metadata store intentionally independent of remote/provider packages.
 * WorkManager jobs and foreground UI share this store; after process death it rebuilds
 * records entirely from app-private files keyed by a hash of canonical asset identity.
 */
class OfflineDownloadStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val records = linkedMapOf<String, OfflineDownloadRecord>()
    private val observers = mutableMapOf<String, MutableStateFlow<OfflineDownloadRecord?>>()
    private val mutableRecords = MutableStateFlow<Map<String, OfflineDownloadRecord>>(emptyMap())

    val allRecords: StateFlow<Map<String, OfflineDownloadRecord>> = mutableRecords.asStateFlow()

    init {
        directory.mkdirs()
        synchronized(lock) {
            directory.listFiles { file -> file.extension == "properties" }
                .orEmpty()
                .sortedBy(File::getName)
                .mapNotNull(::loadRecord)
                .forEach { records[it.asset.assetId] = it }
            publishLocked()
        }
    }

    fun all(): List<OfflineDownloadRecord> = synchronized(lock) { records.values.toList() }

    fun get(assetId: String): OfflineDownloadRecord? = synchronized(lock) { records[assetId] }

    fun observe(assetId: String): StateFlow<OfflineDownloadRecord?> = synchronized(lock) {
        observers.getOrPut(assetId) { MutableStateFlow(records[assetId]) }.asStateFlow()
    }

    fun ensure(asset: OfflineAsset): OfflineDownloadRecord = synchronized(lock) {
        val current = records[asset.assetId]
        val next = when {
            current == null -> OfflineDownloadRecord(asset = asset, lastAccessEpochMs = clock())
            current.asset.normalizedSha256 != asset.normalizedSha256 || current.asset.resourceUri != asset.resourceUri ->
                current.copy(
                    asset = asset,
                    phase = OfflineDownloadPhase.NOT_CACHED,
                    bytesDownloaded = 0L,
                    totalBytes = asset.contentLength,
                    currentEtag = asset.advertisedEtag,
                    currentLastModified = asset.advertisedLastModified,
                    cancelRequested = false,
                    error = null,
                )
            else -> current.copy(asset = asset)
        }
        persistLocked(next)
        next
    }

    fun update(assetId: String, transform: (OfflineDownloadRecord) -> OfflineDownloadRecord): OfflineDownloadRecord? =
        synchronized(lock) {
            val current = records[assetId] ?: return@synchronized null
            val next = transform(current)
            require(next.asset.assetId == assetId) { "asset identity cannot change during update" }
            persistLocked(next)
            next
        }

    fun touch(assetId: String): OfflineDownloadRecord? = update(assetId) { it.copy(lastAccessEpochMs = clock()) }

    /**
     * Linearizes cache eviction against pin/state updates. The deletion callback intentionally
     * executes while the store lock is held so a concurrent pin cannot become visible between
     * the eligibility check and removal of the corresponding media bytes.
     */
    internal fun evictIfEligible(assetId: String, deleteMedia: () -> Long?): Long? = synchronized(lock) {
        val current = records[assetId] ?: return@synchronized null
        if (
            current.pinned ||
            current.phase == OfflineDownloadPhase.QUEUED ||
            current.phase == OfflineDownloadPhase.DOWNLOADING
        ) {
            return@synchronized null
        }
        val freedBytes = deleteMedia() ?: return@synchronized null
        persistLocked(
            current.copy(
                phase = OfflineDownloadPhase.NOT_CACHED,
                bytesDownloaded = 0L,
                totalBytes = current.asset.contentLength,
                currentEtag = current.asset.advertisedEtag,
                currentLastModified = current.asset.advertisedLastModified,
                cancelRequested = false,
                error = null,
            ),
        )
        freedBytes
    }

    private fun persistLocked(record: OfflineDownloadRecord) {
        directory.mkdirs()
        val destination = recordFile(record.asset.assetId)
        val temp = File(destination.parentFile, ".${destination.name}.tmp")
        val properties = record.toProperties()
        FileOutputStream(temp).use { output ->
            properties.store(output, "androidjtools offline cache state")
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        records[record.asset.assetId] = record
        publishLocked(record.asset.assetId)
    }

    private fun publishLocked(assetId: String? = null) {
        mutableRecords.value = records.toMap()
        assetId?.let { observers[it]?.value = records[it] }
    }

    private fun loadRecord(file: File): OfflineDownloadRecord? = runCatching {
        val p = Properties()
        FileInputStream(file).use(p::load)
        val asset = OfflineAsset(
            assetId = p.required("assetId"),
            resourceUri = p.required("resourceUri"),
            contentSha256 = p.required("contentSha256"),
            contentLength = p.getProperty("contentLength")?.toLongOrNull(),
            supportsRange = p.getProperty("supportsRange")?.toBooleanStrictOrNull() ?: true,
            advertisedEtag = p.getProperty("advertisedEtag"),
            advertisedLastModified = p.getProperty("advertisedLastModified"),
        )
        OfflineDownloadRecord(
            asset = asset,
            phase = p.getProperty("phase")?.let(OfflineDownloadPhase::valueOf) ?: OfflineDownloadPhase.NOT_CACHED,
            bytesDownloaded = p.getProperty("bytesDownloaded")?.toLongOrNull() ?: 0L,
            totalBytes = p.getProperty("totalBytes")?.toLongOrNull(),
            currentEtag = p.getProperty("currentEtag"),
            currentLastModified = p.getProperty("currentLastModified"),
            pinOwners = decodeSet(p.getProperty("pinOwners")),
            cancelRequested = p.getProperty("cancelRequested")?.toBooleanStrictOrNull() ?: false,
            lastAccessEpochMs = p.getProperty("lastAccessEpochMs")?.toLongOrNull() ?: 0L,
            error = p.getProperty("errorKind")?.let { kind ->
                OfflineDownloadError(
                    kind = DownloadFailureKind.valueOf(kind),
                    message = p.getProperty("errorMessage").orEmpty(),
                    retryable = p.getProperty("errorRetryable")?.toBooleanStrictOrNull() ?: false,
                )
            },
        )
    }.getOrNull()

    private fun recordFile(assetId: String): File = File(directory, "${sha256Hex(assetId.toByteArray())}.properties")

    private fun OfflineDownloadRecord.toProperties() = Properties().also { p ->
        p["assetId"] = asset.assetId
        p["resourceUri"] = asset.resourceUri
        p["contentSha256"] = asset.normalizedSha256
        asset.contentLength?.let { p["contentLength"] = it.toString() }
        p["supportsRange"] = asset.supportsRange.toString()
        asset.advertisedEtag?.let { p["advertisedEtag"] = it }
        asset.advertisedLastModified?.let { p["advertisedLastModified"] = it }
        p["phase"] = phase.name
        p["bytesDownloaded"] = bytesDownloaded.toString()
        totalBytes?.let { p["totalBytes"] = it.toString() }
        currentEtag?.let { p["currentEtag"] = it }
        currentLastModified?.let { p["currentLastModified"] = it }
        p["pinOwners"] = encodeSet(pinOwners)
        p["cancelRequested"] = cancelRequested.toString()
        p["lastAccessEpochMs"] = lastAccessEpochMs.toString()
        error?.let {
            p["errorKind"] = it.kind.name
            p["errorMessage"] = it.message
            p["errorRetryable"] = it.retryable.toString()
        }
    }

    private fun Properties.required(key: String): String = requireNotNull(getProperty(key)) { "missing $key" }

    private fun encodeSet(values: Set<String>): String = values.sorted().joinToString(",") { value ->
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decodeSet(value: String?): Set<String> = value
        ?.takeIf(String::isNotBlank)
        ?.split(',')
        ?.mapTo(linkedSetOf()) { encoded ->
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }
        ?: emptySet()
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .toHex()

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
