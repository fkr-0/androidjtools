package dev.androidjtools.ui.waveform

import dev.androidjtools.core.provider.ServerEdit
import dev.androidjtools.core.provider.ServerEditStatus

/** Short words for where an edit stands. Only an acknowledged edit says "Saved". */
fun serverEditStatusLabel(status: ServerEditStatus): String = when (status) {
    ServerEditStatus.PENDING -> "Waiting to send"
    ServerEditStatus.SENDING -> "Sending"
    ServerEditStatus.SAVED -> "Saved on server"
    ServerEditStatus.CONFLICT -> "Needs your decision"
    ServerEditStatus.REJECTED -> "Not accepted"
}

/** The edits to show for one track: every unfinished one, newest first, plus the latest few saved ones. */
fun visibleServerEdits(edits: List<ServerEdit>, trackId: String, savedLimit: Int = 3): List<ServerEdit> {
    var savedShown = 0
    return edits.filter { it.trackId == trackId }.filter {
        it.status != ServerEditStatus.SAVED || savedShown++ < savedLimit
    }
}
