package dev.androidjtools.ui.playlist

import dev.androidjtools.core.model.Playlist
import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLogicTest {
    private val tracks = listOf(
        track("a", "Night Bus", "Sample Lab", 92.5, "8A", 4, 0.72, true),
        track("b", "Concrete Flash", "Fixture Artist", 140.0, "9A", 5, 0.86, false),
        track("c", "Dub Colony", "Fixture Artist", 74.0, "2A", 3, 0.55, true),
    )

    @Test
    fun `membership is deduplicated removable and reorderable`() {
        val initial = PlaylistDraft("p", "Set", trackIds = listOf("a", "b"))
        val added = addTracks(initial, listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), added.trackIds)
        assertEquals(listOf("c", "a", "b"), moveTrack(added, 2, 0).trackIds)
        assertEquals(listOf("a", "c"), removeTrack(added, "b").trackIds)
        assertEquals(listOf("a", "b"), initial.trackIds)
    }

    @Test
    fun `delete has precise undo payload`() {
        val drafts = listOf(PlaylistDraft("a", "A"), PlaylistDraft("b", "B"))
        val (remaining, undo) = deletePlaylist(drafts, "a")
        assertEquals(listOf("b"), remaining.map { it.id })
        assertNotNull(undo)
        assertEquals(drafts, restorePlaylist(remaining, undo!!))
    }

    @Test
    fun `nested AND OR smart groups preview deterministically`() {
        val rule = SmartRule.Group(
            SmartMatchMode.ALL,
            listOf(
                SmartRule.Condition(SmartField.BPM, SmartOperator.AT_LEAST, "80"),
                SmartRule.Group(
                    SmartMatchMode.ANY,
                    listOf(
                        SmartRule.Condition(SmartField.ARTIST, SmartOperator.CONTAINS, "sample"),
                        SmartRule.Condition(SmartField.RATING, SmartOperator.AT_LEAST, "5"),
                    ),
                ),
            ),
        )
        assertTrue(validateSmartRule(rule).isEmpty())
        assertEquals(listOf("a", "b"), previewSmartRule(tracks, rule).map { it.id })
        assertTrue(describeSmartRule(rule).contains("AND"))
        assertTrue(describeSmartRule(rule).contains("OR"))
    }

    @Test
    fun `local ids remain unique after delete and same-name recreation in another crate`() {
        val allocator = LocalPlaylistIdAllocator()
        var drafts = listOf(PlaylistDraft("backend", "Imported"))

        val disposable = PlaylistDraft(
            id = allocator.next(drafts.map(PlaylistDraft::id)),
            name = "Disposable",
            cratePath = CratePath.parse("Sets / Utility"),
        )
        drafts = drafts + disposable
        val house = PlaylistDraft(
            id = allocator.next(drafts.map(PlaylistDraft::id)),
            name = "Warmup",
            cratePath = CratePath.parse("Sets / House"),
        )
        drafts = drafts + house
        drafts = deletePlaylist(drafts, disposable.id).first

        val techno = PlaylistDraft(
            id = allocator.next(drafts.map(PlaylistDraft::id)),
            name = "Warmup",
            cratePath = CratePath.parse("Sets / Techno"),
        )

        assertTrue(validatePlaylistDraft(techno, drafts).isEmpty())
        assertFalse(techno.id in drafts.map(PlaylistDraft::id))
        assertFalse(house.id == techno.id)
    }

    @Test
    fun `imported backend smart rules stay opaque instead of being reinterpreted locally`() {
        val imported = Playlist(
            id = "smart",
            name = "High Energy",
            trackIds = listOf("b"),
            smart = true,
            ruleSummary = "energy ≥ 0.75",
        ).toDraft()

        assertTrue(imported.isSmart)
        assertEquals("energy ≥ 0.75", imported.importedRuleSummary)
        assertEquals(null, imported.smartRule)
        assertTrue(validatePlaylistDraft(imported).isEmpty())
    }

    @Test
    fun `invalid smart conditions fail closed`() {
        val invalid = SmartRule.Group(
            SmartMatchMode.ALL,
            listOf(SmartRule.Condition(SmartField.BPM, SmartOperator.AT_LEAST, "fast")),
        )
        assertFalse(validateSmartRule(invalid).isEmpty())
        assertTrue(previewSmartRule(tracks, invalid).isEmpty())
    }

    @Test
    fun `crate paths retain hierarchy and duplicate names are scoped to crate`() {
        val house = PlaylistDraft("1", "Warmup", CratePath.parse("Sets / House"))
        val duplicate = PlaylistDraft("2", "warmup", CratePath.parse("Sets > House"))
        val otherCrate = duplicate.copy(id = "3", cratePath = CratePath.parse("Sets/Techno"))
        assertEquals(listOf("Sets", "House"), house.cratePath.segments)
        assertFalse(validatePlaylistDraft(duplicate, listOf(house)).isEmpty())
        assertTrue(validatePlaylistDraft(otherCrate, listOf(house)).isEmpty())
    }

    @Test
    fun `a server playlist keeps its crate and read-only flag when it becomes a draft`() {
        val server = Playlist("p", "Warmup", listOf("a", "b"), crate = listOf("Sets", "2026"), readOnly = true)
        val draft = server.toDraft()
        assertEquals(listOf("Sets", "2026"), draft.cratePath.segments)
        assertEquals(listOf("a", "b"), draft.trackIds)
        assertTrue(draft.readOnly)
    }

    @Test
    fun `read-only drafts cannot be changed by any membership operation`() {
        val draft = PlaylistDraft("p", "Warmup", trackIds = listOf("a", "b"), readOnly = true)
        assertEquals(draft, addTracks(draft, listOf("c")))
        assertEquals(draft, removeTrack(draft, "a"))
        assertEquals(draft, moveTrack(draft, 0, 1))
    }

    @Test
    fun `read-only drafts cannot be deleted and say why they fail validation`() {
        val draft = PlaylistDraft("p", "Warmup", readOnly = true)
        val (remaining, undo) = deletePlaylist(listOf(draft), "p")
        assertEquals(listOf(draft), remaining)
        assertEquals(null, undo)
        assertTrue(validatePlaylistDraft(draft).any { it.contains("Sample Lib server") })
    }

    @Test
    fun `editable drafts are unaffected by the read-only guard`() {
        val draft = PlaylistDraft("p", "Mine", trackIds = listOf("a"))
        assertEquals(listOf("a", "b"), addTracks(draft, listOf("b")).trackIds)
        assertTrue(validatePlaylistDraft(draft).isEmpty())
    }

    private fun track(id: String, title: String, artist: String, bpm: Double, key: String, rating: Int, energy: Double, offline: Boolean) =
        Track(id, title, artist, "Album", 180_000, bpm, key, energy, rating, offline)
}
