package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProjectionTest {
    private fun asset(id: String, json: String) = CachedEntity("asset", id, "r", "sample-lib.asset/v1", json)
    private fun interval(id: String, json: String) = CachedEntity("interval", id, "r", "sample-lib.interval-editor-state/v1", json)

    private fun intervalJson(
        assetId: String,
        start: Long = 0,
        end: Long = 10_000,
        extra: String = "",
        markers: String = "",
    ) = """{"interval":{"id":"i","asset_id":"$assetId","label":"Loop","start_ms":$start,"end_ms":$end$extra},"markers":[$markers],"revision":"r"}"""

    private fun marker(id: String, pos: Long, type: String, color: String? = "#112233") =
        """{"id":"$id","interval_id":"i","position_ms":$pos,"label":"$id","marker_type":"$type","color":${color?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun assetBecomesTrackWithTempoAndKeyFromTheFullSpanInterval() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"Alpha","artist":"Ann","collection":"LP","duration_ms":10000,"rating":4}""")),
            listOf(interval("i1", intervalJson("a1", extra = ""","bpm":128.0,"key_label":"8A""""))),
        )
        val track = projection.tracks.single()
        assertEquals("a1", track.id)
        assertEquals("Alpha", track.title)
        assertEquals("Ann", track.artist)
        assertEquals("LP", track.album)
        assertEquals(10_000L, track.durationMs)
        assertEquals(4, track.rating)
        assertEquals(128.0, track.bpm!!, 0.0)
        assertEquals("8A", track.key)
        assertTrue(projection.hasTrack("a1"))
    }

    @Test
    fun partialIntervalDoesNotSupplyTrackTempo() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"Alpha","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", start = 1000, end = 4000, extra = ""","bpm":90.0"""))),
        )
        assertNull(projection.tracks.single().bpm)
    }

    @Test
    fun missingTitleAndArtistFallBackWithoutFailing() {
        val projection = LibraryProjector.project(
            listOf(
                asset("a1", """{"original_filename":"take1.wav","duration_ms":5}"""),
                asset("a2", """{"duration_ms":5}"""),
            ),
            emptyList(),
        )
        assertEquals(listOf("take1.wav", "a2"), projection.tracks.map { it.title })
        assertEquals(listOf("Unknown artist", "Unknown artist"), projection.tracks.map { it.artist })
    }

    @Test
    fun sliceAndCustomMarkersBecomeCuesWithArgbColour() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", markers = marker("m1", 1500, "slice") + "," + marker("m2", 2500, "custom")))),
        )
        val cues = projection.cues.getValue("a1")
        assertEquals(listOf("m1", "m2"), cues.map { it.id })
        assertEquals(listOf("slice", "cue"), cues.map { it.role })
        assertEquals(1500L, cues[0].positionMs)
        assertEquals(0xFF112233L, cues[0].colorArgb)
        assertEquals("a1", cues[0].trackId)
    }

    @Test
    fun badOrMissingColourBecomesNullInsteadOfFailing() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(
                interval(
                    "i1",
                    intervalJson("a1", markers = marker("m1", 1, "slice", "red") + "," + marker("m2", 2, "slice", null) + "," + marker("m3", 3, "slice", "#12345")),
                ),
            ),
        )
        assertEquals(listOf(null, null, null), projection.cues.getValue("a1").map { it.colorArgb })
    }

    @Test
    fun loopMarkersPairAndUnpairedOnesAreIgnored() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(
                interval(
                    "i1",
                    intervalJson(
                        "a1",
                        markers = listOf(
                            marker("end0", 100, "loop_end"),
                            marker("s1", 1000, "loop_start"),
                            marker("e1", 2000, "loop_end"),
                            marker("s2", 3000, "loop_start"),
                        ).joinToString(","),
                    ),
                ),
            ),
        )
        val loops = projection.loops.getValue("a1")
        assertEquals(1, loops.size)
        assertEquals("s1~e1", loops.single().id)
        assertEquals(1000L, loops.single().startMs)
        assertEquals(2000L, loops.single().endMs)
        assertTrue(projection.cues["a1"].isNullOrEmpty())
    }

    @Test
    fun loopableIntervalBecomesALoop() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", start = 1000, end = 4000, extra = ""","loopable":1"""))),
        )
        val loop = projection.loops.getValue("a1").single()
        assertEquals("i1", loop.id)
        assertEquals(1000L, loop.startMs)
        assertEquals(4000L, loop.endMs)
    }

    @Test
    fun malformedUnknownAndNullShapedEntitiesAreSkippedNotFatal() {
        val projection = LibraryProjector.project(
            listOf(
                asset("bad", "not json at all"),
                asset("nulls", """{"title":null,"artist":null,"duration_ms":null,"rating":null}"""),
                asset("array", "[1,2,3]"),
                asset("ok", """{"title":"Fine","duration_ms":10}"""),
            ),
            listOf(
                interval("i-bad", "{"),
                interval("i-no-asset", """{"interval":{"id":"x","start_ms":0,"end_ms":1},"markers":[]}"""),
                interval("i-wrong-markers", """{"interval":{"id":"y","asset_id":"ok","start_ms":0,"end_ms":10},"markers":"nope"}"""),
            ),
        )
        assertEquals(listOf("nulls", "ok"), projection.tracks.map { it.id })
        assertEquals("nulls", projection.tracks.first().title)
        assertTrue(projection.cues.isEmpty())
    }

    @Test
    fun aLargeLibraryProjectsEveryAsset() {
        val assets = (1..5_000).map { asset("a$it", """{"title":"T$it","duration_ms":1000}""") }
        val intervals = (1..5_000).map { interval("i$it", intervalJson("a$it", end = 1000, markers = marker("m$it", 10, "slice"))) }
        val projection = LibraryProjector.project(assets, intervals)
        assertEquals(5_000, projection.tracks.size)
        assertEquals(5_000, projection.cues.size)
    }

    @Test
    fun serverTracksAreNotPlayableAndCarryTheNotice() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"Alpha","duration_ms":10000}""")),
            emptyList(),
        )
        val track = projection.tracks.single()
        assertFalse(track.playable)
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, track.unplayableReason)
    }

    @Test
    fun deviceStyleTracksConstructedDirectlyDefaultToPlayable() {
        val track = Track(id = "dev-1", title = "Local", artist = "Me", durationMs = 5000)
        assertTrue(track.playable)
        assertNull(track.unplayableReason)
    }
}
