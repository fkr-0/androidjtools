package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.Tombstone
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibAnalysisProjectionStateTest {
    private fun change(type: String, id: String, body: String, schema: String = "s") =
        EntityChange(type, id, OpaqueRevision("r-$id"), schema, Json.parseToJsonElement(body) as JsonObject)

    private val asset = change("asset", "a1", """{"title":"Alpha","duration_ms":10000}""")
    private val tempo = change(
        "analysis_suggestion", "run-1:tempo:a1",
        """{"id":"run-1:tempo:a1","kind":"tempo","asset_id":"a1","interval_id":null,"analysis_run_id":"run-1","tool":"mir","tool_version":null,"created_at":"2026-10-01T00:00:00Z","confidence":null,"data":{"bpm":120.0,"alternatives":[]}}""",
        ANALYSIS_SUGGESTION_SCHEMA,
    )

    private fun caps(analysis: Set<String>) =
        ServerCapabilities(setOf("asset"), emptySet(), "canonical_v1", analysis, emptySet())

    @Test
    fun pulledSuggestionsAppearInTheProjectionWhenTheServerAdvertisesAnalysis() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(
            cache, backgroundScope, UnconfinedTestDispatcher(testScheduler),
            capabilities = MutableStateFlow(caps(setOf("tempo", "key"))),
        )
        cache.applyPage(listOf(asset, tempo), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        assertEquals(listOf("run-1:tempo:a1"), state.projection.value.suggestionsOf("a1").map { it.id })
    }

    @Test
    fun aServerThatNoLongerAdvertisesAnalysisShowsNoLeftoverSuggestions() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val capabilities = MutableStateFlow<ServerCapabilities?>(caps(setOf("tempo")))
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler), capabilities = capabilities)
        cache.applyPage(listOf(asset, tempo), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(1, state.projection.value.suggestionsOf("a1").size)

        capabilities.value = caps(emptySet())
        advanceUntilIdle()
        assertTrue(state.projection.value.suggestionsOf("a1").isEmpty())
        assertEquals("the library itself is untouched", listOf("Alpha"), state.projection.value.tracks.map { it.title })
    }

    @Test
    fun beforeTheFirstHelloOfAProcessTheCachedSuggestionsAreShown() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler), capabilities = MutableStateFlow(null))
        cache.applyPage(listOf(asset, tempo), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        assertEquals(1, state.projection.value.suggestionsOf("a1").size)
    }

    @Test
    fun aReplacedSuggestionIsATombstoneAndANewIdSoTheOldOneDisappears() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(
            cache, backgroundScope, UnconfinedTestDispatcher(testScheduler), capabilities = MutableStateFlow(caps(setOf("tempo"))),
        )
        cache.applyPage(listOf(asset, tempo), emptyList(), CacheMeta("c1", "g", "s"))
        val replacement = change(
            "analysis_suggestion", "run-2:tempo:a1",
            """{"id":"run-2:tempo:a1","kind":"tempo","asset_id":"a1","analysis_run_id":"run-2","tool":"mir","data":{"bpm":121.0}}""",
            ANALYSIS_SUGGESTION_SCHEMA,
        )
        cache.applyPage(
            listOf(replacement),
            listOf(Tombstone("analysis_suggestion", "run-1:tempo:a1", OpaqueRevision("t"), Instant.EPOCH)),
            CacheMeta("c2", "g", "s"),
        )
        advanceUntilIdle()

        assertEquals(listOf("run-2:tempo:a1"), state.projection.value.suggestionsOf("a1").map { it.id })
    }
}
