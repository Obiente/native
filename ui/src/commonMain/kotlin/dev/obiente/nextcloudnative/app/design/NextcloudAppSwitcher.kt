package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.canonicalAppWorkspaceId

/** Navigation only. Callers retain ownership of draft guards and per-app navigation history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextcloudAppSwitcher(
    identity: NextcloudDesktopIdentity?,
    activeAppId: String?,
    selectedDestination: NextcloudDestination,
    enabled: Boolean,
    onOpenApp: (String) -> Unit,
    onSelected: (NextcloudDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        NextcloudAppSwitcherContent(
            identity, activeAppId, selectedDestination, enabled, onOpenApp, onSelected, onDismiss,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        )
    }
}

@Composable
internal fun NextcloudAppSwitcherContent(
    identity: NextcloudDesktopIdentity?,
    activeAppId: String?,
    selectedDestination: NextcloudDestination,
    enabled: Boolean,
    onOpenApp: (String) -> Unit,
    onSelected: (NextcloudDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(identity?.accountScopeKey) { mutableStateOf("") }
    val apps = nextcloudShellAppItems(identity, activeAppId)
    LazyColumn(modifier.heightIn(max = 640.dp)) {
        item(key = "switcher-heading") {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Switch app", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    identity?.cloudName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
        if (apps.ordered.size > 7 || query.isNotEmpty()) {
            item(key = "app-search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Find an app") },
                    leadingIcon = { Icon(NextcloudIcons.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        fun group(label: String, entries: List<NextcloudDesktopSidebarApp>) {
            val matches = filterNextcloudShellApps(entries, query)
            if (matches.isEmpty()) return
            item(key = "heading:$label") {
                Text(label, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(matches, key = { "app:${canonicalAppWorkspaceId(it.id)}" }) { app ->
                val active = desktopAppIdsMatch(app.id, activeAppId)
                ShellSwitcherRow(
                    app.label, NextcloudIcons.app(app.id), active, enabled,
                    "Switch to ${app.label}", if (active) "Current" else app.badge,
                ) {
                    onDismiss()
                    if (!active) onOpenApp(app.id)
                }
            }
        }
        group("Pinned", apps.pinned)
        group("Recent", listOfNotNull(apps.recent))
        group("Current", listOfNotNull(apps.active).filter { active ->
            (apps.pinned + listOfNotNull(apps.recent)).none { desktopAppIdsMatch(it.id, active.id) }
        })
        if (query.isNotBlank() || apps.pinned.isEmpty()) group("All apps", apps.others)
        if (filterNextcloudShellApps(apps.ordered, query).isEmpty()) {
            item(key = "empty") {
                Text(if (query.isBlank()) "Open Apps to find your installed workspaces." else "No matching apps",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "workspace-actions") {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ShellSwitcherRow(
                "Folder sync", NextcloudIcons.Folder, selectedDestination == NextcloudDestination.FolderSync,
                enabled, "Open Folder sync", null,
            ) { onDismiss(); onSelected(NextcloudDestination.FolderSync) }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onDismiss(); onSelected(NextcloudDestination.Apps) }, enabled = enabled) {
                    Text("Browse apps")
                }
                TextButton(onClick = { onDismiss(); onSelected(NextcloudDestination.Settings) }, enabled = enabled) {
                    Text("Account settings")
                }
            }
        }
    }
}

@Composable
private fun ShellSwitcherRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean,
    description: String,
    badge: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .semantics { contentDescription = description; selected = active },
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
