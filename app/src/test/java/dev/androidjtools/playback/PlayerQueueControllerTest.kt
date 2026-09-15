package dev.androidjtools.playback

import dev.androidjtools.core.provider.PlaybackProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueControllerTest {
    @Test
    fun queueOperationsAreDeterministic() {
        val playback = FakePlaybackProvider()
        val store = InMemoryQueueStateStore()
        val controller = PlayerQueueController(playback, store)

        controller.add("a")
        controller.add("b")
        controller.add("c")
        controller.playNow("b")
        controller.playNext("c")

        assertEquals(listOf("a", "b", "c"), controller.queue.value)

        controller.move(2, 0)
        assertEquals(listOf("c", "a", "b"), controller.queue.value)

        controller.remove("a")
        assertEquals(listOf("c", "b"), controller.queue.value)

        controller.clear()
        assertEquals(listOf("b"), controller.queue.value)
        assertEquals("b", controller.items().single { it.isCurrent }.trackId)
    }

    @Test
    fun transportUsesQueueAndPersistsPosition() {
        val playback = FakePlaybackProvider()
        val store = InMemoryQueueStateStore()
        val controller = PlayerQueueController(playback, store)

        controller.add("a")
        controller.add("b")
        controller.toggle()
        assertEquals("a", playback.currentTrackId.value)
        assertTrue(playback.isPlaying.value)

        controller.seek(12_345)
        assertEquals(12_345L, playback.positionMs.value)

        controller.next()
        assertEquals("b", playback.currentTrackId.value)
        controller.previous()
        assertEquals("a", playback.currentTrackId.value)

        controller.toggle()
        assertFalse(playback.isPlaying.value)
        assertEquals(false, store.load()?.playWhenReady)
    }

    @Test
    fun commandPersistenceDoesNotDependOnImmediateStateFlowPublication() {
        val playback = DelayedPublicationPlaybackProvider()
        val store = InMemoryQueueStateStore()
        val controller = PlayerQueueController(playback, store)

        controller.add("a")
        controller.add("b")
        controller.playNow("a")
        assertEquals(null, playback.currentTrackId.value)
        assertEquals(QueueSnapshot(listOf("a", "b"), "a", 0L, true), store.load())

        controller.seek(12_345)
        assertEquals(0L, playback.positionMs.value)
        assertEquals(12_345L, store.load()?.positionMs)

        controller.toggle()
        assertFalse(store.load()?.playWhenReady ?: true)
        assertEquals(12_345L, store.load()?.positionMs)

        controller.next()
        assertEquals(QueueSnapshot(listOf("a", "b"), "b", 0L, true), store.load())
        assertEquals("b", playback.actualTrackId)
        assertTrue(playback.actualPlaying)
    }

    @Test
    fun pausedRestoreDoesNotDependOnImmediateStateFlowPublication() {
        val store = InMemoryQueueStateStore(
            QueueSnapshot(
                trackIds = listOf("a", "b"),
                currentTrackId = "b",
                positionMs = 42_000,
                playWhenReady = false,
            )
        )
        val playback = DelayedPublicationPlaybackProvider()

        val restored = PlayerQueueController(playback, store)

        assertEquals(null, playback.currentTrackId.value)
        assertFalse(playback.isPlaying.value)
        assertEquals("b", playback.actualTrackId)
        assertEquals(42_000L, playback.actualPositionMs)
        assertFalse(playback.actualPlaying)
        assertEquals(QueueSnapshot(listOf("a", "b"), "b", 42_000L, false), restored.snapshot())
    }

    @Test
    fun recreationRestoresQueueCurrentTrackPositionAndPauseState() {
        val store = InMemoryQueueStateStore(
            QueueSnapshot(
                trackIds = listOf("a", "b", "c"),
                currentTrackId = "b",
                positionMs = 42_000,
                playWhenReady = false,
            )
        )
        val playback = FakePlaybackProvider()

        val restored = PlayerQueueController(playback, store)

        assertEquals(listOf("a", "b", "c"), restored.queue.value)
        assertEquals("b", playback.currentTrackId.value)
        assertEquals(42_000L, playback.positionMs.value)
        assertFalse(playback.isPlaying.value)
        assertEquals("b", restored.items().single { it.isCurrent }.trackId)
    }

    private class DelayedPublicationPlaybackProvider : PlaybackProvider {
        private val publishedTrack = MutableStateFlow<String?>(null)
        private val publishedPlaying = MutableStateFlow(false)
        private val publishedPosition = MutableStateFlow(0L)
        override val currentTrackId: StateFlow<String?> = publishedTrack
        override val isPlaying: StateFlow<Boolean> = publishedPlaying
        override val positionMs: StateFlow<Long> = publishedPosition

        var actualTrackId: String? = null
            private set
        var actualPlaying: Boolean = false
            private set
        var actualPositionMs: Long = 0L
            private set

        override fun play(trackId: String) {
            actualTrackId = trackId
            actualPlaying = true
            actualPositionMs = 0L
        }

        override fun toggle() {
            actualPlaying = !actualPlaying
        }

        override fun seek(positionMs: Long) {
            actualPositionMs = positionMs.coerceAtLeast(0L)
        }
    }

    private class FakePlaybackProvider : PlaybackProvider {
        override val currentTrackId: StateFlow<String?> = MutableStateFlow(null)
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override val positionMs: StateFlow<Long> = MutableStateFlow(0L)

        @Suppress("UNCHECKED_CAST")
        private fun <T> StateFlow<T>.mutable() = this as MutableStateFlow<T>

        override fun play(trackId: String) {
            currentTrackId.mutable().value = trackId
            isPlaying.mutable().value = true
            positionMs.mutable().value = 0L
        }

        override fun toggle() {
            isPlaying.mutable().value = !isPlaying.value
        }

        override fun seek(positionMs: Long) {
            this.positionMs.mutable().value = positionMs.coerceAtLeast(0L)
        }
    }
}
