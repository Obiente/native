package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
    Administration("Administration", "Server apps and capabilities", NextcloudIcons.Apps),
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

@Composable
internal fun DesktopSettingsWorkspace(
    summary: SettingsWorkspaceSummary,
    initialSection: SettingsWorkspaceSection = SettingsWorkspaceSection.Account,
    content: @Composable ColumnScope.(SettingsWorkspaceSection) -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(initialSection.name) }
    val selected = SettingsWorkspaceSection.entries.firstOrNull { it.name == selectedName }
        ?: SettingsWorkspaceSection.Account

    Column(modifier = Modifier.fillMaxSize()) {
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
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(246.dp).fillMaxHeight().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "SETTINGS",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsWorkspaceSection.entries.forEach { section ->
                    SettingsSectionRow(
                        section = section,
                        selected = section == selected,
                        onClick = { selectedName = section.name },
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
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSummaryPane(summary = summary, modifier = Modifier.width(286.dp).fillMaxHeight())
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
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 10.dp),
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
        modifier = modifier.padding(NextcloudSpacing.Medium),
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
