package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun SupportDiagnosticsSettingsCard(services: NextcloudPlatformServices) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val diagnosticsRevision by remember(services) {
        services.supportDiagnosticsRevisions()
    }.collectAsState(0L)
    var summary by remember(services) {
        mutableStateOf(
            SupportDiagnosticsSummary(
                available = false,
                eventCount = 0,
                warningCount = 0,
                errorCount = 0,
                oldestEventAtEpochMillis = null,
                newestEventAtEpochMillis = null,
                components = emptySet(),
                storedBytes = 0L,
                includedFiles = SUPPORT_BUNDLE_INCLUDED_FILES,
                explanation = "Loading private diagnostic history...",
            ),
        )
    }
    LaunchedEffect(services, diagnosticsRevision, refresh) {
        summary = services.loadSupportDiagnosticsSummary()
    }
    var reproductionSteps by rememberSaveable { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSend by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var reportPageIndex by rememberSaveable { mutableStateOf(0) }
    var reportDeletionTarget by remember { mutableStateOf<SupportDiagnosticsSubmissionState.SubmittedReport?>(null) }
    var reportReplyTarget by remember { mutableStateOf<String?>(null) }
    var reportReplyDraft by remember { mutableStateOf("") }
    val submissionState by remember(services) {
        services.supportDiagnosticsSubmissionStates()
    }.collectAsState(SupportDiagnosticsSubmissionState.Initializing)
    val submissionBusy = submissionState is SupportDiagnosticsSubmissionState.Initializing ||
        submissionState is SupportDiagnosticsSubmissionState.Packaging ||
        submissionState is SupportDiagnosticsSubmissionState.Cancelling ||
        submissionState is SupportDiagnosticsSubmissionState.DeletingSubmittedReport ||
        submissionState is SupportDiagnosticsSubmissionState.Uploading
    val submissionCancellable = submissionState is SupportDiagnosticsSubmissionState.Packaging ||
        submissionState is SupportDiagnosticsSubmissionState.Uploading
    val submissionPending = submissionState is SupportDiagnosticsSubmissionState.RetryableFailure ||
        submissionState is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount
    val submissionUnavailable = submissionState is SupportDiagnosticsSubmissionState.Unsupported ||
        submissionState is SupportDiagnosticsSubmissionState.AccountRequired
    LaunchedEffect(submissionBusy, submissionPending, submissionUnavailable) {
        if (submissionBusy || submissionPending || submissionUnavailable) {
            confirmClear = false
            confirmSend = false
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear diagnostic history?") },
            text = {
                Text(
                    "This permanently removes the recorded diagnostic events on this device. " +
                        "It does not remove reports you already exported.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !submissionBusy && !submissionPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            status = if (services.clearSupportDiagnostics()) {
                                refresh += 1
                                "Diagnostic history cleared."
                            } else {
                                "Diagnostic history could not be cleared."
                            }
                        }
                    },
                ) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { if (!submissionBusy) confirmSend = false },
            title = { Text("Send this private report?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text(
                        "The sanitized report, the description you reviewed, and app release details will be sent to Obiente Support.",
                    )
                    Text(
                        "It does not include account credentials, raw account identifiers, server URLs, filenames, or file contents. Reports can include a stable pseudonymous account scope, allowing Obiente Support to correlate reports from the same account on this installation. Private report data is retained for 30 days unless you delete it first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !submissionBusy && !submissionUnavailable,
                    onClick = {
                        confirmSend = false
                        scope.launch { services.submitSupportDiagnostics(reproductionSteps) }
                    },
                ) { Text("Send privately") }
            },
            dismissButton = {
                TextButton(enabled = !submissionBusy, onClick = { confirmSend = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this pending report?") },
            text = {
                Text(
                    "This permanently removes the report prepared on this device. If its upload result is uncertain, the app will first reconcile it and request deletion from Obiente Support.",
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmDiscard = false
                        scope.launch { services.cancelSupportDiagnosticsSubmission() }
                    },
                ) { Text("Discard report") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep report") }
            },
        )
    }

    reportDeletionTarget?.let { report ->
        AlertDialog(
            onDismissRequest = { if (!submissionBusy) reportDeletionTarget = null },
            title = { Text("Delete this submitted report?") },
            text = {
                Text(
                    "This permanently deletes report ${report.supportCode} from Obiente Support and removes its private receipt from this device.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !submissionBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        reportDeletionTarget = null
                        scope.launch {
                            status = when (
                                val result = services.deleteSubmittedSupportDiagnosticsReport(report.deletionUrl)
                            ) {
                                SupportDiagnosticsDeletionResult.Deleted -> "Submitted support report deleted."
                                is SupportDiagnosticsDeletionResult.Failed -> result.message
                                is SupportDiagnosticsDeletionResult.Unsupported -> result.reason
                            }
                        }
                    },
                ) { Text("Delete report") }
            },
            dismissButton = {
                TextButton(enabled = !submissionBusy, onClick = { reportDeletionTarget = null }) {
                    Text("Keep report")
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    Icon(
                        NextcloudIcons.Activity,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Anonymized support report", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Export bounded app events and failure context when you choose. Nothing is uploaded automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (summary.available) {
                Text(
                    "${summary.eventCount} events · ${summary.errorCount} errors · " +
                        "${summary.warningCount} warnings · ${formatVirtualFileBytes(summary.storedBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    summary.components
                        .sortedBy { component -> component.name }
                        .joinToString(prefix = "Areas: ", separator = ", ") { it.name }
                        .takeIf { summary.components.isNotEmpty() }
                        ?: "No diagnostic events have been recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    summary.explanation ?: "Diagnostic storage is unavailable on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = reproductionSteps,
                onValueChange = { reproductionSteps = it.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                enabled = summary.available && !exporting && !submissionBusy && !submissionPending,
                label = { Text("What happened? (optional)") },
                placeholder = { Text("Describe what you did, what you expected, and what happened.") },
                supportingText = {
                    Text(
                        "Recognizable credentials, account details, URLs, and paths are anonymized. " +
                            "Review your description before sharing.",
                    )
                },
                minLines = 3,
                maxLines = 7,
            )

            Text(
                "Includes: ${summary.includedFiles.joinToString()}. " +
                    "Does not include file contents, request bodies, credentials, cookies, or the private alias key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (summary.recentEvents.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        refresh += 1
                        showPreview = !showPreview
                    },
                ) {
                    Text(if (showPreview) "Hide event preview" else "Preview recent events")
                }
                if (showPreview) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(NextcloudRadii.Small),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Latest ${summary.recentEvents.size} sanitized events",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            summary.recentEvents.asReversed().forEach { event ->
                                Text(
                                    buildString {
                                        append(event.severity.name)
                                        append(" · ")
                                        append(event.component.name)
                                        append(" · ")
                                        append(event.operation)
                                        append(" · ")
                                        append(event.outcome)
                                        event.code?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Button(
                    enabled = summary.available && !exporting && !submissionBusy && !submissionPending &&
                        !submissionUnavailable,
                    onClick = { confirmSend = true },
                ) {
                    Text("Send to support")
                }
                OutlinedButton(
                    enabled = summary.available && !exporting && !submissionBusy,
                    onClick = {
                        exporting = true
                        status = null
                        refresh += 1
                        scope.launch {
                            try {
                                status = when (val result = services.exportSupportDiagnostics(reproductionSteps)) {
                                    is SupportDiagnosticsExportResult.Exported ->
                                        "Report prepared: ${result.destination}"
                                    SupportDiagnosticsExportResult.Cancelled -> "Report export cancelled."
                                    is SupportDiagnosticsExportResult.Failed -> result.message
                                    is SupportDiagnosticsExportResult.Unsupported -> result.reason
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                status = "The anonymized support report could not be saved."
                            } finally {
                                exporting = false
                                refresh += 1
                            }
                        }
                    },
                ) {
                    if (exporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (exporting) "Preparing..." else "Save a copy")
                }
                if (submissionCancellable) {
                    OutlinedButton(onClick = {
                        scope.launch { services.cancelSupportDiagnosticsSubmission() }
                    }) {
                        Text("Cancel sending")
                    }
                }
                if (summary.eventCount > 0) {
                    OutlinedButton(
                        enabled = !exporting && !submissionBusy && !submissionPending,
                        onClick = { confirmClear = true },
                    ) { Text("Clear history") }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                when (val current = submissionState) {
                    SupportDiagnosticsSubmissionState.Initializing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Restoring any pending private report...", style = MaterialTheme.typography.bodySmall)
                    }
                    SupportDiagnosticsSubmissionState.AccountRequired -> Text(
                        "Sign in before sending a private support report.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SupportDiagnosticsSubmissionState.Idle -> Unit
                    is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount -> Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SupportDiagnosticsSubmissionState.Packaging -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Preparing the private report...", style = MaterialTheme.typography.bodySmall)
                    }
                    SupportDiagnosticsSubmissionState.Cancelling -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Finishing private report cancellation...", style = MaterialTheme.typography.bodySmall)
                    }
                    SupportDiagnosticsSubmissionState.DeletingSubmittedReport -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Deleting the submitted support report...", style = MaterialTheme.typography.bodySmall)
                    }
                    is SupportDiagnosticsSubmissionState.Uploading -> {
                        if (current.progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { current.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("Sending the private report to Obiente Support...", style = MaterialTheme.typography.bodySmall)
                    }
                    is SupportDiagnosticsSubmissionState.RetryableFailure -> {
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            OutlinedButton(onClick = { scope.launch { services.retrySupportDiagnosticsSubmission() } }) {
                                Text("Retry safely")
                            }
                            TextButton(onClick = { confirmDiscard = true }) {
                                Text("Discard pending report")
                            }
                        }
                    }
                    is SupportDiagnosticsSubmissionState.Rejected -> Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SupportDiagnosticsSubmissionState.Cancelled -> Text(
                        "Private report submission cancelled.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is SupportDiagnosticsSubmissionState.Submitted -> {
                        val reportPage = supportReportPage(current.reports, reportPageIndex)
                        LaunchedEffect(reportPageIndex, reportPage.pageIndex, current.reports.size) {
                            if (reportPageIndex != reportPage.pageIndex) {
                                reportPageIndex = reportPage.pageIndex
                            }
                        }
                        Text(
                            if (current.reports.size == 1) {
                                "Sent privately. Your report remains available until its retention period ends."
                            } else {
                                "${current.reports.size} private reports remain available until their retention periods end."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(
                            enabled = current.reports.none { report -> report.conversationLoading },
                            onClick = {
                                scope.launch {
                                    status = when (val result = services.refreshSubmittedSupportDiagnosticsReports()) {
                                        SupportDiagnosticsConversationResult.Updated -> "Support status refreshed."
                                        is SupportDiagnosticsConversationResult.Failed -> result.message
                                        is SupportDiagnosticsConversationResult.Unsupported -> result.reason
                                    }
                                }
                            },
                        ) { Text("Refresh support status") }
                        reportPage.items.forEach { report ->
                            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                                Text(
                                    "Support code: ${report.supportCode}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Status: ${supportReportStatusLabel(report.status)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (report.conversationLoading) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                if (report.statusChanged || report.unreadMaintainerMessages > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(NextcloudRadii.Small),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                        ) {
                                            Text(
                                                buildString {
                                                    if (report.statusChanged) append("Support updated this report.")
                                                    if (report.statusChanged && report.unreadMaintainerMessages > 0) append(" ")
                                                    if (report.unreadMaintainerMessages > 0) {
                                                        append(report.unreadMaintainerMessages)
                                                        append(if (report.unreadMaintainerMessages == 1) " new message." else " new messages.")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        status = if (
                                                            services.markSubmittedSupportDiagnosticsReportRead(report.statusUrl)
                                                        ) {
                                                            "Support update marked as read."
                                                        } else {
                                                            "The support update could not be marked as read."
                                                        }
                                                    }
                                                },
                                            ) { Text("Mark read") }
                                        }
                                    }
                                }
                                report.conversationError?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                report.messages.takeLast(MAX_VISIBLE_SUPPORT_MESSAGES).forEach { message ->
                                    Surface(
                                        color = if (message.author == SupportDiagnosticsMessageAuthor.Maintainer) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        shape = RoundedCornerShape(NextcloudRadii.Small),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                if (message.author == SupportDiagnosticsMessageAuthor.Maintainer) {
                                                    "Obiente Support"
                                                } else {
                                                    "You"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(message.body, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                                if (reportReplyTarget == report.statusUrl) {
                                    OutlinedTextField(
                                        value = reportReplyDraft,
                                        onValueChange = { reportReplyDraft = it.take(MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !report.conversationLoading,
                                        label = { Text("Reply privately") },
                                        minLines = 2,
                                        maxLines = 6,
                                        supportingText = {
                                            Text("This message is visible only to you and Obiente Support.")
                                        },
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                    ) {
                                        Button(
                                            enabled = reportReplyDraft.isNotBlank() && !report.conversationLoading,
                                            onClick = {
                                                val message = reportReplyDraft
                                                scope.launch {
                                                    status = when (
                                                        val result = services.sendSubmittedSupportDiagnosticsMessage(
                                                            report.statusUrl,
                                                            message,
                                                        )
                                                    ) {
                                                        SupportDiagnosticsConversationResult.Updated -> {
                                                            reportReplyDraft = ""
                                                            reportReplyTarget = null
                                                            "Reply sent privately."
                                                        }
                                                        is SupportDiagnosticsConversationResult.Failed -> result.message
                                                        is SupportDiagnosticsConversationResult.Unsupported -> result.reason
                                                    }
                                                }
                                            },
                                        ) { Text("Send reply") }
                                        TextButton(
                                            onClick = {
                                                reportReplyDraft = ""
                                                reportReplyTarget = null
                                            },
                                        ) { Text("Cancel") }
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            status = if (
                                                services.copyTextToClipboard(
                                                    "Obiente support code",
                                                    report.supportCode,
                                                )
                                            ) {
                                                "Support code copied."
                                            } else {
                                                "The support code could not be copied."
                                            }
                                        },
                                    ) { Text("Copy support code") }
                                    TextButton(onClick = { services.openExternalUrl(report.statusUrl) }) {
                                        Text("Open private status")
                                    }
                                    TextButton(
                                        enabled = !report.conversationLoading,
                                        onClick = {
                                            reportReplyTarget = report.statusUrl
                                            reportReplyDraft = ""
                                        },
                                    ) { Text("Reply in app") }
                                    TextButton(
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                        onClick = { reportDeletionTarget = report },
                                    ) {
                                        Text("Delete report")
                                    }
                                }
                            }
                        }
                        if (reportPage.pageCount > 1) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                OutlinedButton(
                                    enabled = reportPage.pageIndex > 0,
                                    onClick = { reportPageIndex = reportPage.pageIndex - 1 },
                                ) { Text("Previous reports") }
                                Text(
                                    "Page ${reportPage.pageIndex + 1} of ${reportPage.pageCount}",
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    enabled = reportPage.pageIndex + 1 < reportPage.pageCount,
                                    onClick = { reportPageIndex = reportPage.pageIndex + 1 },
                                ) { Text("Next reports") }
                            }
                        }
                    }
                    is SupportDiagnosticsSubmissionState.Unsupported -> Text(
                        current.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            status?.let { message ->
                Text(
                    message,
                    modifier = Modifier.semantics {
                        contentDescription = message
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal data class SupportReportPage<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
)

internal fun <T> supportReportPage(
    reports: List<T>,
    requestedPageIndex: Int,
    pageSize: Int = SUPPORT_REPORT_PAGE_SIZE,
): SupportReportPage<T> {
    require(pageSize > 0)
    val pageCount = if (reports.isEmpty()) 1 else ((reports.size - 1) / pageSize) + 1
    val pageIndex = requestedPageIndex.coerceIn(0, pageCount - 1)
    val firstIndex = pageIndex * pageSize
    return SupportReportPage(
        items = reports.subList(firstIndex, minOf(firstIndex + pageSize, reports.size)),
        pageIndex = pageIndex,
        pageCount = pageCount,
    )
}

private const val SUPPORT_REPORT_PAGE_SIZE = 5
private const val MAX_VISIBLE_SUPPORT_MESSAGES = 20
private const val MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH = 8_192
private const val SUPPORT_CONVERSATION_BACKGROUND_REFRESH_MILLIS = 5L * 60L * 1_000L

private fun supportReportStatusLabel(status: String): String = when (status) {
    "new" -> "Received"
    "needs_information" -> "More information requested"
    "accepted" -> "Accepted"
    "duplicate" -> "Duplicate"
    "resolved" -> "Resolved"
    "rejected" -> "Closed"
    else -> "Updated"
}

