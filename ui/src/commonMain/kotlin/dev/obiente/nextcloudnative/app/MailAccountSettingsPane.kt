package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

private sealed interface MailAliasDialog {
    data object Create : MailAliasDialog
    data class Edit(val alias: MailAlias) : MailAliasDialog
    data class Signature(val alias: MailAlias) : MailAliasDialog
    data class Delete(val alias: MailAlias) : MailAliasDialog
}

/**
 * The account settings Mail exposes to its owner: signature, signature placement, default editor,
 * and aliases. Server, password, and S/MIME settings stay in the Mail web settings.
 */
@Composable
internal fun MailAccountSettingsPane(
    account: MailAccount?,
    controller: MailAccountSettingsController,
    writesAllowed: Boolean,
) {
    if (account == null) {
        EmptyMessage("Choose a mail account first.")
        return
    }
    val state = controller.state
    val editable = writesAllowed && !state.saving && !account.delegated
    var signature by remember(account.id, account.signature) { mutableStateOf(account.signature.orEmpty()) }
    var aliasDialog by remember(account.id) { mutableStateOf<MailAliasDialog?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
            Text("${account.name} (${account.email})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            state.notice?.let { MailNoticeBar(it, "Dismiss", controller::dismissNotice) }
            if (account.delegated) {
                Text("This account is shared with you, so its settings are managed by its owner.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Signature", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it.take(MAX_MAIL_SIGNATURE) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Signature") },
            )
            Button(enabled = editable && signature != account.signature.orEmpty(), onClick = { controller.saveSignature(account, signature) }) {
                Text("Save signature")
            }
            MailSettingSwitch(
                label = "Put the signature above quoted text",
                checked = account.signatureAboveQuote,
                enabled = editable,
            ) { controller.saveSignaturePlacement(account, it) }
            MailSettingSwitch(
                label = "Write new messages in rich text",
                checked = account.editorMode == MailEditorMode.Rich,
                enabled = editable,
            ) { rich -> controller.saveEditorMode(account, if (rich) MailEditorMode.Rich else MailEditorMode.Plain) }
            HorizontalDivider()
            Text("Aliases", style = MaterialTheme.typography.titleSmall)
            Text(
                "Aliases let you send from other addresses of this account. Your mail server must accept them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (account.aliases.isEmpty()) Text("No aliases yet.", style = MaterialTheme.typography.bodyMedium)
            account.aliases.forEach { alias ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("${alias.name ?: account.name} <${alias.email}>", style = MaterialTheme.typography.bodyMedium)
                    if (alias.provisioned) {
                        Text("Managed by your administrator", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Row {
                            TextButton(enabled = editable, onClick = { aliasDialog = MailAliasDialog.Edit(alias) }) { Text("Edit") }
                            TextButton(enabled = editable, onClick = { aliasDialog = MailAliasDialog.Signature(alias) }) { Text("Signature") }
                            TextButton(enabled = editable, onClick = { aliasDialog = MailAliasDialog.Delete(alias) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            OutlinedButton(enabled = editable, onClick = { aliasDialog = MailAliasDialog.Create }) { Text("Add alias") }
            HorizontalDivider()
            Text(
                "Incoming and outgoing server settings, passwords, filters, and S/MIME certificates are changed in the Mail web settings, " +
                    "because they need the mail account password.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    aliasDialog?.let { dialog ->
        val dismiss = { aliasDialog = null }
        when (dialog) {
            MailAliasDialog.Create -> MailAliasEditor("Add alias", "", "", dismiss) { email, name ->
                controller.createAlias(account, email, name)
                dismiss()
            }
            is MailAliasDialog.Edit -> MailAliasEditor("Edit alias", dialog.alias.email, dialog.alias.name.orEmpty(), dismiss) { email, name ->
                controller.updateAlias(account, dialog.alias, email, name)
                dismiss()
            }
            is MailAliasDialog.Signature -> MailAliasSignatureEditor(dialog.alias, dismiss) { text ->
                controller.saveAliasSignature(account, dialog.alias, text)
                dismiss()
            }
            is MailAliasDialog.Delete -> MailConfirmDialog(
                title = "Remove ${dialog.alias.email}?",
                body = "You will no longer be able to send from this address here. Messages already sent are not changed.",
                confirmLabel = "Remove alias",
                onConfirm = {
                    controller.deleteAlias(account, dialog.alias)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }
    }
}

@Composable
private fun MailSettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun MailAliasEditor(
    title: String,
    initialEmail: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var name by remember { mutableStateOf(initialName) }
    val valid = isPlausibleMailAddress(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedTextField(value = email, onValueChange = { email = it.take(MAX_MAIL_ADDRESS) }, label = { Text("Address") }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it.take(MAX_MAIL_DISPLAY_NAME) }, label = { Text("Display name") }, singleLine = true)
                if (email.isNotBlank() && !valid) Text("Enter a valid email address.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(email.trim(), name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MailAliasSignatureEditor(alias: MailAlias, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(alias.id) { mutableStateOf(alias.signature.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signature for ${alias.email}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_MAIL_SIGNATURE) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Signature") },
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
