package dev.androidjtools.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface QueueStateStore {
    fun load(): QueueSnapshot?
    fun save(snapshot: QueueSnapshot)
}

class InMemoryQueueStateStore(initial: QueueSnapshot? = null) : QueueStateStore {
    private var snapshot = initial

    override fun load(): QueueSnapshot? = snapshot

    override fun save(snapshot: QueueSnapshot) {
        this.snapshot = snapshot
    }
}

class SharedPreferencesQueueStateStore(context: Context) : QueueStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): QueueSnapshot? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val queueJson = json.getJSONArray("trackIds")
            val ids = buildList(queueJson.length()) {
                for (index in 0 until queueJson.length()) add(queueJson.getString(index))
            }
            QueueSnapshot(
                trackIds = ids,
                currentTrackId = json.optString("currentTrackId").takeIf { it.isNotBlank() },
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                playWhenReady = json.optBoolean("playWhenReady", false),
            )
        }.getOrNull()
    }

    override fun save(snapshot: QueueSnapshot) {
        val json = JSONObject()
            .put("trackIds", JSONArray(snapshot.trackIds))
            .put("positionMs", snapshot.positionMs)
            .put("playWhenReady", snapshot.playWhenReady)
        snapshot.currentTrackId?.let { json.put("currentTrackId", it) }
        preferences.edit().putString(KEY_SNAPSHOT, json.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "android-dj-tools-player-queue"
        const val KEY_SNAPSHOT = "queue-snapshot-v1"
    }
}
