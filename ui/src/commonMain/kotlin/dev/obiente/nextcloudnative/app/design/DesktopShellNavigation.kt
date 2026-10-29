package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.obiente.nextcloudnative.app.canonicalAppWorkspaceId

/** The same destinations and app selection remain reachable in either desktop width. */
@Composable
internal fun NextcloudDesktopNavigation(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    onOpenApp: (String) -> Unit,
    activeAppId: String?,
    expanded: Boolean,
    canExpand: Boolean,
    onToggleExpanded: () -> Unit,
    navigationEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val apps = nextcloudShellAppItems(identity, activeAppId)
    val highlight = rememberNextcloudSelectionHighlight(nextcloudShellDestinationKey(selected, activeAppId))
    val scrollState = rememberScrollState()
    val selection = nextcloudSelectionColors()
    // The highlight glides to a new app accent; the workspace switches under its entrance fade.
    val accentSpec = NextcloudMotion.accent<Color>(LocalNextcloudReducedMotion.current)
    val highlightFill by animateColorAsState(selection.fill, accentSpec, label = "sidebar highlight fill")
    val highlightOutline by animateColorAsState(selection.outline, accentSpec, label = "sidebar highlight outline")
    // The sidebar sits on the window background so the workspace panel reads as the focus.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = Color.Transparent,
    ) {
        BoxWithConstraints {
            // On short windows the utility/account area joins the scrollable navigation.
            val scrollFooter = maxHeight < 560.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (expanded) 10.dp else 6.dp)
                    .then(highlight.containerModifier())
                    .then(
                        highlight.drawModifier(
                            fill = highlightFill,
                            outline = highlightOutline,
                            cornerRadius = NextcloudRadii.Small,
                            alpha = if (navigationEnabled) 1f else 0.38f,
                            scrollState = scrollState,
                        ),
                    ),
            ) {
                DesktopNavigationHeader(expanded, canExpand, onToggleExpanded)
                Column(
                    Modifier.weight(1f).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DesktopNextcloudNavigationItems
                        .filter { it.destination != NextcloudDestination.Settings }
                        .forEach { item ->
                            DesktopNavigationEntry(
                                key = NextcloudShellDestinationKey.Root(item.destination),
                                highlight = highlight,
                                label = item.label,
                                shortcutHint = nextcloudDesktopShortcutHint(item.destination),
                                icon = NextcloudIcons.destination(item.destination),
                                isSelected = activeAppId == null && selected == item.destination,
                                expanded = expanded,
                                enabled = navigationEnabled,
                                onClick = { onSelected(item.destination) },
                            )
                        }
                    if (apps.pinned.isNotEmpty()) {
                        DesktopNavigationGroup("Pinned", expanded)
                        apps.pinned.forEach { app ->
                            DesktopAppEntry(app, activeAppId, expanded, navigationEnabled, highlight, onOpenApp)
                        }
                    }
                    apps.recent?.let { app ->
                        DesktopNavigationGroup("Recent", expanded)
                        DesktopAppEntry(app, activeAppId, expanded, navigationEnabled, highlight, onOpenApp)
                    }
                    apps.active?.takeIf { active ->
                        apps.pinned.none { desktopAppIdsMatch(it.id, active.id) } &&
                            apps.recent?.let { desktopAppIdsMatch(it.id, active.id) } != true
                    }?.let { app ->
                        DesktopNavigationGroup("Current app", expanded)
                        DesktopAppEntry(app, activeAppId, expanded, navigationEnabled, highlight, onOpenApp)
                    }
                    if (scrollFooter) {
                        DesktopNavigationFooter(identity, selected, activeAppId, expanded, navigationEnabled, highlight, onSelected)
                    }
                }
                if (!scrollFooter) {
                    DesktopNavigationFooter(identity, selected, activeAppId, expanded, navigationEnabled, highlight, onSelected)
                }
            }
        }
    }
}

@Composable
private fun DesktopNavigationHeader(expanded: Boolean, canExpand: Boolean, onToggleExpanded: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(8.dp) else Arrangement.Center,
    ) {
        if (expanded) {
            NativeBrandMark(modifier = Modifier.size(32.dp))
            Text(
                "nati.ve",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded || canExpand) {
            val label = if (expanded) "Collapse sidebar" else "Expand sidebar"
            DesktopNavigationTooltip(label) {
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(40.dp)) {
                    Icon(
                        NextcloudIcons.Menu,
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            NativeBrandMark(modifier = Modifier.padding(8.dp).size(32.dp), contentDescription = "nati.ve")
        }
    }
}

@Composable
private fun DesktopNavigationGroup(label: String, expanded: Boolean) {
    if (expanded) {
        Text(
            label.uppercase(),
            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun DesktopAppEntry(
    app: NextcloudDesktopSidebarApp,
    activeAppId: String?,
    expanded: Boolean,
    enabled: Boolean,
    highlight: NextcloudSelectionHighlightState<NextcloudShellDestinationKey>,
    onOpenApp: (String) -> Unit,
) {
    DesktopNavigationEntry(
        key = NextcloudShellDestinationKey.App(canonicalAppWorkspaceId(app.id)),
        highlight = highlight,
        label = app.label,
        icon = NextcloudIcons.app(app.id),
        isSelected = desktopAppIdsMatch(app.id, activeAppId),
        expanded = expanded,
        enabled = enabled,
        badge = app.badge,
        onClick = { if (!desktopAppIdsMatch(app.id, activeAppId)) onOpenApp(app.id) },
    )
}

@Composable
private fun DesktopNavigationEntry(
    key: NextcloudShellDestinationKey,
    highlight: NextcloudSelectionHighlightState<NextcloudShellDestinationKey>,
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    badge: String? = null,
    shortcutHint: String? = null,
    onClick: () -> Unit,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val selection = nextcloudSelectionColors()
    val iconColor by animateColorAsState(
        if (isSelected) selection.icon else selection.unselectedContent,
        NextcloudMotion.color(reducedMotion),
        label = "navigation entry icon",
    )
    val labelColor by animateColorAsState(
        if (isSelected) selection.selectedLabel else selection.unselectedContent,
        NextcloudMotion.color(reducedMotion),
        label = "navigation entry label",
    )
    DesktopNavigationTooltip(shortcutHint?.let { "$label ($it)" } ?: label) {
        NextcloudContextMenuArea(
            items = { listOf(NextcloudContextMenuItem("Open $label", enabled = enabled, onClick = onClick)) },
        ) {
            Row(
                // The shared highlight draws the selected background behind this row.
                modifier = Modifier.fillMaxWidth()
                    .then(highlight.entryModifier(key))
                    .alpha(if (enabled) 1f else 0.38f)
                    .clip(RoundedCornerShape(NextcloudRadii.Small))
                    .semantics(mergeDescendants = true) {
                        selected = isSelected
                        contentDescription = label
                    }
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open $label", onClick = onClick)
                    .heightIn(min = if (expanded) 40.dp else 52.dp)
                    .padding(horizontal = if (expanded) 12.dp else 2.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.spacedBy(12.dp) else Arrangement.Center,
            ) {
                if (expanded) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    badge?.let {
                        Box(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .heightIn(min = 20.dp).padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopNavigationFooter(
    account: NextcloudDesktopIdentity?,
    selected: NextcloudDestination,
    activeAppId: String?,
    expanded: Boolean,
    enabled: Boolean,
    highlight: NextcloudSelectionHighlightState<NextcloudShellDestinationKey>,
    onSelected: (NextcloudDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        if (expanded && account != null && (account.syncSummary != null || account.storageLabel != null)) {
            DesktopCloudStatus(account, enabled) { onSelected(NextcloudDestination.FolderSync) }
        }
        DesktopNavigationEntry(
            key = NextcloudShellDestinationKey.Root(NextcloudDestination.Settings),
            highlight = highlight,
            label = "Settings",
            shortcutHint = nextcloudDesktopShortcutHint(NextcloudDestination.Settings),
            icon = NextcloudIcons.Settings,
            isSelected = activeAppId == null && selected == NextcloudDestination.Settings,
            expanded = expanded,
            enabled = enabled,
            onClick = { onSelected(NextcloudDestination.Settings) },
        )
        account?.let {
            DesktopAccountEntry(it, expanded, enabled) { onSelected(NextcloudDestination.Settings) }
        }
    }
}

@Composable
private fun DesktopAccountEntry(account: NextcloudDesktopIdentity, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val actionLabel = "Account settings for ${account.displayName}"
    DesktopNavigationTooltip(actionLabel) {
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
                .clip(RoundedCornerShape(NextcloudRadii.Small))
                .semantics(mergeDescendants = true) { contentDescription = actionLabel }
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open account settings", onClick = onClick)
                .padding(horizontal = if (expanded) 8.dp else 0.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.spacedBy(10.dp) else Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(NextcloudTheme.colors.appIconContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (account.avatar != null) {
                    Image(
                        bitmap = account.avatar,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(NextcloudIcons.Profile, contentDescription = null, tint = NextcloudTheme.colors.appIcon, modifier = Modifier.size(19.dp))
                }
            }
            if (expanded) {
                Column(Modifier.weight(1f)) {
                    Text(account.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        account.cloudName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun DesktopCloudStatus(account: NextcloudDesktopIdentity, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(NextcloudRadii.Small))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open folder sync", onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NextcloudIcons.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    account.syncSummary ?: "Cloud storage",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            account.storageLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            account.storageProgress?.takeIf { it.isFinite() }?.let { progress ->
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopNavigationTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
