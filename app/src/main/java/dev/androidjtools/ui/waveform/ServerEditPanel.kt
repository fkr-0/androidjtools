package dev.androidjtools.ui.waveform

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.provider.EditAvailability
import dev.androidjtools.core.provider.ServerEdit
import dev.androidjtools.core.provider.ServerEditProvider
import dev.androidjtools.core.provider.ServerEditStatus
import dev.androidjtools.prep.cues.PreparationStageResult
import kotlinx.coroutines.launch

/**
 * Saving cue and loop edits to the paired server, and where each edit stands. A queued edit is listed as waiting,
 * never as done: the library only changes once the server has answered and the next pull has delivered the result.
 */
@Composable
fun ServerEditPanel(
    edits: ServerEditProvider,
    planner: ServerEditPlanner,
    trackEdits: List<ServerEdit>,
    selected: WaveformOverlay?,
    loopDraft: StagedRangeEdit?,
    positionMs: Long,
    onQueued: () -> Unit,
) {
    val availability by edits.availability.collectAsState()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }

    fun submit(build: EditBuild) {
        when (build) {
            is EditBuild.Refused -> notice = build.reason
            is EditBuild.Ready -> scope.launch {
                notice = when (val result = edits.stage(build.intent)) {
                    is PreparationStageResult.Queued -> {
                        onQueued()
                        "Queued. It is sent to the server as soon as the server can be reached."
                    }
                    is PreparationStageResult.Rejected -> result.detail.ifBlank { "This edit could not be saved." }
                    else -> "This edit could not be saved."
                }
            }
        }
    }

    val canonicalCue = selected?.takeIf { it.source == WaveformOverlaySource.CANONICAL && it.kind == WaveformOverlayKind.CUE }
    val canonicalLoop = selected?.takeIf { it.source == WaveformOverlaySource.CANONICAL && it.kind == WaveformOverlayKind.LOOP }

    Surface(modifier = Modifier.fillMaxWidth().testTag("server-edit-panel"), tonalElevation = 2.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save to server", style = MaterialTheme.typography.titleSmall)
            when (val current = availability) {
                is EditAvailability.Unavailable ->
                    Text(current.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("server-edit-unavailable"))
                EditAvailability.Available -> Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { submit(planner.addCue(positionMs)) },
                        modifier = Modifier.testTag("server-edit-add-cue"),
                    ) { Text("Add cue at playhead") }
                    if (canonicalCue != null) {
                        OutlinedButton(
                            onClick = { submit(planner.moveCue(canonicalCue.id, positionMs)) },
                            modifier = Modifier.testTag("server-edit-move-cue"),
                        ) { Text("Move cue to playhead") }
                        OutlinedButton(
                            onClick = { submit(planner.deleteCue(canonicalCue.id)) },
                            modifier = Modifier.testTag("server-edit-delete-cue"),
                        ) { Text("Delete cue") }
                    }
                    if (canonicalLoop != null && loopDraft != null && loopDraft.id == canonicalLoop.id && loopDraft.dirty) {
                        Button(
                            onClick = { submit(planner.saveLoop(canonicalLoop.id, loopDraft.startMs, loopDraft.endMs)) },
                            modifier = Modifier.testTag("server-edit-save-loop"),
                        ) { Text("Save loop to server") }
                    }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("server-edit-notice")) }
            trackEdits.forEach { edit ->
                ServerEditRow(
                    edit = edit,
                    onUseServer = { scope.launch { edits.useServerState(edit.mutationId) } },
                    onReapply = {
                        scope.launch {
                            val result = edits.reapplyDraft(edit.mutationId)
                            if (result is PreparationStageResult.Rejected) notice = result.detail
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerEditRow(edit: ServerEdit, onUseServer: () -> Unit, onReapply: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("server-edit-${edit.mutationId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${edit.summary} · ${serverEditStatusLabel(edit.status)}", style = MaterialTheme.typography.labelLarge)
        Text(edit.detail, style = MaterialTheme.typography.bodySmall)
        when (edit.status) {
            // Exactly two ways out of a conflict. There is no "keep whichever is newer".
            ServerEditStatus.CONFLICT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUseServer, modifier = Modifier.testTag("server-edit-use-server")) { Text("Use server version") }
                OutlinedButton(onClick = onReapply, modifier = Modifier.testTag("server-edit-reapply")) { Text("Reapply my change") }
            }
            ServerEditStatus.REJECTED ->
                TextButton(onClick = onUseServer, modifier = Modifier.testTag("server-edit-dismiss")) { Text("Dismiss") }
            else -> Unit
        }
    }
}
