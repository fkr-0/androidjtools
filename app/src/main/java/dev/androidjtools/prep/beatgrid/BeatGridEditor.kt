package dev.androidjtools.prep.beatgrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun BeatGridEditor(
    state: BeatGridPreparationState,
    durationMs: Long,
    onStateChange: (BeatGridPreparationState) -> Unit,
    onCommitIntent: (BeatGridCommitIntent) -> Unit,
    playheadMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Beatgrid editor", style = MaterialTheme.typography.titleMedium)
        Text(
            "Canonical %.2f BPM · first downbeat %d ms · rev %d".format(
                state.canonical.bpm,
                state.canonical.anchorMs,
                state.canonical.revision,
            ),
            modifier = Modifier.testTag("beatgrid-canonical-summary"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Staged %.2f BPM · first downbeat %d ms".format(state.staged.bpm, state.staged.anchorMs),
            modifier = Modifier.testTag("beatgrid-staged-summary"),
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = formatEditorBpm(state.staged.bpm),
            onValueChange = { raw -> raw.toDoubleOrNull()?.let { onStateChange(state.setBpm(it)) } },
            label = { Text("BPM") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("beatgrid-bpm-input"),
        )
        OutlinedTextField(
            value = state.staged.anchorMs.toString(),
            onValueChange = { raw ->
                raw.toLongOrNull()?.let { onStateChange(state.setFirstDownbeat(it, durationMs)) }
            },
            label = { Text("First downbeat / anchor (ms)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("beatgrid-anchor-input"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onStateChange(state.halfBpm()) },
                enabled = state.canHalfBpm,
                modifier = Modifier.testTag("beatgrid-half-bpm"),
            ) { Text("Half BPM") }
            OutlinedButton(
                onClick = { onStateChange(state.doubleBpm()) },
                enabled = state.canDoubleBpm,
                modifier = Modifier.testTag("beatgrid-double-bpm"),
            ) { Text("Double BPM") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onStateChange(state.nudgePhase(-10L, durationMs)) },
                modifier = Modifier.testTag("beatgrid-phase-back"),
            ) { Text("Phase −10 ms") }
            OutlinedButton(
                onClick = { onStateChange(state.nudgePhase(10L, durationMs)) },
                modifier = Modifier.testTag("beatgrid-phase-forward"),
            ) { Text("Phase +10 ms") }
        }

        playheadMs?.let { position ->
            OutlinedButton(
                onClick = { onStateChange(state.setFirstDownbeat(position, durationMs)) },
                modifier = Modifier.testTag("beatgrid-set-downbeat-at-playhead"),
            ) { Text("Set first downbeat at playhead") }
        }

        state.candidate?.let { candidate ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Candidate %.2f BPM%s · %s%s".format(
                        candidate.bpm,
                        candidate.anchorMs?.let { " · anchor $it ms" } ?: "",
                        candidate.source,
                        candidate.confidence?.let { " · %.0f%%".format(it * 100.0) } ?: "",
                    ),
                    modifier = Modifier.testTag("beatgrid-candidate-summary"),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onStateChange(state.acceptCandidate()) },
                    modifier = Modifier.testTag("beatgrid-accept-candidate"),
                ) { Text("Accept candidate into staging") }
            }
        }

        if (state.dirty) {
            Text(
                "Pending local beatgrid edit · canonical state is unchanged until a receipt is accepted.",
                modifier = Modifier.testTag("beatgrid-pending-edit"),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onStateChange(state.undo()) },
                enabled = state.canUndo,
                modifier = Modifier.testTag("beatgrid-undo"),
            ) { Text("Undo") }
            OutlinedButton(
                onClick = { onStateChange(state.cancel()) },
                enabled = state.dirty,
                modifier = Modifier.testTag("beatgrid-cancel"),
            ) { Text("Cancel") }
            Button(
                onClick = { onCommitIntent(state.commitIntent()) },
                enabled = state.dirty,
                modifier = Modifier.testTag("beatgrid-commit-intent"),
            ) { Text("Stage commit intent") }
        }
    }
}

private fun formatEditorBpm(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.ROOT, "%.0f", value)
    } else {
        String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
    }
