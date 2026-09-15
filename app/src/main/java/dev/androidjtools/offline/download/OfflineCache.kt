package dev.androidjtools.offline.download

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class OfflineCacheStorage(private val directory: File) {
    init {
        directory.mkdirs()
    }

    fun partialFile(assetId: String): File = File(directory, "${cacheKey(assetId)}.part")
    fun finalFile(assetId: String): File = File(directory, "${cacheKey(assetId)}.media")

    fun complete(assetId: String): File {
        val partial = partialFile(assetId)
        require(partial.isFile) { "partial media is missing for $assetId" }
        val final = finalFile(assetId)
        try {
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return final
    }

    fun delete(assetId: String) {
        partialFile(assetId).delete()
        finalFile(assetId).delete()
    }

    /** Returns bytes freed only when both cache files are gone; null keeps metadata intact. */
    fun evict(assetId: String): Long? {
        val partial = partialFile(assetId)
        val final = finalFile(assetId)
        val before = partial.length() + final.length()
        partial.delete()
        final.delete()
        if (partial.exists() || final.exists()) return null
        return before
    }

    fun deletePartial(assetId: String) {
        partialFile(assetId).delete()
    }

    fun deleteFinal(assetId: String) {
        finalFile(assetId).delete()
    }

    fun usedBytes(): Long = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)

    fun verifyFinal(record: OfflineDownloadRecord): Boolean {
        val file = finalFile(record.asset.assetId)
        return file.isFile && sha256Hex(file).equals(record.asset.normalizedSha256, ignoreCase = true)
    }

    fun verifyPartial(record: OfflineDownloadRecord): Boolean {
        val file = partialFile(record.asset.assetId)
        return file.isFile && sha256Hex(file).equals(record.asset.normalizedSha256, ignoreCase = true)
    }

    private fun cacheKey(assetId: String): String = sha256Hex(assetId.toByteArray(Charsets.UTF_8))
}

data class QuotaResult(
    val allowed: Boolean,
    val freedBytes: Long = 0L,
    val shortfallBytes: Long = 0L,
)

class OfflineCacheQuotaManager(
    private val store: OfflineDownloadStore,
    private val storage: OfflineCacheStorage,
    private val maxBytes: Long,
) {
    private val lock = Any()

    init {
        require(maxBytes > 0L) { "cache quota must be positive" }
    }

    /** Makes room for additional bytes without ever deleting a pinned or active asset. */
    fun ensureCapacity(protectedAssetId: String, additionalBytes: Long): QuotaResult = synchronized(lock) {
        ensureCapacityLocked(protectedAssetId, additionalBytes)
    }

    /**
     * Serializes capacity admission with the actual write. Without holding this lock through the
     * write, two WorkManager workers could both observe the same free bytes and oversubscribe the
     * quota before either chunk became visible in storage.usedBytes().
     */
    fun writeWithinQuota(protectedAssetId: String, additionalBytes: Long, write: () -> Unit): QuotaResult =
        synchronized(lock) {
            val result = ensureCapacityLocked(protectedAssetId, additionalBytes)
            if (result.allowed) write()
            result
        }

    private fun ensureCapacityLocked(protectedAssetId: String, additionalBytes: Long): QuotaResult {
        if (additionalBytes <= 0L) return QuotaResult(allowed = true)
        var used = storage.usedBytes()
        if (used + additionalBytes <= maxBytes) return QuotaResult(allowed = true)

        var freed = 0L
        val candidates = store.all()
            .asSequence()
            .filter {
                it.asset.assetId != protectedAssetId &&
                    !it.pinned &&
                    it.phase != OfflineDownloadPhase.QUEUED &&
                    it.phase != OfflineDownloadPhase.DOWNLOADING
            }
            .sortedBy { it.lastAccessEpochMs }
            .toList()

        for (record in candidates) {
            val evicted = store.evictIfEligible(record.asset.assetId) {
                storage.evict(record.asset.assetId)
            } ?: continue
            freed += evicted
            // Re-read actual bytes rather than trusting pre-delete lengths; failed or partial file
            // operations must never make the quota accounting optimistic.
            used = storage.usedBytes()
            if (used + additionalBytes <= maxBytes) return QuotaResult(allowed = true, freedBytes = freed)
        }

        return QuotaResult(
            allowed = false,
            freedBytes = freed,
            shortfallBytes = (used + additionalBytes - maxBytes).coerceAtLeast(0L),
        )
    }
}

class OfflineMediaResolver(
    private val store: OfflineDownloadStore,
    private val storage: OfflineCacheStorage,
) {
    /** No network access occurs here; safe to call in airplane mode. */
    fun resolve(assetId: String): OfflineCachedMedia? {
        val record = store.get(assetId) ?: return null
        if (record.phase != OfflineDownloadPhase.AVAILABLE) return null
        val file = storage.finalFile(assetId)
        if (!storage.verifyFinal(record)) {
            storage.deleteFinal(assetId)
            store.update(assetId) {
                it.copy(
                    phase = OfflineDownloadPhase.CORRUPT,
                    bytesDownloaded = storage.partialFile(assetId).length(),
                    error = OfflineDownloadError(
                        DownloadFailureKind.INTEGRITY,
                        "cached media failed SHA-256 revalidation",
                        retryable = true,
                    ),
                )
            }
            return null
        }
        store.touch(assetId)
        return OfflineCachedMedia(
            assetId = assetId,
            uri = file.toURI().toString(),
            contentSha256 = record.asset.normalizedSha256,
            sizeBytes = file.length(),
        )
    }

    fun revalidate(assetId: String): Boolean = resolve(assetId) != null
}
