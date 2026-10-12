package dev.androidjtools.sync.samplelib

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.androidjtools.core.model.SyncState
import java.util.concurrent.TimeUnit

/**
 * Periodic sync. [SampleLibSessionManager.refresh] makes no network request when no server is stored
 * (it reports UNCONFIGURED), so the worker never contacts anything for an unpaired app.
 */
class SampleLibSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = SampleLibRuntime.get(applicationContext).manager
        manager.refresh()
        return when (manager.state.value) {
            SyncState.OFFLINE, SyncState.DEGRADED -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Bounded periodic sync on unmetered networks only. Manual refresh is always available. */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<SampleLibSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object {
        const val UNIQUE_NAME = "samplelib-periodic-sync"
    }
}
