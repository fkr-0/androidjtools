package dev.androidjtools.ui.analysis

import dev.androidjtools.core.model.SuggestionKind

object AnalysisAssistantFixtures {
    fun competition(): AnalysisAssistantUiState {
        val sourceA = SuggestionProvenanceUi("sample-intelligence", "tempo-ensemble", "3", "2026.09")
        val sourceB = SuggestionProvenanceUi("tempo-ensemble", "essentia-rhythm", "2", "2026.09")
        val bpmA = candidate("bpm-a", SuggestionKind.BPM, "92.48 BPM", sourceA, 0.96)
        val bpmB = candidate("bpm-b", SuggestionKind.BPM, "184.96 BPM", sourceB, 0.74, stale = true)
        val keyA = candidate("key-a", SuggestionKind.KEY, "8A / A minor", sourceA, 0.86)
        val keyB = candidate("key-b", SuggestionKind.KEY, "8B / C major", sourceB, 0.62)
        val cue = candidate("cue-drop", SuggestionKind.CUE, "Detected drop", sourceA, 0.89, startMs = 31_820)
        val loop = candidate("loop-break", SuggestionKind.LOOP, "Break loop", sourceA, 0.82, startMs = 62_000, endMs = 70_000)
        val transcript = candidate("phrase-a", SuggestionKind.TRANSCRIPT_MARKER, "“bring it back”", SuggestionProvenanceUi("comfyui-asr", "whisper-workflow", "1"), 0.81, startMs = 46_000)
        val stem = candidate("stem-drums", SuggestionKind.STEM, "Drums stem", SuggestionProvenanceUi("demucs", "htdemucs", "4"), 0.99)
        val related = candidate("related-a", SuggestionKind.RELATED_TRACK, "Night Bus", sourceA, 0.78)
        val groups = listOf(
            CandidateGroupUi(SuggestionKind.BPM, "Tempo", listOf(bpmA, bpmB)),
            CandidateGroupUi(SuggestionKind.KEY, "Key", listOf(keyA, keyB)),
            CandidateGroupUi(SuggestionKind.CUE, "Cue points", listOf(cue)),
            CandidateGroupUi(SuggestionKind.LOOP, "Loops", listOf(loop)),
            CandidateGroupUi(SuggestionKind.STEM, "Stems", listOf(stem)),
            CandidateGroupUi(SuggestionKind.TRANSCRIPT_MARKER, "Transcript & phrases", listOf(transcript)),
            CandidateGroupUi(SuggestionKind.RELATED_TRACK, "Related tracks", listOf(related)),
        )
        return AnalysisAssistantUiState(
            trackId = "trk-fixture",
            trackTitle = "Fixture Track",
            offline = true,
            capabilities = listOf(
                CapabilityUi("sample-intelligence", "Sample Intelligence", CapabilityAvailability.AVAILABLE),
                CapabilityUi("demucs", "Demucs stems", CapabilityAvailability.DEGRADED, "Worker busy; cached stem remains available"),
                CapabilityUi("comfyui-asr", "ComfyUI ASR", CapabilityAvailability.UNAVAILABLE, "Not reachable offline"),
            ),
            jobs = listOf(
                AnalysisJobUi("tempo", "Tempo ensemble", AnalysisJobState.CACHED, detail = "Cached results remain reviewable offline"),
                AnalysisJobUi("stems", "Stem separation", AnalysisJobState.PARTIAL, progress = 0.65f, detail = "Drums ready; vocal stem pending"),
                AnalysisJobUi("asr", "Speech transcript", AnalysisJobState.UNAVAILABLE, detail = "Capability unavailable"),
            ),
            candidateGroups = groups,
            safetyFindings = listOf(
                SafetyFindingUi(
                    id = "safety-loudness",
                    title = "Loudness safety",
                    message = "Peak level may be unsafe for headphone audition. Lower playback gain before previewing.",
                    severity = "blocking",
                    provenance = sourceA,
                    stale = false,
                ),
                SafetyFindingUi(
                    id = "safety-spectral",
                    title = "Spectral safety",
                    message = "Possible narrow high-frequency outlier near 0:58.",
                    severity = "warning",
                    provenance = sourceA,
                    stale = false,
                ),
            ),
            timelineItems = listOf(
                TimelineAnalysisItemUi(cue.id, 31_820, label = cue.value, kind = cue.kind, stale = false),
                TimelineAnalysisItemUi(transcript.id, 46_000, label = transcript.value, kind = transcript.kind, stale = false),
                TimelineAnalysisItemUi(loop.id, 62_000, 70_000, loop.value, loop.kind, stale = false),
            ),
            partialFailure = true,
        )
    }

    fun empty() = AnalysisAssistantUiState(
        trackId = "trk-empty",
        trackTitle = "Unanalysed Track",
        offline = false,
        capabilities = listOf(CapabilityUi("sample-intelligence", "Sample Intelligence", CapabilityAvailability.AVAILABLE)),
        jobs = listOf(AnalysisJobUi("analysis", "Analysis", AnalysisJobState.NOT_REQUESTED)),
        candidateGroups = emptyList(),
        safetyFindings = emptyList(),
        timelineItems = emptyList(),
        partialFailure = false,
    )

    fun failure() = empty().copy(
        jobs = listOf(AnalysisJobUi("analysis", "Analysis", AnalysisJobState.FAILED, detail = "Analysis failed; cached metadata is unaffected")),
        failureMessage = "Analysis services failed. Canonical library data is still available.",
    )

    private fun candidate(
        id: String,
        kind: SuggestionKind,
        value: String,
        source: SuggestionProvenanceUi,
        confidence: Double,
        stale: Boolean = false,
        startMs: Long? = null,
        endMs: Long? = null,
    ) = CandidateUi(
        id = id,
        trackId = "trk-fixture",
        kind = kind,
        title = kind.name,
        value = value,
        confidence = confidence,
        provenance = source,
        inputRevision = 42,
        generatedAt = "2026-09-15T12:00:00Z",
        stale = stale,
        timelineStartMs = startMs,
        timelineEndMs = endMs,
    )
}

