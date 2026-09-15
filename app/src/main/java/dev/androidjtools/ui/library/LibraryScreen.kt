package dev.androidjtools.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppProviders
import java.util.Locale

@Composable
fun LibraryScreen(
    providers: AppProviders,
    surfaceState: LibrarySurfaceState? = null,
) {
    val providerUiState by providers.runtime.uiState.collectAsState()
    val tracks by providers.library.tracks.collectAsState()
    val currentTrackId by providers.playback.currentTrackId.collectAsState()
    val isPlaying by providers.playback.isPlaying.collectAsState()
    val syncState by providers.sync.state.collectAsState()
    val effectiveSurfaceState = surfaceState ?: providerUiState.toLibrarySurfaceState()

    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.TITLE.name) }
    var directionName by rememberSaveable { mutableStateOf(SortDirection.ASCENDING.name) }
    var bpmBandName by rememberSaveable { mutableStateOf(BpmBand.ANY.name) }
    var keyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var ratingFourPlus by rememberSaveable { mutableStateOf(false) }
    var energyHalfPlus by rememberSaveable { mutableStateOf(false) }
    var availabilityName by rememberSaveable { mutableStateOf(AvailabilityFilter.ANY.name) }
    var analysisName by rememberSaveable { mutableStateOf(AnalysisFilter.ANY.name) }
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) { mutableStateOf(emptySet()) }
    var savedViews by rememberSaveable(stateSaver = SavedViewsSaver) { mutableStateOf(emptyList()) }
    var activeSavedViewId by rememberSaveable { mutableStateOf<String?>(null) }
    var nextSavedViewOrdinal by rememberSaveable { mutableStateOf(1) }
    var detailTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val sort = remember(sortName) { enumValueOrDefault(sortName, LibrarySort.TITLE) }
    val direction = remember(directionName) { enumValueOrDefault(directionName, SortDirection.ASCENDING) }
    val filters = remember(
        bpmBandName,
        keyFilter,
        ratingFourPlus,
        energyHalfPlus,
        availabilityName,
        analysisName,
    ) {
        LibraryFilters(
            bpmBand = enumValueOrDefault(bpmBandName, BpmBand.ANY),
            key = keyFilter,
            ratingFourPlus = ratingFourPlus,
            energyHalfPlus = energyHalfPlus,
            availability = enumValueOrDefault(availabilityName, AvailabilityFilter.ANY),
            analysis = enumValueOrDefault(analysisName, AnalysisFilter.ANY),
        )
    }
    val browseState = remember(query, sort, direction, filters) {
        LibraryBrowseState(query = query, sort = sort, direction = direction, filters = filters)
    }
    val visibleTracks = remember(tracks, browseState) { filterAndSortTracks(tracks, browseState) }
    val availableKeys = remember(tracks) {
        tracks.mapNotNull { it.key?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val selectedSet = selectedIds
    val visibleSelectedCount = remember(visibleTracks, selectedSet) {
        visibleTracks.count { it.id in selectedSet }
    }
    val detailTrack = remember(tracks, detailTrackId) { tracks.firstOrNull { it.id == detailTrackId } }

    LaunchedEffect(tracks) {
        val liveIds = tracks.asSequence().map { it.id }.toSet()
        selectedIds = selectedIds.filterTo(linkedSetOf()) { it in liveIds }
        detailTrackId?.let { trackId -> if (trackId !in liveIds) detailTrackId = null }
    }

    fun applyBrowseState(state: LibraryBrowseState) {
        query = state.query
        sortName = state.sort.name
        directionName = state.direction.name
        bpmBandName = state.filters.bpmBand.name
        keyFilter = state.filters.key
        ratingFourPlus = state.filters.ratingFourPlus
        energyHalfPlus = state.filters.energyHalfPlus
        availabilityName = state.filters.availability.name
        analysisName = state.filters.analysis.name
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val roomy = maxWidth >= 600.dp
        val horizontalPadding = if (roomy) 24.dp else 12.dp

        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                totalCount = tracks.size,
                visibleCount = visibleTracks.size,
                query = query,
                onQueryChange = {
                    query = it
                    activeSavedViewId = null
                },
                sort = sort,
                onCycleSort = {
                    sortName = sort.next().name
                    activeSavedViewId = null
                },
                direction = direction,
                onToggleDirection = {
                    directionName = direction.toggle().name
                    activeSavedViewId = null
                },
                filters = filters,
                availableKeys = availableKeys,
                onFiltersChange = { updated ->
                    bpmBandName = updated.bpmBand.name
                    keyFilter = updated.key
                    ratingFourPlus = updated.ratingFourPlus
                    energyHalfPlus = updated.energyHalfPlus
                    availabilityName = updated.availability.name
                    analysisName = updated.analysis.name
                    activeSavedViewId = null
                },
                savedViews = savedViews,
                activeSavedViewId = activeSavedViewId,
                onSaveCurrentView = {
                    val ordinal = nextSavedViewOrdinal
                    val id = "saved-$ordinal"
                    savedViews = saveLibraryView(savedViews, id, "View $ordinal", browseState)
                    activeSavedViewId = id
                    nextSavedViewOrdinal = ordinal + 1
                },
                onApplySavedView = { view ->
                    applyBrowseState(view.state)
                    activeSavedViewId = view.id
                },
                onRenameSavedView = { id, name ->
                    savedViews = renameSavedLibraryView(savedViews, id, name)
                },
                onDeleteSavedView = { id ->
                    savedViews = deleteSavedLibraryView(savedViews, id)
                    if (activeSavedViewId == id) activeSavedViewId = null
                },
                horizontalPadding = horizontalPadding,
            )

            if (effectiveSurfaceState == LibrarySurfaceState.Offline || syncState == SyncState.OFFLINE) {
                OfflineBanner(horizontalPadding)
            }

            Box(Modifier.fillMaxSize()) {
                when (effectiveSurfaceState) {
                    LibrarySurfaceState.Loading -> LibraryLoadingState()
                    LibrarySurfaceState.Empty -> LibraryMessageState(
                        title = "Library is empty",
                        body = "The fixture-first shell is ready; add or select a fixture dataset to populate tracks.",
                    )
                    is LibrarySurfaceState.Error -> LibraryMessageState(
                        title = "Library unavailable",
                        body = effectiveSurfaceState.message,
                    )
                    is LibrarySurfaceState.Conflict -> LibraryMessageState(
                        title = "Library conflict",
                        body = effectiveSurfaceState.detail,
                    )
                    LibrarySurfaceState.Ready,
                    LibrarySurfaceState.Offline,
                    -> {
                        when {
                            detailTrack != null -> LibraryTrackDetail(
                                track = detailTrack,
                                isCurrent = currentTrackId == detailTrack.id,
                                isPlaying = currentTrackId == detailTrack.id && isPlaying,
                                selected = detailTrack.id in selectedSet,
                                onBack = { detailTrackId = null },
                                onPlay = { providers.playback.play(detailTrack.id) },
                                onToggleSelection = {
                                    selectedIds = toggleSelection(selectedIds, detailTrack.id)
                                },
                                horizontalPadding = horizontalPadding,
                            )
                            tracks.isEmpty() -> LibraryMessageState(
                                title = "Library is empty",
                                body = "The fixture-first shell is ready; add or select a fixture dataset to populate tracks.",
                            )
                            visibleTracks.isEmpty() -> LibraryMessageState(
                                title = "No matches",
                                body = "Try another search term or clear one of the active filters.",
                            )
                            else -> TrackList(
                                tracks = visibleTracks,
                                currentTrackId = currentTrackId,
                                isPlaying = isPlaying,
                                selectedIds = selectedSet,
                                onPlay = providers.playback::play,
                                onOpenDetail = { detailTrackId = it },
                                onToggleSelection = { trackId ->
                                    selectedIds = toggleSelection(selectedSet, trackId)
                                },
                                contentPadding = PaddingValues(
                                    start = horizontalPadding,
                                    end = horizontalPadding,
                                    bottom = if (selectedIds.isNotEmpty()) 92.dp else 16.dp,
                                ),
                                roomy = roomy,
                                listState = listState,
                            )
                        }
                    }
                }

                if (
                    detailTrack == null &&
                    selectedIds.isNotEmpty() &&
                    (effectiveSurfaceState == LibrarySurfaceState.Ready || effectiveSurfaceState == LibrarySurfaceState.Offline)
                ) {
                    BatchActionBar(
                        selectedCount = selectedIds.size,
                        visibleCount = visibleTracks.size,
                        visibleSelectedCount = visibleSelectedCount,
                        onSelectVisible = {
                            selectedIds = selectedSet + visibleTracks.map { it.id }
                        },
                        onPlayFirst = {
                            tracks.firstOrNull { it.id in selectedSet }?.let { providers.playback.play(it.id) }
                        },
                        onClear = { selectedIds = emptySet() },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    totalCount: Int,
    visibleCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    sort: LibrarySort,
    onCycleSort: () -> Unit,
    direction: SortDirection,
    onToggleDirection: () -> Unit,
    filters: LibraryFilters,
    availableKeys: List<String>,
    onFiltersChange: (LibraryFilters) -> Unit,
    savedViews: List<SavedLibraryView>,
    activeSavedViewId: String?,
    onSaveCurrentView: () -> Unit,
    onApplySavedView: (SavedLibraryView) -> Unit,
    onRenameSavedView: (String, String) -> Unit,
    onDeleteSavedView: (String) -> Unit,
    horizontalPadding: Dp,
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var savedViewsExpanded by rememberSaveable { mutableStateOf(false) }
    var keyMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var renamingViewId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var pendingDeleteViewId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeView = savedViews.firstOrNull { it.id == activeSavedViewId }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (visibleCount == totalCount) "$totalCount tracks" else "$visibleCount of $totalCount tracks",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AssistChip(
                onClick = onCycleSort,
                label = { Text("Sort: ${sort.label}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                modifier = Modifier.semantics { contentDescription = "Sort by ${sort.label}. Double tap to change field." },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search library" },
            singleLine = true,
            label = { Text("Search tracks, artists, albums or keys") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onToggleDirection,
                label = { Text(direction.label) },
                modifier = Modifier.semantics {
                    contentDescription = "Sort direction ${direction.label}. Double tap to reverse."
                },
            )
            FilterChip(
                selected = filters.activeCount > 0,
                onClick = { showFilters = !showFilters },
                label = { Text(if (filters.activeCount == 0) "Filters" else "Filters ${filters.activeCount}") },
            )
            AssistChip(
                onClick = onSaveCurrentView,
                label = { Text("Save view") },
                leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
            )
            Box {
                AssistChip(
                    onClick = { savedViewsExpanded = true },
                    enabled = savedViews.isNotEmpty(),
                    label = { Text(if (savedViews.isEmpty()) "No saved views" else "Saved ${savedViews.size}") },
                    leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                )
                DropdownMenu(expanded = savedViewsExpanded, onDismissRequest = { savedViewsExpanded = false }) {
                    savedViews.forEach { view ->
                        DropdownMenuItem(
                            text = { Text(if (view.id == activeSavedViewId) "✓ ${view.name}" else view.name) },
                            onClick = {
                                savedViewsExpanded = false
                                onApplySavedView(view)
                            },
                        )
                    }
                }
            }
            if (activeView != null) {
                TextButton(onClick = {
                    renamingViewId = activeView.id
                    renameText = activeView.name
                    pendingDeleteViewId = null
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rename")
                }
                TextButton(onClick = { pendingDeleteViewId = activeView.id }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }

        if (showFilters) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { onFiltersChange(filters.copy(bpmBand = filters.bpmBand.next())) },
                    label = { Text(filters.bpmBand.label) },
                )
                Box {
                    AssistChip(
                        onClick = { keyMenuExpanded = true },
                        label = { Text(filters.key?.let { "Key: $it" } ?: "Any key") },
                    )
                    DropdownMenu(expanded = keyMenuExpanded, onDismissRequest = { keyMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Any key") },
                            onClick = {
                                keyMenuExpanded = false
                                onFiltersChange(filters.copy(key = null))
                            },
                        )
                        availableKeys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key) },
                                onClick = {
                                    keyMenuExpanded = false
                                    onFiltersChange(filters.copy(key = key))
                                },
                            )
                        }
                    }
                }
                FilterChip(
                    selected = filters.ratingFourPlus,
                    onClick = { onFiltersChange(filters.copy(ratingFourPlus = !filters.ratingFourPlus)) },
                    label = { Text("4+ stars") },
                )
                FilterChip(
                    selected = filters.energyHalfPlus,
                    onClick = { onFiltersChange(filters.copy(energyHalfPlus = !filters.energyHalfPlus)) },
                    label = { Text("Energy ≥ 0.5") },
                )
                AssistChip(
                    onClick = { onFiltersChange(filters.copy(availability = filters.availability.next())) },
                    label = { Text(filters.availability.label) },
                )
                AssistChip(
                    onClick = { onFiltersChange(filters.copy(analysis = filters.analysis.next())) },
                    label = { Text(filters.analysis.label) },
                )
                if (filters.activeCount > 0) {
                    TextButton(onClick = { onFiltersChange(LibraryFilters()) }) { Text("Clear filters") }
                }
            }
        }

        if (renamingViewId != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Rename saved view") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        renamingViewId?.let { id ->
                            onRenameSavedView(id, renameText)
                            renamingViewId = null
                        }
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Save name") }
                TextButton(onClick = { renamingViewId = null }) { Text("Cancel") }
            }
        }

        val pendingDelete = savedViews.firstOrNull { it.id == pendingDeleteViewId }
        if (pendingDelete != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Delete ${pendingDelete.name}?", modifier = Modifier.weight(1f))
                TextButton(onClick = { pendingDeleteViewId = null }) { Text("Cancel") }
                Button(onClick = {
                    onDeleteSavedView(pendingDelete.id)
                    pendingDeleteViewId = null
                }) { Text("Delete view") }
            }
        }
    }
}

@Composable
private fun OfflineBanner(horizontalPadding: Dp) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null)
            Column {
                Text("Offline", style = MaterialTheme.typography.labelLarge)
                Text("Offline-ready tracks remain available.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun TrackList(
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    selectedIds: Set<String>,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    contentPadding: PaddingValues,
    roomy: Boolean,
    listState: LazyListState,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(
            items = tracks,
            key = { it.id },
            contentType = { "track-row" },
        ) { track ->
            TrackRow(
                track = track,
                isCurrent = currentTrackId == track.id,
                isPlaying = currentTrackId == track.id && isPlaying,
                selected = track.id in selectedIds,
                onPlay = { onPlay(track.id) },
                onOpenDetail = { onOpenDetail(track.id) },
                onToggleSelection = { onToggleSelection(track.id) },
                roomy = roomy,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleSelection: () -> Unit,
    roomy: Boolean,
) {
    var menuExpanded by rememberSaveable(track.id) { mutableStateOf(false) }
    val metadata = remember(track) { trackMetadata(track) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = if (isCurrent || selected) 2.dp else 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.semantics {
                    contentDescription = if (selected) "Deselect ${track.title}" else "Select ${track.title}"
                },
            )
            ArtworkPlaceholder(track)
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenDetail)
                    .semantics { contentDescription = "Open details for ${track.title}" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Playing" else "Current track",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append(track.artist)
                        track.album?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = if (roomy) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onPlay,
                modifier = Modifier.semantics { contentDescription = "Play ${track.title}" },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${track.title}")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        onClick = {
                            menuExpanded = false
                            onOpenDetail()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Play now") },
                        onClick = {
                            menuExpanded = false
                            onPlay()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (selected) "Deselect" else "Select") },
                        onClick = {
                            menuExpanded = false
                            onToggleSelection()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTrackDetail(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selected: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onToggleSelection: () -> Unit,
    horizontalPadding: Dp,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
            }
            Text("Track details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkPlaceholder(track)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(track.artist, style = MaterialTheme.typography.titleMedium)
                track.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Text(trackMetadata(track), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (track.offlineAvailable) "Available offline" else "Streaming availability",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (track.isAnalyzed) "Analysis available" else "Analysis pending",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay) {
                Icon(if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isCurrent && isPlaying) "Playing" else "Play")
            }
            FilterChip(
                selected = selected,
                onClick = onToggleSelection,
                label = { Text(if (selected) "Selected" else "Select") },
            )
        }
    }
}

@Composable
private fun ArtworkPlaceholder(track: Track) {
    val initials = remember(track.artist, track.title) {
        listOf(track.artist, track.title)
            .mapNotNull { it.trim().firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifBlank { "♪" }
    }
    Surface(
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "Artwork placeholder for ${track.title}"
        },
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    visibleCount: Int,
    visibleSelectedCount: Int,
    onSelectVisible: () -> Unit,
    onPlayFirst: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Text("$selectedCount selected", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSelectVisible, enabled = visibleSelectedCount < visibleCount) { Text("Select visible") }
            Button(onClick = onPlayFirst) { Text("Play first") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun LibraryLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading library…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LibraryMessageState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val SelectedIdsSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

private val SavedViewsSaver = listSaver<List<SavedLibraryView>, String>(
    save = { views -> views.map(::serializeSavedLibraryView) },
    restore = { encoded -> encoded.mapNotNull(::deserializeSavedLibraryView) },
)

private fun trackMetadata(track: Track): String = buildList {
    add(track.bpm?.let { String.format(Locale.ROOT, "%.1f BPM", it) } ?: "BPM —")
    add(track.key ?: "Key —")
    add(track.rating?.let { "★".repeat(it.coerceIn(1, 5)) } ?: "Unrated")
    add(if (track.offlineAvailable) "Offline" else "Stream")
    add(if (track.isAnalyzed) "Analyzed" else "Needs analysis")
}.joinToString(" · ")
