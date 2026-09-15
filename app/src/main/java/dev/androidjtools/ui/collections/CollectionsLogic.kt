package dev.androidjtools.ui.collections

import dev.androidjtools.core.provider.ProviderUiState

sealed interface CollectionsSurfaceState {
    data object Ready : CollectionsSurfaceState
    data object Loading : CollectionsSurfaceState
    data object Empty : CollectionsSurfaceState
    data object Offline : CollectionsSurfaceState
    data class Error(val message: String) : CollectionsSurfaceState
    data class Conflict(val detail: String) : CollectionsSurfaceState
}

fun ProviderUiState.toCollectionsSurfaceState(): CollectionsSurfaceState = when (this) {
    ProviderUiState.Ready -> CollectionsSurfaceState.Ready
    ProviderUiState.Loading -> CollectionsSurfaceState.Loading
    ProviderUiState.Empty -> CollectionsSurfaceState.Empty
    ProviderUiState.Offline -> CollectionsSurfaceState.Offline
    is ProviderUiState.Error -> CollectionsSurfaceState.Error(message)
    is ProviderUiState.Conflict -> CollectionsSurfaceState.Conflict(detail)
}

fun CollectionsSurfaceState.statusCopy(): Pair<String, String>? = when (this) {
    CollectionsSurfaceState.Ready -> null
    CollectionsSurfaceState.Loading -> "Loading collections" to "Preparing fixture-backed collection data."
    CollectionsSurfaceState.Empty -> "No collection data" to "The selected provider contains no tracks or playlists yet."
    CollectionsSurfaceState.Offline -> "Offline preparation" to "Cached collection data remains editable locally; saves are queued as local intents."
    is CollectionsSurfaceState.Error -> "Collections unavailable" to message
    is CollectionsSurfaceState.Conflict -> "Conflict requires review" to detail
}
