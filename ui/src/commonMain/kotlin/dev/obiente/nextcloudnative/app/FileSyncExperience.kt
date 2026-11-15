package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
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

internal enum class FileSyncListFilter(val title: String) {
    All("All"),
    Syncing("Syncing"),
    Attention("Attention"),
    Paused("Paused"),
    Offline("Offline"),
}

internal enum class FileSyncSetupStep(val title: String) {
    Locations("Locations"),
    Direction("Direction"),
    Rules("What syncs"),
    Review("Review"),
}

private enum class FileSyncRulePreset(val title: String, val supportingText: String) {
    Everything("Everything", "Sync the whole folder without priority groups."),
    PhotoRawFirst("Photos and RAW first", "Ignore temporary previews and transfer RAW before JPEG."),
    ChooseFolders("Choose folders", "Sync only the folders and files you select."),
}

internal enum class FileSyncInspectorTab(val title: String) {
    Overview("Overview"),
    Activity("Activity"),
    Rules("Rules"),
    Settings("Settings"),
}

@Composable
internal fun GuidedAddFolderSyncDialog(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit = {},
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Medium)) {
            val compact = maxWidth < 720.dp
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FileSyncSetupSurface(
                localRoot = localRoot,
                mediaSuggestion = mediaSuggestion,
                remotePath = remotePath,
                configuration = configuration,
                mediaPreview = mediaPreview,
                mediaPreviewLoading = mediaPreviewLoading,
                mediaPreviewError = mediaPreviewError,
                busy = busy,
                onDismiss = onDismiss,
                onChooseDestination = onChooseDestination,
                onChooseSelectedPaths = onChooseSelectedPaths,
                onConfigurationChanged = onConfigurationChanged,
                onAdd = onAdd,
                    modifier = if (compact) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.widthIn(max = 920.dp).fillMaxWidth().heightIn(min = 620.dp, max = 760.dp)
                    },
                )
            }
        }
    }
}

@Composable
internal fun FileSyncSetupSurface(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit = {},
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    initialStep: FileSyncSetupStep = FileSyncSetupStep.Locations,
    initialAdvancedSettingsVisible: Boolean = false,
    syntheticScopeSummary: String? = null,
) {
    var stepName by rememberSaveable(localRoot.localRootId, initialStep.name) {
        mutableStateOf(initialStep.name)
    }
    val step = FileSyncSetupStep.entries.firstOrNull { it.name == stepName } ?: initialStep
    val setupStateHolder = rememberSaveableStateHolder()
    var deviceLabelDraft by rememberSaveable(localRoot.localRootId) { mutableStateOf(configuration.deviceLabel) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(NextcloudRadii.Large),
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val desktop = maxWidth >= 720.dp
            Column(Modifier.fillMaxSize()) {
                FileSyncSetupHeader(step = step, onDismiss = onDismiss, enabled = !busy)
                HorizontalDivider()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (desktop) {
                        FileSyncStepRail(current = step, onSelect = { stepName = it.name },
                            modifier = Modifier.width(210.dp).fillMaxHeight())
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (!desktop) {
                            FileSyncStepProgress(current = step, onSelect = { stepName = it.name })
                            HorizontalDivider()
                        }
                        setupStateHolder.SaveableStateProvider(step.name) {
                            FileSyncStepContent(
                                step = step, localRoot = localRoot, mediaSuggestion = mediaSuggestion,
                                remotePath = remotePath, configuration = configuration,
                                mediaPreview = mediaPreview, mediaPreviewLoading = mediaPreviewLoading,
                                mediaPreviewError = mediaPreviewError, onChooseDestination = onChooseDestination,
                                onChooseSelectedPaths = onChooseSelectedPaths, onConfigurationChanged = onConfigurationChanged,
                                deviceLabelDraft = deviceLabelDraft,
                                onDeviceLabelChanged = { value ->
                                    deviceLabelDraft = value.take(128)
                                    if (validFileSyncDeviceLabel(deviceLabelDraft)) {
                                        onConfigurationChanged(configuration.copy(deviceLabel = deviceLabelDraft))
                                    }
                                },
                                initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
                                syntheticScopeSummary = syntheticScopeSummary, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider()
                FileSyncSetupFooter(
                    step = step,
                    busy = busy,
                    validLabel = validFileSyncDeviceLabel(deviceLabelDraft),
                    mediaReady = isMediaFolderPreviewReady(mediaSuggestion, mediaPreview),
                    onBack = { stepName = FileSyncSetupStep.entries[step.ordinal - 1].name },
                    onNext = { stepName = FileSyncSetupStep.entries[step.ordinal + 1].name },
                    onAdd = onAdd,
                )
            }
        }
    }
}

@Composable
private fun FileSyncSetupHeader(step: FileSyncSetupStep, onDismiss: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Add sync", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Step ${step.ordinal + 1} of ${FileSyncSetupStep.entries.size}: ${step.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(enabled = enabled, onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun FileSyncStepRail(
    current: FileSyncSetupStep,
    onSelect: (FileSyncSetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        FileSyncSetupStep.entries.forEach { step ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(step) },
                color = if (step == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileSyncStepNumber(step = step, selected = step == current)
                    Text(step.title, fontWeight = if (step == current) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(NextcloudSpacing.Medium))
        Text(
            "Review each option before adding the sync. Setup never deletes existing files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileSyncStepProgress(current: FileSyncSetupStep, onSelect: (FileSyncSetupStep) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FileSyncSetupStep.entries.forEach { step ->
            Column(
                modifier = Modifier.clickable { onSelect(step) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FileSyncStepNumber(step, selected = step == current)
                Text(step.title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FileSyncStepNumber(step: FileSyncSetupStep, selected: Boolean) {
    Surface(
        color = animatedColor(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(999.dp),
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Text(
                (step.ordinal + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = animatedColor(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun FileSyncStepContent(
    step: FileSyncSetupStep,
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    configuration: FileSyncConfiguration,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    onChooseDestination: () -> Unit,
    onChooseSelectedPaths: () -> Unit,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    initialAdvancedSettingsVisible: Boolean,
    deviceLabelDraft: String,
    onDeviceLabelChanged: (String) -> Unit,
    syntheticScopeSummary: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        when (step) {
            FileSyncSetupStep.Locations -> FileSyncLocationsStep(
                localRoot = localRoot,
                mediaSuggestion = mediaSuggestion,
                remotePath = remotePath,
                mediaPreview = mediaPreview,
                mediaPreviewLoading = mediaPreviewLoading,
                mediaPreviewError = mediaPreviewError,
                onChooseDestination = onChooseDestination,
            )
            FileSyncSetupStep.Direction -> FileSyncDirectionStep(
                mediaSuggestion = mediaSuggestion,
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
            )
            FileSyncSetupStep.Rules -> FileSyncRulesStep(
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
                onChooseSelectedPaths = onChooseSelectedPaths,
                syntheticScopeSummary = syntheticScopeSummary,
            )
            FileSyncSetupStep.Review -> FileSyncReviewStep(
                localRoot = localRoot,
                remotePath = remotePath,
                configuration = configuration,
                onConfigurationChanged = onConfigurationChanged,
                initialAdvancedSettingsVisible = initialAdvancedSettingsVisible,
                deviceLabelDraft = deviceLabelDraft, onDeviceLabelChanged = onDeviceLabelChanged,
                syntheticScopeSummary = syntheticScopeSummary,
            )
        }
    }
}

@Composable
private fun FileSyncLocationsStep(
    localRoot: FileSyncLocalRoot,
    mediaSuggestion: MediaSyncFolderSuggestion?,
    remotePath: String,
    mediaPreview: MediaSyncFolderPreview?,
    mediaPreviewLoading: Boolean,
    mediaPreviewError: String?,
    onChooseDestination: () -> Unit,
) {
    FileSyncStepIntro("Where should changes go?", "Connect one folder on this device to one folder in Nextcloud.")
    FileSyncSetupLocationRow(
        icon = NextcloudIcons.FolderOpen,
        eyebrow = "Folder on this device",
        title = mediaSuggestion?.relativePath ?: localRoot.displayName,
        supporting = "Selected and kept under your control",
    )
    FileSyncSetupLocationRow(
        icon = NextcloudIcons.Cloud,
        eyebrow = "Folder in Nextcloud",
        title = if (remotePath.isBlank()) "Files root" else "/$remotePath",
        supporting = "This can have a different folder name",
        actionLabel = "Choose",
        onAction = onChooseDestination,
    )
    if (mediaSuggestion != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Small),
        ) {
            Column(Modifier.padding(NextcloudSpacing.Medium)) {
                Text("Media preview", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        mediaPreviewLoading -> "Checking the selected media folder..."
                        mediaPreviewError != null -> mediaPreviewError
                        mediaPreview != null -> "${mediaPreview.totalItems} items - ${formatByteSize(mediaPreview.totalBytes)}"
                        else -> "Preview will appear before sync is enabled."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mediaPreviewError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    FileSyncNotice("The first scan compares both locations before any transfer or deletion decision is made.")
}

@Composable
private fun FileSyncDirectionStep(
    mediaSuggestion: MediaSyncFolderSuggestion?,
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
) {
    FileSyncStepIntro("How should changes move?", "Choose the behavior that matches this folder.")
    val options = if (mediaSuggestion == null) FileSyncDirection.entries else listOf(FileSyncDirection.UploadOnly)
    options.forEach { direction ->
        FileSyncChoiceCard(
            selected = configuration.direction == direction,
            title = direction.syncDirectionTitle(),
            supporting = direction.syncDirectionDescription(),
            icon = when (direction) {
                FileSyncDirection.Bidirectional -> NextcloudIcons.Refresh
                FileSyncDirection.UploadOnly -> NextcloudIcons.Cloud
                FileSyncDirection.DownloadOnly -> NextcloudIcons.FolderOpen
            },
            onClick = { onConfigurationChanged(configuration.copy(direction = direction)) },
        )
    }
    if (mediaSuggestion != null) {
        FileSyncNotice("Detected media folders upload only. Nextcloud never writes back into the device's photo library.")
    }
}

@Composable
private fun FileSyncRulesStep(
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    onChooseSelectedPaths: () -> Unit,
    syntheticScopeSummary: String?,
) {
    var customEditorVisible by rememberSaveable {
        mutableStateOf(configuration.selectedPaths.isNotEmpty())
    }
    FileSyncStepIntro("Choose what syncs first", "Start with a safe preset, then refine it only if you need to.")
    FileSyncRulePreset.entries.forEach { preset ->
        val selected = preset.matches(configuration)
        FileSyncChoiceCard(
            selected = selected,
            title = preset.title,
            supporting = preset.supportingText,
            icon = when (preset) {
                FileSyncRulePreset.Everything -> NextcloudIcons.Folder
                FileSyncRulePreset.PhotoRawFirst -> NextcloudIcons.Photo
                FileSyncRulePreset.ChooseFolders -> NextcloudIcons.CheckCircle
            },
            onClick = {
                val next = preset.applyTo(configuration)
                customEditorVisible = preset == FileSyncRulePreset.ChooseFolders
                onConfigurationChanged(next)
            },
        )
    }
    FileSyncScopeSummary(configuration, syntheticScopeSummary)
    TextButton(onClick = { customEditorVisible = !customEditorVisible }) {
        Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NextcloudSpacing.Small))
        Text(if (customEditorVisible) "Hide custom rules" else "Customize rules")
    }
    if (customEditorVisible) {
        StructuredFileSyncRulesEditor(configuration, onConfigurationChanged, onChooseSelectedPaths)
    }
}

@Composable
private fun FileSyncReviewStep(
    localRoot: FileSyncLocalRoot,
    remotePath: String,
    configuration: FileSyncConfiguration,
    onConfigurationChanged: (FileSyncConfiguration) -> Unit,
    initialAdvancedSettingsVisible: Boolean,
    deviceLabelDraft: String,
    onDeviceLabelChanged: (String) -> Unit,
    syntheticScopeSummary: String?,
) {
    var advancedVisible by rememberSaveable(initialAdvancedSettingsVisible) {
        mutableStateOf(initialAdvancedSettingsVisible)
    }
    FileSyncStepIntro("Review and start safely", "The first scan creates a plan. Conflicts and deletions still require your chosen policy.")
    FileSyncReviewRow("This device", localRoot.displayName)
    FileSyncReviewRow("Nextcloud", if (remotePath.isBlank()) "Files root" else "/$remotePath")
    FileSyncReviewRow("Direction", configuration.direction.syncDirectionTitle())
    FileSyncReviewRow("Conflicts", configuration.conflictPolicy.syncConflictTitle())
    FileSyncReviewRow("Deletions", configuration.deletionPolicy.syncDeletionTitle())
    FileSyncScopeSummary(configuration, syntheticScopeSummary)
    TextButton(onClick = { advancedVisible = !advancedVisible }) {
        Icon(NextcloudIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NextcloudSpacing.Small))
        Text(if (advancedVisible) "Hide safety settings" else "Safety, network, and power")
    }
    if (advancedVisible) {
        FileSyncAdvancedSettings(configuration, onConfigurationChanged, deviceLabelDraft, onDeviceLabelChanged)
    }
    FileSyncNotice("If the app closes or the network drops, completed work is preserved and the remaining queue resumes safely.")
}

@Composable
private fun FileSyncStepIntro(title: String, supporting: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileSyncSetupLocationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    eyebrow: String,
    title: String,
    supporting: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun FileSyncChoiceCard(
    selected: Boolean,
    title: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
        color = animatedColor(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(NextcloudRadii.Card),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(NextcloudIcons.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FileSyncScopeSummary(configuration: FileSyncConfiguration, syntheticScopeSummary: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Scope preview", style = MaterialTheme.typography.labelLarge)
            syntheticScopeSummary?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (configuration.selectedPaths.isEmpty()) "Whole folder included" else "${configuration.selectedPaths.size} selected paths",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${configuration.ignoredPatterns.size} ignore rules", style = MaterialTheme.typography.bodySmall)
            Text(
                if (configuration.priorityRules.isEmpty()) "Normal transfer order" else configuration.priorityRules.joinToString(
                    prefix = "Priority: ",
                    separator = " then ",
                    transform = { it.pattern.fileSyncFriendlyPattern() },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FileSyncReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.width(104.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FileSyncSetupFooter(
    step: FileSyncSetupStep,
    busy: Boolean,
    validLabel: Boolean,
    mediaReady: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != FileSyncSetupStep.Locations) {
            OutlinedButton(enabled = !busy, onClick = onBack) { Text("Back") }
        }
        if (step != FileSyncSetupStep.Review) {
            Button(enabled = !busy, onClick = onNext) { Text("Continue") }
        } else {
            Button(
                enabled = !busy && validLabel && mediaReady,
                onClick = onAdd,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Add sync")
            }
        }
    }
}

private fun FileSyncRulePreset.matches(configuration: FileSyncConfiguration): Boolean = when (this) {
    FileSyncRulePreset.Everything -> configuration.selectedPaths.isEmpty() &&
        configuration.ignoredPatterns.isEmpty() && configuration.priorityRules.isEmpty()
    FileSyncRulePreset.PhotoRawFirst -> configuration.selectedPaths.isEmpty() &&
        configuration.priorityRules.map(FileSyncPriorityRule::pattern) == listOf("**/*.raf", "**/*.jpg", "**/*.jpeg")
    FileSyncRulePreset.ChooseFolders -> configuration.selectedPaths.isNotEmpty()
}

private fun FileSyncRulePreset.applyTo(configuration: FileSyncConfiguration): FileSyncConfiguration = when (this) {
    FileSyncRulePreset.Everything -> configuration.copy(
        selectedPaths = emptyList(),
        ignoredPatterns = emptyList(),
        priorityRules = emptyList(),
    )
    FileSyncRulePreset.PhotoRawFirst -> configuration.copy(
        selectedPaths = emptyList(),
        ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
        priorityRules = listOf(
            FileSyncPriorityRule("**/*.raf"),
            FileSyncPriorityRule("**/*.jpg"),
            FileSyncPriorityRule("**/*.jpeg"),
        ),
    )
    FileSyncRulePreset.ChooseFolders -> configuration
}

internal fun FileSyncDirection.syncDirectionTitle(): String = when (this) {
    FileSyncDirection.Bidirectional -> "Two-way"
    FileSyncDirection.UploadOnly -> "Device to Nextcloud"
    FileSyncDirection.DownloadOnly -> "Nextcloud to device"
}

internal fun FileSyncDirection.syncDirectionDescription(): String = when (this) {
    FileSyncDirection.Bidirectional -> "Changes on either side are copied to the other side."
    FileSyncDirection.UploadOnly -> "Changes from this device upload; Nextcloud never writes back."
    FileSyncDirection.DownloadOnly -> "Changes from Nextcloud download; local changes never upload."
}

internal fun FileSyncDirection.syncDirectionGlyph(): String = when (this) {
    FileSyncDirection.Bidirectional -> "<->"
    FileSyncDirection.UploadOnly -> "->"
    FileSyncDirection.DownloadOnly -> "<-"
}

internal fun FileSyncPairSummary.queuedLabel(): String = when {
    runningCount > 0 -> "$runningCount active"
    readyCount > 0 -> "$readyCount queued"
    else -> "None"
}

internal fun FileSyncPairSummary.selectionSummary(): String = if (configuration.selectedPaths.isEmpty()) {
    "Everything in this folder"
} else {
    "${configuration.selectedPaths.size} selected paths"
}

internal fun FileSyncPairSummary.ignoreSummary(): String = if (configuration.ignoredPatterns.isEmpty()) {
    "No ignored patterns"
} else {
    "Ignore ${configuration.ignoredPatterns.size} patterns"
}

internal fun FileSyncPairSummary.prioritySummary(): String = if (configuration.priorityRules.isEmpty()) {
    "Normal transfer priority"
} else {
    configuration.priorityRules.joinToString(
        prefix = "Priority: ",
        separator = " then ",
        transform = { it.pattern.fileSyncFriendlyPattern() },
    )
}

internal fun String.fileSyncFriendlyPattern(): String = when (lowercase()) {
    "**/*.raf" -> "RAW (.raf)"
    "**/*.jpg" -> "JPEG (.jpg)"
    "**/*.jpeg" -> "JPEG (.jpeg)"
    else -> this
}

internal fun FileSyncConflictPolicy.syncConflictTitle(): String = when (this) {
    FileSyncConflictPolicy.Ask -> "Ask before changing either copy"
    FileSyncConflictPolicy.KeepBoth -> "Keep both copies"
    FileSyncConflictPolicy.PreferLocal -> "Prefer this device"
    FileSyncConflictPolicy.PreferRemote -> "Prefer Nextcloud"
}

internal fun FileSyncDeletionPolicy.syncDeletionTitle(): String = when (this) {
    FileSyncDeletionPolicy.Ask -> "Ask before deleting the other copy"
    FileSyncDeletionPolicy.Propagate -> "Delete the other copy"
    FileSyncDeletionPolicy.RestoreMissing -> "Restore the missing copy"
}

internal fun FileSyncNetworkPolicy.syncNetworkTitle(): String = when (this) {
    FileSyncNetworkPolicy.AnyConnection -> "Wi-Fi or mobile data"
    FileSyncNetworkPolicy.Unmetered -> "Unmetered network only"
}

internal fun FileSyncPowerPolicy.syncPowerTitle(): String = when (this) {
    FileSyncPowerPolicy.AnyPower -> "Any battery level"
    FileSyncPowerPolicy.BatteryNotLow -> "Pause when battery is low"
    FileSyncPowerPolicy.Charging -> "Only while charging"
}
