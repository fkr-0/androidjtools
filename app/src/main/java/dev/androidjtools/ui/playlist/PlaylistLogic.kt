package dev.androidjtools.ui.playlist

import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.Track
import java.util.Locale

data class CratePath(val segments: List<String>) {
    val displayName: String get() = segments.joinToString(" / ").ifBlank { "Unfiled" }

    companion object {
        fun parse(raw: String): CratePath = CratePath(
            raw.split('/', '>')
                .map(String::trim)
                .filter(String::isNotEmpty),
        )
    }
}

enum class SmartMatchMode { ALL, ANY }

enum class SmartField(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    BPM("BPM"),
    KEY("Key"),
    RATING("Rating"),
    ENERGY("Energy"),
    OFFLINE("Offline"),
}

enum class SmartOperator(val label: String) {
    CONTAINS("contains"),
    EQUALS("equals"),
    AT_LEAST("at least"),
    AT_MOST("at most"),
    IS_TRUE("is true"),
}

sealed interface SmartRule {
    data class Condition(
        val field: SmartField,
        val operator: SmartOperator,
        val value: String = "",
    ) : SmartRule

    data class Group(
        val mode: SmartMatchMode,
        val children: List<SmartRule>,
    ) : SmartRule
}

data class SavedSmartFilter(
    val id: String,
    val name: String,
    val rule: SmartRule.Group,
)

data class PlaylistDraft(
    val id: String,
    val name: String,
    val cratePath: CratePath = CratePath(emptyList()),
    val trackIds: List<String> = emptyList(),
    val smartRule: SmartRule.Group? = null,
    val importedRuleSummary: String? = null,
) {
    val isSmart: Boolean get() = smartRule != null || importedRuleSummary != null
}

data class PlaylistMutationIntent(
    val playlistId: String,
    val operation: String,
    val targetLabel: String,
    val detail: String,
)

data class PlaylistDeleteUndo(
    val removed: PlaylistDraft,
    val index: Int,
)

class LocalPlaylistIdAllocator(startAt: Long = 1L) {
    private var nextOrdinal = startAt.coerceAtLeast(1L)

    fun next(existingIds: Collection<String>): String {
        val occupied = existingIds.toHashSet()
        while (true) {
            val candidate = "local-ui-${nextOrdinal++}"
            if (candidate !in occupied) return candidate
        }
    }
}

fun Playlist.toDraft(cratePath: CratePath = CratePath(emptyList())) = PlaylistDraft(
    id = id,
    name = name,
    cratePath = cratePath,
    trackIds = trackIds,
    importedRuleSummary = if (smart) ruleSummary ?: "Backend smart rule" else null,
)

fun validatePlaylistDraft(draft: PlaylistDraft, siblings: List<PlaylistDraft> = emptyList()): List<String> = buildList {
    if (draft.name.isBlank()) add("Playlist name is required")
    if (draft.name.length > 80) add("Playlist name must be 80 characters or fewer")
    if (draft.cratePath.segments.any { it.length > 60 }) add("Crate path segments must be 60 characters or fewer")
    if (siblings.any { it.id != draft.id && it.name.equals(draft.name, ignoreCase = true) && it.cratePath == draft.cratePath }) {
        add("A playlist with this name already exists in ${draft.cratePath.displayName}")
    }
    draft.smartRule?.let { addAll(validateSmartRule(it)) }
}

fun addTracks(draft: PlaylistDraft, trackIds: Collection<String>): PlaylistDraft = draft.copy(
    trackIds = (draft.trackIds + trackIds).distinct(),
)

fun removeTrack(draft: PlaylistDraft, trackId: String): PlaylistDraft = draft.copy(
    trackIds = draft.trackIds.filterNot { it == trackId },
)

fun moveTrack(draft: PlaylistDraft, fromIndex: Int, toIndex: Int): PlaylistDraft {
    if (fromIndex !in draft.trackIds.indices || toIndex !in draft.trackIds.indices || fromIndex == toIndex) return draft
    val ids = draft.trackIds.toMutableList()
    val moved = ids.removeAt(fromIndex)
    ids.add(toIndex, moved)
    return draft.copy(trackIds = ids)
}

fun deletePlaylist(drafts: List<PlaylistDraft>, playlistId: String): Pair<List<PlaylistDraft>, PlaylistDeleteUndo?> {
    val index = drafts.indexOfFirst { it.id == playlistId }
    if (index < 0) return drafts to null
    val removed = drafts[index]
    return drafts.toMutableList().apply { removeAt(index) } to PlaylistDeleteUndo(removed, index)
}

fun restorePlaylist(drafts: List<PlaylistDraft>, undo: PlaylistDeleteUndo): List<PlaylistDraft> =
    drafts.toMutableList().apply { add(undo.index.coerceIn(0, size), undo.removed) }

fun validateSmartRule(rule: SmartRule): List<String> = when (rule) {
    is SmartRule.Condition -> buildList {
        val numeric = rule.field in setOf(SmartField.BPM, SmartField.RATING, SmartField.ENERGY)
        val boolean = rule.field == SmartField.OFFLINE
        val text = !numeric && !boolean
        if (boolean && rule.operator !in setOf(SmartOperator.IS_TRUE, SmartOperator.EQUALS)) {
            add("${rule.field.label} only supports boolean matching")
        }
        if (boolean && rule.operator == SmartOperator.EQUALS && rule.value.toBooleanStrictOrNull() == null) {
            add("${rule.field.label} requires true or false")
        }
        if (numeric && rule.operator !in setOf(SmartOperator.EQUALS, SmartOperator.AT_LEAST, SmartOperator.AT_MOST)) {
            add("${rule.field.label} only supports numeric comparisons")
        }
        if (numeric && rule.value.toDoubleOrNull() == null) add("${rule.field.label} requires a numeric value")
        if (text && rule.operator !in setOf(SmartOperator.CONTAINS, SmartOperator.EQUALS)) {
            add("${rule.field.label} only supports text matching")
        }
        if (text && rule.value.isBlank()) add("${rule.field.label} requires a value")
    }
    is SmartRule.Group -> buildList {
        if (rule.children.isEmpty()) add("Rule group must contain at least one condition")
        rule.children.forEach { addAll(validateSmartRule(it)) }
    }
}

fun previewSmartRule(tracks: List<Track>, rule: SmartRule.Group): List<Track> =
    if (validateSmartRule(rule).isNotEmpty()) emptyList() else tracks.filter { matchesSmartRule(it, rule) }

fun matchesSmartRule(track: Track, rule: SmartRule): Boolean = when (rule) {
    is SmartRule.Group -> when (rule.mode) {
        SmartMatchMode.ALL -> rule.children.all { matchesSmartRule(track, it) }
        SmartMatchMode.ANY -> rule.children.any { matchesSmartRule(track, it) }
    }
    is SmartRule.Condition -> conditionMatches(track, rule)
}

fun describeSmartRule(rule: SmartRule): String = when (rule) {
    is SmartRule.Condition -> when (rule.operator) {
        SmartOperator.IS_TRUE -> "${rule.field.label} ${rule.operator.label}"
        else -> "${rule.field.label} ${rule.operator.label} ${rule.value.ifBlank { "—" }}"
    }
    is SmartRule.Group -> {
        val joiner = if (rule.mode == SmartMatchMode.ALL) " AND " else " OR "
        rule.children.joinToString(joiner, prefix = "(", postfix = ")") { describeSmartRule(it) }
    }
}

private fun conditionMatches(track: Track, condition: SmartRule.Condition): Boolean {
    val textValue = when (condition.field) {
        SmartField.TITLE -> track.title
        SmartField.ARTIST -> track.artist
        SmartField.ALBUM -> track.album.orEmpty()
        SmartField.KEY -> track.key.orEmpty()
        else -> null
    }
    if (textValue != null) {
        return when (condition.operator) {
            SmartOperator.CONTAINS -> textValue.lowercase(Locale.ROOT).contains(condition.value.lowercase(Locale.ROOT))
            SmartOperator.EQUALS -> textValue.equals(condition.value, ignoreCase = true)
            else -> false
        }
    }

    if (condition.field == SmartField.OFFLINE) {
        val expected = condition.value.ifBlank { "true" }.toBooleanStrictOrNull() ?: true
        return track.offlineAvailable == expected
    }

    val actual = when (condition.field) {
        SmartField.BPM -> track.bpm
        SmartField.RATING -> track.rating?.toDouble()
        SmartField.ENERGY -> track.energy
        else -> null
    } ?: return false
    val expected = condition.value.toDoubleOrNull() ?: return false
    return when (condition.operator) {
        SmartOperator.EQUALS -> actual == expected
        SmartOperator.AT_LEAST -> actual >= expected
        SmartOperator.AT_MOST -> actual <= expected
        else -> false
    }
}
