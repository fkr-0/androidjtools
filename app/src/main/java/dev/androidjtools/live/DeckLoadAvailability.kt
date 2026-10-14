package dev.androidjtools.live

import dev.androidjtools.core.model.Track

data class DeckLoadAvailability(val enabled: Boolean, val reason: String?)

/** A deck takes device files and downloaded copies only; anything that would stream stays off the decks. */
fun deckLoadAvailability(track: Track): DeckLoadAvailability = when {
    !track.playable -> DeckLoadAvailability(false, track.unplayableReason ?: "This track cannot be played.")
    track.deckBlockedReason != null -> DeckLoadAvailability(false, track.deckBlockedReason)
    else -> DeckLoadAvailability(true, null)
}
