package dev.androidjtools.playback

data class QueueSnapshot(
    val trackIds: List<String> = emptyList(),
    val currentTrackId: String? = null,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = false,
) {
    init {
        require(positionMs >= 0) { "positionMs must be non-negative" }
        require(currentTrackId == null || currentTrackId in trackIds) {
            "currentTrackId must be present in the queue"
        }
    }
}

data class QueueItemState(
    val trackId: String,
    val isCurrent: Boolean,
)
