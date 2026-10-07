package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
internal fun LoginDiagnosticsView(services: NextcloudPlatformServices, drafts: SupportSettingsDraftState) {
    LoginDiagnosticsView(
        drafts, services::loadSupportDiagnosticsSummary, services::exportSupportDiagnostics,
        remember(services) { services.supportDiagnosticsRevisions() },
    )
}

@Composable
internal fun LoginDiagnosticsView(
    drafts: SupportSettingsDraftState,
    loadSummary: suspend () -> SupportDiagnosticsSummary,
    exportReport: suspend (String) -> SupportDiagnosticsExportResult,
    revisions: Flow<Long>,
) {
    val scope = rememberCoroutineScope()
    val revision by revisions.collectAsState(0L)
    var refresh by remember { mutableStateOf(0) }
    var summary by remember(loadSummary) { mutableStateOf<SupportDiagnosticsSummary?>(null) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loadSummary, revision, refresh) {
        try {
            summary = loadSummary()
            summaryError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            summaryError = "Diagnostics could not be loaded. Try again."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        Text("Save a report without signing in. Review it before sharing it privately.")
        Text(
            "Automatically collected diagnostics exclude passwords, cookies, private URLs, filenames, and file content.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = drafts.reportDraft,
            onValueChange = { drafts.updateReportDraft(it.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !exporting,
            label = { Text("What happened? (optional)") },
            supportingText = { Text("Check your notes for passwords and private content before exporting. They may not be removed automatically. This draft is not saved to disk.") },
            minLines = 2,
            maxLines = 4,
        )
        val currentSummary = summary
        Text(when {
            summaryError != null -> requireNotNull(summaryError)
            currentSummary == null -> "Loading diagnostics..."
            !currentSummary.available -> currentSummary.explanation ?: "Diagnostics are unavailable on this device."
            currentSummary.eventCount == 0 -> "No diagnostic events have been recorded. Device information can still be saved."
            else -> "${currentSummary.eventCount} diagnostic events available."
        })
        Button(
            enabled = currentSummary?.available == true && !exporting,
            onClick = {
                val draft = drafts.reportDraft
                exporting = true
                scope.launch {
                    try {
                        notice = when (val result = exportReport(draft)) {
                            is SupportDiagnosticsExportResult.Exported -> "Report prepared: ${result.destination}"
                            SupportDiagnosticsExportResult.Cancelled -> "Export cancelled."
                            is SupportDiagnosticsExportResult.Failed -> result.message
                            is SupportDiagnosticsExportResult.Unsupported -> result.reason
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notice = "The diagnostics report could not be saved. Try again."
                    } finally {
                        exporting = false
                    }
                }
            },
        ) { Text(if (exporting) "Preparing diagnostics..." else "Save login diagnostics") }
        if (summaryError != null || currentSummary?.available == false) {
            TextButton(onClick = { refresh += 1 }, enabled = !exporting) { Text("Try again") }
        }
        notice?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
internal fun SessionRecoveryContent(
    state: NextcloudSessionLoadState,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SessionLoadingRecoveryScreen(state, onRetry, onSignInAgain)
        TextButton(onClick = onDiagnostics) { Text("Export login diagnostics") }
    }
}

@Composable
internal fun SessionRecoveryDiagnosticsView(
    services: NextcloudPlatformServices,
    state: NextcloudSessionLoadState,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val drafts = remember { SupportSettingsDraftRegistry.loginState() }
    SessionRecoveryContent(state, onRetry, onSignInAgain, onDiagnostics = { showDiagnostics = true })
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Login diagnostics") },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    LoginDiagnosticsView(services, drafts)
                }
            },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } },
        )
    }
}
