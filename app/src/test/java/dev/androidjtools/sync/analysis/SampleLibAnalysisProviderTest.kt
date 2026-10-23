package dev.androidjtools.sync.analysis

import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AnalysisActionResult
import dev.androidjtools.core.provider.AnalysisRequestAvailability
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.remote.samplelib.AnalysisRequestResult
import dev.androidjtools.remote.samplelib.AnalysisRequestStatus
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalConflict
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.journal.JournalReceipt
import dev.androidjtools.sync.journal.JournalReceiptOutcome
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import dev.androidjtools.sync.samplelib.ANALYSIS_SUGGESTION_SCHEMA
import dev.androidjtools.sync.samplelib.AnalysisRequestOutcome
import dev.androidjtools.sync.samplelib.CachedEntity
import dev.androidjtools.sync.samplelib.InMemorySuggestionDismissals
import dev.androidjtools.sync.samplelib.LibraryProjection
import dev.androidjtools.sync.samplelib.LibraryProjector
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibAnalysisProviderTest {
    private class MemoryStorage : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            durable = snapshot
        }
    }

    private val acceptOp = "analysis_suggestion.accept"
    private val kinds = setOf("tempo", "key", "beat_grid", "cue_candidates")

    private fun caps(
        analysis: Set<String> = kinds,
        request: String? = "enqueue_v1",
        mutations: Set<String> = setOf("interval.editor_state.replace", acceptOp),
    ) = ServerCapabilities(setOf("asset", "interval"), mutations, "canonical_v1", analysis, emptySet(), request)

    private fun suggestion(
        id: String,
        kind: String,
        data: String,
        revision: String = "rev-$id",
        assetId: String = "a1",
        intervalId: String? = null,
        confidence: String = "0.8",
        createdAt: String = "2026-10-01T10:00:00Z",
    ) = CachedEntity(
        "analysis_suggestion", id, revision, ANALYSIS_SUGGESTION_SCHEMA,
        """{"id":"$id","kind":"$kind","asset_id":"$assetId","interval_id":${intervalId?.let { "\"$it\"" } ?: "null"},"analysis_run_id":"run-1","tool":"mir.essentia_lowlevel","tool_version":"2.1","created_at":"$createdAt","confidence":$confidence,"data":$data}""",
    )

    private val tempo = suggestion("run-1:tempo:a1", "tempo", """{"bpm":120.0,"alternatives":[60.0,240.0]}""")
    private val key = suggestion("run-1:key:a1", "key", """{"key_label":"A minor","root":"A","mode":"minor"}""", confidence = "null")
    private val grid = suggestion("run-1:beat_grid:a1", "beat_grid", """{"bpm":120.0,"first_beat_ms":250,"beat_times_ms":[250,750,1250]}""")
    private val cues = suggestion("run-1:cue_candidates:a1", "cue_candidates", """{"points_ms":[1000,2000,3500]}""")

    private fun asset(id: String = "a1") = CachedEntity("asset", id, "r", "s", """{"title":"$id","duration_ms":10000}""")
    private fun interval(id: String, start: Int, end: Int, bpm: String = "null", keyLabel: String = "null", asset: String = "a1") = CachedEntity(
        "interval", id, "irev-$id", "s",
        """{"interval":{"id":"$id","asset_id":"$asset","start_ms":$start,"end_ms":$end,"bpm":$bpm,"key_label":$keyLabel},"markers":[]}""",
    )

    private fun library(
        suggestions: List<CachedEntity> = listOf(tempo, key, grid, cues),
        intervals: List<CachedEntity> = listOf(interval("iv-full", 0, 10_000)),
    ): LibraryProjection = LibraryProjector.project(listOf(asset()), intervals, analysisSuggestions = suggestions)

    private class Requests : dev.androidjtools.sync.samplelib.AnalysisRequester {
        val assets = mutableListOf<String>()
        var outcome: AnalysisRequestOutcome = AnalysisRequestOutcome.Answered(
            AnalysisRequestResult("a1", AnalysisRequestStatus.QUEUED, emptyMap(), "job-1", null),
        )
        override suspend fun requestAnalysis(assetId: String): AnalysisRequestOutcome {
            assets += assetId
            return outcome
        }
    }

    private class Fixture(
        scope: TestScope,
        capabilities: ServerCapabilities?,
        state: SyncState,
        library: LibraryProjection,
        io: CoroutineDispatcher,
    ) {
        val storage = MemoryStorage()
        var journalLoadedOn: String? = null
        val journal: DurableMutationJournal by lazy {
            journalLoadedOn = Thread.currentThread().name
            DurableMutationJournal(storage)
        }
        val capabilities = MutableStateFlow(capabilities)
        val state = MutableStateFlow(state)
        val library = MutableStateFlow(library)
        val dismissals = InMemorySuggestionDismissals()
        val requests = Requests()
        var flushes = 0
        val provider = SampleLibAnalysisProvider(
            projection = this.library,
            capabilities = this.capabilities,
            syncState = this.state,
            lazyJournal = lazy { journal },
            dismissals = dismissals,
            requester = requests,
            requestFlush = { flushes++ },
            ioDispatcher = io,
            scope = scope.backgroundScope,
            clock = { 5_000L },
        )
    }

    private fun TestScope.fixture(
        capabilities: ServerCapabilities? = caps(),
        state: SyncState = SyncState.ONLINE_IDLE,
        library: LibraryProjection = library(),
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) = Fixture(this, capabilities, state, library, io)

    private fun Fixture.suggestionList() = provider.suggestions("a1").value
    private fun Fixture.byKind(kind: SuggestionKind) = suggestionList().single { it.kind == kind }

    // ---- what is shown ----

    @Test
    fun everyKindIsPresentedWithItsToolRunAndOptionalConfidence() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()

        val bpm = f.byKind(SuggestionKind.BPM)
        assertEquals(SuggestionPayload.Bpm(120.0, listOf(60.0, 240.0)), bpm.payload)
        assertEquals("mir.essentia_lowlevel", bpm.source.service)
        assertEquals("2.1", bpm.source.version)
        assertEquals("run-1", bpm.analysisRunId)
        assertEquals(0.8, bpm.confidence!!, 0.0)
        assertEquals(SuggestionPayload.Key("A minor"), f.byKind(SuggestionKind.KEY).payload)
        assertNull("a run that recorded no confidence shows none", f.byKind(SuggestionKind.KEY).confidence)
        assertEquals(SuggestionPayload.Grid(120.0, 250L, 3), f.byKind(SuggestionKind.BEAT_GRID).payload)
        assertEquals(SuggestionPayload.CuePoints(listOf(1000L, 2000L, 3500L)), f.byKind(SuggestionKind.CUE).payload)
        assertTrue(f.suggestionList().all { it.decision == SuggestionDecision.PROPOSED })
    }

    @Test
    fun aTrackWithoutSuggestionsHasAnEmptyListAndOtherTracksAreUntouched() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        assertTrue(f.provider.suggestions("nobody").value.isEmpty())
    }

    @Test
    fun theListFollowsThePulledLibrary() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(suggestions = emptyList()))
        assertTrue(f.suggestionList().isEmpty())

        f.library.value = library(listOf(tempo))
        assertEquals(1, f.suggestionList().size)
    }

    @Test
    fun aSuggestionTheLibraryAlreadyHoldsIsShownAsAccepted() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(intervals = listOf(interval("iv-full", 0, 10_000, bpm = "120.0", keyLabel = "\"A minor\""))))

        assertEquals(SuggestionDecision.ACCEPTED, f.byKind(SuggestionKind.BPM).decision)
        assertEquals(SuggestionDecision.ACCEPTED, f.byKind(SuggestionKind.KEY).decision)
        assertEquals(SuggestionDecision.ACCEPTED, f.byKind(SuggestionKind.BEAT_GRID).decision)
        assertEquals(SuggestionDecision.PROPOSED, f.byKind(SuggestionKind.CUE).decision)
    }

    @Test
    fun capabilitiesFollowTheAdvertisedKinds() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = caps(analysis = setOf("tempo", "key")))
        val byId = f.provider.capabilities.value.associateBy { it.id }

        assertTrue(byId.getValue("analysis.tempo").available)
        assertTrue(byId.getValue("analysis.key").available)
        assertFalse(byId.getValue("analysis.beat_grid").available)
        assertFalse(byId.getValue("analysis.cue_candidates").available)
        assertTrue(byId.values.all { it.anySource })
    }

    // ---- dismissing ----

    @Test
    fun aDismissedSuggestionIsHiddenButAReplacementWithAnotherRevisionOrIdAppears() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.dismissSuggestion("run-1:tempo:a1")
        assertTrue(f.suggestionList().none { it.kind == SuggestionKind.BPM })
        assertEquals(4 - 1, f.suggestionList().size)

        f.library.value = library(listOf(suggestion("run-1:tempo:a1", "tempo", """{"bpm":121.0}""", revision = "rev-new"), key, grid, cues))
        assertEquals("same id, new revision", 4, f.suggestionList().size)

        f.library.value = library(listOf(suggestion("run-2:tempo:a1", "tempo", """{"bpm":121.0}"""), key, grid, cues))
        assertEquals("a re-run has a new id", 4, f.suggestionList().size)
    }

    @Test
    fun dismissingNeverTouchesTheServerOrTheJournal() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.dismissSuggestion("run-1:key:a1")

        assertEquals(0, f.flushes)
        assertTrue(f.journal.all().isEmpty())
        assertTrue(f.requests.assets.isEmpty())
    }

    @Test
    fun rejectIsTheSameLocalDismissal() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.reject("run-1:key:a1")
        assertTrue(f.suggestionList().none { it.kind == SuggestionKind.KEY })
    }

    // ---- accepting ----

    private fun Fixture.payloadOf(id: String) = Json.parseToJsonElement(journal.mutation(id)!!.canonicalPayload).jsonObject

    @Test
    fun acceptingAnAssetLevelTempoQueuesAnAcceptOnTheTracksFullSpanInterval() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(intervals = listOf(interval("iv-part", 1_000, 4_000), interval("iv-full", 0, 10_000))))

        val result = f.provider.acceptSuggestion("run-1:tempo:a1")

        assertTrue(result is AnalysisActionResult.Done)
        val queued = f.journal.all().single()
        assertEquals("analysis_suggestion.accept", queued.operation)
        assertEquals("run-1:tempo:a1", queued.entityId)
        assertEquals("rev-run-1:tempo:a1", queued.baseRevision)
        assertEquals("iv-full", f.payloadOf(queued.mutationId).getValue("target_interval_id").jsonPrimitive.content)
        assertEquals("delivery is asked for", 1, f.flushes)
    }

    @Test
    fun anIntervalScopedSuggestionTargetsItsOwnInterval() = runTest(UnconfinedTestDispatcher()) {
        val scoped = suggestion("run-1:key:iv-part", "key", """{"key_label":"C major"}""", intervalId = "iv-part")
        val f = fixture(library = library(listOf(scoped), listOf(interval("iv-part", 1_000, 4_000), interval("iv-full", 0, 10_000))))

        f.provider.acceptSuggestion("run-1:key:iv-part")

        assertEquals("iv-part", f.payloadOf(f.journal.all().single().mutationId).getValue("target_interval_id").jsonPrimitive.content)
    }

    @Test
    fun withoutAFullLengthSectionOnTheServerNothingIsQueuedAndTheReasonIsGiven() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(intervals = listOf(interval("iv-part", 1_000, 4_000))))

        val result = f.provider.acceptSuggestion("run-1:tempo:a1")

        assertTrue((result as AnalysisActionResult.Refused).reason.contains("full", ignoreCase = true))
        assertTrue(f.journal.all().isEmpty())
        assertEquals(0, f.flushes)
    }

    @Test
    fun aTempoAlternativeCanBePickedAndAnythingElseIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()

        assertTrue(f.provider.acceptSuggestion("run-1:tempo:a1", 60.0) is AnalysisActionResult.Done)
        assertEquals(60.0, f.payloadOf(f.journal.all().single().mutationId).getValue("bpm").jsonPrimitive.content.toDouble(), 0.0)

        assertTrue(f.provider.acceptSuggestion("run-1:tempo:a1", 99.0) is AnalysisActionResult.Refused)
        assertTrue("a bpm choice means nothing for a key", f.provider.acceptSuggestion("run-1:key:a1", 60.0) is AnalysisActionResult.Refused)
        assertEquals(1, f.journal.all().size)
    }

    @Test
    fun cueCandidatesAreNotAcceptedHereAndUnknownIdsAreRefused() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()

        val cue = f.provider.acceptSuggestion("run-1:cue_candidates:a1") as AnalysisActionResult.Refused
        assertTrue(cue.reason.contains("cue", ignoreCase = true))
        assertTrue(f.provider.acceptSuggestion("nope") is AnalysisActionResult.Refused)
        assertTrue(f.journal.all().isEmpty())
    }

    @Test
    fun acceptingNeverChangesTheLibraryOrTheSuggestionUntilAPullDeliversIt() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(intervals = listOf(interval("iv-full", 0, 10_000, bpm = "100.0"))))
        val before = f.library.value.tracks

        f.provider.acceptSuggestion("run-1:tempo:a1")

        assertEquals(before, f.library.value.tracks)
        assertEquals(100.0, f.library.value.tracks.single().bpm!!, 0.0)
        assertEquals(SuggestionDecision.PROPOSED, f.byKind(SuggestionKind.BPM).decision)
    }

    @Test
    fun legacyAcceptQueuesToo() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.accept("run-1:key:a1")
        assertEquals(1, f.journal.all().size)
    }

    // ---- availability of accepting ----

    @Test
    fun acceptingIsRefusedWithAReasonWhenTheServerCannotTakeIt() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = caps(mutations = setOf("interval.editor_state.replace")))

        val availability = f.provider.acceptAvailability.value as EditAvailability.Unavailable
        assertTrue(availability.reason.contains("accept", ignoreCase = true))
        assertTrue(f.provider.acceptSuggestion("run-1:tempo:a1") is AnalysisActionResult.Refused)
        assertTrue(f.journal.all().isEmpty())
    }

    @Test
    fun acceptingCanBeQueuedOfflineBeforeTheServerHasBeenAskedThisSession() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = null, state = SyncState.OFFLINE)
        assertEquals(EditAvailability.Available, f.provider.acceptAvailability.value)
        assertTrue(f.provider.acceptSuggestion("run-1:tempo:a1") is AnalysisActionResult.Done)
    }

    @Test
    fun notConnectedAndPairAgainAreSaid() = runTest(UnconfinedTestDispatcher()) {
        assertTrue((fixture(capabilities = null, state = SyncState.UNCONFIGURED).provider.acceptAvailability.value as EditAvailability.Unavailable).reason.contains("Connect"))
        assertTrue((fixture(state = SyncState.AUTH_REQUIRED).provider.acceptAvailability.value as EditAvailability.Unavailable).reason.contains("Pair again"))
    }

    // ---- where an acceptance stands ----

    private fun Fixture.accepted(): String {
        kotlinx.coroutines.runBlocking { provider.acceptSuggestion("run-1:tempo:a1") }
        return journal.all().single().mutationId
    }

    private fun conflict(id: String) = JournalReceipt(
        id, JournalReceiptOutcome.CONFLICT,
        conflict = JournalConflict("suggestion_changed", "analysis_suggestion", null, "{}", null),
    )

    @Test
    fun anAcceptanceIsWaitingThenSendingThenSavedAndOnlyTheReceiptSaysSaved() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val id = f.accepted()
        assertEquals(ServerEditStatus.PENDING, f.provider.acceptances.value.getValue("run-1:tempo:a1").status)

        f.journal.markApplying(id)
        assertEquals(ServerEditStatus.SENDING, f.provider.acceptances.value.getValue("run-1:tempo:a1").status)

        f.journal.recordReceipt(JournalReceipt(id, JournalReceiptOutcome.APPLIED, "rev-run-1:tempo:a1"))
        val saved = f.provider.acceptances.value.getValue("run-1:tempo:a1")
        assertEquals(ServerEditStatus.SAVED, saved.status)
        assertEquals(id, saved.mutationId)
    }

    @Test
    fun aSavedAcceptanceOfAnOlderRevisionIsNotShownForTheNewOne() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val id = f.accepted()
        f.journal.recordReceipt(JournalReceipt(id, JournalReceiptOutcome.APPLIED, "rev-run-1:tempo:a1"))

        f.library.value = library(listOf(suggestion("run-1:tempo:a1", "tempo", """{"bpm":120.0}""", revision = "rev-changed"), key, grid, cues))

        assertNull(f.provider.acceptances.value["run-1:tempo:a1"])
    }

    @Test
    fun aRefusalCarriesTheServersWords() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val id = f.accepted()
        f.journal.recordReceipt(JournalReceipt(id, JournalReceiptOutcome.REJECTED, detail = "target_interval_mismatch: wrong asset"))

        val acceptance = f.provider.acceptances.value.getValue("run-1:tempo:a1")
        assertEquals(ServerEditStatus.REJECTED, acceptance.status)
        assertTrue(acceptance.detail.contains("target_interval_mismatch"))
    }

    @Test
    fun aConflictOffersOnlyDismissAndDismissingClosesItHidesTheOldSuggestionAndAsksForARefresh() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val id = f.accepted()
        f.journal.recordReceipt(conflict(id))
        assertEquals(ServerEditStatus.CONFLICT, f.provider.acceptances.value.getValue("run-1:tempo:a1").status)
        val flushesBefore = f.flushes

        f.provider.dismissSuggestion("run-1:tempo:a1")

        assertEquals(JournalMutationState.RESOLVED, f.journal.mutation(id)?.state)
        assertNull(f.provider.acceptances.value["run-1:tempo:a1"])
        assertTrue(f.suggestionList().none { it.kind == SuggestionKind.BPM })
        assertEquals("the replacement arrives with the refresh", flushesBefore + 1, f.flushes)
        assertTrue("there is no reapply: nothing was re-queued", f.journal.all().size == 1)
    }

    @Test
    fun aRefusedAcceptanceCanBeDismissedToo() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val id = f.accepted()
        f.journal.recordReceipt(JournalReceipt(id, JournalReceiptOutcome.REJECTED, detail = "invalid_payload: no"))

        f.provider.dismissSuggestion("run-1:tempo:a1")

        assertEquals(JournalMutationState.RESOLVED, f.journal.mutation(id)?.state)
        assertNull(f.provider.acceptances.value["run-1:tempo:a1"])
    }

    @Test
    fun theJournalIsReadOnTheIoDispatcherNeverOnTheCallersThread() = runTest(UnconfinedTestDispatcher()) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "analysis-io-test") }
        try {
            val f = fixture(io = executor.asCoroutineDispatcher())
            f.provider.suggestions("a1")
            f.provider.acceptSuggestion("run-1:tempo:a1")

            assertTrue("loaded on ${f.journalLoadedOn}", f.journalLoadedOn.orEmpty().startsWith("analysis-io-test"))
        } finally {
            executor.shutdown()
        }
    }

    // ---- the explicit request ----

    @Test
    fun theRequestIsOfferedOnlyWhenTheServerSaidSo() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(AnalysisRequestAvailability.Offered, fixture().provider.requestAvailability.value)

        val reasons = listOf(
            fixture(capabilities = null, state = SyncState.UNCONFIGURED) to "Connect",
            fixture(capabilities = caps(analysis = emptySet(), request = null)) to "no analysis available",
            fixture(capabilities = caps(request = null)) to "on request",
            fixture(capabilities = caps(request = "unavailable")) to "on request",
            fixture(capabilities = caps(request = "enqueue_v2")) to "on request",
            fixture(state = SyncState.AUTH_REQUIRED) to "Pair again",
        )
        for ((f, word) in reasons) {
            val unavailable = f.provider.requestAvailability.value as AnalysisRequestAvailability.Unavailable
            assertTrue("'${unavailable.reason}' should mention '$word'", unavailable.reason.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun aServerWithNoAnalysisAtAllSaysSoInOneLine() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = caps(analysis = emptySet(), request = null))
        val reason = (f.provider.requestAvailability.value as AnalysisRequestAvailability.Unavailable).reason
        assertEquals("This server has no analysis available.", reason)
    }

    @Test
    fun anExplicitRequestAsksOnceForTheTrackAndReportsTheAnswerWithoutPolling() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()

        val result = f.provider.requestAnalysis("a1") as AnalysisActionResult.Done

        assertEquals(listOf("a1"), f.requests.assets)
        assertTrue(result.message.contains("Queued"))
        assertEquals("a queued job is not refreshed for", 0, f.flushes)
    }

    @Test
    fun theAnswerWordsAreHonest() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        fun answer(status: AnalysisRequestStatus, message: String? = null) {
            f.requests.outcome = AnalysisRequestOutcome.Answered(AnalysisRequestResult("a1", status, emptyMap(), null, message))
        }

        answer(AnalysisRequestStatus.RUNNING)
        assertTrue((f.provider.requestAnalysis("a1") as AnalysisActionResult.Done).message.contains("running", ignoreCase = true))

        answer(AnalysisRequestStatus.UNAVAILABLE, "No analysis worker is configured.")
        assertEquals("No analysis worker is configured.", (f.provider.requestAnalysis("a1") as AnalysisActionResult.Done).message)

        answer(AnalysisRequestStatus.UNKNOWN)
        assertTrue((f.provider.requestAnalysis("a1") as AnalysisActionResult.Done).message.contains("not know", ignoreCase = true))

        val flushesBefore = f.flushes
        answer(AnalysisRequestStatus.CACHED)
        assertTrue((f.provider.requestAnalysis("a1") as AnalysisActionResult.Done).message.contains("already", ignoreCase = true))
        assertEquals("cached results are fetched by the refresh the person just asked for", flushesBefore + 1, f.flushes)
    }

    @Test
    fun aFailedRequestIsRefusedWithTheMessage() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.requests.outcome = AnalysisRequestOutcome.Failed("The server is unreachable. Try again when it is.")

        assertEquals(
            AnalysisActionResult.Refused("The server is unreachable. Try again when it is."),
            f.provider.requestAnalysis("a1"),
        )
    }

    @Test
    fun nothingIsRequestedWhenItIsNotOfferedOrTheTrackIsNotOnTheServer() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(capabilities = caps(request = "unavailable"))
        assertTrue(f.provider.requestAnalysis("a1") is AnalysisActionResult.Refused)

        val g = fixture()
        assertTrue(g.provider.requestAnalysis("device-track") is AnalysisActionResult.Refused)
        assertTrue(f.requests.assets.isEmpty() && g.requests.assets.isEmpty())
    }

    @Test
    fun showingRefreshingAndAcceptingNeverStartAnalysis() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(library = library(suggestions = emptyList()))
        f.provider.suggestions("a1")
        f.provider.refresh("a1")
        f.library.value = library()
        f.provider.acceptSuggestion("run-1:tempo:a1")

        assertTrue(f.requests.assets.isEmpty())
        assertEquals(2, f.flushes)
    }

    @Test
    fun refreshAsksTheSessionToSyncAndNothingElse() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.provider.refresh("a1")
        assertEquals(1, f.flushes)
    }
}
