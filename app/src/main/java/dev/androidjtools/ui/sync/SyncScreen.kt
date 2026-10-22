package dev.androidjtools.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.sync.samplelib.ServerSessionController

@Composable
fun SyncScreen(providers: AppProviders, server: ServerSessionController? = null) {
    val pending by providers.sync.pendingMutationCount.collectAsState()
    if (server == null) {
        val state by providers.sync.state.collectAsState()
        LegacySyncContent(providers, state, pending)
    } else {
        ServerSyncContent(server, pending)
    }
}

@Composable
private fun LegacySyncContent(providers: AppProviders, state: SyncState, pending: Int) {
    val capabilities by providers.analysis.capabilities.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync & backend", style = MaterialTheme.typography.headlineMedium)
        Text("State: ${state.name}")
        Text("Pending local mutations: $pending")
        Text("Cloud dependency: none")
        Text("Backend capabilities", style = MaterialTheme.typography.titleMedium)
        capabilities.forEach { capability ->
            Text("• ${capability.displayName}: ${if (capability.available) "available" else "unavailable"}")
        }
    }
}

@Composable
private fun ServerSyncContent(server: ServerSessionController, pending: Int) {
    val state by server.state.collectAsState()
    val capabilities by server.capabilities.collectAsState()
    val error by server.error.collectAsState()
    val savedUrl by server.serverUrl.collectAsState()
    var url by rememberSaveable(savedUrl) { mutableStateOf(savedUrl.orEmpty()) }
    // Deliberately not rememberSaveable: the pairing code must never be written into saved instance state.
    var code by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    val rows = remember(capabilities) { capabilityRows(capabilities) }
    val busy = state == SyncState.PAIRING || state == SyncState.PULLING

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { Text("Sync & backend", style = MaterialTheme.typography.headlineMedium) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("State: ${syncStateLabel(state)}", modifier = Modifier.testTag("sync-state"))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sync-error"))
                }
                Text("Pending local mutations: $pending")
                Text("Cloud dependency: none")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample Lib server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("server-url-field"),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("pairing-code-field"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            server.pair(url, code)
                            code = ""
                        },
                        enabled = canPair(url, code, state),
                        modifier = Modifier.testTag("pair-button"),
                    ) { Text(if (savedUrl == null) "Pair" else "Pair again") }
                    TextButton(
                        onClick = server::refresh,
                        enabled = savedUrl != null && !busy,
                        modifier = Modifier.testTag("refresh-button"),
                    ) { Text("Refresh") }
                    TextButton(
                        onClick = { confirmForget = true },
                        enabled = savedUrl != null,
                        modifier = Modifier.testTag("forget-button"),
                    ) { Text("Forget server") }
                }
                if (confirmForget) {
                    AlertDialog(
                        onDismissRequest = { confirmForget = false },
                        title = { Text("Forget this server?") },
                        text = {
                            Text(
                                "This removes the server connection, its synced library, any audio downloaded from it and any edits that were not yet sent from this device.",
                                modifier = Modifier.testTag("forget-dialog-text"),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmForget = false
                                    server.forget()
                                },
                                modifier = Modifier.testTag("forget-confirm"),
                            ) { Text("Forget") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmForget = false }, modifier = Modifier.testTag("forget-cancel")) { Text("Cancel") }
                        },
                    )
                }
            }
        }
        item { Text("What this server supports", style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { it.id }) { row ->
            Text(
                "${if (row.available) "Available" else "Unavailable"}: ${row.label}. ${row.detail}",
                modifier = Modifier.testTag("capability-${row.id}"),
            )
        }
    }
}
