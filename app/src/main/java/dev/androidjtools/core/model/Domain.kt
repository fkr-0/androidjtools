package dev.androidjtools.core.model

import java.time.Instant

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val bpm: Double? = null,
    val key: String? = null,
    val energy: Double? = null,
    val rating: Int? = null,
    val offlineAvailable: Boolean = false,
)

data class Cue(
    val id: String,
    val trackId: String,
    val positionMs: Long,
    val role: String = "cue",
    val label: String? = null,
    val colorArgb: Long? = null,
    val hotCueNumber: Int? = null,
)

data class Loop(
    val id: String,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val role: String = "loop",
    val label: String? = null,
)

data class BeatGrid(
    val trackId: String,
    val anchorMs: Long,
    val bpm: Double,
    val revision: Long,
)

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val smart: Boolean = false,
    val ruleSummary: String? = null,
)

enum class DownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, AVAILABLE, FAILED, CANCELLED, CORRUPT }

data class TrackDownloadState(
    val trackId: String,
    val status: DownloadStatus,
    val progress: Double = 0.0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
)

data class OfflineCacheSummary(
    val usedBytes: Long = 0L,
    val maxBytes: Long = 0L,
    val evictableBytes: Long = 0L,
    val pinnedTrackIds: Set<String> = emptySet(),
    val pinnedPlaylistIds: Set<String> = emptySet(),
)

data class PendingMutation(
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseRevision: Long? = null,
    val createdAt: Instant = Instant.EPOCH,
    val provenance: String? = null,
)

enum class ReceiptOutcome { APPLIED, NO_OP, REJECTED, CONFLICT }

data class MutationReceipt(
    val mutationId: String,
    val outcome: ReceiptOutcome,
    val authoritativeRevision: Long? = null,
    val detail: String? = null,
)

enum class SyncState {
    UNCONFIGURED, DISCOVERED, PAIRING, ONLINE_IDLE, PULLING, PUSHING, PENDING_LOCAL,
    OFFLINE, DEGRADED, CONFLICT, AUTH_REQUIRED, SERVER_INCOMPATIBLE, LOCAL_MIGRATION_REQUIRED,
}

enum class SuggestionKind {
    BPM, KEY, CUE, LOOP, REGION, STEM, TRANSCRIPT_MARKER, RELATED_TRACK,
    LOUDNESS_WARNING, SPECTRAL_OUTLIER_WARNING, MIX_PREP,
}

enum class SuggestionDecision { PROPOSED, ACCEPTED, REJECTED, SUPERSEDED }

data class IntelligenceSource(
    val service: String,
    val model: String? = null,
    val version: String? = null,
    val pipelineVersion: String? = null,
)

sealed interface SuggestionPayload {
    data class Bpm(val value: Double) : SuggestionPayload
    data class Key(val value: String) : SuggestionPayload
    data class Point(val positionMs: Long, val role: String, val label: String? = null) : SuggestionPayload
    data class Range(val startMs: Long, val endMs: Long, val role: String, val label: String? = null) : SuggestionPayload
    data class Stem(val stem: String, val mediaRef: String) : SuggestionPayload
    data class Transcript(val positionMs: Long, val text: String, val language: String? = null) : SuggestionPayload
    data class Related(val trackId: String, val dimensions: List<String>) : SuggestionPayload
    data class Warning(val code: String, val message: String, val severity: String) : SuggestionPayload
    data class Text(val title: String, val detail: String) : SuggestionPayload
}

data class AnalysisSuggestion(
    val id: String,
    val trackId: String,
    val kind: SuggestionKind,
    val payload: SuggestionPayload,
    val source: IntelligenceSource,
    val confidence: Double? = null,
    val generatedAt: Instant = Instant.EPOCH,
    val inputRevision: Long? = null,
    val decision: SuggestionDecision = SuggestionDecision.PROPOSED,
    val stale: Boolean = false,
)

data class AnalysisCapability(
    val id: String,
    val displayName: String,
    val available: Boolean,
    val kinds: Set<SuggestionKind>,
    val detail: String? = null,
)
