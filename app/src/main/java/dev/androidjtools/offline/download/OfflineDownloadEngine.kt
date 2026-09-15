package dev.androidjtools.offline.download

import java.io.FileOutputStream
import java.io.IOException

class OfflineDownloadEngine(
    private val store: OfflineDownloadStore,
    private val storage: OfflineCacheStorage,
    private val quota: OfflineCacheQuotaManager,
    private val transport: RangeTransport,
    private val progressPersistBytes: Long = 256L * 1024L,
) {
    suspend fun download(asset: OfflineAsset): DownloadAttemptResult {
        val previous = store.get(asset.assetId)
        if (
            previous != null &&
            (previous.asset.normalizedSha256 != asset.normalizedSha256 || previous.asset.resourceUri != asset.resourceUri)
        ) {
            // A stable asset ID may point at a newly revised media resource. Never append stale
            // bytes or expose the old completed file under the replacement descriptor.
            storage.delete(asset.assetId)
        }
        var record = store.ensure(asset)
        if (record.cancelRequested) return DownloadAttemptResult.Cancelled

        if (record.phase == OfflineDownloadPhase.AVAILABLE && storage.verifyFinal(record)) {
            store.touch(asset.assetId)
            return DownloadAttemptResult.Success
        }
        if (storage.finalFile(asset.assetId).exists()) {
            storage.deleteFinal(asset.assetId)
            record = store.update(asset.assetId) {
                it.copy(
                    phase = OfflineDownloadPhase.CORRUPT,
                    error = OfflineDownloadError(
                        DownloadFailureKind.INTEGRITY,
                        "cached media failed SHA-256 verification",
                        retryable = true,
                    ),
                )
            } ?: record
        }

        var partialBytes = storage.partialFile(asset.assetId).length()
        if (partialBytes > 0L && !asset.supportsRange) {
            storage.deletePartial(asset.assetId)
            partialBytes = 0L
        }
        store.update(asset.assetId) {
            it.copy(
                phase = OfflineDownloadPhase.DOWNLOADING,
                bytesDownloaded = partialBytes,
                totalBytes = asset.contentLength,
                error = null,
            )
        }

        var forceFull = partialBytes == 0L
        var fallbackUsed = false
        while (true) {
            record = store.get(asset.assetId) ?: return protocolFailure(asset.assetId, "download state disappeared")
            if (record.cancelRequested) return cancelled(asset.assetId)

            val start = if (!forceFull) storage.partialFile(asset.assetId).length().takeIf { it > 0L } else null
            val validator = start?.let { record.currentEtag ?: record.currentLastModified }
            val response = try {
                transport.open(RangeRequest(asset, startByte = start, ifRange = validator))
            } catch (e: IOException) {
                return retry(asset.assetId, DownloadFailureKind.NETWORK, e.message ?: "network I/O failed")
            }

            response.use { incoming ->
                if (incoming.statusCode == 416 && start != null) {
                    if (storage.verifyPartial(record)) return finalizeVerified(asset.assetId)
                    if (!fallbackUsed) {
                        storage.deletePartial(asset.assetId)
                        resetResumeState(asset.assetId)
                        forceFull = true
                        fallbackUsed = true
                        return@use
                    }
                    return protocolFailure(asset.assetId, "server rejected byte range")
                }
                if (incoming.statusCode == 408 || incoming.statusCode == 429 || incoming.statusCode >= 500) {
                    return retry(asset.assetId, DownloadFailureKind.HTTP, "HTTP ${incoming.statusCode}")
                }
                if (incoming.statusCode !in 200..299) {
                    return fail(asset.assetId, DownloadFailureKind.HTTP, "HTTP ${incoming.statusCode}", retryable = false)
                }

                val append = when {
                    start == null -> false
                    incoming.statusCode == 200 -> false // range ignored or If-Range validator changed: full body replaces partial.
                    incoming.statusCode == 206 && incoming.contentRangeStart == start && validatorCompatible(record, incoming) -> true
                    else -> {
                        if (!fallbackUsed) {
                            storage.deletePartial(asset.assetId)
                            resetResumeState(asset.assetId)
                            forceFull = true
                            fallbackUsed = true
                            return@use
                        }
                        return protocolFailure(asset.assetId, "unsafe range response cannot be appended")
                    }
                }

                if (!append) storage.deletePartial(asset.assetId)
                val part = storage.partialFile(asset.assetId)
                part.parentFile?.mkdirs()
                val startingBytes = if (append) part.length() else 0L
                val responseTotal = incoming.contentLength?.let { length -> if (append) startingBytes + length else length }
                val total = asset.contentLength ?: responseTotal
                store.update(asset.assetId) {
                    it.copy(
                        phase = OfflineDownloadPhase.DOWNLOADING,
                        bytesDownloaded = startingBytes,
                        totalBytes = total,
                        currentEtag = incoming.etag ?: it.currentEtag,
                        currentLastModified = incoming.lastModified ?: it.currentLastModified,
                        error = null,
                    )
                }

                var persistedAt = startingBytes
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        if (store.get(asset.assetId)?.cancelRequested == true) return cancelled(asset.assetId)
                        val count = incoming.body.read(buffer)
                        if (count < 0) break
                        val quotaResult = quota.writeWithinQuota(asset.assetId, count.toLong()) {
                            output.write(buffer, 0, count)
                        }
                        if (!quotaResult.allowed) {
                            return fail(
                                asset.assetId,
                                DownloadFailureKind.QUOTA,
                                "cache quota shortfall ${quotaResult.shortfallBytes} bytes; pinned media preserved",
                                retryable = false,
                            )
                        }
                        val downloaded = part.length()
                        if (downloaded - persistedAt >= progressPersistBytes) {
                            store.update(asset.assetId) { it.copy(bytesDownloaded = downloaded, totalBytes = total) }
                            persistedAt = downloaded
                        }
                    }
                    output.fd.sync()
                }
                val downloaded = part.length()
                store.update(asset.assetId) { it.copy(bytesDownloaded = downloaded, totalBytes = total) }

                if (asset.contentLength != null && downloaded != asset.contentLength) {
                    return retry(
                        asset.assetId,
                        DownloadFailureKind.NETWORK,
                        "download ended at $downloaded bytes; expected ${asset.contentLength}",
                    )
                }
                val actualHash = sha256Hex(part)
                if (!actualHash.equals(asset.normalizedSha256, ignoreCase = true)) {
                    storage.deletePartial(asset.assetId)
                    resetResumeState(asset.assetId)
                    return retry(asset.assetId, DownloadFailureKind.INTEGRITY, "downloaded media SHA-256 mismatch")
                }
                return finalizeVerified(asset.assetId)
            }
            // A validator/range fallback requested a second, full response.
            if (!forceFull) return protocolFailure(asset.assetId, "resume fallback did not converge")
        }
    }

    private fun validatorCompatible(record: OfflineDownloadRecord, response: RangeResponse): Boolean {
        if (record.currentEtag != null && response.etag != null && record.currentEtag != response.etag) return false
        if (record.currentLastModified != null && response.lastModified != null && record.currentLastModified != response.lastModified) return false
        return true
    }

    private fun resetResumeState(assetId: String) {
        store.update(assetId) {
            it.copy(
                bytesDownloaded = 0L,
                currentEtag = it.asset.advertisedEtag,
                currentLastModified = it.asset.advertisedLastModified,
            )
        }
    }

    private fun finalizeVerified(assetId: String): DownloadAttemptResult {
        val record = store.get(assetId) ?: return protocolFailure(assetId, "download state disappeared")
        val part = storage.partialFile(assetId)
        if (!part.isFile || !sha256Hex(part).equals(record.asset.normalizedSha256, ignoreCase = true)) {
            return retry(assetId, DownloadFailureKind.INTEGRITY, "partial media failed final SHA-256 verification")
        }
        val final = storage.complete(assetId)
        store.update(assetId) {
            it.copy(
                phase = OfflineDownloadPhase.AVAILABLE,
                bytesDownloaded = final.length(),
                totalBytes = final.length(),
                cancelRequested = false,
                error = null,
            )
        }
        store.touch(assetId)
        return DownloadAttemptResult.Success
    }

    private fun retry(assetId: String, kind: DownloadFailureKind, message: String): DownloadAttemptResult.Retry {
        val error = OfflineDownloadError(kind, message, retryable = true)
        store.update(assetId) {
            it.copy(
                phase = OfflineDownloadPhase.QUEUED,
                bytesDownloaded = storage.partialFile(assetId).length(),
                error = error,
            )
        }
        return DownloadAttemptResult.Retry(error)
    }

    private fun fail(assetId: String, kind: DownloadFailureKind, message: String, retryable: Boolean): DownloadAttemptResult.Failed {
        val error = OfflineDownloadError(kind, message, retryable)
        store.update(assetId) {
            it.copy(
                phase = OfflineDownloadPhase.FAILED,
                bytesDownloaded = storage.partialFile(assetId).length(),
                error = error,
            )
        }
        return DownloadAttemptResult.Failed(error)
    }

    private fun protocolFailure(assetId: String, message: String): DownloadAttemptResult.Failed =
        fail(assetId, DownloadFailureKind.PROTOCOL, message, retryable = false)

    private fun cancelled(assetId: String): DownloadAttemptResult.Cancelled {
        store.update(assetId) {
            it.copy(
                phase = OfflineDownloadPhase.CANCELLED,
                bytesDownloaded = storage.partialFile(assetId).length(),
                error = OfflineDownloadError(DownloadFailureKind.CANCELLED, "download cancelled", retryable = true),
            )
        }
        return DownloadAttemptResult.Cancelled
    }
}
