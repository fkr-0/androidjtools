package dev.androidjtools.offline.download

/**
 * Stops queued and running download work, flags running downloads so their loops end, then removes every record
 * and cached byte. Returns true only when nothing is left; callers must treat false as "not forgotten".
 */
fun clearOfflineDownloads(store: OfflineDownloadStore, storage: OfflineCacheStorage, scheduler: DownloadScheduler): Boolean {
    scheduler.cancelAll()
    store.all().forEach { record -> store.update(record.asset.assetId) { it.copy(cancelRequested = true) } }
    val filesGone = storage.deleteAll()
    val stateGone = store.clear()
    // A worker that was mid-write may have recreated a file between the two deletions: look once more.
    return filesGone && stateGone && storage.usedBytes() == 0L && store.all().isEmpty()
}
