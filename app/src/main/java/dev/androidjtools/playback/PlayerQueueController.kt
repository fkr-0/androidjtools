package dev.androidjtools.playback

import dev.androidjtools.core.provider.PlaybackProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerQueueController(
    private val playback: PlaybackProvider,
    private val store: QueueStateStore,
) {
    private data class TransportIntent(
        val currentTrackId: String?,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private val mutableQueue = MutableStateFlow<List<String>>(emptyList())
    val queue: StateFlow<List<String>> = mutableQueue.asStateFlow()

    private var transportIntent = TransportIntent(
        currentTrackId = playback.currentTrackId.value,
        positionMs = playback.positionMs.value.coerceAtLeast(0L),
        playWhenReady = playback.isPlaying.value,
    )

    init {
        restore()
    }

    fun add(trackId: String) {
        if (trackId !in mutableQueue.value) {
            mutableQueue.value = mutableQueue.value + trackId
            persist()
        }
    }

    fun playNow(trackId: String) {
        if (trackId !in mutableQueue.value) mutableQueue.value = mutableQueue.value + trackId
        playback.play(trackId)
        transportIntent = TransportIntent(trackId, positionMs = 0L, playWhenReady = true)
        persist()
    }

    fun playNext(trackId: String) {
        val queue = mutableQueue.value.toMutableList().apply { remove(trackId) }
        val currentIndex = transportIntent.currentTrackId?.let(queue::indexOf)?.takeIf { it >= 0 }
        val insertionIndex = if (currentIndex == null) 0 else currentIndex + 1
        queue.add(insertionIndex.coerceAtMost(queue.size), trackId)
        mutableQueue.value = queue
        persist()
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val queue = mutableQueue.value
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        mutableQueue.value = mutable
        persist()
    }

    fun remove(trackId: String) {
        val queue = mutableQueue.value
        if (trackId !in queue) return
        val wasCurrent = transportIntent.currentTrackId == trackId
        val oldIndex = queue.indexOf(trackId)
        val nextQueue = queue.filterNot { it == trackId }
        mutableQueue.value = nextQueue
        if (wasCurrent && nextQueue.isNotEmpty()) {
            val replacement = nextQueue[oldIndex.coerceAtMost(nextQueue.lastIndex)]
            playback.play(replacement)
            transportIntent = TransportIntent(replacement, positionMs = 0L, playWhenReady = true)
        } else if (wasCurrent) {
            if (transportIntent.playWhenReady) playback.toggle()
            transportIntent = TransportIntent(currentTrackId = null, positionMs = 0L, playWhenReady = false)
        }
        persist()
    }

    /** Clear queued-upcoming tracks while preserving the active audition. */
    fun clear() {
        val current = transportIntent.currentTrackId
        mutableQueue.value = current?.let(::listOf) ?: emptyList()
        persist()
    }

    fun toggle() {
        val current = transportIntent.currentTrackId
        if (current == null) {
            val first = mutableQueue.value.firstOrNull() ?: return
            playback.play(first)
            transportIntent = TransportIntent(first, positionMs = 0L, playWhenReady = true)
        } else {
            playback.toggle()
            transportIntent = transportIntent.copy(
                playWhenReady = !transportIntent.playWhenReady,
            )
        }
        persist()
    }

    fun seek(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        playback.seek(target)
        transportIntent = transportIntent.copy(positionMs = target)
        persist()
    }

    fun next() = step(+1)

    fun previous() = step(-1)

    fun items(): List<QueueItemState> = mutableQueue.value.map { id ->
        QueueItemState(id, isCurrent = id == transportIntent.currentTrackId)
    }

    fun snapshot(): QueueSnapshot = QueueSnapshot(
        trackIds = mutableQueue.value,
        currentTrackId = transportIntent.currentTrackId?.takeIf { it in mutableQueue.value },
        positionMs = transportIntent.positionMs,
        playWhenReady = transportIntent.playWhenReady,
    )

    private fun step(delta: Int) {
        val queue = mutableQueue.value
        if (queue.isEmpty()) return
        val currentIndex = transportIntent.currentTrackId?.let(queue::indexOf)?.takeIf { it >= 0 }
        val targetIndex = when {
            currentIndex == null -> if (delta > 0) 0 else queue.lastIndex
            else -> (currentIndex + delta).coerceIn(0, queue.lastIndex)
        }
        val targetId = queue[targetIndex]
        if (targetId != transportIntent.currentTrackId) {
            playback.play(targetId)
            transportIntent = TransportIntent(targetId, positionMs = 0L, playWhenReady = true)
        }
        persist()
    }

    private fun restore() {
        val snapshot = store.load() ?: return
        mutableQueue.value = snapshot.trackIds.distinct()
        transportIntent = TransportIntent(
            currentTrackId = snapshot.currentTrackId?.takeIf { it in mutableQueue.value },
            positionMs = snapshot.positionMs,
            playWhenReady = snapshot.playWhenReady,
        )
        val current = transportIntent.currentTrackId ?: return
        playback.play(current)
        playback.seek(transportIntent.positionMs)
        // PlaybackProvider.play() semantically starts playback. Toggle unconditionally for a
        // persisted paused intent instead of sampling a possibly-delayed isPlaying StateFlow.
        if (!transportIntent.playWhenReady) playback.toggle()
    }

    private fun persist() {
        store.save(snapshot())
    }
}
