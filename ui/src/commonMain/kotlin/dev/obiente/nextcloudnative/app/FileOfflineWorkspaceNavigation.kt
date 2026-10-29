package dev.obiente.nextcloudnative.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudSelectionColors
import dev.obiente.nextcloudnative.app.design.rememberNextcloudSelectionHighlight

@Composable
internal fun FileOfflineWorkspaceNavigation(
    selected: FileOfflineWorkspaceSection,
    onSelected: (FileOfflineWorkspaceSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One pill glides between sections, like the app sidebar, instead of each row repainting.
    val highlight = rememberNextcloudSelectionHighlight(selected)
    val selection = nextcloudSelectionColors()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().selectableGroup().padding(NextcloudSpacing.Medium)
                .then(highlight.containerModifier())
                .then(highlight.drawModifier(selection.fill, selection.outline, NextcloudRadii.Small)),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                "SYNC WORKSPACE",
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.Small),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FileOfflineWorkspaceSection.entries.forEach { section ->
                val isSelected = selected == section
                val content by animateColorAsState(
                    if (isSelected) selection.selectedLabel else MaterialTheme.colorScheme.onSurface,
                    NextcloudMotion.color(LocalNextcloudReducedMotion.current),
                    label = "offline section content",
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().then(highlight.entryModifier(section))
                        .selectable(isSelected, role = Role.Tab, onClick = { onSelected(section) }),
                    color = Color.Transparent,
                    contentColor = content,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NextcloudSpacing.Medium,
                            vertical = NextcloudSpacing.Medium,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            section.fileOfflineWorkspaceIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(section.title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(NextcloudSpacing.Medium))
            Text(
                "Folder mappings and offline copies are managed separately, so each workspace can use the screen well.",
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FileOfflineWorkspaceTabs(
    selected: FileOfflineWorkspaceSection,
    onSelected: (FileOfflineWorkspaceSection) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(FileOfflineWorkspaceSection.entries, key = FileOfflineWorkspaceSection::name) { section ->
            NextcloudFilterChip(
                selected = selected == section,
                onClick = { onSelected(section) },
                label = { Text(section.title) },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        section.fileOfflineWorkspaceIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

private fun FileOfflineWorkspaceSection.fileOfflineWorkspaceIcon() = when (this) {
    FileOfflineWorkspaceSection.FolderSync -> NextcloudIcons.Refresh
    FileOfflineWorkspaceSection.OfflineFiles -> NextcloudIcons.FolderOpen
    FileOfflineWorkspaceSection.VirtualFiles -> NextcloudIcons.Cloud
}
