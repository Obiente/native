package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.launch

/**
 * Settings sheet for any app whose contract describes a settings document.
 *
 * [readOnlyEntries] shows record-scoped values (such as a board's own settings) that the contract
 * gives no write for. Folder-valued settings use the native remote folder picker when available.
 */
@Composable
internal fun SemanticSettingsDialog(
    title: String,
    controller: SemanticSettingsController?,
    onDismiss: () -> Unit,
    readOnlyTitle: String? = null,
    readOnlyEntries: List<Pair<String, String>> = emptyList(),
    folderPicker: RemoteFolderPickerOperations? = null,
) {
    val scope = rememberCoroutineScope()
    if (controller != null) LaunchedEffect(controller) { controller.load() }
    val state = controller?.state
    var choosingFolderFor by remember { mutableStateOf<SemanticSettingEntry?>(null) }
    AlertDialog(
        onDismissRequest = { if (state?.busyKey == null) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                if (readOnlyEntries.isNotEmpty()) {
                    readOnlyTitle?.let { SettingsHeading(it) }
                    readOnlyEntries.forEach { (label, value) -> SettingsFact(label, value) }
                }
                state?.errorMessage?.let { SettingsMessage(it, error = true) }
                state?.notice?.let { SettingsMessage(it, error = false) }
                when {
                    controller == null || state == null -> Unit
                    state.loading -> CircularProgressIndicator()
                    state.entries.isEmpty() -> OutlinedButton(onClick = { scope.launch { controller.load() } }) {
                        Text("Load settings again")
                    }
                    else -> {
                        if (state.outcomeUnknown) {
                            OutlinedButton(onClick = { scope.launch { controller.load() } }) { Text("Reload settings") }
                        }
                        state.entries.forEach { entry ->
                            SettingRow(
                                entry = entry,
                                enabled = entry.writable && state.canChange,
                                busy = state.busyKey == entry.key,
                                canPickFolder = folderPicker != null,
                                onChange = { value -> scope.launch { controller.change(entry, value) } },
                                onPickFolder = { choosingFolderFor = entry },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = state?.busyKey == null) { Text("Close") }
        },
    )
    val folderEntry = choosingFolderFor
    if (folderEntry != null && folderPicker != null && controller != null) {
        RemoteFolderPickerDialog(
            operations = folderPicker,
            initialPath = folderEntry.value.trim('/'),
            onDismiss = { choosingFolderFor = null },
            onSelected = { path ->
                choosingFolderFor = null
                if (path.isNotBlank()) scope.launch { controller.change(folderEntry, path) }
            },
        )
    }
}

@Composable
private fun SettingRow(
    entry: SemanticSettingEntry,
    enabled: Boolean,
    busy: Boolean,
    canPickFolder: Boolean,
    onChange: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    when (entry.kind) {
        SemanticSettingKind.Toggle -> {
            val checked = entry.value.toBooleanStrictOrNull() == true
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onChange(it.toString()) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.label, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator() else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        }
        SemanticSettingKind.Choice -> Column(Modifier.selectableGroup()) {
            SettingsHeading(entry.label)
            entry.options.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = option == entry.value,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onChange(option) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    RadioButton(selected = option == entry.value, onClick = null, enabled = enabled)
                    Text(option)
                }
            }
        }
        SemanticSettingKind.Folder -> if (canPickFolder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.titleSmall)
                    Text("/${entry.value.trim('/')}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onPickFolder, enabled = enabled) { Text("Choose folder") }
            }
        } else {
            SettingText(entry, enabled, onChange)
        }
        SemanticSettingKind.Text -> SettingText(entry, enabled, onChange)
    }
}

@Composable
private fun SettingText(entry: SemanticSettingEntry, enabled: Boolean, onChange: (String) -> Unit) {
    if (!entry.writable) {
        SettingsFact(entry.label, entry.value)
        return
    }
    var draft by remember(entry.key, entry.value) { mutableStateOf(entry.value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(entry.label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange(draft.trim()) }, enabled = enabled && draft.trim() != entry.value) {
            Text("Save")
        }
    }
}

@Composable
private fun SettingsFact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "Not set" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SettingsMessage(message: String, error: Boolean) {
    Text(
        message,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
    )
}
