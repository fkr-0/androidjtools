package dev.androidjtools.offline.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class WorkManagerDownloadScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) : DownloadScheduler {
    override fun enqueue(asset: OfflineAsset, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(asset.toWorkData())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(assetTag(asset.assetId))
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(asset.assetId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel(assetId: String) {
        workManager.cancelUniqueWork(uniqueName(assetId))
    }

    companion object {
        private const val TAG = "offline-download"
        internal fun uniqueName(assetId: String): String = "offline-download-${sha256Hex(assetId.toByteArray()).take(24)}"
        private fun assetTag(assetId: String): String = "offline-asset-${sha256Hex(assetId.toByteArray()).take(24)}"
    }
}

class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val asset = inputData.toOfflineAsset() ?: return Result.failure()
        val runtime = OfflineDownloadRuntime.get(applicationContext)
        return when (val result = runtime.engine.download(asset)) {
            DownloadAttemptResult.Success -> Result.success()
            DownloadAttemptResult.Cancelled -> Result.failure()
            is DownloadAttemptResult.Failed -> Result.failure(
                Data.Builder().putString("error", result.error.message).build(),
            )
            is DownloadAttemptResult.Retry -> {
                if (runAttemptCount < MAX_RETRIES) Result.retry()
                else {
                    runtime.store.update(asset.assetId) {
                        it.copy(phase = OfflineDownloadPhase.FAILED, error = result.error.copy(retryable = false))
                    }
                    Result.failure(Data.Builder().putString("error", result.error.message).build())
                }
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}

data class OfflineDownloadRuntime(
    val store: OfflineDownloadStore,
    val storage: OfflineCacheStorage,
    val resolver: OfflineMediaResolver,
    val engine: OfflineDownloadEngine,
) {
    companion object {
        private const val DEFAULT_QUOTA_BYTES = 4L * 1024L * 1024L * 1024L
        @Volatile private var instance: OfflineDownloadRuntime? = null

        fun get(context: Context): OfflineDownloadRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        fun coordinator(context: Context): OfflineDownloadCoordinator {
            val runtime = get(context)
            return OfflineDownloadCoordinator(runtime.store, WorkManagerDownloadScheduler(context), runtime.resolver)
        }

        private fun create(context: Context): OfflineDownloadRuntime {
            val root = File(context.filesDir, "offline-downloads")
            val store = OfflineDownloadStore(File(root, "state"))
            val storage = OfflineCacheStorage(File(root, "media"))
            val resolver = OfflineMediaResolver(store, storage)
            val quota = OfflineCacheQuotaManager(store, storage, DEFAULT_QUOTA_BYTES)
            val transport = OkHttpRangeTransport(OkHttpClient())
            return OfflineDownloadRuntime(
                store = store,
                storage = storage,
                resolver = resolver,
                engine = OfflineDownloadEngine(store, storage, quota, transport),
            )
        }
    }
}

private const val KEY_ASSET_ID = "asset_id"
private const val KEY_RESOURCE_URI = "resource_uri"
private const val KEY_SHA256 = "content_sha256"
private const val KEY_LENGTH = "content_length"
private const val KEY_RANGE = "supports_range"
private const val KEY_ETAG = "etag"
private const val KEY_LAST_MODIFIED = "last_modified"

private fun OfflineAsset.toWorkData(): Data = Data.Builder()
    .putString(KEY_ASSET_ID, assetId)
    .putString(KEY_RESOURCE_URI, resourceUri)
    .putString(KEY_SHA256, normalizedSha256)
    .putLong(KEY_LENGTH, contentLength ?: -1L)
    .putBoolean(KEY_RANGE, supportsRange)
    .apply {
        advertisedEtag?.let { putString(KEY_ETAG, it) }
        advertisedLastModified?.let { putString(KEY_LAST_MODIFIED, it) }
    }
    .build()

private fun Data.toOfflineAsset(): OfflineAsset? = runCatching {
    OfflineAsset(
        assetId = requireNotNull(getString(KEY_ASSET_ID)),
        resourceUri = requireNotNull(getString(KEY_RESOURCE_URI)),
        contentSha256 = requireNotNull(getString(KEY_SHA256)),
        contentLength = getLong(KEY_LENGTH, -1L).takeIf { it >= 0L },
        supportsRange = getBoolean(KEY_RANGE, true),
        advertisedEtag = getString(KEY_ETAG),
        advertisedLastModified = getString(KEY_LAST_MODIFIED),
    )
}.getOrNull()
