package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistProjectionTest {
    private fun asset(id: String) = CachedEntity("asset", id, "r", "sample-lib.asset/v1", """{"title":"$id","duration_ms":1000}""")

    private fun playlist(id: String, name: String, kind: String = "playlist", parent: String? = null) = CachedEntity(
        "playlist", id, "1", "sample-lib.playlist/v1",
        """{"id":"$id","name":"$name","kind":"$kind","parent_id":${parent?.let { "\"$it\"" } ?: "null"}}""",
    )

    private fun item(id: String, playlistId: String, assetId: String, position: Int) = CachedEntity(
        "playlist_item", id, "r", "sample-lib.playlist-item/v1",
        """{"id":"$id","playlist_id":"$playlistId","asset_id":"$assetId","position":$position}""",
    )

    private fun project(
        playlists: List<CachedEntity>,
        items: List<CachedEntity>,
        assets: List<CachedEntity> = listOf(asset("a1"), asset("a2"), asset("a3")),
    ): List<Playlist> = LibraryProjector.project(assets, emptyList(), playlists = playlists, playlistItems = items).playlists

    @Test
    fun membershipFollowsThePositionNotThePullOrNorTheItemId() {
        val result = project(
            listOf(playlist("pl", "Warmup")),
            listOf(item("it-a", "pl", "a3", 2), item("it-b", "pl", "a1", 0), item("it-c", "pl", "a2", 1)),
        )
        assertEquals(listOf("a1", "a2", "a3"), result.single().trackIds)
    }

    @Test
    fun foldersBecomeTheCratePathAndAreNotListedAsPlaylists() {
        val result = project(
            listOf(
                playlist("f1", "Sets", kind = "folder"),
                playlist("f2", "2026", kind = "folder", parent = "f1"),
                playlist("pl", "Warmup", parent = "f2"),
            ),
            emptyList(),
        )
        val only = result.single()
        assertEquals("Warmup", only.name)
        assertEquals(listOf("Sets", "2026"), only.crate)
    }

    @Test
    fun aParentCycleTerminates() {
        val result = project(
            listOf(
                playlist("f1", "A", kind = "folder", parent = "f2"),
                playlist("f2", "B", kind = "folder", parent = "f1"),
                playlist("pl", "Warmup", parent = "f1"),
            ),
            emptyList(),
        )
        assertEquals("Warmup", result.single().name)
        assertTrue(result.single().crate.size <= 2)
    }

    @Test
    fun aMissingParentLeavesThePlaylistUnfiled() {
        val result = project(listOf(playlist("pl", "Warmup", parent = "gone")), emptyList())
        assertEquals(emptyList<String>(), result.single().crate)
    }

    @Test
    fun itemsForUnknownAssetsAreSkippedAndRepeatedAssetsAreKept() {
        val result = project(
            listOf(playlist("pl", "Warmup")),
            listOf(item("i0", "pl", "a1", 0), item("i1", "pl", "not-synced", 1), item("i2", "pl", "a1", 2)),
        )
        assertEquals(listOf("a1", "a1"), result.single().trackIds)
    }

    @Test
    fun itemsOfAnUnknownPlaylistAreIgnoredAndAnEmptyPlaylistIsKept() {
        val result = project(
            listOf(playlist("pl", "Empty")),
            listOf(item("i0", "other", "a1", 0)),
        )
        assertEquals(emptyList<String>(), result.single().trackIds)
    }

    @Test
    fun everyServerPlaylistIsReadOnly() {
        val result = project(listOf(playlist("p1", "One"), playlist("p2", "Two")), emptyList())
        assertEquals(2, result.size)
        assertTrue(result.all { it.readOnly })
        assertTrue(result.none { it.smart })
    }

    @Test
    fun malformedRowsAreSkippedWithoutFailingTheProjection() {
        val result = project(
            listOf(
                CachedEntity("playlist", "bad", "1", "s", """{"kind":"playlist"}"""),
                CachedEntity("playlist", "worse", "1", "s", "not json"),
                playlist("pl", "Warmup"),
            ),
            listOf(
                CachedEntity("playlist_item", "x", "r", "s", """{"playlist_id":"pl","asset_id":"a1"}"""),
                item("ok", "pl", "a2", 0),
            ),
        )
        assertEquals(listOf("pl"), result.map { it.id })
        assertEquals(listOf("a2"), result.single().trackIds)
    }

    @Test
    fun playlistsAreOrderedByCrateThenNameIgnoringCase() {
        val result = project(
            listOf(
                playlist("f", "Sets", kind = "folder"),
                playlist("p1", "banana", parent = "f"),
                playlist("p2", "Apple", parent = "f"),
                playlist("p3", "Zulu"),
            ),
            emptyList(),
        )
        assertEquals(listOf("Zulu", "Apple", "banana"), result.map { it.name })
    }
}
