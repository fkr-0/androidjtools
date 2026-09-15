package dev.androidjtools.ui.analysis

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AnalysisAssistantContent(
    state: AnalysisAssistantUiState,
    acceptanceEnabled: Boolean,
    onAction: (AnalysisUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("analysis-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Preparation assistant", style = MaterialTheme.typography.headlineMedium)
                Text(state.trackTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Analysis is advisory. Canonical preparation changes only after a normal journal mutation receives an authoritative receipt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.offline) {
                    AssistChip(onClick = {}, label = { Text("Offline · cached intelligence") })
                }
            }
        }

        if (state.safetyFindings.isNotEmpty()) {
            item { SafetySection(state.safetyFindings) }
        }

        state.failureMessage?.let { message ->
            item {
                OutlinedCard(Modifier.fillMaxWidth().testTag("analysis-failure")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Analysis unavailable", fontWeight = FontWeight.SemiBold)
                        Text(message)
                        OutlinedButton(onClick = { onAction(AnalysisUiAction.Refresh(state.trackId)) }) { Text("Retry analysis") }
                    }
                }
            }
        }

        item { CapabilityStrip(state.capabilities, state.partialFailure) }
        item { JobSection(state.jobs) }

        if (!state.hasSuggestions) {
            item {
                OutlinedCard(Modifier.fillMaxWidth().testTag("analysis-empty")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No analysis candidates yet", style = MaterialTheme.typography.titleMedium)
                        Text("The track remains fully usable. Run analysis when a capability is available, or keep preparing manually.")
                        OutlinedButton(onClick = { onAction(AnalysisUiAction.Refresh(state.trackId)) }) { Text("Run analysis") }
                    }
                }
            }
        } else {
            state.candidateGroups.forEach { group ->
                item(key = "group-${group.kind}") {
                    CandidateGroupCard(group, state.offline, acceptanceEnabled, onAction)
                }
            }
        }

        if (state.timelineItems.isNotEmpty()) {
            item { TimelineAnalysisCard(state.timelineItems, onAction) }
        }
    }
}

@Composable
fun SafetySection(findings: List<SafetyFindingUi>) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("safety-section"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio safety", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Safety findings are separate from musical ranking. Check material warnings before loud headphone preview.")
            findings.forEachIndexed { index, finding ->
                if (index > 0) HorizontalDivider()
                Column(Modifier.testTag("safety-${finding.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${finding.title} · ${finding.severity.uppercase()}", fontWeight = FontWeight.SemiBold)
                    Text(finding.message)
                    Text(
                        buildString {
                            append(finding.provenance.label)
                            if (finding.stale) append(" · STALE")
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun CapabilityStrip(capabilities: List<CapabilityUi>, partialFailure: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Capabilities", style = MaterialTheme.typography.titleMedium)
            if (partialFailure) Text("Partial backend availability", style = MaterialTheme.typography.labelMedium)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("capability-strip"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            capabilities.forEach { capability ->
                FilterChip(
                    selected = capability.availability == CapabilityAvailability.AVAILABLE,
                    onClick = {},
                    label = { Text("${capability.label}: ${capability.availability.name.lowercase()}") },
                    modifier = Modifier.testTag("capability-${capability.id}"),
                )
            }
        }
    }
}

@Composable
fun JobSection(jobs: List<AnalysisJobUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("analysis-jobs")) {
        Text("Analysis jobs", style = MaterialTheme.typography.titleMedium)
        jobs.forEach { job ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("job-${job.id}")) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(job.label, fontWeight = FontWeight.SemiBold)
                        Text(job.state.name.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                    }
                    job.progress?.let { progress ->
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun CandidateGroupCard(
    group: CandidateGroupUi,
    offline: Boolean,
    acceptanceEnabled: Boolean,
    onAction: (AnalysisUiAction) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("candidate-group-${group.kind.name}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(group.label, style = MaterialTheme.typography.titleMedium)
                if (group.competing) {
                    Text("${group.candidates.size} competing candidates · compare below", style = MaterialTheme.typography.labelMedium)
                }
            }
            group.candidates.forEachIndexed { index, candidate ->
                if (index > 0) HorizontalDivider()
                CandidateCard(candidate, offline, acceptanceEnabled, onAction)
            }
        }
    }
}

@Composable
fun CandidateCard(
    candidate: CandidateUi,
    offline: Boolean,
    acceptanceEnabled: Boolean,
    onAction: (AnalysisUiAction) -> Unit,
) {
    var staleAcceptanceConfirmed by rememberSaveable(candidate.id) { mutableStateOf(false) }
    Column(Modifier.testTag("candidate-${candidate.id}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(candidate.value, fontWeight = FontWeight.SemiBold)
            Text(candidate.reviewState.name.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
        }
        candidate.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            buildString {
                append(candidate.provenance.label)
                candidate.confidence?.let { append(" · confidence ${"%.0f".format(it * 100)}%") }
            },
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            buildString {
                append(if (candidate.stale) "STALE" else "Fresh")
                append(" · input revision ")
                append(candidate.inputRevision?.toString() ?: "unknown")
                append(" · generated ")
                append(candidate.generatedAt)
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("freshness-${candidate.id}"),
        )
        if (candidate.stale) {
            Text("Source input changed. Explicit reconfirmation is required before this can commit.", style = MaterialTheme.typography.bodySmall)
            if (staleAcceptanceConfirmed) {
                Text("Stale proposal selected. Confirm once more to create the review intent.", style = MaterialTheme.typography.labelMedium)
            }
        }
        when (candidate.reviewState) {
            CandidateReviewState.PROPOSED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidate.timelineStartMs != null) {
                        OutlinedButton(onClick = { onAction(AnalysisUiAction.Preview(candidate.id)) }) { Text("Preview") }
                    }
                    Button(
                        onClick = {
                            if (candidate.stale && !staleAcceptanceConfirmed) {
                                staleAcceptanceConfirmed = true
                            } else {
                                candidate.acceptanceIntent(offline)?.let { onAction(AnalysisUiAction.QueueAcceptance(it)) }
                            }
                        },
                        enabled = acceptanceEnabled,
                        modifier = Modifier.testTag("accept-${candidate.id}"),
                    ) {
                        Text(
                            when {
                                candidate.stale && !staleAcceptanceConfirmed -> "Review stale"
                                candidate.stale -> "Confirm stale acceptance"
                                offline -> "Queue accept"
                                else -> "Accept"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { onAction(AnalysisUiAction.Reject(candidate.id)) },
                        modifier = Modifier.testTag("reject-${candidate.id}"),
                    ) { Text("Reject") }
                }
                if (!acceptanceEnabled) {
                    Text("Review-only: journal mutation adapter is not connected yet.", style = MaterialTheme.typography.labelSmall)
                }
            }
            CandidateReviewState.QUEUED -> Text("Queued in the normal mutation journal; not canonical until an authoritative receipt arrives.")
            CandidateReviewState.ACCEPTED -> Text("Accepted · authoritative receipt confirmed. Provenance retained above.")
            CandidateReviewState.REJECTED -> Text("Rejected locally; canonical preparation was not changed.")
            CandidateReviewState.SUPERSEDED -> Text("Superseded by newer analysis; retained for provenance.")
        }
    }
}

@Composable
fun TimelineAnalysisCard(items: List<TimelineAnalysisItemUi>, onAction: (AnalysisUiAction) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("analysis-timeline")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Timeline candidates", style = MaterialTheme.typography.titleMedium)
            Text("Compact cue, loop, region and phrase markers for waveform/list integration.", style = MaterialTheme.typography.bodySmall)
            items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatTimelineTime(item.startMs)} · ${item.label}")
                        Text(
                            buildString {
                                append(item.kind.name.replace('_', ' '))
                                item.endMs?.let { append(" → ${formatTimelineTime(it)}") }
                                if (item.stale) append(" · STALE")
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(onClick = { onAction(AnalysisUiAction.Preview(item.id)) }) { Text("Preview") }
                }
            }
        }
    }
}

private fun formatTimelineTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

