package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.runtime.emailBodyToPlainText
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailComposerPane(
    composer: MailComposerController,
    mail: MailWorkspaceSession,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
) {
    val state = composer.state
    val draft = state.draft
    val account = mail.workspace.state.accounts?.firstOrNull { it.id == draft.accountId }
    val editable = state.editable
    var showCopies by remember(draft.slotId) { mutableStateOf(draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) }
    var filesPicker by remember(draft.slotId) { mutableStateOf(false) }
    var scheduleDialog by remember(draft.slotId) { mutableStateOf(false) }
    var discardDialog by remember(draft.slotId) { mutableStateOf(false) }
    var cancelDialog by remember(draft.slotId) { mutableStateOf(false) }
    var resendDialog by remember(draft.slotId) { mutableStateOf(false) }
    val problems = composer.problems
    if (account == null) {
        Text(
            "This account is no longer available. The draft is kept on this device.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(NextcloudSpacing.Large),
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Column(modifier = Modifier.widthIn(max = MAIL_COMPOSER_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            MailSendStatus(
                phase = state.phase,
                onUndo = composer::undo,
                onSendNow = composer::sendImmediately,
                onRetrySend = composer::retrySend,
                onCancelHeld = { cancelDialog = true },
                onCheck = composer::checkStatus,
                onSendAgain = { resendDialog = true },
                onClose = mail::closeComposer,
            )
            listOfNotNull(state.notice, state.attachmentNotice, state.persistenceNotice).forEach { notice ->
                MailNoticeBar(notice, "Dismiss", composer::dismissNotice)
            }
            MailIdentityPicker(account, draft, editable) { aliasId -> composer.update { it.copy(aliasId = aliasId) } }
            MailRecipientInput("To", MailRecipientField.To, draft.to, composer, editable)
            if (showCopies) {
                MailRecipientInput("Cc", MailRecipientField.Cc, draft.cc, composer, editable)
                MailRecipientInput("Bcc", MailRecipientField.Bcc, draft.bcc, composer, editable)
            } else {
                TextButton(onClick = { showCopies = true }, enabled = editable) { Text("Add Cc or Bcc") }
            }
            OutlinedTextField(
                value = draft.subject,
                onValueChange = { value -> composer.update { it.copy(subject = value.take(MAX_MAIL_SUBJECT)) } },
                enabled = editable,
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Format:", style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = editable && draft.rich, onClick = {
                    composer.update { it.copy(rich = false, body = emailBodyToPlainText(it.body)) }
                }) { Text(if (!draft.rich) "Plain text (selected)" else "Plain text") }
                TextButton(enabled = editable && !draft.rich, onClick = {
                    composer.update { it.copy(rich = true, body = it.body.escapeMailHtml().replace("\n", "<br>")) }
                }) { Text(if (draft.rich) "Rich text (selected)" else "Rich text") }
            }
            if (draft.rich) {
                key(draft.slotId, "rich") {
                    MailRichBodyEditor(draft.slotId, draft.body, editable) { html -> composer.update { it.copy(body = html) } }
                }
            } else {
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = { value -> composer.update { it.copy(body = value.take(MAX_MAIL_BODY_CHARACTERS)) } },
                    enabled = editable,
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                )
            }
            val signature = draft.aliasId?.let { id -> account.aliases.firstOrNull { it.id == id }?.signature } ?: account.signature
            if (!signature.isNullOrBlank()) {
                MailCheckRow("Add signature", draft.includeSignature, editable) { checked -> composer.update { it.copy(includeSignature = checked) } }
            }
            draft.quote?.let { quote ->
                MailCheckRow(
                    if (quote.forward) "Include the forwarded message" else "Quote the original message",
                    draft.includeQuote,
                    editable,
                ) { checked -> composer.update { it.copy(includeQuote = checked) } }
                if (draft.includeQuote) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            quote.header + "\n" + quote.text.lines().take(MAIL_QUOTE_PREVIEW_LINES).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = MAIL_QUOTE_PREVIEW_LINES + 6,
                            modifier = Modifier.padding(NextcloudSpacing.Medium),
                        )
                    }
                }
            }
            MailComposeAttachments(draft, state.uploading, editable, composer::removeAttachment)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedButton(enabled = editable, onClick = composer::attachFromDevice) { Text("Attach from device") }
                OutlinedButton(enabled = editable, onClick = { filesPicker = true }) { Text("Attach from Files") }
            }
            if (editable && problems.isNotEmpty() && draft.hasContent) {
                problems.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                val canSend = editable && problems.isEmpty() && state.uploading.isEmpty()
                Button(enabled = canSend, onClick = { composer.send() }) { Text("Send") }
                OutlinedButton(enabled = canSend, onClick = { scheduleDialog = true }) { Text("Send later...") }
                OutlinedButton(enabled = editable && !state.savingServerDraft && draft.hasContent, onClick = {
                    composer.saveToServerDrafts(onSaved = mail::closeComposer)
                }) { Text(if (state.savingServerDraft) "Saving..." else "Save to Drafts") }
                TextButton(enabled = editable, onClick = { if (draft.hasContent) discardDialog = true else composer.discard(mail::closeComposer) }) {
                    Text("Discard")
                }
                TextButton(onClick = mail::closeComposer) { Text("Close") }
            }
            Text(
                "Sent messages wait ${MailUndoDelay.Medium.label} in your server's Outbox so you can undo them. " +
                    "Unsent drafts are kept on this device until you send or discard them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (filesPicker) {
        RemoteFilePickerDialog(
            services = services,
            session = session,
            userId = userId,
            title = "Attach from Files",
            confirmLabel = "Attach",
            maximumSelection = (MAX_MAIL_ATTACHMENTS - draft.attachments.size).coerceAtLeast(1),
            onDismiss = { filesPicker = false },
            onSelected = { files ->
                composer.attachFromFiles(files)
                filesPicker = false
            },
        )
    }
    if (scheduleDialog) {
        MailScheduleDialog(onDismiss = { scheduleDialog = false }) { sendAt ->
            scheduleDialog = false
            composer.send(scheduledAtEpochSeconds = sendAt)
        }
    }
    if (discardDialog) {
        MailConfirmDialog(
            title = "Discard this draft?",
            body = "The message and its attachments are removed from this device. This cannot be undone.",
            confirmLabel = "Discard",
            onConfirm = {
                discardDialog = false
                composer.discard(mail::closeComposer)
            },
            onDismiss = { discardDialog = false },
        )
    }
    if (cancelDialog) {
        MailConfirmDialog(
            title = "Cancel sending?",
            body = "The message is removed from the Outbox and returns to the editor.",
            confirmLabel = "Cancel sending",
            onConfirm = {
                cancelDialog = false
                composer.cancelHeld()
            },
            onDismiss = { cancelDialog = false },
        )
    }
    if (resendDialog) {
        MailConfirmDialog(
            title = "Send this message again?",
            body = "An earlier attempt may already have been delivered. Check your Sent folder first; sending again can deliver a duplicate.",
            confirmLabel = "Send again",
            onConfirm = {
                resendDialog = false
                composer.send(acknowledgeMayBeSent = true)
            },
            onDismiss = { resendDialog = false },
        )
    }
}

@Composable
private fun MailSendStatus(
    phase: MailSendPhase,
    onUndo: () -> Unit,
    onSendNow: () -> Unit,
    onRetrySend: () -> Unit,
    onCancelHeld: () -> Unit,
    onCheck: () -> Unit,
    onSendAgain: () -> Unit,
    onClose: () -> Unit,
) {
    if (phase == MailSendPhase.Editing) return
    Surface(
        color = when (phase) {
            is MailSendPhase.NeedsAttention, is MailSendPhase.Unknown -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            when (phase) {
                MailSendPhase.Storing, MailSendPhase.Sending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Sending...", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                }
                is MailSendPhase.Undoable -> {
                    var now by remember(phase) { mutableLongStateOf(Clock.System.now().epochSeconds) }
                    LaunchedEffect(phase) {
                        while (now < phase.sendAtEpochSeconds) {
                            delay(1_000L)
                            now = Clock.System.now().epochSeconds
                        }
                    }
                    Text("Sending in ${(phase.sendAtEpochSeconds - now).coerceAtLeast(0L)} s")
                    Row {
                        Button(onClick = onUndo) { Text("Undo") }
                        TextButton(onClick = onSendNow) { Text("Send now") }
                    }
                }
                is MailSendPhase.Scheduled -> {
                    Text("Scheduled for ${mailQuoteDate(phase.sendAtEpochSeconds)}. It waits in the Outbox, where you can cancel it.")
                    TextButton(onClick = onClose) { Text("Close") }
                }
                MailSendPhase.Sent -> {
                    Text("Message sent.")
                    TextButton(onClick = onClose) { Text("Close") }
                }
                MailSendPhase.Queued -> {
                    Text("The message is waiting in the Outbox. Nextcloud sends it shortly, even if you close the app.")
                    TextButton(onClick = onClose) { Text("Close") }
                }
                is MailSendPhase.NeedsAttention -> {
                    Text(phase.message)
                    Row {
                        if (phase.canSendAnyway) Button(onClick = onRetrySend) { Text("Send anyway") }
                        if (phase.canRetrySend) Button(onClick = onRetrySend) { Text("Try again") }
                        if (phase.outboxId != null) TextButton(onClick = onCancelHeld) { Text("Cancel sending") }
                    }
                }
                is MailSendPhase.Unknown -> {
                    Text(phase.message)
                    Row {
                        Button(onClick = onCheck) { Text("Check status") }
                        if (phase.mayAlreadyBeSent) TextButton(onClick = onSendAgain) { Text("Send again...") }
                    }
                }
                MailSendPhase.Editing -> Unit
            }
        }
    }
}

@Composable
private fun MailCheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

/** Quick choices plus an exact local date and time, converted in the device time zone. */
@Composable
private fun MailScheduleDialog(onDismiss: () -> Unit, onSchedule: (Long) -> Unit) {
    var date by remember { mutableStateOf(deckLocalDatePlusDays(1)) }
    var time by remember { mutableStateOf("08:00") }
    var menu by remember { mutableStateOf(false) }
    val sendAt = mailScheduledEpochSeconds(date, time)
    val future = sendAt != null && sendAt > Clock.System.now().epochSeconds + MAIL_SCHEDULE_MINIMUM_LEAD_SECONDS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send later") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("Quick choices") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("Tomorrow morning" to 1, "In two days" to 2, "Next week" to 7).forEach { (label, days) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                date = deckLocalDatePlusDays(days)
                                time = "08:00"
                                menu = false
                            })
                        }
                    }
                }
                OutlinedTextField(value = date, onValueChange = { date = it.take(10) }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(value = time, onValueChange = { time = it.take(5) }, label = { Text("Time (HH:MM)") }, singleLine = true)
                if (!future) Text("Choose a valid time at least a minute from now.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(enabled = future, onClick = { sendAt?.let(onSchedule) }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Converts a validated local date and time to epoch seconds, or null when either is invalid. */
internal fun mailScheduledEpochSeconds(date: String, time: String): Long? {
    if (!date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) || !time.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) return null
    val month = date.substring(5, 7).toInt()
    val day = date.substring(8, 10).toInt()
    if (month !in 1..12 || day !in 1..31) return null
    return try {
        Instant.parse(deckLocalDateTimeToInstant(date, time)).epochSeconds
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private val MAIL_COMPOSER_MAX_WIDTH = 820.dp
private const val MAIL_QUOTE_PREVIEW_LINES = 6
private const val MAIL_SCHEDULE_MINIMUM_LEAD_SECONDS = 60L
