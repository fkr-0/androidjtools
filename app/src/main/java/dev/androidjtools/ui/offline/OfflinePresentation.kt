package dev.androidjtools.ui.offline

import dev.androidjtools.core.model.DownloadStatus
import dev.androidjtools.core.model.OfflineCacheSummary
import dev.androidjtools.core.model.TrackDownloadState
import java.util.Locale

internal data class OfflineTrackActions(
    val canPin: Boolean,
    val canUnpin: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canPlay: Boolean,
)

internal fun offlineTrackActions(
    state: TrackDownloadState,
    pinned: Boolean,
): OfflineTrackActions = OfflineTrackActions(
    canPin = !pinned,
    canUnpin = pinned,
    canCancel = state.status == DownloadStatus.QUEUED || state.status == DownloadStatus.DOWNLOADING,
    canRetry = state.status == DownloadStatus.FAILED || state.status == DownloadStatus.CANCELLED || state.status == DownloadStatus.CORRUPT,
    canPlay = state.status == DownloadStatus.AVAILABLE,
)

internal fun downloadStatusText(state: TrackDownloadState): String = when (state.status) {
    DownloadStatus.NOT_DOWNLOADED -> "Not downloaded"
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading ${formatPercent(state.progress)}"
    DownloadStatus.AVAILABLE -> "Available offline"
    DownloadStatus.FAILED -> "Download failed"
    DownloadStatus.CANCELLED -> "Cancelled"
    DownloadStatus.CORRUPT -> "Needs repair"
}

internal fun cacheUsageText(summary: OfflineCacheSummary): String {
    val max = summary.maxBytes.takeIf { it > 0L }
    return if (max == null) {
        "${formatBytes(summary.usedBytes)} used"
    } else {
        "${formatBytes(summary.usedBytes)} of ${formatBytes(max)} used"
    }
}

internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1_000_000_000.0 -> String.format(Locale.ROOT, "%.1f GB", value / 1_000_000_000.0)
        value >= 1_000_000.0 -> String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0)
        value >= 1_000.0 -> String.format(Locale.ROOT, "%.1f KB", value / 1_000.0)
        else -> "${value.toLong()} B"
    }
}

private fun formatPercent(progress: Double): String =
    String.format(Locale.ROOT, "%.0f%%", progress.coerceIn(0.0, 1.0) * 100.0)
