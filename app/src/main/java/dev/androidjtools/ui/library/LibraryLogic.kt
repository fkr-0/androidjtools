package dev.androidjtools.ui.library

import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.ProviderUiState
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

sealed interface LibrarySurfaceState {
    data object Ready : LibrarySurfaceState
    data object Loading : LibrarySurfaceState
    data object Empty : LibrarySurfaceState
    data object Offline : LibrarySurfaceState
    data class Error(val message: String) : LibrarySurfaceState
    data class Conflict(val detail: String) : LibrarySurfaceState
}

internal fun ProviderUiState.toLibrarySurfaceState(): LibrarySurfaceState = when (this) {
    ProviderUiState.Loading -> LibrarySurfaceState.Loading
    ProviderUiState.Ready -> LibrarySurfaceState.Ready
    ProviderUiState.Empty -> LibrarySurfaceState.Empty
    is ProviderUiState.Error -> LibrarySurfaceState.Error(message)
    ProviderUiState.Offline -> LibrarySurfaceState.Offline
    is ProviderUiState.Conflict -> LibrarySurfaceState.Conflict(detail)
}

enum class LibrarySort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    BPM("BPM"),
    KEY("Key"),
    RATING("Rating"),
    ;

    fun next(): LibrarySort = entries[(ordinal + 1) % entries.size]
}

enum class SortDirection(val label: String) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
    ;

    fun toggle(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

enum class BpmBand(val label: String) {
    ANY("Any BPM"),
    SLOW("< 100 BPM"),
    MID("100–129 BPM"),
    FAST("130+ BPM"),
    ;

    fun next(): BpmBand = entries[(ordinal + 1) % entries.size]

    fun matches(bpm: Double?): Boolean = when (this) {
        ANY -> true
        SLOW -> bpm != null && bpm < 100.0
        MID -> bpm != null && bpm >= 100.0 && bpm < 130.0
        FAST -> bpm != null && bpm >= 130.0
    }
}

enum class AvailabilityFilter(val label: String) {
    ANY("Any availability"),
    OFFLINE("Offline"),
    STREAMING("Streaming"),
    ;

    fun next(): AvailabilityFilter = entries[(ordinal + 1) % entries.size]
}

enum class AnalysisFilter(val label: String) {
    ANY("Any analysis"),
    ANALYZED("Analyzed"),
    NEEDS_ANALYSIS("Needs analysis"),
    ;

    fun next(): AnalysisFilter = entries[(ordinal + 1) % entries.size]
}

data class LibraryFilters(
    val bpmBand: BpmBand = BpmBand.ANY,
    val key: String? = null,
    val ratingFourPlus: Boolean = false,
    val energyHalfPlus: Boolean = false,
    val availability: AvailabilityFilter = AvailabilityFilter.ANY,
    val analysis: AnalysisFilter = AnalysisFilter.ANY,
) {
    val activeCount: Int
        get() = listOf(
            bpmBand != BpmBand.ANY,
            key != null,
            ratingFourPlus,
            energyHalfPlus,
            availability != AvailabilityFilter.ANY,
            analysis != AnalysisFilter.ANY,
        ).count { it }
}

data class LibraryBrowseState(
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val direction: SortDirection = SortDirection.ASCENDING,
    val filters: LibraryFilters = LibraryFilters(),
)

data class SavedLibraryView(
    val id: String,
    val name: String,
    val state: LibraryBrowseState,
)

internal fun filterAndSortTracks(
    tracks: List<Track>,
    state: LibraryBrowseState,
): List<Track> {
    val needle = state.query.trim().lowercase(Locale.ROOT)
    val filters = state.filters
    return tracks.asSequence()
        .filter { track ->
            needle.isEmpty() || listOfNotNull(track.title, track.artist, track.album, track.key)
                .any { it.lowercase(Locale.ROOT).contains(needle) }
        }
        .filter { track -> filters.bpmBand.matches(track.bpm) }
        .filter { track -> filters.key == null || track.key.equals(filters.key, ignoreCase = true) }
        .filter { track -> !filters.ratingFourPlus || (track.rating ?: 0) >= 4 }
        .filter { track -> !filters.energyHalfPlus || (track.energy ?: -1.0) >= 0.5 }
        .filter { track ->
            when (filters.availability) {
                AvailabilityFilter.ANY -> true
                AvailabilityFilter.OFFLINE -> track.offlineAvailable
                AvailabilityFilter.STREAMING -> !track.offlineAvailable
            }
        }
        .filter { track ->
            when (filters.analysis) {
                AnalysisFilter.ANY -> true
                AnalysisFilter.ANALYZED -> track.isAnalyzed
                AnalysisFilter.NEEDS_ANALYSIS -> !track.isAnalyzed
            }
        }
        .sortedWith(trackComparator(state.sort, state.direction))
        .toList()
}

private fun trackComparator(sort: LibrarySort, direction: SortDirection): Comparator<Track> = Comparator { left, right ->
    val primary = when (sort) {
        LibrarySort.TITLE -> compareText(left.title, right.title, direction)
        LibrarySort.ARTIST -> compareText(left.artist, right.artist, direction)
        LibrarySort.BPM -> compareNullable(left.bpm, right.bpm, direction, Comparator.naturalOrder<Double>())
        LibrarySort.KEY -> compareNullable(left.key, right.key, direction, String.CASE_INSENSITIVE_ORDER)
        LibrarySort.RATING -> compareNullable(left.rating, right.rating, direction, Comparator.naturalOrder<Int>())
    }
    if (primary != 0) {
        primary
    } else {
        val title = String.CASE_INSENSITIVE_ORDER.compare(left.title, right.title)
        if (title != 0) title else left.id.compareTo(right.id)
    }
}

private fun compareText(left: String, right: String, direction: SortDirection): Int {
    val value = String.CASE_INSENSITIVE_ORDER.compare(left, right)
    return if (direction == SortDirection.ASCENDING) value else -value
}

private fun <T> compareNullable(
    left: T?,
    right: T?,
    direction: SortDirection,
    comparator: Comparator<T>,
): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    else -> comparator.compare(left, right).let { if (direction == SortDirection.ASCENDING) it else -it }
}

internal val Track.isAnalyzed: Boolean
    get() = bpm != null || key != null || energy != null

internal fun toggleSelection(selectedIds: Collection<String>, trackId: String): Set<String> =
    selectedIds.toMutableSet().apply {
        if (!add(trackId)) remove(trackId)
    }

internal fun saveLibraryView(
    existing: List<SavedLibraryView>,
    id: String,
    name: String,
    state: LibraryBrowseState,
): List<SavedLibraryView> {
    val normalized = name.trim().ifBlank { "Saved view" }
    return existing.filterNot { it.id == id } + SavedLibraryView(id = id, name = normalized, state = state)
}

internal fun renameSavedLibraryView(
    existing: List<SavedLibraryView>,
    id: String,
    name: String,
): List<SavedLibraryView> {
    val normalized = name.trim()
    if (normalized.isBlank()) return existing
    return existing.map { view -> if (view.id == id) view.copy(name = normalized) else view }
}

internal fun deleteSavedLibraryView(existing: List<SavedLibraryView>, id: String): List<SavedLibraryView> =
    existing.filterNot { it.id == id }

internal fun serializeSavedLibraryView(view: SavedLibraryView): String = listOf(
    encode(view.id),
    encode(view.name),
    encode(view.state.query),
    view.state.sort.name,
    view.state.direction.name,
    view.state.filters.bpmBand.name,
    encode(view.state.filters.key.orEmpty()),
    view.state.filters.ratingFourPlus.toString(),
    view.state.filters.energyHalfPlus.toString(),
    view.state.filters.availability.name,
    view.state.filters.analysis.name,
).joinToString("|")

internal fun deserializeSavedLibraryView(raw: String): SavedLibraryView? = runCatching {
    val parts = raw.split('|')
    require(parts.size == 11)
    SavedLibraryView(
        id = decode(parts[0]),
        name = decode(parts[1]),
        state = LibraryBrowseState(
            query = decode(parts[2]),
            sort = enumValueOrDefault(parts[3], LibrarySort.TITLE),
            direction = enumValueOrDefault(parts[4], SortDirection.ASCENDING),
            filters = LibraryFilters(
                bpmBand = enumValueOrDefault(parts[5], BpmBand.ANY),
                key = decode(parts[6]).ifBlank { null },
                ratingFourPlus = parts[7].toBooleanStrictOrNull() ?: false,
                energyHalfPlus = parts[8].toBooleanStrictOrNull() ?: false,
                availability = enumValueOrDefault(parts[9], AvailabilityFilter.ANY),
                analysis = enumValueOrDefault(parts[10], AnalysisFilter.ANY),
            ),
        ),
    )
}.getOrNull()

private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decode(value: String): String = String(
    Base64.getUrlDecoder().decode(value),
    StandardCharsets.UTF_8,
)

internal inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
