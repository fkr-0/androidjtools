package dev.androidjtools.sync.analysis

import dev.androidjtools.core.model.AnalysisCapability
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.SuggestionKind
import dev.androidjtools.core.provider.AnalysisProvider
import dev.androidjtools.remote.samplelib.AnalysisCandidateDecision
import dev.androidjtools.remote.samplelib.AnalysisCandidateFreshness
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.SampleLibAnalysisCandidate
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibSession
import dev.androidjtools.remote.samplelib.pullAnalysisCandidates
import dev.androidjtools.sync.journal.AnalysisAcceptanceState
import dev.androidjtools.sync.journal.AnalysisSuggestionAcceptanceCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real-provider adapter for the existing backend-neutral AnalysisProvider contract.
 * Provider/model names remain provenance only; availability is exposed through semantic IDs.
 */
class SampleLibAnalysisProvider(
    private val client: SampleLibClient,
    private val session: SampleLibSession,
    private val scope: CoroutineScope,
    private val acceptance: AnalysisSuggestionAcceptanceCoordinator,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AnalysisProvider {
    private val candidatesById = linkedMapOf<String, SampleLibAnalysisCandidate>()
    private val byAsset = mutableMapOf<String, MutableStateFlow<List<AnalysisSuggestion>>>()

    private val _capabilities = MutableStateFlow(
        if ("suggestions.read" in session.capabilities.analysis && "analysis_suggestion" in session.capabilities.pull) {
            listOf(
                AnalysisCapability("analysis.tempo", "Tempo analysis", true, setOf(SuggestionKind.BPM)),
                AnalysisCapability("analysis.key", "Musical key analysis", true, setOf(SuggestionKind.KEY)),
                AnalysisCapability("analysis.structure", "Structure analysis", true, setOf(SuggestionKind.CUE, SuggestionKind.LOOP, SuggestionKind.REGION)),
                AnalysisCapability("analysis.related_tracks", "Related tracks", true, setOf(SuggestionKind.RELATED_TRACK)),
            )
        } else {
            listOf(
                AnalysisCapability("analysis.tempo", "Tempo analysis", false, setOf(SuggestionKind.BPM), "Sample Lib suggestion feed unavailable"),
                AnalysisCapability("analysis.key", "Musical key analysis", false, setOf(SuggestionKind.KEY), "Sample Lib suggestion feed unavailable"),
                AnalysisCapability("analysis.structure", "Structure analysis", false, setOf(SuggestionKind.CUE, SuggestionKind.LOOP, SuggestionKind.REGION), "Sample Lib suggestion feed unavailable"),
                AnalysisCapability("analysis.related_tracks", "Related tracks", false, setOf(SuggestionKind.RELATED_TRACK), "Sample Lib suggestion feed unavailable"),
            )
        }
    )
    override val capabilities: StateFlow<List<AnalysisCapability>> = _capabilities.asStateFlow()

    override fun suggestions(trackId: String): StateFlow<List<AnalysisSuggestion>> =
        synchronized(this) { byAsset.getOrPut(trackId) { MutableStateFlow(emptyList()) } }.asStateFlow()

    override fun refresh(trackId: String) {
        scope.launch {
            var cursor: String? = null
            val loaded = mutableListOf<SampleLibAnalysisCandidate>()
            do {
                val page = client.pullAnalysisCandidates(session, cursor)
                loaded += page.candidates
                cursor = page.nextCursor
                val more = page.hasMore
            } while (more)
            synchronized(this@SampleLibAnalysisProvider) {
                loaded.forEach { candidatesById[it.candidateId] = it }
                emitAsset(trackId)
            }
        }
    }

    /** Reject is local advisory state only and never creates a mutation. */
    override fun reject(suggestionId: String) {
        synchronized(this) {
            val current = candidatesById[suggestionId] ?: return
            candidatesById[suggestionId] = current.copy(decision = AnalysisCandidateDecision.REJECTED)
            emitAsset(current.assetId)
        }
    }

    /** Accept queues normal sync intent but deliberately keeps the proposal unaccepted until a receipt. */
    override fun accept(suggestionId: String) {
        synchronized(this) {
            val current = candidatesById[suggestionId] ?: return
            acceptance.queue(current, clockMillis())
            emitAsset(current.assetId)
        }
    }

    fun recordReceipt(suggestionId: String, receipt: MutationReceipt): AnalysisAcceptanceState = synchronized(this) {
        val current = candidatesById[suggestionId] ?: throw IllegalArgumentException("unknown analysis suggestion $suggestionId")
        val resolution = acceptance.recordAuthoritativeReceipt(current, receipt)
        candidatesById[suggestionId] = when (resolution.state) {
            AnalysisAcceptanceState.ACCEPTED -> current.copy(decision = AnalysisCandidateDecision.ACCEPTED)
            AnalysisAcceptanceState.REJECTED -> current.copy(decision = AnalysisCandidateDecision.REJECTED)
            AnalysisAcceptanceState.CONFLICT -> current.copy(freshness = AnalysisCandidateFreshness.STALE)
            else -> current
        }
        emitAsset(current.assetId)
        resolution.state
    }

    fun candidate(suggestionId: String): SampleLibAnalysisCandidate? = synchronized(this) { candidatesById[suggestionId] }

    private fun emitAsset(assetId: String) {
        val flow = byAsset.getOrPut(assetId) { MutableStateFlow(emptyList()) }
        flow.value = candidatesById.values
            .asSequence()
            .filter { it.assetId == assetId }
            .sortedBy { it.candidateId }
            .map(SampleLibAnalysisCandidate::toPresentationSuggestion)
            .toList()
    }
}
