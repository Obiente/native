package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SupportDiagnosticsSettingsCard(services: NextcloudPlatformServices) {
    val drafts = remember { SupportSettingsDraftState() }
    SupportSettingsView(services, drafts)
}

@Composable
internal fun SupportSettingsView(services: NextcloudPlatformServices, drafts: SupportSettingsDraftState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var summaryRefresh by remember { mutableStateOf(0) }
    val diagnosticsRevision by remember(services) { services.supportDiagnosticsRevisions() }.collectAsState(0L)
    var summary by remember(services) { mutableStateOf(loadingSummary()) }
    val submission by remember(services) { services.supportDiagnosticsSubmissionStates() }
        .collectAsState(SupportDiagnosticsSubmissionState.Initializing)
    var notice by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(0) }
    var replyId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var sendDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val reports = (submission as? SupportDiagnosticsSubmissionState.Submitted)?.reports.orEmpty()
    val busy = submission.busy()
    val pending = submission is SupportDiagnosticsSubmissionState.RetryableFailure ||
        submission is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount

    LaunchedEffect(services, diagnosticsRevision, summaryRefresh) {
        summary = services.loadSupportDiagnosticsSummary()
    }
    LaunchedEffect(submission) {
        val submittedReports = (submission as? SupportDiagnosticsSubmissionState.Submitted)?.reports
            ?: return@LaunchedEffect
        drafts.retainReplyDrafts(submittedReports.mapTo(mutableSetOf()) { it.recordId })
        if (replyId != null && submittedReports.none { it.recordId == replyId }) {
            replyId = null
        }
        if (deleteId != null && submittedReports.none { it.recordId == deleteId }) deleteId = null
    }
    SupportDialogs(
        services = services,
        reports = reports,
        busy = busy,
        send = sendDialog,
        onSend = { sendDialog = it },
        discard = discardDialog,
        onDiscard = { discardDialog = it },
        clear = clearDialog,
        onClear = { clearDialog = it },
        deleteId = deleteId,
        onDeleteId = { deleteId = it },
        draft = drafts.reportDraft,
        onNotice = { notice = it },
        onHistoryCleared = { summaryRefresh += 1 },
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        Text("Support", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Send a private report, follow replies, or review what the app can include.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            SupportTab.entries.forEachIndexed { index, item ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(item.label) })
            }
        }
        notice?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (SupportTab.entries[tab]) {
            SupportTab.Requests -> RequestsTab(
                services, submission, reports, page, { page = it },
                replyId, { replyId = it }, drafts,
                { deleteId = it }, { notice = it },
            )
            SupportTab.NewReport -> NewReportTab(
                services, summary, submission, drafts.reportDraft, drafts::updateReportDraft,
                exporting, { exporting = it }, { sendDialog = true }, { discardDialog = true },
                { notice = it }, { summaryRefresh += 1 },
            )
            SupportTab.Privacy -> PrivacyTab(
                summary, preview, { preview = it },
                summary.eventCount > 0 && !busy && !pending, { clearDialog = true },
            )
        }
    }
}

@Composable
private fun RequestsTab(
    services: NextcloudPlatformServices,
    state: SupportDiagnosticsSubmissionState,
    reports: List<SupportDiagnosticsSubmissionState.SubmittedReport>,
    requestedPage: Int,
    onPage: (Int) -> Unit,
    replyId: String?,
    onReplyId: (String?) -> Unit,
    drafts: SupportSettingsDraftState,
    onDelete: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (reports.isEmpty()) {
        RequestsEmptyState(state)
        return
    }
    val reportPage = supportReportPage(reports, requestedPage)
    LaunchedEffect(requestedPage, reportPage.pageIndex, reports.size) {
        if (requestedPage != reportPage.pageIndex) onPage(reportPage.pageIndex)
    }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            OutlinedButton(
                enabled = reports.none { it.conversationLoading },
                onClick = {
                    scope.launch {
                        when (val result = services.refreshSubmittedSupportDiagnosticsReports()) {
                            SupportDiagnosticsConversationResult.Updated -> {
                                drafts.clearReplyRefreshRequirements()
                                onNotice("Support requests refreshed.")
                            }
                            is SupportDiagnosticsConversationResult.ReplyDeliveryUnknown -> onNotice(result.message)
                            is SupportDiagnosticsConversationResult.Failed -> onNotice(result.message)
                            is SupportDiagnosticsConversationResult.Unsupported -> onNotice(result.reason)
                        }
                    }
                },
            ) { Text("Refresh requests") }
            Text("Stored on this device: " + reports.size, style = MaterialTheme.typography.bodySmall)
        }
        reportPage.items.forEach { report ->
            key(report.recordId) {
                RequestCard(
                    services = services,
                    report = report,
                    replyOpen = replyId == report.recordId,
                    onReplyOpen = { open ->
                        onReplyId(report.recordId.takeIf { open })
                    },
                    replyDraft = drafts.replyDraft(report.recordId),
                    onReplyDraft = { drafts.updateReplyDraft(report.recordId, it) },
                    refreshRequired = drafts.replyRequiresRefresh(report.recordId),
                    onRefreshRequired = { drafts.updateReplyRefreshRequirement(report.recordId, it) },
                    onDelete = { onDelete(report.recordId) },
                    onNotice = onNotice,
                )
            }
        }
        if (reportPage.pageCount > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedButton(
                    enabled = reportPage.pageIndex > 0,
                    onClick = { onPage(reportPage.pageIndex - 1) },
                ) { Text("Previous") }
                Text("Page " + (reportPage.pageIndex + 1) + " of " + reportPage.pageCount)
                OutlinedButton(
                    enabled = reportPage.pageIndex + 1 < reportPage.pageCount,
                    onClick = { onPage(reportPage.pageIndex + 1) },
                ) { Text("Next") }
            }
        }
    }
}

@Composable
private fun RequestCard(
    services: NextcloudPlatformServices,
    report: SupportDiagnosticsSubmissionState.SubmittedReport,
    replyOpen: Boolean,
    onReplyOpen: (Boolean) -> Unit,
    replyDraft: String,
    onReplyDraft: (String) -> Unit,
    refreshRequired: Boolean,
    onRefreshRequired: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(report.supportCode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(reportStatus(report.status).label, color = MaterialTheme.colorScheme.primary)
            Text("Created " + dateLabel(report.createdAt), style = MaterialTheme.typography.bodySmall)
            report.updatedAt?.let { Text("Updated " + timestampLabel(it), style = MaterialTheme.typography.bodySmall) }
            Text(
                "Available on this device until " + dateLabel(report.retentionUntil) + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.conversationLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (report.statusChanged || report.unreadMaintainerMessages > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small)) {
                        Text(
                            when (report.unreadMaintainerMessages) {
                                0 -> "Support changed this request's status."
                                1 -> "One unread message from Obiente Support."
                                else -> report.unreadMaintainerMessages.toString() +
                                    " unread messages from Obiente Support."
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                onNotice(if (services.markSubmittedSupportDiagnosticsReportRead(report.recordId)) {
                                    "Support update marked as read."
                                } else {
                                    "The support update could not be marked as read."
                                })
                            }
                        }) { Text("Mark read") }
                    }
                }
            }
            report.conversationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                    Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small)) {
                        Text(
                            if (message.author == SupportDiagnosticsMessageAuthor.Maintainer) {
                                "Obiente Support"
                            } else {
                                "You"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(message.body)
                        Text(
                            timestampLabel(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (replyOpen) {
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { onReplyDraft(it.take(MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !refreshRequired && !report.conversationLoading,
                    label = { Text("Reply privately") },
                    minLines = 2,
                    maxLines = 6,
                    supportingText = {
                        Text(if (refreshRequired) {
                            "Delivery was uncertain. Refresh requests before sending again."
                        } else {
                            replyDraft.length.toString() + " / " + MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH
                        })
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Button(
                        enabled = replyDraft.isNotBlank() && !refreshRequired && !report.conversationLoading,
                        onClick = {
                            scope.launch {
                                when (val result = services.sendSubmittedSupportDiagnosticsMessage(
                                    report.recordId,
                                    replyDraft,
                                )) {
                                    SupportDiagnosticsConversationResult.Updated -> {
                                        onReplyDraft("")
                                        onReplyOpen(false)
                                        onNotice("Private reply sent.")
                                    }
                                    is SupportDiagnosticsConversationResult.ReplyDeliveryUnknown -> {
                                        onRefreshRequired(true)
                                        onNotice(result.message)
                                    }
                                    is SupportDiagnosticsConversationResult.Failed -> onNotice(result.message)
                                    is SupportDiagnosticsConversationResult.Unsupported -> onNotice(result.reason)
                                }
                            }
                        },
                    ) { Text("Send reply") }
                    TextButton(onClick = { onReplyOpen(false) }) { Text("Close reply") }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                OutlinedButton(onClick = {
                    onNotice(if (services.copyTextToClipboard("Obiente support code", report.supportCode)) {
                        "Support code copied."
                    } else {
                        "The support code could not be copied."
                    })
                }) { Text("Copy code") }
                if (!replyOpen) OutlinedButton(onClick = { onReplyOpen(true) }) { Text("Reply") }
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = onDelete,
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun RequestsEmptyState(state: SupportDiagnosticsSubmissionState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
            if (state is SupportDiagnosticsSubmissionState.Initializing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Restoring support requests stored on this device...")
            } else {
                Text("No support requests on this device", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This list contains private receipts created by this installation for the active account. " +
                        "Requests from another device cannot be discovered from a support code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                when (state) {
                    SupportDiagnosticsSubmissionState.AccountRequired -> Text("Sign in to view account-scoped receipts.")
                    is SupportDiagnosticsSubmissionState.Unsupported -> Text(state.reason)
                    is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount -> Text(state.message)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun NewReportTab(
    services: NextcloudPlatformServices,
    summary: SupportDiagnosticsSummary,
    state: SupportDiagnosticsSubmissionState,
    draft: String,
    onDraft: (String) -> Unit,
    exporting: Boolean,
    onExporting: (Boolean) -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    onNotice: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val busy = state.busy()
    val pending = state is SupportDiagnosticsSubmissionState.RetryableFailure ||
        state is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount
    val unavailable = state is SupportDiagnosticsSubmissionState.Unsupported ||
        state is SupportDiagnosticsSubmissionState.AccountRequired
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        Text("New private report", style = MaterialTheme.typography.titleLarge)
        Text(
            "The draft stays in memory while Settings is open. It is not saved when Settings closes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { onDraft(it.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = summary.available && !exporting && !busy && !pending,
            label = { Text("What happened? (optional)") },
            placeholder = { Text("What did you do, what did you expect, and what happened?") },
            supportingText = { Text("Review this text. Do not include passwords or private file content.") },
            minLines = 4,
            maxLines = 8,
        )
        Text(
            "Includes " + summary.includedFiles.joinToString() +
                ". Excludes credentials, cookies, request bodies, private URLs, filenames, file content, and the alias key.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Button(
                enabled = summary.available && !exporting && !busy && !pending && !unavailable,
                onClick = onSend,
            ) { Text("Review and send") }
            OutlinedButton(
                enabled = summary.available && !exporting && !busy,
                onClick = {
                    onExporting(true)
                    onRefresh()
                    scope.launch {
                        try {
                            onNotice(when (val result = services.exportSupportDiagnostics(draft)) {
                                is SupportDiagnosticsExportResult.Exported ->
                                    "Report prepared: " + result.destination
                                SupportDiagnosticsExportResult.Cancelled -> "Report export cancelled."
                                is SupportDiagnosticsExportResult.Failed -> result.message
                                is SupportDiagnosticsExportResult.Unsupported -> result.reason
                            })
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            onNotice("The anonymized support report could not be saved.")
                        } finally {
                            onExporting(false)
                            onRefresh()
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
            if (state is SupportDiagnosticsSubmissionState.Packaging ||
                state is SupportDiagnosticsSubmissionState.Uploading
            ) {
                OutlinedButton(onClick = { scope.launch { services.cancelSupportDiagnosticsSubmission() } }) {
                    Text("Cancel sending")
                }
            }
        }
        SubmissionProgress(
            state,
            { scope.launch { services.retrySupportDiagnosticsSubmission() } },
            onDiscard,
        )
    }
}

@Composable
private fun SubmissionProgress(
    state: SupportDiagnosticsSubmissionState,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            SupportDiagnosticsSubmissionState.Initializing -> ProgressMessage("Restoring private support state...")
            SupportDiagnosticsSubmissionState.AccountRequired -> Text("Sign in before sending a private report.")
            SupportDiagnosticsSubmissionState.Idle -> Unit
            is SupportDiagnosticsSubmissionState.BlockedByAnotherAccount -> Text(state.message)
            SupportDiagnosticsSubmissionState.Packaging -> ProgressMessage("Preparing the private report...")
            SupportDiagnosticsSubmissionState.Cancelling -> ProgressMessage("Finishing report cancellation...")
            SupportDiagnosticsSubmissionState.DeletingSubmittedReport -> ProgressMessage("Deleting the report...")
            is SupportDiagnosticsSubmissionState.Uploading -> {
                if (state.progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("Sending the private report to Obiente Support...")
            }
            is SupportDiagnosticsSubmissionState.RetryableFailure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    OutlinedButton(onClick = onRetry) { Text("Retry safely") }
                    TextButton(onClick = onDiscard) { Text("Discard pending report") }
                }
            }
            is SupportDiagnosticsSubmissionState.Rejected -> Text(state.message, color = MaterialTheme.colorScheme.error)
            SupportDiagnosticsSubmissionState.Cancelled -> Text("Private report submission cancelled.")
            is SupportDiagnosticsSubmissionState.Submitted -> Text("Report sent. Follow it from Requests.")
            is SupportDiagnosticsSubmissionState.Unsupported -> Text(state.reason)
        }
    }
}

@Composable
private fun ProgressMessage(message: String) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(message, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PrivacyTab(
    summary: SupportDiagnosticsSummary,
    preview: Boolean,
    onPreview: (Boolean) -> Unit,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        Text("What private support means", style = MaterialTheme.typography.titleLarge)
        PrivacyPoint("Nothing is uploaded automatically. Sending always requires your explicit confirmation.")
        PrivacyPoint("Nextcloud Native reports are retained by Obiente Support for 30 days unless deleted first.")
        PrivacyPoint(
            "Authorized maintainers can read reports and replies. Server storage is encrypted, but this is not end-to-end encryption.",
        )
        PrivacyPoint(
            "This device stores a private receipt only for the account that created it. " +
                "A support code cannot rediscover a lost receipt.",
        )
        PrivacyPoint(
            "Reports include a stable pseudonymous account scope so maintainers can connect reports from the same " +
                "account on this installation. It does not contain the account address or login name.",
        )
        PrivacyPoint(
            "Deleting revokes the live request and removes diagnostics. Backups have separate retention, " +
                "so immediate deletion from every backup is not guaranteed.",
        )
        Text("Diagnostic history on this device", style = MaterialTheme.typography.titleMedium)
        Text(
            if (summary.available) {
                summary.eventCount.toString() + " events, " + summary.errorCount + " errors, " +
                    summary.warningCount + " warnings, " + formatVirtualFileBytes(summary.storedBytes)
            } else {
                summary.explanation ?: "Diagnostic storage is unavailable on this device."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.recentEvents.isNotEmpty()) {
            OutlinedButton(onClick = { onPreview(!preview) }) {
                Text(if (preview) "Hide event preview" else "Preview recent events")
            }
            if (preview) Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
                    summary.recentEvents.asReversed().forEach { event ->
                        Text(
                            listOfNotNull(
                                event.severity.name,
                                event.component.name,
                                event.operation,
                                event.outcome,
                                event.code,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (summary.eventCount > 0) TextButton(
            enabled = canClear,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = onClear,
        ) { Text("Clear diagnostic history") }
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Text(text, modifier = Modifier.padding(NextcloudSpacing.Medium))
    }
}

@Composable
private fun SupportDialogs(
    services: NextcloudPlatformServices,
    reports: List<SupportDiagnosticsSubmissionState.SubmittedReport>,
    busy: Boolean,
    send: Boolean,
    onSend: (Boolean) -> Unit,
    discard: Boolean,
    onDiscard: (Boolean) -> Unit,
    clear: Boolean,
    onClear: (Boolean) -> Unit,
    deleteId: String?,
    onDeleteId: (String?) -> Unit,
    draft: String,
    onNotice: (String) -> Unit,
    onHistoryCleared: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (clear) AlertDialog(
        onDismissRequest = { onClear(false) },
        title = { Text("Clear diagnostic history?") },
        text = { Text("This removes diagnostic events from this device, not submitted requests.") },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onClear(false)
                    scope.launch {
                        onNotice(if (services.clearSupportDiagnostics()) {
                            "Diagnostic history cleared."
                        } else {
                            "Diagnostic history could not be cleared."
                        })
                        onHistoryCleared()
                    }
                },
            ) { Text("Clear history") }
        },
        dismissButton = { TextButton(onClick = { onClear(false) }) { Text("Cancel") } },
    )
    if (send) AlertDialog(
        onDismissRequest = { if (!busy) onSend(false) },
        title = { Text("Send this private report?") },
        text = {
            Text(
                "Obiente Support receives the text you reviewed, sanitized diagnostics, and release details. " +
                    "A stable pseudonymous account scope links reports from the same account on this installation. " +
                    "Authorized maintainers can read it. Retention is 30 days unless you delete it first.",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onSend(false)
                    scope.launch { services.submitSupportDiagnostics(draft) }
                },
            ) { Text("Send privately") }
        },
        dismissButton = { TextButton(onClick = { onSend(false) }) { Text("Cancel") } },
    )
    if (discard) AlertDialog(
        onDismissRequest = { onDiscard(false) },
        title = { Text("Discard the pending report?") },
        text = { Text("The app reconciles an uncertain upload and requests deletion before removing local state.") },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onDiscard(false)
                    scope.launch { services.cancelSupportDiagnosticsSubmission() }
                },
            ) { Text("Discard report") }
        },
        dismissButton = { TextButton(onClick = { onDiscard(false) }) { Text("Keep report") } },
    )
    deleteId?.let { recordId ->
        reports.firstOrNull { it.recordId == recordId }?.let { report ->
            AlertDialog(
                onDismissRequest = { if (!busy) onDeleteId(null) },
                title = { Text("Delete " + report.supportCode + "?") },
                text = {
                    Text(
                        "This revokes the request, removes diagnostics, and removes this device's receipt. " +
                            "Deletion from retained backups may take longer.",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            onDeleteId(null)
                            scope.launch {
                                onNotice(when (val result = services.deleteSubmittedSupportDiagnosticsReport(recordId)) {
                                    SupportDiagnosticsDeletionResult.Deleted -> "Support request deleted."
                                    is SupportDiagnosticsDeletionResult.Failed -> result.message
                                    is SupportDiagnosticsDeletionResult.Unsupported -> result.reason
                                })
                            }
                        },
                    ) { Text("Delete request") }
                },
                dismissButton = { TextButton(onClick = { onDeleteId(null) }) { Text("Keep request") } },
            )
        }
    }
}

internal data class SupportReportPage<T>(val items: List<T>, val pageIndex: Int, val pageCount: Int)

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
        reports.subList(firstIndex, minOf(firstIndex + pageSize, reports.size)),
        pageIndex,
        pageCount,
    )
}

private enum class SupportTab(val label: String) {
    Requests("Requests"),
    NewReport("New report"),
    Privacy("Privacy"),
}

private enum class ReportStatus(val wire: String, val label: String) {
    New("new", "Received"),
    NeedsInformation("needs_information", "More information requested"),
    Accepted("accepted", "Accepted for review"),
    Duplicate("duplicate", "Marked as duplicate"),
    Resolved("resolved", "Resolved by support"),
    Rejected("rejected", "Closed by support"),
    Unknown("", "Status updated"),
}

private fun reportStatus(value: String) =
    ReportStatus.entries.firstOrNull { it.wire == value } ?: ReportStatus.Unknown

private fun dateLabel(value: String) = value.substringBefore('T').ifBlank { value }
private fun timestampLabel(value: String) = value.replace('T', ' ').removeSuffix("Z")

private fun SupportDiagnosticsSubmissionState.busy() =
    this is SupportDiagnosticsSubmissionState.Initializing ||
        this is SupportDiagnosticsSubmissionState.Packaging ||
        this is SupportDiagnosticsSubmissionState.Cancelling ||
        this is SupportDiagnosticsSubmissionState.DeletingSubmittedReport ||
        this is SupportDiagnosticsSubmissionState.Uploading

private fun loadingSummary() = SupportDiagnosticsSummary(
    false, 0, 0, 0, null, null, emptySet(), 0L, SUPPORT_BUNDLE_INCLUDED_FILES,
    explanation = "Loading private diagnostic history...",
)

private const val SUPPORT_REPORT_PAGE_SIZE = 5
private const val MAX_VISIBLE_SUPPORT_MESSAGES = 20
internal const val MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH = 8_192
internal const val SUPPORT_CONVERSATION_BACKGROUND_REFRESH_MILLIS = 5L * 60L * 1_000L
