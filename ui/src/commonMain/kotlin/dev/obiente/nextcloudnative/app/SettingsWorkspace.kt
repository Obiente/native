package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

internal enum class SettingsWorkspaceSection(
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    Account("Account", "Identity, server, and security", NextcloudIcons.Profile),
    Appearance("Appearance", "Theme and workspace presentation", NextcloudIcons.LightMode),
    SyncAndStorage("Sync & storage", "Folders, offline files, and transfers", NextcloudIcons.Cloud),
    NotificationsAndDevice("Notifications & device", "Permissions and background features", NextcloudIcons.Activity),
    DesktopApp("Desktop app", "Startup and local integration", NextcloudIcons.Settings),
    Updates("Updates", "Release channel and installation", NextcloudIcons.Refresh),
    Support("Support", "Create reports, review diagnostics, and follow up", NextcloudIcons.Activity),
    HelpAndGuides("Help & guides", "Learn workflows and find project help", NextcloudIcons.Info),
    Administration("Administration", "Server apps and capabilities", NextcloudIcons.Apps),
}

internal fun visibleSettingsSections(
    isDesktop: Boolean,
    hasDeviceSettings: Boolean,
    hasDesktopAppSettings: Boolean = isDesktop,
): List<SettingsWorkspaceSection> = SettingsWorkspaceSection.entries.filter { section ->
    when (section) {
        SettingsWorkspaceSection.DesktopApp -> isDesktop && hasDesktopAppSettings
        SettingsWorkspaceSection.NotificationsAndDevice -> hasDeviceSettings
        else -> true
    }
}

internal fun resolveSettingsWorkspaceSection(
    restoredSectionName: String?,
    visibleSections: List<SettingsWorkspaceSection>,
): SettingsWorkspaceSection {
    val compatibleName = when (restoredSectionName) {
        "Diagnostics" -> SettingsWorkspaceSection.Support.name
        else -> restoredSectionName
    }
    val restoredSection = SettingsWorkspaceSection.entries.firstOrNull { it.name == compatibleName }
    return restoredSection?.takeIf(visibleSections::contains)
        ?: visibleSections.firstOrNull()
        ?: SettingsWorkspaceSection.Account
}

internal data class SettingsWorkspaceSummary(
    val displayName: String,
    val cloudName: String,
    val serverUrl: String,
    val serverVersion: String?,
    val installedApps: Int,
    val connectionLabel: String = "Connected",
    val syncLabel: String = "Folder sync ready",
    val storageLabel: String? = null,
)

internal enum class SettingsWorkspaceMode {
    Compact,
    TwoPane,
    ThreePane,
}

internal data class SettingsWorkspaceLayout(
    val categoryWidthDp: Int,
    val showSummaryPane: Boolean,
    val mode: SettingsWorkspaceMode,
)

internal fun resolveSettingsWorkspaceLayout(availableWidthDp: Int): SettingsWorkspaceLayout {
    require(availableWidthDp >= 0) { "availableWidthDp must not be negative" }
    val mode = when {
        availableWidthDp < 600 -> SettingsWorkspaceMode.Compact
        availableWidthDp < 1_020 -> SettingsWorkspaceMode.TwoPane
        else -> SettingsWorkspaceMode.ThreePane
    }
    return SettingsWorkspaceLayout(
        categoryWidthDp = if (availableWidthDp < 820) 206 else 246,
        showSummaryPane = mode == SettingsWorkspaceMode.ThreePane,
        mode = mode,
    )
}

internal fun useExpandedSettingsWorkspace(isDesktop: Boolean, availableWidthDp: Int): Boolean {
    val layout = resolveSettingsWorkspaceLayout(availableWidthDp)
    return isDesktop || layout.mode != SettingsWorkspaceMode.Compact
}

@Composable
internal fun DesktopSettingsWorkspace(
    summary: SettingsWorkspaceSummary,
    visibleSections: List<SettingsWorkspaceSection>,
    selectedSection: SettingsWorkspaceSection?,
    onSectionSelected: (SettingsWorkspaceSection?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(SettingsWorkspaceSection) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Configure this device, account, and connected cloud",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Text(
                    summary.connectionLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = NextcloudTheme.colors.success,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = resolveSettingsWorkspaceLayout(maxWidth.value.toInt())
            if (layout.mode == SettingsWorkspaceMode.Compact) {
                MobileSettingsWorkspace(
                    visibleSections = visibleSections,
                    selectedSection = selectedSection,
                    onSectionSelected = onSectionSelected,
                    modifier = Modifier.fillMaxSize(),
                    showOverviewHeader = false,
                    content = content,
                )
                return@BoxWithConstraints
            }
            val selected = selectedSection?.takeIf(visibleSections::contains)
                ?: visibleSections.firstOrNull()
                ?: SettingsWorkspaceSection.Account
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.width(layout.categoryWidthDp.dp).fillMaxHeight()
                        .selectableGroup().verticalScroll(rememberScrollState())
                        .padding(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        "SETTINGS",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    visibleSections.forEach { section ->
                        SettingsSectionRow(
                            section = section,
                            selected = section == selected,
                            onClick = { onSectionSelected(section) },
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Text(selected.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        selected.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    content(selected)
                    Spacer(Modifier.height(NextcloudSpacing.Large))
                }
                if (layout.showSummaryPane) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSummaryPane(summary = summary, modifier = Modifier.width(286.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
internal fun MobileSettingsWorkspace(
    visibleSections: List<SettingsWorkspaceSection>,
    selectedSection: SettingsWorkspaceSection?,
    onSectionSelected: (SettingsWorkspaceSection?) -> Unit,
    modifier: Modifier = Modifier,
    showOverviewHeader: Boolean = true,
    content: @Composable ColumnScope.(SettingsWorkspaceSection) -> Unit,
) {
    val selected = selectedSection?.takeIf(visibleSections::contains)
    PlatformBackHandler(
        enabled = selected != null,
        onBack = { onSectionSelected(null) },
    )
    if (selected == null) {
        Column(modifier = modifier.fillMaxSize()) {
            if (showOverviewHeader) {
                ProductHeader("Settings")
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(visibleSections, key = SettingsWorkspaceSection::name) { section ->
                    SettingsActionCard(
                        title = section.title,
                        description = section.description,
                        icon = section.icon,
                        onClick = { onSectionSelected(section) },
                        modifier = Modifier.testTag("settings-overview-section-${section.name}"),
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            ScreenHeader(
                selected.title,
                selected.description,
                { onSectionSelected(null) },
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                content(selected)
                Spacer(Modifier.height(NextcloudSpacing.Large))
            }
        }
    }
}

@Composable
private fun SettingsSectionRow(
    section: SettingsWorkspaceSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(NextcloudRadii.Small))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .heightIn(min = 48.dp)
            .testTag("settings-desktop-section-${section.name}")
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .padding(horizontal = 11.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                section.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                section.description.substringBefore(','),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .74f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSummaryPane(summary: SettingsWorkspaceSummary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text("This account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(modifier = Modifier.size(38.dp), shape = CircleShape, color = NextcloudTheme.colors.appIconContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(NextcloudIcons.Profile, contentDescription = null, modifier = Modifier.size(21.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(summary.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            summary.cloudName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsSummaryFact("Status", summary.connectionLabel, success = true)
                SettingsSummaryFact("Server", summary.serverVersion?.let { "Nextcloud $it" } ?: "Nextcloud")
                SettingsSummaryFact("Apps", "${summary.installedApps} installed")
                SettingsSummaryFact("Files", summary.syncLabel, success = true)
                summary.storageLabel?.let { SettingsSummaryFact("Storage", it) }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
                Text("Server address", style = MaterialTheme.typography.labelMedium)
                Text(
                    summary.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsSummaryFact(label: String, value: String, success: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium,
            color = if (success) NextcloudTheme.colors.success else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun SettingsActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Small)) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
