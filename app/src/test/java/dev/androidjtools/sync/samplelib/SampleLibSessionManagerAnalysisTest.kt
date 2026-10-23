package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.AnalysisRequestResult
import dev.androidjtools.remote.samplelib.AnalysisRequestStatus
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Analysis in the session: which scopes are pulled, and that nothing ever starts analysis by itself. */
class SampleLibSessionManagerAnalysisTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, SAMPLE_LIB_CLIENT_ID, "install-1")
    private lateinit var store: SampleLibProfileStore
    private lateinit var manager: SampleLibSessionManager

    @Before
    fun setUp() {
        store = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher())
        manager = SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" })
    }

    @After
    fun close() = db.close()

    private val analysisServer get() = transport.capabilities.copy(
        analysis = setOf("tempo", "key", "beat_grid", "cue_candidates"),
        analysisRequest = "enqueue_v1",
    )

    private val library = setOf("library")
    private val both = setOf("library", "analysis")

    private fun offerAnalysis() {
        transport.capabilities = analysisServer
        transport.grantedScopes = setOf("sync.read", "sync.write", "analysis.read")
    }

    // ---- pull scopes ----

    @Test
    fun anOlderServerIsPulledExactlyAsBefore() = runBlocking {
        transport.enqueuePage(page("c1"))
        manager.pair("https://samplelab.example", "246810")

        assertEquals(listOf(library), transport.pullScopes)
        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
    }

    @Test
    fun aServerAdvertisingAnalysisIsPulledWithTheAnalysisScopeInTheSameRequest() = runBlocking {
        offerAnalysis()
        transport.enqueuePage(page("c1"))
        manager.pair("https://samplelab.example", "246810")

        assertEquals(listOf(both), transport.pullScopes)
    }

    @Test
    fun aCredentialWithoutTheAnalysisScopeNeverAsksForIt() = runBlocking {
        transport.capabilities = analysisServer
        transport.grantedScopes = setOf("sync.read", "sync.write")
        transport.enqueuePage(page("c1"))
        manager.pair("https://samplelab.example", "246810")

        assertEquals(listOf(library), transport.pullScopes)
        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
    }

    @Test
    fun theGrantedScopesAreRememberedForLaterRefreshes() = runBlocking {
        offerAnalysis()
        transport.enqueuePage(page("c1"))
        manager.pair("https://samplelab.example", "246810")
        transport.enqueuePage(page("c2"))

        manager.refresh()

        assertEquals(listOf(both, both), transport.pullScopes)
        assertEquals(setOf("sync.read", "sync.write", "analysis.read"), (store.load() as StoredSessionResult.Loaded).session.profile.scopes)
    }

    @Test
    fun aProfileThatNeverRecordedItsScopesTriesAndFallsBackWhenTheServerRefusesTheScope() = runBlocking {
        transport.capabilities = analysisServer
        store.save(SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1"), MobileCredential.issued("token-secret"))
        transport.failScope = "analysis" to SampleLibFailure.ScopeForbidden()
        transport.enqueuePage(page("c1"))

        manager.refresh()

        assertEquals("not a pairing problem", SyncState.ONLINE_IDLE, manager.state.value)
        assertEquals(listOf(both, library), transport.pullScopes)

        transport.enqueuePage(page("c2"))
        manager.refresh()
        assertEquals("the refusal is remembered, so it is not asked again", library, transport.pullScopes.last())
    }

    @Test
    fun aRefusedLibraryScopeIsStillAPairingProblem() = runBlocking {
        store.save(SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1"), MobileCredential.issued("token-secret"))
        transport.failScope = "library" to SampleLibFailure.ScopeForbidden()

        manager.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, manager.state.value)
    }

    // ---- nothing starts analysis ----

    @Test
    fun pairingRefreshingAndSyncingNeverAskForAnalysis() = runBlocking {
        offerAnalysis()
        transport.enqueuePage(page("c1"))
        transport.enqueuePage(page("c2"))
        manager.pair("https://samplelab.example", "246810")
        manager.refresh()

        assertFalse("analysis_request" in transport.calls)
    }

    // ---- the explicit request ----

    private fun paired() = runBlocking {
        offerAnalysis()
        transport.enqueuePage(page("c1"))
        manager.pair("https://samplelab.example", "246810")
        transport.calls.clear()
    }

    @Test
    fun anExplicitRequestAsksTheServerOnceForThatAssetAndReturnsTheAnswer() = runBlocking {
        paired()
        var seen: Triple<String, List<String>?, Boolean>? = null
        transport.requestScript = { asset, kinds, force ->
            seen = Triple(asset, kinds, force)
            AnalysisRequestResult(asset, AnalysisRequestStatus.QUEUED, mapOf("tempo" to AnalysisRequestStatus.QUEUED), "job-1", null)
        }

        val outcome = manager.requestAnalysis("a1")

        assertEquals(Triple("a1", null, false), seen)
        assertEquals(listOf("hello", "analysis_request"), transport.calls)
        assertEquals(AnalysisRequestStatus.QUEUED, (outcome as AnalysisRequestOutcome.Answered).result.status)
    }

    @Test
    fun aServerThatStoppedOfferingItIsNotAskedAndTheCapabilitiesAreUpdated() = runBlocking {
        paired()
        transport.capabilities = analysisServer.copy(analysisRequest = "unavailable")

        val outcome = manager.requestAnalysis("a1")

        assertTrue(outcome is AnalysisRequestOutcome.Failed)
        assertFalse("analysis_request" in transport.calls)
        assertEquals("unavailable", manager.capabilities.value?.analysisRequest)
    }

    @Test
    fun noConnectionIsExplainedNotThrown() = runBlocking {
        val outcome = manager.requestAnalysis("a1")

        assertTrue((outcome as AnalysisRequestOutcome.Failed).message.contains("Connect"))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun failuresBecomeShortHumanMessagesWithoutTheServerAddress() = runBlocking {
        paired()
        val expectations = listOf(
            SampleLibFailure.Offline() to "unreachable",
            SampleLibFailure.EntityNotFound() to "does not know",
            SampleLibFailure.AuthRequired() to "Pair again",
            SampleLibFailure.Unavailable() to "unexpected",
        )
        for ((failure, word) in expectations) {
            transport.requestScript = { _, _, _ -> throw failure }
            val message = (manager.requestAnalysis("a1") as AnalysisRequestOutcome.Failed).message
            assertTrue("$message should mention '$word'", message.contains(word, ignoreCase = true))
            assertFalse(message.contains("samplelab.example"))
        }
        assertEquals("a failed request does not change the sync state", SyncState.ONLINE_IDLE, manager.state.value)
        assertNull(manager.error.value)
    }
}
