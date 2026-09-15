package dev.androidjtools.prep.cues

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

fun hotCueContentDescription(cue: HotCueDraft): String = buildString {
    append("Hot cue")
    cue.number?.let { append(" ").append(it) }
    append(", ").append(cue.role.name.lowercase().replace('_', ' '))
    cue.label?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
    append(", at ").append(formatPreparationTime(cue.positionMs))
}

fun Modifier.hotCuePreparationSemantics(
    cue: HotCueDraft,
    staged: Boolean,
    pendingDelete: Boolean = false,
): Modifier = semantics(mergeDescendants = true) {
    contentDescription = hotCueContentDescription(cue)
    stateDescription = when {
        pendingDelete -> "Delete confirmation pending"
        staged -> "Staged edit"
        else -> "Canonical"
    }
}

internal fun formatPreparationTime(timeMs: Long): String {
    val totalMillis = timeMs.coerceAtLeast(0L)
    val minutes = totalMillis / 60_000L
    val seconds = (totalMillis % 60_000L) / 1_000L
    val millis = totalMillis % 1_000L
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}
