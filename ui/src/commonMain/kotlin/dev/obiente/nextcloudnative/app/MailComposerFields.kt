package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.OutlinedRichTextEditor
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One recipient field: chips for added recipients and a text input with autocomplete. Typing a
 * comma, a semicolon, or pressing Enter turns every complete address into a chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailRecipientInput(
    label: String,
    field: MailRecipientField,
    recipients: List<MailRecipient>,
    composer: MailComposerController,
    enabled: Boolean,
) {
    var text by remember(composer, field) { mutableStateOf("") }
    val state = composer.state
    Column(modifier = Modifier.fillMaxWidth()) {
        if (recipients.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                recipients.forEach { recipient ->
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { composer.removeRecipient(field, recipient) },
                        label = { Text(recipient.displayName, maxLines = 1) },
                        trailingIcon = { Icon(NextcloudIcons.Close, contentDescription = "Remove ${recipient.email}", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Box {
            OutlinedTextField(
                value = text,
                enabled = enabled,
                onValueChange = { value ->
                    text = if (value.any { it == ',' || it == ';' || it == '\n' }) composer.commitTypedRecipients(field, value) else value
                    composer.suggest(field, text)
                },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { text = composer.commitTypedRecipients(field, text) }),
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = enabled && state.suggestionField == field && state.suggestions.isNotEmpty(),
                onDismissRequest = composer::clearSuggestions,
            ) {
                state.suggestions.forEach { match ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(match.label ?: match.email)
                                if (match.label != null) Text(match.email, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            composer.addRecipient(field, match.toRecipient())
                            text = ""
                        },
                    )
                }
            }
        }
        if (text.isNotBlank() && !isPlausibleMailAddress(text.trim()) && text.trim().contains('@')) {
            Text("Finish the address, then press Enter.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun MailIdentityPicker(
    account: MailAccount,
    draft: MailComposeDraft,
    enabled: Boolean,
    onSelect: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val alias = draft.aliasId?.let { id -> account.aliases.firstOrNull { it.id == id } }
    val current = alias?.let { "${it.name ?: account.name} <${it.email}>" } ?: "${account.name} <${account.email}>"
    Box {
        TextButton(enabled = enabled && account.aliases.isNotEmpty(), onClick = { open = true }) {
            Text("From: $current", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("${account.name} <${account.email}>") }, onClick = {
                open = false
                onSelect(null)
            })
            account.aliases.forEach { option ->
                DropdownMenuItem(text = { Text("${option.name ?: account.name} <${option.email}>") }, onClick = {
                    open = false
                    onSelect(option.id)
                })
            }
        }
    }
}

/** Rich body editing backed by Compose Rich Editor; the draft keeps the editor's HTML. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailRichBodyEditor(
    slotId: String,
    initialHtml: String,
    enabled: Boolean,
    onHtmlChanged: (String) -> Unit,
) {
    val rich = rememberRichTextState()
    LaunchedEffect(slotId) {
        if (initialHtml.isNotBlank()) rich.setHtml(initialHtml)
        // The draft follows the editor at once, so a send right after typing carries the latest
        // text. Saving to the device is debounced by the composer itself.
        snapshotFlow { rich.toHtml() }
            .distinctUntilChanged()
            .collect(onHtmlChanged)
    }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Row {
                MailFormatButton(NextcloudIcons.FormatBold, "Bold", enabled) { rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                MailFormatButton(NextcloudIcons.FormatItalic, "Italic", enabled) { rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                MailFormatButton(Icons.Outlined.FormatUnderlined, "Underline", enabled) {
                    rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                }
                MailFormatButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list", enabled) { rich.toggleUnorderedList() }
                MailFormatButton(Icons.Outlined.FormatListNumbered, "Numbered list", enabled) { rich.toggleOrderedList() }
            }
        }
        OutlinedRichTextEditor(
            state = rich,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            label = { Text("Message") },
        )
    }
}

@Composable
private fun MailFormatButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
internal fun MailComposeAttachments(
    draft: MailComposeDraft,
    uploading: List<String>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
) {
    if (draft.attachments.isEmpty() && uploading.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text("Attachments", style = MaterialTheme.typography.titleSmall)
        draft.attachments.forEach { attachment ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    attachment.fileName + when (attachment) {
                        is MailComposeAttachment.FromFiles -> " (from Files)"
                        is MailComposeAttachment.Forwarded -> " (from the original message)"
                        is MailComposeAttachment.Uploaded -> ""
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(enabled = enabled, onClick = { onRemove(attachment.key) }) {
                    Icon(NextcloudIcons.Close, contentDescription = "Remove ${attachment.fileName}")
                }
            }
        }
        uploading.forEach { name ->
            Text("Uploading $name...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = NextcloudSpacing.XSmall))
        }
    }
}

