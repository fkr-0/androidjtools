package dev.androidjtools.prep.loops

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

fun loopContentDescription(loop: LoopDraft): String = buildString {
    append("Loop, ").append(loop.role.name.lowercase().replace('_', ' '))
    loop.label?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
    append(", ").append(loop.startMs).append(" to ").append(loop.endMs).append(" milliseconds")
}

fun Modifier.loopPreparationSemantics(
    loop: LoopDraft,
    staged: Boolean,
    pendingDelete: Boolean = false,
): Modifier = semantics(mergeDescendants = true) {
    contentDescription = loopContentDescription(loop)
    stateDescription = when {
        pendingDelete -> "Delete confirmation pending"
        loop.active && staged -> "Active, staged edit"
        loop.active -> "Active"
        staged -> "Staged edit"
        else -> "Inactive canonical loop"
    }
}
