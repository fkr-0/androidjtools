package dev.androidjtools.ui.collections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.AnalysisSuggestion
import dev.androidjtools.core.model.SuggestionPayload
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.ui.metadata.BatchMetadataPatch
import dev.androidjtools.ui.metadata.MetadataEditorState
import dev.androidjtools.ui.metadata.acceptSuggestionIntoStage
import dev.androidjtools.ui.metadata.applyBatchMetadataPatch
import dev.androidjtools.ui.metadata.cancelMetadataChanges
import dev.androidjtools.ui.metadata.queueMetadataSave
import dev.androidjtools.ui.metadata.startMetadataEditor
import dev.androidjtools.ui.metadata.validateMetadataDraft
import dev.androidjtools.ui.playlist.CratePath
import dev.androidjtools.ui.playlist.LocalPlaylistIdAllocator
import dev.androidjtools.ui.playlist.PlaylistDeleteUndo
import dev.androidjtools.ui.playlist.PlaylistDraft
import dev.androidjtools.ui.playlist.PlaylistMutationIntent
import dev.androidjtools.ui.playlist.SavedSmartFilter
import dev.androidjtools.ui.playlist.SmartField
import dev.androidjtools.ui.playlist.SmartMatchMode
import dev.androidjtools.ui.playlist.SmartOperator
import dev.androidjtools.ui.playlist.SmartRule
import dev.androidjtools.ui.playlist.addTracks
import dev.androidjtools.ui.playlist.deletePlaylist
import dev.androidjtools.ui.playlist.describeSmartRule
import dev.androidjtools.ui.playlist.moveTrack
import dev.androidjtools.ui.playlist.previewSmartRule
import dev.androidjtools.ui.playlist.removeTrack
import dev.androidjtools.ui.playlist.restorePlaylist
import dev.androidjtools.ui.playlist.toDraft
import dev.androidjtools.ui.playlist.validatePlaylistDraft
import dev.androidjtools.ui.playlist.validateSmartRule

private enum class CollectionsMode(val label: String) {
    PLAYLISTS("Playlists"),
    SMART("Smart filters"),
    METADATA("Metadata"),
}

@Composable
fun CollectionsScreen(providers: AppProviders) {
    val providerState by providers.runtime.uiState.collectAsState()
    val playlists by providers.playlists.playlists.collectAsState()
    val tracks by providers.library.tracks.collectAsState()
    val surfaceState = providerState.toCollectionsSurfaceState()
    var mode by remember { mutableStateOf(CollectionsMode.PLAYLISTS) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Collections & metadata", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Fixture-first preparation. Changes stay staged locally until a backend mutation contract accepts them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CollectionsMode.entries.forEach { item ->
                    FilterChip(selected = item == mode, onClick = { mode = item }, label = { Text(item.label) })
                }
            }
        }

        surfaceState.statusCopy()?.let { (title, body) ->
            StatusBanner(title, body)
        }

        when (mode) {
            CollectionsMode.PLAYLISTS -> PlaylistWorkspace(playlists.map { it.toDraft() }, tracks)
            CollectionsMode.SMART -> SmartFilterWorkspace(tracks)
            CollectionsMode.METADATA -> MetadataWorkspace(providers, tracks, surfaceState)
        }
    }
}

@Composable
private fun StatusBanner(title: String, body: String) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaylistWorkspace(sourceDrafts: List<PlaylistDraft>, tracks: List<Track>) {
    var drafts by remember(sourceDrafts) { mutableStateOf(sourceDrafts) }
    var selectedId by remember(sourceDrafts) { mutableStateOf(sourceDrafts.firstOrNull()?.id) }
    var newName by remember { mutableStateOf("") }
    var newCrate by remember { mutableStateOf("Sets") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var deleteUndo by remember { mutableStateOf<PlaylistDeleteUndo?>(null) }
    var latestIntent by remember { mutableStateOf<PlaylistMutationIntent?>(null) }
    val localIdAllocator = remember { LocalPlaylistIdAllocator() }

    val selected = drafts.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(0.42f).fillMaxSize().padding(start = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Crates & playlists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(newName, { newName = it }, label = { Text("New playlist") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newCrate, { newCrate = it }, label = { Text("Crate path") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val draft = PlaylistDraft(
                            id = localIdAllocator.next(drafts.map(PlaylistDraft::id)),
                            name = newName.trim(),
                            cratePath = CratePath.parse(newCrate),
                        )
                        if (validatePlaylistDraft(draft, drafts).isEmpty()) {
                            drafts = drafts + draft
                            selectedId = draft.id
                            latestIntent = PlaylistMutationIntent(draft.id, "create", draft.name, "Create in ${draft.cratePath.displayName}")
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create locally") }
                deleteUndo?.let { undo ->
                    TextButton(
                        onClick = {
                            drafts = restorePlaylist(drafts, undo)
                            selectedId = undo.removed.id
                            deleteUndo = null
                        },
                    ) { Text("Undo delete: ${undo.removed.name}") }
                }
            }
            items(drafts, key = { it.id }) { draft ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(draft.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(draft.cratePath.displayName, style = MaterialTheme.typography.labelMedium)
                        Text("${draft.trackIds.size} tracks${if (draft.isSmart) " · smart" else ""}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selectedId = draft.id }) { Text(if (selectedId == draft.id) "Selected" else "Edit") }
                    }
                }
            }
        }

        Column(
            Modifier.weight(0.58f).fillMaxSize().padding(start = 6.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            latestIntent?.let {
                Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                    Text("Queued locally: ${it.operation} ${it.targetLabel} · ${it.detail}", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select or create a playlist") }
            } else {
                PlaylistEditor(
                    draft = selected,
                    siblings = drafts,
                    tracks = tracks,
                    pendingDelete = pendingDeleteId == selected.id,
                    onPendingDelete = { pendingDeleteId = if (it) selected.id else null },
                    onChange = { changed -> drafts = drafts.map { if (it.id == changed.id) changed else it } },
                    onIntent = { latestIntent = it },
                    onDelete = {
                        val (next, undo) = deletePlaylist(drafts, selected.id)
                        drafts = next
                        deleteUndo = undo
                        latestIntent = PlaylistMutationIntent(selected.id, "delete", selected.name, "Deletion can be undone in this session")
                        pendingDeleteId = null
                        selectedId = next.firstOrNull()?.id
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistEditor(
    draft: PlaylistDraft,
    siblings: List<PlaylistDraft>,
    tracks: List<Track>,
    pendingDelete: Boolean,
    onPendingDelete: (Boolean) -> Unit,
    onChange: (PlaylistDraft) -> Unit,
    onIntent: (PlaylistMutationIntent) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(draft.id, draft.name) { mutableStateOf(draft.name) }
    var crate by remember(draft.id, draft.cratePath) { mutableStateOf(draft.cratePath.segments.joinToString(" / ")) }
    val staged = draft.copy(name = name, cratePath = CratePath.parse(crate))
    val errors = validatePlaylistDraft(staged, siblings)

    Text("Edit ${draft.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(crate, { crate = it }, label = { Text("Nested crate path") }, modifier = Modifier.fillMaxWidth())
    if (errors.isNotEmpty()) Text(errors.joinToString(" · "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onChange(staged)
                onIntent(PlaylistMutationIntent(draft.id, "rename/move", staged.name, "Stage in ${staged.cratePath.displayName}"))
            },
            enabled = errors.isEmpty() && staged != draft,
        ) { Text("Stage details") }
        TextButton(
            onClick = {
                val changed = addTracks(draft, tracks.map { it.id })
                onChange(changed)
                onIntent(PlaylistMutationIntent(draft.id, "batch-add", draft.name, "Add ${changed.trackIds.size - draft.trackIds.size} unique tracks"))
            },
            enabled = tracks.any { it.id !in draft.trackIds },
        ) { Text("Add all") }
    }
    HorizontalDivider()
    Text("Ordered membership", style = MaterialTheme.typography.labelLarge)
    LazyColumn(
        Modifier.fillMaxWidth().height(260.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(draft.trackIds, key = { _, id -> id }) { index, trackId ->
            val track = tracks.firstOrNull { it.id == trackId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: trackId, style = MaterialTheme.typography.bodyMedium)
                    Text(track?.artist ?: "Missing fixture track", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onChange(moveTrack(draft, index, index - 1)) }, enabled = index > 0) { Text("↑") }
                TextButton(onClick = { onChange(moveTrack(draft, index, index + 1)) }, enabled = index < draft.trackIds.lastIndex) { Text("↓") }
                TextButton(onClick = { onChange(removeTrack(draft, trackId)) }) { Text("Remove") }
            }
        }
        tracks.filter { it.id !in draft.trackIds }.forEach { track ->
            item(key = "add-${track.id}") {
                TextButton(onClick = { onChange(addTracks(draft, listOf(track.id))) }) { Text("+ ${track.title}") }
            }
        }
    }
    HorizontalDivider()
    if (pendingDelete) {
        Text("Delete “${draft.name}” from ${draft.cratePath.displayName}? This only stages a local deletion and can be undone.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDelete) { Text("Confirm delete ${draft.name}") }
            TextButton(onClick = { onPendingDelete(false) }) { Text("Cancel") }
        }
    } else {
        TextButton(onClick = { onPendingDelete(true) }) { Text("Delete…") }
    }
}

@Composable
private fun SmartFilterWorkspace(tracks: List<Track>) {
    var rootMode by remember { mutableStateOf(SmartMatchMode.ALL) }
    var secondaryMode by remember { mutableStateOf(SmartMatchMode.ANY) }
    var minimumBpm by remember { mutableStateOf("90") }
    var artistContains by remember { mutableStateOf("fixture") }
    var requiredKey by remember { mutableStateOf("") }
    var filterName by remember { mutableStateOf("Late set candidates") }
    var saved by remember { mutableStateOf(emptyList<SavedSmartFilter>()) }

    val nestedChildren = buildList<SmartRule> {
        if (artistContains.isNotBlank()) add(SmartRule.Condition(SmartField.ARTIST, SmartOperator.CONTAINS, artistContains))
        if (requiredKey.isNotBlank()) add(SmartRule.Condition(SmartField.KEY, SmartOperator.EQUALS, requiredKey))
    }
    val rule = SmartRule.Group(
        rootMode,
        buildList {
            if (minimumBpm.isNotBlank()) add(SmartRule.Condition(SmartField.BPM, SmartOperator.AT_LEAST, minimumBpm))
            if (nestedChildren.isNotEmpty()) add(SmartRule.Group(secondaryMode, nestedChildren))
        },
    )
    val errors = validateSmartRule(rule)
    val preview = previewSmartRule(tracks, rule)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Smart playlist rule builder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Groups are explicit: root ${rootMode.name} with a nested ${secondaryMode.name} group.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartMatchMode.entries.forEach { item ->
                    FilterChip(item == rootMode, { rootMode = item }, label = { Text("Root ${item.name}") })
                }
            }
            OutlinedTextField(minimumBpm, { minimumBpm = it }, label = { Text("BPM at least") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartMatchMode.entries.forEach { item ->
                    FilterChip(item == secondaryMode, { secondaryMode = item }, label = { Text("Nested ${item.name}") })
                }
            }
            OutlinedTextField(artistContains, { artistContains = it }, label = { Text("Artist contains") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(requiredKey, { requiredKey = it }, label = { Text("Key equals (optional)") }, modifier = Modifier.fillMaxWidth())
            Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                Column(Modifier.padding(10.dp)) {
                    Text(describeSmartRule(rule), style = MaterialTheme.typography.bodyMedium)
                    Text("Preview: ${preview.size} of ${tracks.size} tracks", style = MaterialTheme.typography.labelLarge)
                    preview.take(5).forEach { Text("• ${it.artist} — ${it.title}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (errors.isNotEmpty()) Text(errors.joinToString(" · "), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(filterName, { filterName = it }, label = { Text("Saved filter name") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    saved = saved + SavedSmartFilter("filter-${saved.size + 1}", filterName.trim(), rule)
                },
                enabled = filterName.isNotBlank() && errors.isEmpty(),
            ) { Text("Save filter") }
        }
        if (saved.isNotEmpty()) {
            item { Text("Saved filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(saved, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.name, fontWeight = FontWeight.Medium)
                        Text(describeSmartRule(item.rule), style = MaterialTheme.typography.bodySmall)
                        Text("${previewSmartRule(tracks, item.rule).size} matches", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataWorkspace(
    providers: AppProviders,
    tracks: List<Track>,
    surfaceState: CollectionsSurfaceState,
) {
    var selectedTrackId by remember(tracks) { mutableStateOf(tracks.firstOrNull()?.id) }
    var states by remember(tracks) {
        mutableStateOf(
            tracks.associate { track ->
                track.id to startMetadataEditor(
                    track,
                    conflictDetail = (surfaceState as? CollectionsSurfaceState.Conflict)?.detail,
                )
            },
        )
    }
    var batchGenre by remember { mutableStateOf("") }
    var batchTag by remember { mutableStateOf("") }
    val selectedTrack = tracks.firstOrNull { it.id == selectedTrackId }
    val selectedState = selectedTrackId?.let(states::get)
    val suggestions = if (selectedTrackId != null) {
        providers.analysis.suggestions(selectedTrackId!!).collectAsState().value
    } else {
        emptyList()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Metadata editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Canonical values stay visible; edits and accepted intelligence proposals remain staged until Save queues a local mutation intent.", style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tracks.forEach { track ->
                FilterChip(track.id == selectedTrackId, { selectedTrackId = track.id }, label = { Text(track.title) })
            }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tracks available for metadata editing") }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(batchGenre, { batchGenre = it }, label = { Text("Batch genre") }, modifier = Modifier.weight(1f))
            OutlinedTextField(batchTag, { batchTag = it }, label = { Text("Batch tag namespace:value") }, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val patch = BatchMetadataPatch(
                        genre = batchGenre.takeIf(String::isNotBlank),
                        addTags = batchTag.takeIf { ':' in it }?.let(::setOf).orEmpty(),
                    )
                    states = states.mapValues { (_, state) -> applyBatchMetadataPatch(state, patch) }
                },
                enabled = batchGenre.isNotBlank() || ':' in batchTag,
            ) { Text("Stage all") }
        }
        selectedTrack?.let { track ->
            selectedState?.let { state ->
                MetadataEditor(
                    track = track,
                    state = state,
                    suggestions = suggestions,
                    onStateChange = { changed -> states = states + (track.id to changed) },
                )
            }
        }
    }
}

@Composable
private fun MetadataEditor(
    track: Track,
    state: MetadataEditorState,
    suggestions: List<AnalysisSuggestion>,
    onStateChange: (MetadataEditorState) -> Unit,
) {
    val draft = state.staged
    val errors = validateMetadataDraft(draft)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            state.conflictDetail?.let {
                Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                    Text("Conflict-ready: $it. Local edits are preserved; remote overwrite is not attempted.", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.queuedIntent?.let { intent ->
                Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
                    Text("Queued locally for ${track.title}: ${intent.changedFields.sorted().joinToString()}", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Canonical: ${state.canonical.artist} — ${state.canonical.title} · ${state.canonical.bpm.ifBlank { "BPM —" }} · ${state.canonical.key.ifBlank { "Key —" }}", style = MaterialTheme.typography.labelMedium)
            MetadataField("Title", draft.title, errors["title"]) { onStateChange(state.copy(staged = draft.copy(title = it))) }
            MetadataField("Artist", draft.artist, errors["artist"]) { onStateChange(state.copy(staged = draft.copy(artist = it))) }
            MetadataField("Album", draft.album, null) { onStateChange(state.copy(staged = draft.copy(album = it))) }
            MetadataField("Genre", draft.genre, null) { onStateChange(state.copy(staged = draft.copy(genre = it))) }
            MetadataField("Year", draft.year, errors["year"]) { onStateChange(state.copy(staged = draft.copy(year = it))) }
            Text("Rating", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { rating ->
                    FilterChip(draft.rating == rating, { onStateChange(state.copy(staged = draft.copy(rating = rating))) }, label = { Text("$rating★") })
                }
                TextButton(onClick = { onStateChange(state.copy(staged = draft.copy(rating = null))) }) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetadataField("Key", draft.key, null, Modifier.weight(1f)) { onStateChange(state.copy(staged = draft.copy(key = it))) }
                MetadataField("BPM", draft.bpm, errors["bpm"], Modifier.weight(1f)) { onStateChange(state.copy(staged = draft.copy(bpm = it))) }
                MetadataField("Energy 0–1", draft.energy, errors["energy"], Modifier.weight(1f)) { onStateChange(state.copy(staged = draft.copy(energy = it))) }
            }
            MetadataField("Tags (namespace:value, comma separated)", draft.tags.sorted().joinToString(", "), errors["tags"]) {
                val tags = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
                onStateChange(state.copy(staged = draft.copy(tags = tags)))
            }
            MetadataField("Comments", draft.comments, null) { onStateChange(state.copy(staged = draft.copy(comments = it))) }
            MetadataField("Mix-in note", draft.mixInNote, null) { onStateChange(state.copy(staged = draft.copy(mixInNote = it))) }
            MetadataField("Mix-out note", draft.mixOutNote, null) { onStateChange(state.copy(staged = draft.copy(mixOutNote = it))) }
            MetadataField("DJ preparation note", draft.preparationNote, null) { onStateChange(state.copy(staged = draft.copy(preparationNote = it))) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onStateChange(queueMetadataSave(state)) }, enabled = state.dirty && errors.isEmpty()) { Text("Save locally") }
                TextButton(onClick = { onStateChange(cancelMetadataChanges(state)) }, enabled = state.dirty) { Text("Cancel staged") }
                Text(if (state.dirty) "Unsaved staged changes" else "Clean", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Intelligence proposals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Proposals never overwrite canonical metadata automatically.", style = MaterialTheme.typography.bodySmall)
        }
        val usable = suggestions.filter { it.payload is SuggestionPayload.Bpm || it.payload is SuggestionPayload.Key || (it.payload is SuggestionPayload.Text && it.payload.title.equals("tag", true)) }
        if (usable.isEmpty()) {
            item { Text("No BPM/key/tag proposals from the current fixture provider.", style = MaterialTheme.typography.bodySmall) }
        }
        items(usable, key = { it.id }) { suggestion ->
            SuggestionRow(state, suggestion) { onStateChange(acceptSuggestionIntoStage(state, suggestion)) }
        }
    }
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    error: String?,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> ({ Text(message) }) },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SuggestionRow(
    state: MetadataEditorState,
    suggestion: AnalysisSuggestion,
    onAccept: () -> Unit,
) {
    val proposed = when (val payload = suggestion.payload) {
        is SuggestionPayload.Bpm -> "BPM ${payload.value}"
        is SuggestionPayload.Key -> "Key ${payload.value}"
        is SuggestionPayload.Text -> "Tag ${payload.detail}"
        else -> return
    }
    val canonical = when (suggestion.payload) {
        is SuggestionPayload.Bpm -> "canonical ${state.canonical.bpm.ifBlank { "—" }}"
        is SuggestionPayload.Key -> "canonical ${state.canonical.key.ifBlank { "—" }}"
        else -> "canonical tags ${state.canonical.tags.sorted().joinToString().ifBlank { "—" }}"
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(proposed, fontWeight = FontWeight.Medium)
                Text("$canonical · ${suggestion.source.service}${suggestion.confidence?.let { " · ${(it * 100).toInt()}%" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAccept) { Text("Accept into staged") }
        }
    }
}
