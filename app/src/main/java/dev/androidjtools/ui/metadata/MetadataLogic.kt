package dev.androidjtools.ui.metadata

import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.SuggestionDecision
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.Track

data class MetadataDraft(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val rating: Int? = null,
    val key: String = "",
    val bpm: String = "",
    val energy: String = "",
    val tags: Set<String> = emptySet(),
    val comments: String = "",
    val mixInNote: String = "",
    val mixOutNote: String = "",
    val preparationNote: String = "",
)

data class AcceptedProposal(
    val field: String,
    val suggestionId: String,
    val source: String,
    val confidence: Double?,
)

data class MetadataEditorState(
    val canonical: MetadataDraft,
    val staged: MetadataDraft = canonical,
    val acceptedProposals: Map<String, AcceptedProposal> = emptyMap(),
    val conflictDetail: String? = null,
    val queuedIntent: MetadataMutationIntent? = null,
) {
    val dirty: Boolean get() = (queuedIntent?.staged ?: canonical) != staged
}

data class MetadataMutationIntent(
    val trackId: String,
    val changedFields: Set<String>,
    val staged: MetadataDraft,
    val provenance: Map<String, AcceptedProposal>,
)

data class BatchMetadataPatch(
    val genre: String? = null,
    val rating: Int? = null,
    val key: String? = null,
    val bpm: String? = null,
    val addTags: Set<String> = emptySet(),
    val removeTags: Set<String> = emptySet(),
    val commentsSuffix: String? = null,
    val preparationNote: String? = null,
)

fun Track.toMetadataDraft() = MetadataDraft(
    trackId = id,
    title = title,
    artist = artist,
    album = album.orEmpty(),
    rating = rating,
    key = key.orEmpty(),
    bpm = bpm?.toString().orEmpty(),
    energy = energy?.toString().orEmpty(),
)

fun startMetadataEditor(track: Track, conflictDetail: String? = null) = MetadataEditorState(
    canonical = track.toMetadataDraft(),
    conflictDetail = conflictDetail,
)

fun validateMetadataDraft(draft: MetadataDraft): Map<String, String> = buildMap {
    if (draft.title.isBlank()) put("title", "Title is required")
    if (draft.artist.isBlank()) put("artist", "Artist is required")
    if (draft.year.isNotBlank()) {
        val year = draft.year.toIntOrNull()
        if (year == null || year !in 1000..2100) put("year", "Year must be between 1000 and 2100")
    }
    if (draft.rating != null && draft.rating !in 1..5) put("rating", "Rating must be 1–5")
    if (draft.bpm.isNotBlank()) {
        val bpm = draft.bpm.toDoubleOrNull()
        if (bpm == null || bpm !in 20.0..300.0) put("bpm", "BPM must be between 20 and 300")
    }
    if (draft.energy.isNotBlank()) {
        val energy = draft.energy.toDoubleOrNull()
        if (energy == null || energy !in 0.0..1.0) put("energy", "Energy must be between 0 and 1")
    }
    val invalidTag = draft.tags.firstOrNull { ':' !in it || it.substringBefore(':').isBlank() || it.substringAfter(':').isBlank() }
    if (invalidTag != null) put("tags", "Use namespaced tags such as mood:late-night")
}

fun cancelMetadataChanges(state: MetadataEditorState): MetadataEditorState = state.copy(
    staged = state.queuedIntent?.staged ?: state.canonical,
    acceptedProposals = emptyMap(),
)

fun queueMetadataSave(state: MetadataEditorState): MetadataEditorState {
    if (!state.dirty || validateMetadataDraft(state.staged).isNotEmpty()) return state
    val changed = changedMetadataFields(state.canonical, state.staged)
    val intent = MetadataMutationIntent(
        trackId = state.staged.trackId,
        changedFields = changed,
        staged = state.staged,
        provenance = (state.queuedIntent?.provenance.orEmpty() + state.acceptedProposals)
            .filterKeys(changed::contains),
    )
    return state.copy(acceptedProposals = emptyMap(), queuedIntent = intent)
}

fun applyBatchMetadataPatch(state: MetadataEditorState, patch: BatchMetadataPatch): MetadataEditorState {
    val staged = state.staged.copy(
        genre = patch.genre ?: state.staged.genre,
        rating = patch.rating ?: state.staged.rating,
        key = patch.key ?: state.staged.key,
        bpm = patch.bpm ?: state.staged.bpm,
        tags = (state.staged.tags + patch.addTags) - patch.removeTags,
        comments = patch.commentsSuffix?.let { suffix ->
            listOf(state.staged.comments, suffix).filter(String::isNotBlank).joinToString("\n")
        } ?: state.staged.comments,
        preparationNote = patch.preparationNote ?: state.staged.preparationNote,
    )
    return state.copy(staged = staged)
}

fun acceptSuggestionIntoStage(
    state: MetadataEditorState,
    suggestion: AnalysisSuggestion,
): MetadataEditorState {
    if (suggestion.trackId != state.staged.trackId || suggestion.stale || suggestion.decision != SuggestionDecision.PROPOSED) return state
    val source = buildString {
        append(suggestion.source.service)
        suggestion.source.model?.let { append(" / ").append(it) }
        suggestion.source.version?.let { append(" v").append(it) }
    }
    val accepted = when (val payload = suggestion.payload) {
        is SuggestionPayload.Bpm -> Triple("bpm", state.staged.copy(bpm = payload.value.toString()), source)
        is SuggestionPayload.Key -> Triple("key", state.staged.copy(key = payload.value), source)
        is SuggestionPayload.Text -> {
            val value = payload.detail.trim()
            if (!payload.title.equals("tag", ignoreCase = true) || ':' !in value) return state
            Triple("tags", state.staged.copy(tags = state.staged.tags + value), source)
        }
        else -> return state
    }
    return state.copy(
        staged = accepted.second,
        acceptedProposals = state.acceptedProposals + (
            accepted.first to AcceptedProposal(
                field = accepted.first,
                suggestionId = suggestion.id,
                source = accepted.third,
                confidence = suggestion.confidence,
            )
        ),
    )
}

fun changedMetadataFields(canonical: MetadataDraft, staged: MetadataDraft): Set<String> = buildSet {
    if (canonical.title != staged.title) add("title")
    if (canonical.artist != staged.artist) add("artist")
    if (canonical.album != staged.album) add("album")
    if (canonical.genre != staged.genre) add("genre")
    if (canonical.year != staged.year) add("year")
    if (canonical.rating != staged.rating) add("rating")
    if (canonical.key != staged.key) add("key")
    if (canonical.bpm != staged.bpm) add("bpm")
    if (canonical.energy != staged.energy) add("energy")
    if (canonical.tags != staged.tags) add("tags")
    if (canonical.comments != staged.comments) add("comments")
    if (canonical.mixInNote != staged.mixInNote) add("mixInNote")
    if (canonical.mixOutNote != staged.mixOutNote) add("mixOutNote")
    if (canonical.preparationNote != staged.preparationNote) add("preparationNote")
}
