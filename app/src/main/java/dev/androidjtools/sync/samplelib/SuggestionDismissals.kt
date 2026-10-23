package dev.androidjtools.sync.samplelib

import dev.androidjtools.sync.journal.DurableMutationJournal
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Suggestions the person dismissed on this device, keyed by suggestion id and revision. Local only: nothing is sent
 * anywhere. Because the revision is part of the key, a suggestion the server replaced (new id or new revision) is a
 * different suggestion and shows up again.
 */
interface SuggestionDismissals {
    val keys: StateFlow<Set<String>>

    /** Loads what was saved earlier. Blocking disk I/O, idempotent: call it off the main thread. */
    fun ensureLoaded()

    /** Blocking disk I/O when persistent. The suggestion is hidden at once even if saving fails. */
    fun dismiss(suggestionId: String, revision: String)

    /** Forget server and server switching: dismissals belong to the server they were made against. */
    fun clear()

    fun isDismissed(suggestionId: String, revision: String): Boolean = keyOf(suggestionId, revision) in keys.value

    companion object {
        fun keyOf(suggestionId: String, revision: String) = "$suggestionId@$revision"
    }
}

private const val MAX_DISMISSALS = 2_000

class InMemorySuggestionDismissals : SuggestionDismissals {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    override val keys: StateFlow<Set<String>> = state.asStateFlow()
    override fun ensureLoaded() = Unit
    override fun dismiss(suggestionId: String, revision: String) {
        state.value = bounded(state.value, SuggestionDismissals.keyOf(suggestionId, revision))
    }
    override fun clear() {
        state.value = emptySet()
    }
}

internal fun bounded(current: Set<String>, added: String): Set<String> {
    val ordered = LinkedHashSet(current).apply { remove(added); add(added) }
    return if (ordered.size <= MAX_DISMISSALS) ordered else ordered.toList().takeLast(MAX_DISMISSALS).toCollection(LinkedHashSet())
}

class FileSuggestionDismissals(private val file: File) : SuggestionDismissals {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    override val keys: StateFlow<Set<String>> = state.asStateFlow()
    private val lock = Any()
    private var loaded = false

    override fun ensureLoaded() = synchronized(lock) {
        if (loaded) return@synchronized
        loaded = true
        val saved = try {
            (Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        } catch (_: Exception) {
            emptyList() // missing or unreadable: nothing was dismissed, which only means suggestions show again
        }
        state.value = LinkedHashSet(saved) + state.value
    }

    override fun dismiss(suggestionId: String, revision: String) = synchronized(lock) {
        ensureLoaded()
        state.value = bounded(state.value, SuggestionDismissals.keyOf(suggestionId, revision))
        persist()
    }

    override fun clear() = synchronized(lock) {
        loaded = true
        state.value = emptySet()
        file.delete()
        Unit
    }

    private fun persist() {
        try {
            AtomicFiles.write(file, JsonArray(state.value.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Hidden for this run is still the right answer; it will show again after a restart.
        }
    }
}

/**
 * Everything the device holds that belongs to one particular server and must not outlive it: unsent and unanswered
 * edits of every operation family, and the local dismissals. False when the journal could not be cleared.
 */
fun discardServerBoundState(journal: Lazy<DurableMutationJournal>, dismissals: SuggestionDismissals): Boolean {
    val editsDropped = try {
        journal.value.clear()
        true
    } catch (_: IOException) {
        false
    }
    if (editsDropped) dismissals.clear()
    return editsDropped
}
