package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.runtime.emailBodyToPlainText
import dev.obiente.nextcloudnative.nativeui.runtime.sanitizeNativeMailHtml

@Composable
internal fun MailMessageReader(
    opened: MailOpenedMessage,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    mail: MailWorkspaceSession,
) {
    val summary = opened.summary
    var dialog by remember(summary.id) { mutableStateOf<MailMessageDialog?>(null) }
    var menuOpen by remember(summary.id) { mutableStateOf(false) }
    val scroll = key(summary.id) { rememberScrollState() }
    val detail = opened.detail
    val role = state.mailbox(summary.mailboxId)?.let(state::role)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.widthIn(max = MAIL_READER_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            Text(
                summary.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            MailAddressLine("From", summary.from)
            MailAddressLine("To", summary.to)
            MailAddressLine("Cc", summary.cc)
            detail?.replyTo?.takeIf { replyTo -> replyTo.map { it.email } != summary.from.map { it.email } }?.let { MailAddressLine("Reply to", it) }
            summary.sentAtEpochSeconds?.let { sent ->
                Text(mailQuoteDate(sent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.tags.isNotEmpty()) MailTagChips(summary.tags)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                val canRespond = state.writesAllowed && detail != null
                if (role == MailMailboxRole.Drafts && detail != null) {
                    MailReaderAction(Icons.Outlined.Edit, "Edit", "Continue editing this draft", canRespond) {
                        mail.respond(MailComposeMode.EditDraft, detail)
                    }
                } else {
                    MailReaderAction(Icons.AutoMirrored.Outlined.Reply, "Reply", "Reply to the sender", canRespond) {
                        detail?.let { mail.respond(MailComposeMode.Reply, it) }
                    }
                    MailReaderAction(Icons.AutoMirrored.Outlined.ReplyAll, "Reply all", "Reply to everyone", canRespond) {
                        detail?.let { mail.respond(MailComposeMode.ReplyAll, it) }
                    }
                }
                MailReaderAction(Icons.AutoMirrored.Outlined.Forward, "Forward", "Forward this message", canRespond) {
                    detail?.let { mail.respond(MailComposeMode.Forward, it) }
                }
                if (state.canChangeMessages(summary.mailboxId)) {
                    MailReaderAction(
                        if (summary.flags.flagged) Icons.Outlined.Flag else Icons.Outlined.OutlinedFlag,
                        if (summary.flags.flagged) "Flagged" else "Flag",
                        if (summary.flags.flagged) "Remove flag" else "Flag message",
                        summary.id !in state.pendingMessageIds,
                    ) { controller.toggleFlagged(summary) }
                }
                NextcloudCardOverflow(
                    itemLabel = summary.subject.ifBlank { "message" },
                    actions = mailMessageActions(summary, state, controller) { dialog = it },
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                )
            }
            HorizontalDivider()
            when {
                opened.failure != null -> Column {
                    Text(opened.failure.title, style = MaterialTheme.typography.titleSmall)
                    Text(opened.failure.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = controller::retryOpen) { Text("Try again") }
                }
                detail == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading the message...", modifier = Modifier.padding(start = NextcloudSpacing.Medium))
                }
                else -> MailMessageBody(detail)
            }
            detail?.attachments?.takeIf(List<MailMessageAttachment>::isNotEmpty)?.let { attachments ->
                HorizontalDivider()
                Text("${attachments.size} attachment(s)", style = MaterialTheme.typography.titleSmall)
                attachments.forEach { attachment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            listOfNotNull(attachment.fileName, attachment.sizeBytes?.let(::formatByteSize)).joinToString(" - "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = NextcloudSpacing.Small),
                        )
                    }
                }
            }
            Text(
                "Remote images and embedded content are not loaded, so opening a message does not contact the sender.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    dialog?.let { MailMessageDialogHost(it, state, controller, onDismiss = { dialog = null }) }
}

@Composable
private fun MailMessageBody(detail: MailMessageDetail) {
    val html = detail.bodyHtml
    if (html != null) {
        val sanitized = remember(detail.id, html) { sanitizeNativeMailHtml(html) }
        val rich = remember(sanitized) { RichTextState().apply { if (sanitized.isNotBlank()) setHtml(sanitized) } }
        if (sanitized.isNotBlank()) {
            RichText(state = rich, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
            return
        }
    }
    // Mail turns links in plain-text bodies into anchors; show the text itself.
    val text = remember(detail.id, detail.bodyText) { detail.bodyText?.let(::emailBodyToPlainText).orEmpty() }
    Text(text.ifBlank { "This message has no text." }, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun MailAddressLine(label: String, recipients: List<MailRecipient>) {
    if (recipients.isEmpty()) return
    Text(
        "$label: " + recipients.joinToString(", ") { recipient ->
            recipient.label?.takeIf(String::isNotBlank)?.let { "$it <${recipient.email}>" } ?: recipient.email
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MailReaderAction(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
    }
}

private val MAIL_READER_MAX_WIDTH = 820.dp
