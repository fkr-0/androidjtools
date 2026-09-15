package dev.androidjtools.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidjtools.AndroidDjToolsApp
import dev.androidjtools.ui.theme.AndroidDjToolsTheme

class DebugHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = DebugHarnessController(this)
        setContent {
            AndroidDjToolsTheme {
                DebugHarnessScreen(controller)
            }
        }
    }
}

@Composable
private fun DebugHarnessScreen(controller: DebugHarnessController) {
    val config by controller.config.collectAsState()
    val providers by controller.providers.collectAsState()
    val health by controller.health.collectAsState()
    val pending by providers.journal.pending.collectAsState()
    val receipts by providers.journal.recentReceipts.collectAsState()
    val effectiveSync by providers.sync.state.collectAsState()
    val downloadProbe by providers.downloads.state("trk-001").collectAsState()

    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("DOGFOOD / DEBUG ONLY", style = MaterialTheme.typography.titleMedium)
                        Text("Deterministic state injection", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = controller::reset) { Text("Reset") }
                }

                DebugSelector("Provider", config.providerKind.displayName(), controller::cycleProvider)
                DebugSelector("Fixture dataset", config.dataset.displayName(), controller::cycleDataset)
                DebugSelector("Network", config.networkState.displayName(), controller::cycleNetwork)
                DebugSelector("Declared sync state", config.syncState.displayName(), controller::cycleSyncState)
                DebugSelector("Failure mode", config.failureMode.displayName(), controller::cycleFailure)

                HorizontalDivider()
                Text(
                    "Effective sync: ${effectiveSync.name} · backend: ${health.state.name}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "${health.endpoint} · ${health.protocol} · latency ${health.latencyMs?.let { "$it ms" } ?: "n/a"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(health.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Download probe trk-001: ${downloadProbe.status.name}${downloadProbe.error?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Mutation journal (${pending.size} pending)", style = MaterialTheme.typography.labelLarge)
                pending.take(3).forEach { mutation ->
                    Text(
                        "• ${mutation.mutationId}: ${mutation.operation} ${mutation.entityType}/${mutation.entityId} @ base ${mutation.baseRevision}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Receipts (${receipts.size})", style = MaterialTheme.typography.labelLarge)
                receipts.take(4).forEach { receipt ->
                    Text(
                        "• ${receipt.mutationId}: ${receipt.outcome.name} @ ${receipt.authoritativeRevision ?: "-"}${receipt.detail?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            AndroidDjToolsApp(providers)
        }
    }
}

@Composable
private fun DebugSelector(label: String, value: String, onCycle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onCycle) {
            Text(value)
        }
    }
}

private fun Enum<*>.displayName(): String = name
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
