package dev.androidjtools.sync.samplelib

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSuggestionProjectionTest {
    private fun suggestion(
        id: String,
        kind: String,
        data: String,
        assetId: String = "a1",
        intervalId: String? = null,
        confidence: String? = "0.8",
        revision: String = "rev-1",
        schema: String = ANALYSIS_SUGGESTION_SCHEMA,
        createdAt: String? = "2026-10-01T10:00:00Z",
        tool: String? = "mir.essentia_lowlevel",
        extra: String = "",
    ) = CachedEntity(
        "analysis_suggestion", id, revision, schema,
        buildString {
            append("""{"id":"$id","kind":"$kind","asset_id":"$assetId","interval_id":${intervalId?.let { "\"$it\"" } ?: "null"},""")
            append(""""analysis_run_id":"run-1",""")
            tool?.let { append(""""tool":"$it","tool_version":"1.2",""") }
            createdAt?.let { append(""""created_at":"$it",""") }
            confidence?.let { append(""""confidence":$it,""") }
            append(extra)
            append(""""data":$data}""")
        },
    )

    private val tempo = """{"bpm":120.0,"alternatives":[60.0,240.0]}"""
    private val key = """{"key_label":"A minor","root":"A","mode":"minor"}"""
    private val grid = """{"bpm":120.0,"first_beat_ms":250,"beat_times_ms":[250,750,1250]}"""
    private val cues = """{"points_ms":[1000,2000,3500]}"""

    private fun parse(entity: CachedEntity) = AnalysisSuggestions.parse(entity)

    // ---- the four kinds ----

    @Test
    fun tempoCarriesTheServersAliasesAndTheRunItCameFrom() {
        val record = parse(suggestion("run-1:tempo:a1", "tempo", tempo))!!
        assertEquals("run-1:tempo:a1", record.id)
        assertEquals("rev-1", record.revision)
        assertEquals(SuggestionWireKind.TEMPO, record.kind)
        assertEquals("a1", record.assetId)
        assertNull(record.intervalId)
        assertEquals("run-1", record.analysisRunId)
        assertEquals("mir.essentia_lowlevel", record.tool)
        assertEquals("1.2", record.toolVersion)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), record.createdAt)
        assertEquals(0.8, record.confidence!!, 0.0)
        assertEquals(SuggestionData.Tempo(120.0, listOf(60.0, 240.0)), record.data)
    }

    @Test
    fun keyKeepsTheLabelAndAnUnsplitRootAndModeStayNull() {
        val split = parse(suggestion("k1", "key", key))!!.data as SuggestionData.Key
        assertEquals(Triple("A minor", "A", "minor"), Triple(split.label, split.root, split.mode))

        val unsplit = parse(suggestion("k2", "key", """{"key_label":"8A","root":null,"mode":null}"""))!!.data as SuggestionData.Key
        assertEquals("8A", unsplit.label)
        assertNull(unsplit.root)
        assertNull(unsplit.mode)
    }

    @Test
    fun beatGridKeepsTheBpmFirstBeatAndTheBeats() {
        val data = parse(suggestion("g1", "beat_grid", grid))!!.data
        assertEquals(SuggestionData.BeatGrid(120.0, 250L, listOf(250L, 750L, 1250L)), data)
    }

    @Test
    fun cueCandidatesAreAscendingAndDeduplicatedWhateverTheWireOrder() {
        val data = parse(suggestion("c1", "cue_candidates", """{"points_ms":[3500,1000,1000,2000]}"""))!!.data
        assertEquals(SuggestionData.CueCandidates(listOf(1000L, 2000L, 3500L)), data)
    }

    @Test
    fun theServersCapsAreHonouredEvenIfTheWireExceedsThem() {
        val manyBeats = (0 until 600).joinToString(",") { (it * 500).toString() }
        val beats = (parse(suggestion("g", "beat_grid", """{"bpm":120.0,"first_beat_ms":0,"beat_times_ms":[$manyBeats]}"""))!!.data as SuggestionData.BeatGrid)
        assertEquals(512, beats.beatTimesMs.size)

        val manyCues = (0 until 300).joinToString(",") { (it * 100).toString() }
        val points = (parse(suggestion("c", "cue_candidates", """{"points_ms":[$manyCues]}"""))!!.data as SuggestionData.CueCandidates)
        assertEquals(256, points.pointsMs.size)
    }

    // ---- confidence is optional, informational and never a reason to drop or hide ----

    @Test
    fun aSuggestionWithoutConfidenceIsStillASuggestion() {
        assertNull(parse(suggestion("a", "tempo", tempo, confidence = null))!!.confidence)
        assertNull(parse(suggestion("b", "tempo", tempo, confidence = "null"))!!.confidence)
    }

    @Test
    fun aConfidenceThatIsNotAProbabilityIsDroppedButTheSuggestionStays() {
        assertNull(parse(suggestion("a", "tempo", tempo, confidence = "\"high\""))!!.confidence)
        assertNull(parse(suggestion("b", "tempo", tempo, confidence = "7.5"))!!.confidence)
        assertNull(parse(suggestion("c", "tempo", tempo, confidence = "-0.1"))!!.confidence)
    }

    // ---- unknown or malformed data is skipped, never fatal ----

    @Test
    fun anUnknownKindIsSkipped() {
        assertNull(parse(suggestion("s", "stems", """{"drums":"x"}""")))
    }

    @Test
    fun aSchemaVersionThisAppDoesNotKnowIsSkipped() {
        assertNull(parse(suggestion("s", "tempo", tempo, schema = "sample-lib.analysis-suggestion/v2")))
    }

    @Test
    fun malformedPayloadsAreSkipped() {
        listOf(
            suggestion("t1", "tempo", """{"alternatives":[]}"""),
            suggestion("t2", "tempo", """{"bpm":0}"""),
            suggestion("t3", "tempo", """{"bpm":-90.0}"""),
            suggestion("t4", "tempo", """{"bpm":"fast"}"""),
            suggestion("t5", "tempo", "[]"),
            suggestion("k1", "key", """{"root":"A"}"""),
            suggestion("k2", "key", """{"key_label":"  "}"""),
            suggestion("g1", "beat_grid", """{"bpm":120.0,"beat_times_ms":[0]}"""),
            suggestion("g2", "beat_grid", """{"bpm":120.0,"first_beat_ms":-5,"beat_times_ms":[0]}"""),
            suggestion("c1", "cue_candidates", """{"points_ms":[]}"""),
            suggestion("c2", "cue_candidates", """{"points_ms":["a"]}"""),
            suggestion("c3", "cue_candidates", """{"points_ms":[-1000]}"""),
            suggestion("x1", "tempo", tempo, assetId = ""),
            CachedEntity("analysis_suggestion", "bad", "r", ANALYSIS_SUGGESTION_SCHEMA, "not json"),
        ).forEach { assertNull("${it.entityId} must be skipped", parse(it)) }
    }

    @Test
    fun aBadAliasIsDroppedButTheTempoSuggestionSurvives() {
        val data = parse(suggestion("t", "tempo", """{"bpm":100.0,"alternatives":[50.0,"x",0,-1,200.0,null]}"""))!!.data as SuggestionData.Tempo
        assertEquals(listOf(50.0, 200.0), data.alternatives)
    }

    @Test
    fun missingToolAndTimeDoNotMakeASuggestionUnusable() {
        val record = parse(suggestion("t", "tempo", tempo, tool = null, createdAt = null))!!
        assertEquals("analysis", record.tool)
        assertNull(record.toolVersion)
        assertNull(record.createdAt)
    }

    @Test
    fun theEntityIdIsTheIdentityEvenIfTheValueDisagrees() {
        val entity = suggestion("run-1:tempo:a1", "tempo", tempo).copy(valueJson = suggestion("other", "tempo", tempo).valueJson)
        assertEquals("run-1:tempo:a1", parse(entity)!!.id)
    }

    // ---- projection ----

    private fun asset(id: String, duration: Int = 10_000) = CachedEntity("asset", id, "r", "sample-lib.asset/v1", """{"title":"$id","duration_ms":$duration}""")

    private fun interval(id: String, assetId: String, start: Int, end: Int, bpm: String = "null", key: String = "null") = CachedEntity(
        "interval", id, "irev-$id", "s",
        """{"interval":{"id":"$id","asset_id":"$assetId","start_ms":$start,"end_ms":$end,"bpm":$bpm,"key_label":$key},"markers":[]}""",
    )

    @Test
    fun suggestionsAreGroupedPerTrackAndTracksTheLibraryDoesNotHaveAreLeftOut() {
        val projection = LibraryProjector.project(
            listOf(asset("a1"), asset("a2")), emptyList(),
            analysisSuggestions = listOf(
                suggestion("s1", "tempo", tempo, assetId = "a1"),
                suggestion("s2", "key", key, assetId = "a1"),
                suggestion("s3", "tempo", tempo, assetId = "a2"),
                suggestion("s4", "tempo", tempo, assetId = "ghost"),
            ),
        )
        assertEquals(setOf("s1", "s2"), projection.suggestionsOf("a1").map { it.id }.toSet())
        assertEquals(listOf("s3"), projection.suggestionsOf("a2").map { it.id })
        assertTrue(projection.suggestionsOf("ghost").isEmpty())
    }

    @Test
    fun newestRunFirstWithoutLookingAtConfidenceAndMissingTimesDoNotHideAnything() {
        val projection = LibraryProjector.project(
            listOf(asset("a1")), emptyList(),
            analysisSuggestions = listOf(
                suggestion("old", "tempo", tempo, createdAt = "2026-09-01T00:00:00Z", confidence = "0.99"),
                suggestion("new", "tempo", tempo, createdAt = "2026-10-01T00:00:00Z", confidence = null),
                suggestion("undated", "tempo", tempo, createdAt = null, confidence = "0.5"),
            ),
        )
        assertEquals(listOf("new", "old", "undated"), projection.suggestionsOf("a1").map { it.id })
    }

    @Test
    fun anUnknownKindNextToAKnownOneLeavesTheKnownOneAlone() {
        val projection = LibraryProjector.project(
            listOf(asset("a1")), emptyList(),
            analysisSuggestions = listOf(suggestion("weird", "stems", "{}"), suggestion("good", "key", key)),
        )
        assertEquals(listOf("good"), projection.suggestionsOf("a1").map { it.id })
    }

    @Test
    fun theTargetOfAnAssetLevelSuggestionIsTheTracksFullSpanInterval() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", 10_000), asset("a2", 10_000)),
            listOf(
                interval("part", "a1", 1_000, 4_000),
                interval("whole", "a1", 0, 10_000),
                interval("only-part", "a2", 0, 4_000),
            ),
        )
        assertEquals("whole", projection.fullSpanIntervalOf("a1"))
        assertNull("no full-length section means no target", projection.fullSpanIntervalOf("a2"))
        assertNull(projection.fullSpanIntervalOf("unknown"))
    }

    @Test
    fun theLibraryChangesOnlyFromPulledIntervalStateNeverFromASuggestion() {
        val withoutSuggestion = LibraryProjector.project(listOf(asset("a1")), listOf(interval("whole", "a1", 0, 10_000, bpm = "100.0")))
        val withSuggestion = LibraryProjector.project(
            listOf(asset("a1")), listOf(interval("whole", "a1", 0, 10_000, bpm = "100.0")),
            analysisSuggestions = listOf(suggestion("s", "tempo", tempo)),
        )
        assertEquals(100.0, withSuggestion.tracks.single().bpm!!, 0.0)
        assertEquals(withoutSuggestion.tracks, withSuggestion.tracks)
    }

    @Test
    fun theTracksCurrentValuesAreAvailableToCompareAgainst() {
        val projection = LibraryProjector.project(
            listOf(asset("a1")), listOf(interval("whole", "a1", 0, 10_000, bpm = "120.0", key = "\"A minor\"")),
        )
        val track = projection.tracks.single()
        assertEquals(120.0, track.bpm!!, 0.0)
        assertEquals("A minor", track.key)
    }
}
