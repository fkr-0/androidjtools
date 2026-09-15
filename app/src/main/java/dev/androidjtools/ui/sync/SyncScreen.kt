package dev.androidjtools.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.provider.AppProviders

@Composable
fun SyncScreen(providers: AppProviders) {
    val state by providers.sync.state.collectAsState()
    val pending by providers.sync.pendingMutationCount.collectAsState()
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
