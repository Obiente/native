package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

/** Scheduled, waiting, and failed messages of the account, from Mail's server Outbox. */
@Composable
internal fun MailOutboxPane(controller: MailOutboxController, account: MailAccount?) {
    val state = controller.state
    var cancelTarget by remember { mutableStateOf<MailOutboxMessage?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        state.notice?.let { MailNoticeBar(it, "Dismiss", controller::dismissNotice) }
        val messages = state.messages?.filter { account == null || it.accountId == account.id }
        when {
            messages == null && state.failure != null -> MailFailurePane(state.failure, onRetry = controller::load)
            messages == null -> LoadingMessage("Loading the Outbox...")
            messages.isEmpty() -> EmptyMessage("The Outbox is empty. Scheduled messages and messages waiting to be sent appear here.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages, key = MailOutboxMessage::id) { message ->
                    MailOutboxRow(
                        message = message,
                        pending = message.id in state.pendingIds,
                        onSendNow = { controller.sendNow(message) },
                        onCancel = { cancelTarget = message },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    cancelTarget?.let { target ->
        MailConfirmDialog(
            title = "Cancel this message?",
            body = "\"${target.subject.ifBlank { "(no subject)" }}\" is removed from the Outbox and will not be sent. This cannot be undone.",
            confirmLabel = "Cancel message",
            onConfirm = {
                controller.cancel(target)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
        )
    }
}

@Composable
private fun MailOutboxRow(
    message: MailOutboxMessage,
    pending: Boolean,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
) {
    var menu by remember(message.id) { mutableStateOf(false) }
    val problem = message.status.explanation
    val status = when {
        pending -> "Updating..."
        problem != null -> problem
        message.sendAtEpochSeconds != null -> "Sends ${mailQuoteDate(message.sendAtEpochSeconds)}"
        else -> "Waiting to be sent"
    }
    val actions = buildList {
        // Mail refuses to resend after an unclear SMTP result, so the action is not offered then.
        if (message.status != MailOutboxStatus.Error) add(NextcloudCardAction("Send now", enabled = !pending, onClick = onSendNow))
        add(NextcloudCardAction("Cancel message", destructive = true, enabled = !pending, onClick = onCancel))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .nextcloudCardInteractions(onOpen = null, onShowActions = { menu = true }, actionsLabel = "Outbox message actions")
            .semantics(mergeDescendants = true) { stateDescription = status }
            .padding(start = NextcloudSpacing.Large, top = NextcloudSpacing.Medium, bottom = NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(message.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "To: " + (message.to + message.cc + message.bcc).joinToString(", ") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NextcloudCardOverflow(itemLabel = message.subject.ifBlank { "message" }, actions = actions, expanded = menu, onExpandedChange = { menu = it })
    }
}
