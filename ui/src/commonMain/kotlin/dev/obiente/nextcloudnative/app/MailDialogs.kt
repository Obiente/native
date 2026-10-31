package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal sealed interface MailMessageDialog {
    val message: MailMessageSummary

    data class Move(override val message: MailMessageSummary) : MailMessageDialog
    data class Tags(override val message: MailMessageSummary) : MailMessageDialog
    data class Delete(override val message: MailMessageSummary) : MailMessageDialog
}

internal sealed interface MailFolderDialog {
    data class Create(val parent: MailMailbox?) : MailFolderDialog
    data class Rename(val mailbox: MailMailbox) : MailFolderDialog
    data class Delete(val mailbox: MailMailbox) : MailFolderDialog
}

/** Secondary message actions, shared by the list overflow, long press, and the reader. */
internal fun mailMessageActions(
    message: MailMessageSummary,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    onDialog: (MailMessageDialog) -> Unit,
): List<NextcloudCardAction> {
    if (!state.writesAllowed) return emptyList()
    val pending = message.id in state.pendingMessageIds
    val mailboxId = message.mailboxId
    val inTrash = state.mailbox(mailboxId)?.let(state::role) == MailMailboxRole.Trash
    val archiveId = state.selectedAccount?.archiveMailboxId
    return buildList {
        if (state.canChangeSeen(mailboxId)) {
            add(NextcloudCardAction(if (message.flags.seen) "Mark as unread" else "Mark as read", enabled = !pending) { controller.toggleRead(message) })
        }
        if (state.canChangeMessages(mailboxId)) {
            add(NextcloudCardAction(if (message.flags.flagged) "Remove flag" else "Flag", enabled = !pending) { controller.toggleFlagged(message) })
            add(
                NextcloudCardAction(if (message.flags.important) "Mark as not important" else "Mark as important", enabled = !pending) {
                    controller.toggleImportant(message)
                },
            )
            add(NextcloudCardAction(if (message.flags.junk) "Mark as not junk" else "Mark as junk", enabled = !pending) { controller.toggleJunk(message) })
            add(NextcloudCardAction("Tags...", enabled = !pending) { onDialog(MailMessageDialog.Tags(message)) })
        }
        if (state.canRemoveMessages(mailboxId)) {
            add(NextcloudCardAction("Move to...", enabled = !pending) { onDialog(MailMessageDialog.Move(message)) })
            if (archiveId != null && archiveId != mailboxId && state.mailbox(archiveId) != null) {
                add(NextcloudCardAction("Archive", enabled = !pending) { controller.archive(message) })
            }
            add(
                NextcloudCardAction(if (inTrash) "Delete permanently" else "Move to Trash", destructive = true, enabled = !pending) {
                    onDialog(MailMessageDialog.Delete(message))
                },
            )
        }
    }
}

@Composable
internal fun MailMessageDialogHost(
    dialog: MailMessageDialog,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is MailMessageDialog.Move -> MailMoveDialog(dialog.message, state, onDismiss) { destination ->
            controller.move(dialog.message, destination.id)
            onDismiss()
        }
        is MailMessageDialog.Tags -> MailTagsDialog(dialog.message, state, controller, onDismiss)
        is MailMessageDialog.Delete -> {
            val permanent = state.mailbox(dialog.message.mailboxId)?.let(state::role) == MailMailboxRole.Trash
            MailConfirmDialog(
                title = if (permanent) "Delete this message permanently?" else "Move this message to Trash?",
                body = if (permanent) {
                    "\"${dialog.message.subject.ifBlank { "(no subject)" }}\" will be removed from the server. This cannot be undone."
                } else {
                    "\"${dialog.message.subject.ifBlank { "(no subject)" }}\" will be moved to Trash."
                },
                confirmLabel = if (permanent) "Delete permanently" else "Move to Trash",
                onConfirm = {
                    controller.delete(dialog.message)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun MailMoveDialog(
    message: MailMessageSummary,
    state: MailWorkspaceUiState,
    onDismiss: () -> Unit,
    onMove: (MailMailbox) -> Unit,
) {
    val targets = state.moveTargets(message.mailboxId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            if (targets.isEmpty()) {
                Text("There is no other folder you can move this message to.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(targets, key = { it.id }) { mailbox ->
                        Text(
                            text = mailbox.name.replace(mailbox.delimiter ?: "\u0000", " / "),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Move here") { onMove(mailbox) }
                                .padding(vertical = NextcloudSpacing.Medium, horizontal = NextcloudSpacing.Small),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MailTagsDialog(
    message: MailMessageSummary,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    onDismiss: () -> Unit,
) {
    var newName by remember(message.id) { mutableStateOf("") }
    val current = (state.opened?.summary?.takeIf { it.id == message.id }
        ?: (state.messages as? MailMessagesState.Loaded)?.items?.firstOrNull { it.id == message.id }
        ?: message)
    val applied = current.tags.mapTo(mutableSetOf()) { it.imapLabel }
    val tags = (state.knownTags + current.tags).distinctBy(MailTag::imapLabel).sortedBy { it.displayName.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                if (tags.isEmpty()) {
                    Text(
                        "No tags are known yet. Tags appear here once a message that carries them is loaded, or create one below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(tags, key = MailTag::imapLabel) { tag ->
                        val checked = tag.imapLabel in applied
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Checkbox) { controller.setTag(current, tag, !checked) },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { controller.setTag(current, tag, it) })
                            Text(tag.displayName)
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(MAX_MAIL_TAG_NAME) },
                    label = { Text("New tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    controller.createTagAndApply(current, newName.trim(), MAIL_DEFAULT_TAG_COLOR)
                    newName = ""
                },
            ) { Text("Create and apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun MailFolderDialogHost(
    dialog: MailFolderDialog,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is MailFolderDialog.Create -> MailFolderNameDialog(
            title = dialog.parent?.let { "New folder in ${it.displayName}" } ?: "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = onDismiss,
        ) { name ->
            controller.createMailbox(name, dialog.parent)
            onDismiss()
        }
        is MailFolderDialog.Rename -> MailFolderNameDialog(
            title = "Rename folder",
            initial = dialog.mailbox.displayName,
            confirmLabel = "Rename",
            onDismiss = onDismiss,
        ) { name ->
            controller.renameMailbox(dialog.mailbox, name)
            onDismiss()
        }
        is MailFolderDialog.Delete -> {
            val children = state.mailboxes.orEmpty().count { it.name.startsWith(dialog.mailbox.name + (dialog.mailbox.delimiter ?: "/")) }
            MailConfirmDialog(
                title = "Delete \"${dialog.mailbox.displayName}\"?",
                body = buildString {
                    append("The folder and every message in it will be removed from the mail server. This cannot be undone.")
                    if (children > 0) append(" It also contains $children subfolder(s).")
                },
                confirmLabel = "Delete folder",
                onConfirm = {
                    controller.deleteMailbox(dialog.mailbox)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun MailFolderNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_MAIL_MAILBOX_NAME) },
                label = { Text("Folder name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && name.trim() != initial, onClick = { onConfirm(name.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A separate confirmation with destructive styling, used before any removal. */
@Composable
internal fun MailConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAIL_DEFAULT_TAG_COLOR = "#0082C9"
