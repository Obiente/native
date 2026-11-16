package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun StructuredFileSyncRulesEditor(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onChooseSelectedPaths: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
        FileSyncSelectionEditor(
            values = configuration.selectedPaths,
            onChoose = onChooseSelectedPaths,
            onRemove = { removed ->
                onConfigurationChanged(
                    configuration.copy(selectedPaths = configuration.selectedPaths - removed),
                )
            },
        )
        FileSyncRuleListEditor(
            title = "Ignored files",
            supporting = "Temporary and generated files never enter the queue.",
            placeholder = "**/Cache/**",
            values = configuration.ignoredPatterns,
            validate = { value -> runCatching { requireValidFileSyncGlob(value) }.isSuccess },
            onValuesChanged = { onConfigurationChanged(configuration.copy(ignoredPatterns = it)) },
        )
        FileSyncRuleListEditor(
            title = "Transfer priority",
            supporting = "Higher rows transfer first. This does not skip lower rows.",
            placeholder = "**/*.raf",
            values = configuration.priorityRules.map(FileSyncPriorityRule::pattern),
            validate = { value -> runCatching { requireValidFileSyncGlob(value) }.isSuccess },
            reorderable = true,
            onValuesChanged = { values ->
                onConfigurationChanged(configuration.copy(priorityRules = values.map(::FileSyncPriorityRule)))
            },
        )
    }
}

@Composable
private fun FileSyncSelectionEditor(
    values: List<String>,
    onChoose: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text("Selected folders and files", style = MaterialTheme.typography.labelLarge)
        Text(
            "Choose verified items from the mapped Nextcloud folder. Leave empty to include everything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        values.forEach { value ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(start = NextcloudSpacing.Medium, end = NextcloudSpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NextcloudIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(NextcloudSpacing.Small))
                    Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onRemove(value) }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = onChoose) {
            Icon(NextcloudIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(NextcloudSpacing.Small))
            Text(if (values.isEmpty()) "Choose folders or files" else "Change selection")
        }
    }
}

@Composable
private fun FileSyncRuleListEditor(
    title: String,
    supporting: String,
    placeholder: String,
    values: List<String>,
    validate: (String) -> Boolean,
    reorderable: Boolean = false,
    onValuesChanged: (List<String>) -> Unit,
) {
    var draft by rememberSaveable(title) { mutableStateOf("") }
    val normalizedDraft = draft.trim()
    val canAdd = normalizedDraft.isNotEmpty() && normalizedDraft !in values && validate(normalizedDraft)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        values.forEachIndexed { index, value ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(start = NextcloudSpacing.Medium, end = NextcloudSpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value.fileSyncFriendlyPattern(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (reorderable && index > 0) {
                        TextButton(onClick = {
                            val next = values.toMutableList()
                            val moved = next.removeAt(index)
                            next.add(index - 1, moved)
                            onValuesChanged(next)
                        }) { Text("Up") }
                    }
                    if (reorderable && index < values.lastIndex) {
                        TextButton(onClick = {
                            val next = values.toMutableList()
                            val moved = next.removeAt(index)
                            next.add(index + 1, moved)
                            onValuesChanged(next)
                        }) { Text("Down") }
                    }
                    TextButton(onClick = { onValuesChanged(values.filterIndexed { itemIndex, _ -> itemIndex != index }) }) {
                        Text("Remove")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1_024) },
                modifier = Modifier.weight(1f),
                label = { Text("Add rule") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                isError = normalizedDraft.isNotEmpty() && !canAdd,
            )
            Button(
                enabled = canAdd,
                onClick = {
                    onValuesChanged(values + normalizedDraft)
                    draft = ""
                },
            ) { Text("Add") }
        }
    }
}

@Composable
internal fun FileSyncAdvancedSettings(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    deviceLabelDraft: String,
    onDeviceLabelChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
        FileSyncSettingChoices(
            title = "When both copies changed",
            options = FileSyncConflictPolicy.entries,
            selected = configuration.conflictPolicy,
            label = FileSyncConflictPolicy::syncConflictTitle,
            onSelected = { onConfigurationChanged(configuration.copy(conflictPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "When a file was deleted",
            options = FileSyncDeletionPolicy.entries,
            selected = configuration.deletionPolicy,
            label = FileSyncDeletionPolicy::syncDeletionTitle,
            onSelected = { onConfigurationChanged(configuration.copy(deletionPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "Connection",
            options = FileSyncNetworkPolicy.entries,
            selected = configuration.networkPolicy,
            label = FileSyncNetworkPolicy::syncNetworkTitle,
            onSelected = { onConfigurationChanged(configuration.copy(networkPolicy = it)) },
        )
        FileSyncSettingChoices(
            title = "Power",
            options = FileSyncPowerPolicy.entries,
            selected = configuration.powerPolicy,
            label = FileSyncPowerPolicy::syncPowerTitle,
            onSelected = { onConfigurationChanged(configuration.copy(powerPolicy = it)) },
        )
        OutlinedTextField(
            value = deviceLabelDraft,
            onValueChange = onDeviceLabelChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device label for conflict copies") },
            singleLine = true,
            isError = !validFileSyncDeviceLabel(deviceLabelDraft),
            supportingText = if (!validFileSyncDeviceLabel(deviceLabelDraft)) {
                { Text("Enter a device label before continuing.") }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun <T> FileSyncSettingChoices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        options.forEach { option ->
            FileSyncChoiceCard(
                selected = option == selected,
                enabled = enabled,
                title = label(option),
                supporting = if (option == selected) "Selected" else "Tap to select",
                icon = if (option == selected) NextcloudIcons.CheckCircle else NextcloudIcons.Info,
                onClick = { onSelected(option) },
            )
        }
    }
}

