package dev.androidjtools.sync.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.IntelligenceSource
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AnalysisActionResult
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.core.provider.AnalysisRequestAvailability
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.core.provider.SuggestionAcceptance
import dev.androidjtools.remote.samplelib.AnalysisRequestStatus
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.journal.AnalysisSuggestionAcceptanceCoordinator
import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.IdempotencyKeyReuseException
import dev.androidjtools.sync.journal.JournalMutation
import dev.androidjtools.sync.journal.JournalMutationState
import dev.androidjtools.sync.samplelib.ANALYSIS_ACCEPT_OPERATION
import dev.androidjtools.sync.samplelib.AnalysisRequestOutcome
import dev.androidjtools.sync.samplelib.AnalysisRequester
import dev.androidjtools.sync.samplelib.AnalysisSuggestionRecord
import dev.androidjtools.sync.samplelib.LibraryProjection
import dev.androidjtools.sync.samplelib.SuggestionData
import dev.androidjtools.sync.samplelib.SuggestionDismissals
import dev.androidjtools.sync.samplelib.SuggestionWireKind
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The paired server's analysis suggestions behind the app's [AnalysisProvider] contract.
 *
 * Read side: suggestions come from the pulled library projection and nothing else, so accepted values appear only
 * after the next pull. Write side: accepting queues a durable `analysis_suggestion.accept`; dismissing is local to
 * this device. Analysis itself starts in exactly one place, [requestAnalysis], which only a deliberate user action
 * calls and which refuses unless the server advertised `analysis_request == enqueue_v1`.
 *
 * Journal and dismissal files are read and written on [ioDispatcher], never on the caller's thread.
 */
class SampleLibAnalysisProvider(
    private val projection: StateFlow<LibraryProjection>,
    capabilities: StateFlow<ServerCapabilities?>,
    syncState: StateFlow<SyncState>,
    private val lazyJournal: Lazy<DurableMutationJournal>,
    private val dismissals: SuggestionDismissals,
    private val requester: AnalysisRequester,
    private val requestFlush: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnalysisProvider {
    private val journal: DurableMutationJournal by lazyJournal
    private val coordinator by lazy { AnalysisSuggestionAcceptanceCoordinator(journal) }
    private val perTrack = ConcurrentHashMap<String, StateFlow<List<AnalysisSuggestion>>>()

    init {
        scope.launch(ioDispatcher) { dismissals.ensureLoaded() }
    }

    override val capabilities: StateFlow<List<AnalysisCapability>> =
        combine(capabilities, syncState) { caps, _ -> capabilitiesOf(caps) }
            .stateIn(scope, SharingStarted.Eagerly, capabilitiesOf(capabilities.value))

    override fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>> = perTrack.getOrPut(trackId) {
        combine(projection, dismissals.keys) { library, dismissed -> visibleFor(library, dismissed, trackId) }
            .stateIn(scope, SharingStarted.Eagerly, visibleFor(projection.value, dismissals.keys.value, trackId))
    }

    override val requestAvailability: StateFlow<AnalysisRequestAvailability> =
        combine(syncState, capabilities, ::requestAvailabilityOf)
            .stateIn(scope, SharingStarted.Eagerly, requestAvailabilityOf(syncState.value, capabilities.value))

    override val acceptAvailability: StateFlow<EditAvailability> =
        combine(syncState, capabilities, ::acceptAvailabilityOf)
            .stateIn(scope, SharingStarted.Eagerly, acceptAvailabilityOf(syncState.value, capabilities.value))

    // The journal file is read the first time this is collected, on the I/O dispatcher, never at construction.
    override val acceptances: StateFlow<Map<String, SuggestionAcceptance>> =
        flow { emitAll(combine(journal.changes, projection) { _, library -> acceptancesOf(library) }) }
            .flowOn(ioDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun refresh(trackId: String) = requestFlush()

    override fun accept(suggestionId: String) {
        scope.launch { acceptSuggestion(suggestionId) }
    }

    override fun reject(suggestionId: String) {
        scope.launch { dismissSuggestion(suggestionId) }
    }

    // ---- accept ----

    override suspend fun acceptSuggestion(suggestionId: String, bpm: Double?): AnalysisActionResult {
        (acceptAvailability.value as? EditAvailability.Unavailable)?.let { return AnalysisActionResult.Refused(it.reason) }
        val library = projection.value
        val record = find(library, suggestionId) ?: return AnalysisActionResult.Refused("This suggestion is no longer there.")
        if (record.kind == SuggestionWireKind.CUE_CANDIDATES) {
            return AnalysisActionResult.Refused("Cue points are added as cues, not accepted.")
        }
        if (bpm != null && !isOneOfTheTempoOptions(record, bpm)) {
            return AnalysisActionResult.Refused("That tempo is not one of this suggestion's options.")
        }
        val target = record.intervalId ?: library.fullSpanIntervalOf(record.assetId)
            ?: return AnalysisActionResult.Refused("This track has no full-length section on the server to apply this to.")
        val failure = withContext(ioDispatcher) {
            try {
                coordinator.queueAccept(record, target, bpm, clock())
                null
            } catch (_: IdempotencyKeyReuseException) {
                "This was already queued differently."
            } catch (invalid: IllegalArgumentException) {
                invalid.message ?: "This suggestion cannot be accepted."
            } catch (_: IOException) {
                "The choice could not be saved on this device."
            }
        }
        if (failure != null) return AnalysisActionResult.Refused(failure)
        requestFlush()
        return AnalysisActionResult.Done("Queued. It is sent to the server as soon as the server can be reached.")
    }

    // ---- dismiss ----

    override suspend fun dismissSuggestion(suggestionId: String) {
        val record = find(projection.value, suggestionId) ?: return
        val closedConflict = withContext(ioDispatcher) {
            dismissals.dismiss(record.id, record.revision)
            val open = try {
                coordinator.latestFor(record.id)
            } catch (_: IOException) {
                null
            }
            when (open?.state) {
                JournalMutationState.CONFLICT -> closeQuietly(open.mutationId)
                JournalMutationState.REJECTED -> {
                    closeQuietly(open.mutationId)
                    false
                }
                else -> false
            }
        }
        // The server replaced the suggestion this was made against: a refresh shows what replaced it.
        if (closedConflict) requestFlush()
    }

    private fun closeQuietly(mutationId: String): Boolean = try {
        journal.resolve(mutationId)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IOException) {
        false
    }

    // ---- the one place analysis can start ----

    override suspend fun requestAnalysis(trackId: String): AnalysisActionResult {
        (requestAvailability.value as? AnalysisRequestAvailability.Unavailable)?.let { return AnalysisActionResult.Refused(it.reason) }
        if (!projection.value.hasTrack(trackId)) return AnalysisActionResult.Refused("Only tracks from the server can be analysed.")
        return when (val outcome = requester.requestAnalysis(trackId)) {
            is AnalysisRequestOutcome.Failed -> AnalysisActionResult.Refused(outcome.message)
            is AnalysisRequestOutcome.Answered -> when (outcome.result.status) {
                AnalysisRequestStatus.QUEUED -> AnalysisActionResult.Done("Queued on the server. Check for results in a moment.")
                AnalysisRequestStatus.RUNNING -> AnalysisActionResult.Done("Running on the server. Check for results in a moment.")
                AnalysisRequestStatus.CACHED -> {
                    requestFlush()
                    AnalysisActionResult.Done("Results already exist on the server. Fetching them now.")
                }
                AnalysisRequestStatus.UNAVAILABLE ->
                    AnalysisActionResult.Done(outcome.result.message ?: "The server cannot analyse this track.")
                AnalysisRequestStatus.UNKNOWN ->
                    AnalysisActionResult.Done("The server answered with something this app does not know. Check for results later.")
            }
        }
    }

    // ---- derivations ----

    private fun find(library: LibraryProjection, id: String): AnalysisSuggestionRecord? =
        library.suggestions.values.asSequence().flatten().firstOrNull { it.id == id }

    private fun isOneOfTheTempoOptions(record: AnalysisSuggestionRecord, bpm: Double): Boolean {
        val tempo = record.data as? SuggestionData.Tempo ?: return false
        return (tempo.alternatives + tempo.bpm).any { abs(it - bpm) < 1e-9 }
    }

    private fun visibleFor(library: LibraryProjection, dismissed: Set<String>, trackId: String): List<AnalysisSuggestion> {
        val track = library.tracks.firstOrNull { it.id == trackId }
        return library.suggestionsOf(trackId)
            .filter { SuggestionDismissals.keyOf(it.id, it.revision) !in dismissed }
            .map { toSuggestion(it, track?.bpm, track?.key) }
    }

    private fun toSuggestion(record: AnalysisSuggestionRecord, trackBpm: Double?, trackKey: String?): AnalysisSuggestion {
        val (kind, payload) = when (val data = record.data) {
            is SuggestionData.Tempo -> SuggestionKind.BPM to SuggestionPayload.Bpm(data.bpm, data.alternatives)
            is SuggestionData.Key -> SuggestionKind.KEY to SuggestionPayload.Key(data.label)
            is SuggestionData.BeatGrid -> SuggestionKind.BEAT_GRID to SuggestionPayload.Grid(data.bpm, data.firstBeatMs, data.beatTimesMs.size)
            is SuggestionData.CueCandidates -> SuggestionKind.CUE to SuggestionPayload.CuePoints(data.pointsMs)
        }
        // "Accepted" is only ever read off the pulled library: the track already carries what the suggestion proposes.
        val matches = record.intervalId == null && when (val data = record.data) {
            is SuggestionData.Tempo -> trackBpm != null && abs(trackBpm - data.bpm) < 0.005
            is SuggestionData.BeatGrid -> trackBpm != null && abs(trackBpm - data.bpm) < 0.005
            is SuggestionData.Key -> trackKey != null && trackKey.equals(data.label, ignoreCase = true)
            is SuggestionData.CueCandidates -> false
        }
        return AnalysisSuggestion(
            id = record.id,
            trackId = record.assetId,
            kind = kind,
            payload = payload,
            source = IntelligenceSource(service = record.tool, version = record.toolVersion),
            confidence = record.confidence,
            generatedAt = record.createdAt ?: Instant.EPOCH,
            decision = if (matches) SuggestionDecision.ACCEPTED else SuggestionDecision.PROPOSED,
            analysisRunId = record.analysisRunId,
        )
    }

    private fun capabilitiesOf(caps: ServerCapabilities?): List<AnalysisCapability> = listOf(
        Triple("tempo", "Tempo analysis", setOf(SuggestionKind.BPM)),
        Triple("key", "Musical key analysis", setOf(SuggestionKind.KEY)),
        Triple("beat_grid", "Beat grid", setOf(SuggestionKind.BEAT_GRID)),
        Triple("cue_candidates", "Cue point candidates", setOf(SuggestionKind.CUE)),
    ).map { (wire, name, kinds) ->
        val available = caps != null && wire in caps.analysis
        AnalysisCapability(
            id = "analysis.$wire",
            displayName = name,
            available = available,
            kinds = kinds,
            detail = when {
                available -> null
                caps == null -> "Not connected to a server"
                else -> "This server does not present $name"
            },
            anySource = true,
        )
    }

    private fun requestAvailabilityOf(state: SyncState, caps: ServerCapabilities?): AnalysisRequestAvailability = when {
        state == SyncState.UNCONFIGURED -> AnalysisRequestAvailability.Unavailable("Connect to a Sample Lib server to get analysis.")
        state == SyncState.AUTH_REQUIRED -> AnalysisRequestAvailability.Unavailable("Pair again to ask the server for analysis.")
        state == SyncState.SERVER_INCOMPATIBLE -> AnalysisRequestAvailability.Unavailable("This server has no analysis available.")
        caps == null -> AnalysisRequestAvailability.Unavailable("Waiting to hear from the server.")
        caps.analysis.isEmpty() -> AnalysisRequestAvailability.Unavailable("This server has no analysis available.")
        !caps.canRequestAnalysis -> AnalysisRequestAvailability.Unavailable("This server cannot analyse tracks on request.")
        else -> AnalysisRequestAvailability.Offered
    }

    private fun acceptAvailabilityOf(state: SyncState, caps: ServerCapabilities?): EditAvailability = when {
        state == SyncState.UNCONFIGURED -> EditAvailability.Unavailable("Connect to a Sample Lib server to accept suggestions.")
        state == SyncState.AUTH_REQUIRED -> EditAvailability.Unavailable("Pair again to accept suggestions.")
        state == SyncState.SERVER_INCOMPATIBLE -> EditAvailability.Unavailable("This server does not support accepting suggestions from this app version.")
        caps != null && ANALYSIS_ACCEPT_OPERATION !in caps.mutations ->
            EditAvailability.Unavailable("This server does not accept analysis suggestions.")
        else -> EditAvailability.Available
    }

    private fun acceptancesOf(library: LibraryProjection): Map<String, SuggestionAcceptance> {
        val current = library.suggestions.values.asSequence().flatten().associateBy { it.id }
        return journal.all()
            .asSequence()
            .filter { it.operation == ANALYSIS_ACCEPT_OPERATION && it.state != JournalMutationState.RESOLVED }
            .groupBy { it.entityId }
            .mapNotNull { (suggestionId, mutations) ->
                val record = current[suggestionId] ?: return@mapNotNull null
                val latest = mutations.maxByOrNull { it.sequence } ?: return@mapNotNull null
                // A saved acceptance says nothing about a suggestion that changed afterwards.
                if (latest.state == JournalMutationState.RECEIPT_RECORDED && latest.baseRevision != record.revision) return@mapNotNull null
                val status = statusOf(latest.state)
                suggestionId to SuggestionAcceptance(suggestionId, latest.mutationId, status, detailOf(status, latest))
            }
            .toMap()
    }

    private fun statusOf(state: JournalMutationState): ServerEditStatus = when (state) {
        JournalMutationState.PENDING -> ServerEditStatus.PENDING
        JournalMutationState.APPLYING -> ServerEditStatus.SENDING
        JournalMutationState.RECEIPT_RECORDED -> ServerEditStatus.SAVED
        JournalMutationState.REJECTED -> ServerEditStatus.REJECTED
        JournalMutationState.CONFLICT -> ServerEditStatus.CONFLICT
        JournalMutationState.RESOLVED -> error("resolved acceptances are not listed")
    }

    private fun detailOf(status: ServerEditStatus, mutation: JournalMutation): String = when (status) {
        ServerEditStatus.PENDING -> "Waiting to be sent to the server."
        ServerEditStatus.SENDING -> "Sending to the server…"
        ServerEditStatus.SAVED -> "Saved on the server."
        ServerEditStatus.CONFLICT -> "The server's suggestion changed since you saw it. Dismiss it; a refresh shows the new one."
        ServerEditStatus.REJECTED -> mutation.receipt?.detail ?: "The server could not accept this."
    }
}
