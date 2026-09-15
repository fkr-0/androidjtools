package dev.androidjtools.ui.library

import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.ProviderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    private val tracks = listOf(
        track("1", "Night Bus", "Sample Lab", bpm = 92.5, key = "8A", rating = 4, offline = true, album = "Night Routes", energy = 0.7),
        track("2", "Concrete Flash", "Fixture Artist", bpm = 140.0, key = "9A", rating = 5, offline = false, energy = 0.8),
        track("3", "Dub Colony", "Fixture Artist", bpm = null, key = null, rating = 3, offline = true, energy = null),
    )

    @Test
    fun `search matches title artist album and key case insensitively`() {
        assertEquals(listOf("1"), filterAndSortTracks(tracks, state(query = "night")).map { it.id })
        assertEquals(listOf("2", "3"), filterAndSortTracks(tracks, state(query = "fixture artist")).map { it.id })
        assertEquals(listOf("1"), filterAndSortTracks(tracks, state(query = "night routes")).map { it.id })
        assertEquals(listOf("2"), filterAndSortTracks(tracks, state(query = "9a")).map { it.id })
    }

    @Test
    fun `compound filters compose across bpm key rating energy availability and analysis`() {
        val fastStreaming = LibraryFilters(
            bpmBand = BpmBand.FAST,
            key = "9a",
            ratingFourPlus = true,
            energyHalfPlus = true,
            availability = AvailabilityFilter.STREAMING,
            analysis = AnalysisFilter.ANALYZED,
        )
        assertEquals(listOf("2"), filterAndSortTracks(tracks, state(filters = fastStreaming)).map { it.id })

        val offlineSlow = LibraryFilters(
            bpmBand = BpmBand.SLOW,
            ratingFourPlus = true,
            availability = AvailabilityFilter.OFFLINE,
        )
        assertEquals(listOf("1"), filterAndSortTracks(tracks, state(filters = offlineSlow)).map { it.id })

        val needsAnalysis = LibraryFilters(analysis = AnalysisFilter.NEEDS_ANALYSIS)
        assertEquals(listOf("3"), filterAndSortTracks(tracks, state(filters = needsAnalysis)).map { it.id })
    }

    @Test
    fun `sort order is explicit reversible and keeps missing values last`() {
        assertEquals(
            listOf("1", "2", "3"),
            filterAndSortTracks(tracks, state(sort = LibrarySort.BPM)).map { it.id },
        )
        assertEquals(
            listOf("2", "1", "3"),
            filterAndSortTracks(
                tracks,
                state(sort = LibrarySort.BPM, direction = SortDirection.DESCENDING),
            ).map { it.id },
        )
        assertEquals(
            listOf("1", "3", "2"),
            filterAndSortTracks(
                tracks,
                state(sort = LibrarySort.TITLE, direction = SortDirection.DESCENDING),
            ).map { it.id },
        )
    }

    @Test
    fun `large library filtering is deterministic and preserves stable ids`() {
        val large = (0 until 10_000).map { index ->
            track(
                id = "track-$index",
                title = "Track ${index.toString().padStart(5, '0')}",
                artist = if (index % 2 == 0) "Even Crew" else "Odd Crew",
                bpm = 80.0 + (index % 90),
                key = "${index % 12}A",
                rating = (index % 5) + 1,
                offline = index % 3 == 0,
                energy = (index % 10) / 10.0,
            )
        }
        val browse = state(
            query = "even crew",
            sort = LibrarySort.BPM,
            filters = LibraryFilters(
                ratingFourPlus = true,
                energyHalfPlus = true,
                availability = AvailabilityFilter.OFFLINE,
            ),
        )

        val first = filterAndSortTracks(large, browse)
        val second = filterAndSortTracks(large, browse)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.size, first.map { it.id }.distinct().size)
        assertTrue(first.all { it.artist == "Even Crew" && it.offlineAvailable && (it.rating ?: 0) >= 4 })
        assertEquals(first.mapNotNull { it.bpm }, first.mapNotNull { it.bpm }.sorted())
    }

    @Test
    fun `saved view serializes restores renames and deletes without losing compound state`() {
        val saved = SavedLibraryView(
            id = "warm-set",
            name = "Warm | set",
            state = LibraryBrowseState(
                query = "sample | bus",
                sort = LibrarySort.RATING,
                direction = SortDirection.DESCENDING,
                filters = LibraryFilters(
                    bpmBand = BpmBand.MID,
                    key = "8A",
                    ratingFourPlus = true,
                    energyHalfPlus = true,
                    availability = AvailabilityFilter.OFFLINE,
                    analysis = AnalysisFilter.ANALYZED,
                ),
            ),
        )

        val encoded = serializeSavedLibraryView(saved)
        assertEquals(saved, deserializeSavedLibraryView(encoded))
        assertNull(deserializeSavedLibraryView("not-a-view"))

        val initial = saveLibraryView(emptyList(), saved.id, saved.name, saved.state)
        assertEquals(saved, initial.single())
        val renamed = renameSavedLibraryView(initial, saved.id, "Late set")
        assertEquals("Late set", renamed.single().name)
        assertEquals(renamed, renameSavedLibraryView(renamed, saved.id, "   "))
        assertTrue(deleteSavedLibraryView(renamed, saved.id).isEmpty())
    }

    @Test
    fun `provider states map deterministically to library surface states`() {
        assertEquals(LibrarySurfaceState.Loading, ProviderUiState.Loading.toLibrarySurfaceState())
        assertEquals(LibrarySurfaceState.Ready, ProviderUiState.Ready.toLibrarySurfaceState())
        assertEquals(LibrarySurfaceState.Empty, ProviderUiState.Empty.toLibrarySurfaceState())
        assertEquals(LibrarySurfaceState.Offline, ProviderUiState.Offline.toLibrarySurfaceState())
        assertEquals(
            LibrarySurfaceState.Error("fixture error"),
            ProviderUiState.Error("fixture error").toLibrarySurfaceState(),
        )
        assertEquals(
            LibrarySurfaceState.Conflict("fixture conflict"),
            ProviderUiState.Conflict("fixture conflict").toLibrarySurfaceState(),
        )
    }

    @Test
    fun `selection toggle is idempotent per tap and does not mutate input`() {
        val original = setOf("1")
        val added = toggleSelection(original, "2")
        val removed = toggleSelection(added, "1")

        assertEquals(setOf("1"), original)
        assertTrue("2" in added)
        assertFalse("1" in removed)
        assertEquals(setOf("2"), removed)
    }

    private fun state(
        query: String = "",
        sort: LibrarySort = LibrarySort.TITLE,
        direction: SortDirection = SortDirection.ASCENDING,
        filters: LibraryFilters = LibraryFilters(),
    ) = LibraryBrowseState(query = query, sort = sort, direction = direction, filters = filters)

    private fun track(
        id: String,
        title: String,
        artist: String,
        bpm: Double?,
        key: String?,
        rating: Int,
        offline: Boolean,
        energy: Double? = 0.5,
        album: String = "Fixture Cuts",
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000,
        bpm = bpm,
        key = key,
        energy = energy,
        rating = rating,
        offlineAvailable = offline,
    )
}
