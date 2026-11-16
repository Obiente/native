package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import kotlinx.serialization.encodeToString

internal fun FileSyncConfiguration.settingsRevision(): String =
    publicContentSha256(kotlinx.serialization.json.Json.encodeToString(this).encodeToByteArray())

internal fun validFileSyncDeviceLabel(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)

/** Only small policy values and a non-secret digest enter Android saved state. */
@Composable
internal fun FileSyncSettingsEditor(
    pair: FileSyncPairSummary,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    saveEnabled: Boolean = true,
    onSave: (FileSyncConfiguration, FileSyncConfiguration) -> Unit,
) {
    val original = pair.configuration
    val currentRevision = remember(original) { original.settingsRevision() }
    val reviewedRevision = rememberSaveable(pair.id) { currentRevision }
    var direction by rememberSaveable(pair.id) { mutableStateOf(original.direction) }
    var conflict by rememberSaveable(pair.id) { mutableStateOf(original.conflictPolicy) }
    var deletion by rememberSaveable(pair.id) { mutableStateOf(original.deletionPolicy) }
    var network by rememberSaveable(pair.id) { mutableStateOf(original.networkPolicy) }
    var power by rememberSaveable(pair.id) { mutableStateOf(original.powerPolicy) }
    var label by rememberSaveable(pair.id) { mutableStateOf(original.deviceLabel) }
    var confirmSave by rememberSaveable(pair.id) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(pair.id) { mutableStateOf(false) }
    val validLabel = validFileSyncDeviceLabel(label)
    fun hasChanges() = direction != original.direction || conflict != original.conflictPolicy ||
        deletion != original.deletionPolicy || network != original.networkPolicy || power != original.powerPolicy || label != original.deviceLabel
    val stale = reviewedRevision != currentRevision
    val canSave = saveEnabled && !busy && !stale && validLabel && hasChanges() && direction in pair.allowedDirections
    val dismiss = { if (!busy) { if (hasChanges()) confirmDiscard = true else onDismiss() } }
    NextcloudDetailSheet(title = "Edit sync settings", onDismiss = dismiss, closeLabel = "Cancel settings changes",
        bodyHeightFraction = 0.94f, canDismiss = { !busy && !hasChanges() }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(pair.localDisplayName, style = MaterialTheme.typography.titleLarge)
            if (stale) Text("Sync settings changed. Close this editor and review them before saving again.", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(value = label, onValueChange = { label = it.take(128) },
                enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Device label for conflict copies") }, isError = !validLabel,
                supportingText = { if (!validLabel) Text("Enter a device label without control characters.") })
            FileSyncSettingChoices("Direction", FileSyncDirection.entries.filter { it in pair.allowedDirections }, direction,
                FileSyncDirection::syncDirectionTitle, onSelected = { direction = it }, enabled = !busy)
            Text(direction.syncDirectionDescription(), style = MaterialTheme.typography.bodySmall)
            FileSyncSettingChoices("When both copies changed", FileSyncConflictPolicy.entries, conflict,
                FileSyncConflictPolicy::syncConflictTitle, onSelected = { conflict = it }, enabled = !busy)
            FileSyncSettingChoices("When a file was deleted", FileSyncDeletionPolicy.entries, deletion,
                FileSyncDeletionPolicy::syncDeletionTitle, onSelected = { deletion = it }, enabled = !busy)
            FileSyncSettingChoices("Connection", FileSyncNetworkPolicy.entries, network,
                FileSyncNetworkPolicy::syncNetworkTitle, onSelected = { network = it }, enabled = !busy)
            FileSyncSettingChoices("Power", FileSyncPowerPolicy.entries, power,
                FileSyncPowerPolicy::syncPowerTitle, onSelected = { power = it }, enabled = !busy)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") }
            Button(onClick = { confirmSave = true }, enabled = canSave) { Text(if (busy) "Saving..." else "Save changes") }
        }
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { if (!busy) confirmSave = false }, title = { Text("Apply sync settings?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(direction.syncDirectionTitle())
            Text(conflict.syncConflictTitle())
            Text(deletion.syncDeletionTitle())
            Text("The next check will rebuild queued work with these settings. Review overwrite and deletion choices carefully.")
        } },
        confirmButton = { Button(enabled = canSave, onClick = {
            confirmSave = false
            onSave(original, original.copy(direction = direction, conflictPolicy = conflict, deletionPolicy = deletion,
                networkPolicy = network, powerPolicy = power, deviceLabel = label))
        }) { Text("Apply changes") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }, enabled = !busy) { Text("Keep editing") } },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("Discard settings changes?") },
        text = { Text("Your saved sync settings will stay the same.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
}
