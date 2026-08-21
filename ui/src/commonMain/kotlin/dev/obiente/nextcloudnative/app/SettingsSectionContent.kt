package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Immutable
internal data class SettingsAccountSectionState(
    val displayName: String,
    val serverAddress: String,
    val serverVersionLabel: String,
    val accountLabel: String = "Primary account",
    val securityDescription: String =
        "This device uses an app password. Signing out revokes its access without changing other sessions.",
    val trustedCertificate: SettingsTrustedCertificateState? = null,
    val signingOut: Boolean = false,
    val signOutError: String? = null,
)

@Immutable
internal data class SettingsTrustedCertificateState(
    val label: String,
    val description: String,
    val removing: Boolean = false,
    val removalError: String? = null,
)

@Composable
internal fun SettingsAccountSectionContent(
    state: SettingsAccountSectionState,
    onRemoveTrustedCertificate: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    Icon(
                        NextcloudIcons.Profile,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        state.serverAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.serverVersionLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    state.accountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text("Security", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.securityDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.trustedCertificate?.let { certificate ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(certificate.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        certificate.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        modifier = Modifier.heightIn(min = MinimumSettingsTargetDp),
                        enabled = !certificate.removing,
                        onClick = onRemoveTrustedCertificate,
                    ) {
                        Text(if (certificate.removing) "Removing trust..." else "Remove trusted certificate")
                    }
                    if (certificate.removing) {
                        SettingsLiveMessage("Removing the trusted certificate.", SettingsMessagePriority.Polite)
                    }
                    certificate.removalError?.let { message ->
                        SettingsLiveMessage(message, SettingsMessagePriority.Assertive)
                    }
                }
                OutlinedButton(
                    modifier = Modifier.heightIn(min = MinimumSettingsTargetDp),
                    enabled = !state.signingOut,
                    onClick = onSignOut,
                ) {
                    Icon(NextcloudIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(NextcloudSpacing.Small))
                    Text(if (state.signingOut) "Signing out..." else "Sign out and revoke access")
                }
                if (state.signingOut) {
                    SettingsLiveMessage("Signing out and revoking this device's access.", SettingsMessagePriority.Polite)
                }
                state.signOutError?.let { message ->
                    SettingsLiveMessage(message, SettingsMessagePriority.Assertive)
                }
            }
        }
    }
}

@Composable
internal fun SettingsAppearanceSectionContent(
    selectedTheme: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text("Color theme", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            ThemePreference.entries.forEach { preference ->
                FilterChip(
                    modifier = Modifier.heightIn(min = MinimumSettingsTargetDp),
                    selected = selectedTheme == preference,
                    onClick = { onThemeSelected(preference) },
                    label = { Text(preference.name) },
                    leadingIcon = {
                        Icon(
                            when (preference) {
                                ThemePreference.System -> NextcloudIcons.SystemMode
                                ThemePreference.Light -> NextcloudIcons.LightMode
                                ThemePreference.Dark -> NextcloudIcons.DarkMode
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
            ) {
                Text("Designed for this screen", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The workspace keeps platform navigation conventions and adapts its content to the available window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal enum class SettingsSyncAction {
    OpenSyncWorkspace,
    OpenMediaTransfers,
    OpenOfflineAvailability,
}

@Immutable
internal data class SettingsSyncAndStorageSectionState(
    val workspaceDescription: String,
    val workspaceStatus: String? = null,
    val mediaTransfersVisible: Boolean,
)

@Composable
internal fun SettingsSyncAndStorageSectionContent(
    state: SettingsSyncAndStorageSectionState,
    onAction: (SettingsSyncAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        SettingsSectionActionRow(
            title = "Folder sync workspace",
            description = state.workspaceDescription,
            icon = NextcloudIcons.Cloud,
            trailing = state.workspaceStatus,
            onClick = { onAction(SettingsSyncAction.OpenSyncWorkspace) },
        )
        if (state.mediaTransfersVisible) {
            SettingsSectionActionRow(
                title = "Media transfers",
                description = "Review pending, active, failed, and completed uploads",
                icon = NextcloudIcons.Refresh,
                onClick = { onAction(SettingsSyncAction.OpenMediaTransfers) },
            )
        }
        SettingsSectionActionRow(
            title = "Offline availability",
            description = "Choose what stays available when this device is offline",
            icon = NextcloudIcons.FolderOpen,
            onClick = { onAction(SettingsSyncAction.OpenOfflineAvailability) },
        )
    }
}

@Immutable
internal data class SettingsDeviceFeatureId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@Immutable
internal data class SettingsDeviceFeatureItem(
    val id: SettingsDeviceFeatureId,
    val label: String,
    val description: String,
    val statusLabel: String,
    val actionLabel: String? = null,
    val statusIsSuccess: Boolean = false,
) {
    init {
        require(label.isNotBlank())
        require(description.isNotBlank())
        require(statusLabel.isNotBlank())
        require(actionLabel == null || actionLabel.isNotBlank())
    }
}

@Composable
internal fun SettingsDeviceFeaturesSectionContent(
    features: List<SettingsDeviceFeatureItem>,
    onFeatureAction: (SettingsDeviceFeatureId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        if (features.isEmpty()) {
            Text(
                "No device permissions are required on this platform.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        features.forEach { feature ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumSettingsTargetDp)
                        .padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NextcloudIcons.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(feature.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            feature.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    feature.actionLabel?.let { actionLabel ->
                        TextButton(
                            modifier = Modifier.heightIn(min = MinimumSettingsTargetDp),
                            onClick = { onFeatureAction(feature.id) },
                        ) {
                            Text(actionLabel)
                        }
                    } ?: Text(
                        feature.statusLabel,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (feature.statusIsSuccess) {
                            NextcloudTheme.colors.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal enum class SettingsDesktopPreferenceId {
    KeepRunningInBackground,
    StartOnLogin,
}

@Immutable
internal data class SettingsDesktopPreferenceItem(
    val id: SettingsDesktopPreferenceId,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    init {
        require(title.isNotBlank())
        require(description.isNotBlank())
        require(message == null || message.isNotBlank())
    }
}

internal fun settingsDesktopPreferences(
    keepRunningInBackground: Boolean?,
    startOnLogin: Boolean?,
    startOnLoginMessage: String? = null,
): List<SettingsDesktopPreferenceItem> = buildList {
    keepRunningInBackground?.let { enabled ->
        add(
            SettingsDesktopPreferenceItem(
                SettingsDesktopPreferenceId.KeepRunningInBackground,
                "Keep running in background",
                "Keep sync and transfer work active after the window closes",
                enabled,
            ),
        )
    }
    startOnLogin?.let { enabled ->
        add(
            SettingsDesktopPreferenceItem(
                SettingsDesktopPreferenceId.StartOnLogin,
                "Start on login",
                "Open Nextcloud Native when you sign in to this device",
                enabled,
                startOnLoginMessage,
                startOnLoginMessage != null,
            ),
        )
    }
}

@Composable
internal fun SettingsDesktopAppSectionContent(
    preferences: List<SettingsDesktopPreferenceItem>,
    onPreferenceChanged: (SettingsDesktopPreferenceId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        preferences.forEach { preference ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumSettingsTargetDp)
                            .toggleable(
                                value = preference.enabled,
                                role = Role.Switch,
                                onValueChange = { enabled -> onPreferenceChanged(preference.id, enabled) },
                            )
                            .padding(NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preference.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                preference.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = preference.enabled, onCheckedChange = null)
                    }
                    preference.message?.let { message ->
                        SettingsLiveMessage(
                            message,
                            if (preference.messageIsError) {
                                SettingsMessagePriority.Assertive
                            } else {
                                SettingsMessagePriority.Polite
                            },
                            modifier = Modifier.padding(
                                start = NextcloudSpacing.Medium,
                                end = NextcloudSpacing.Medium,
                                bottom = NextcloudSpacing.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}

internal enum class SettingsHelpAction {
    OpenGuides,
    OpenProjectNews,
}

@Immutable
internal data class SettingsHelpSectionState(
    val guidesDescription: String,
    val guidesTrailingLabel: String? = null,
    val projectNewsDescription: String = "Read release notes and development updates in a cached native view",
)

@Composable
internal fun SettingsHelpSectionContent(
    state: SettingsHelpSectionState,
    onAction: (SettingsHelpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        SettingsSectionActionRow(
            title = "Guides",
            description = state.guidesDescription,
            icon = NextcloudIcons.Info,
            trailing = state.guidesTrailingLabel,
            onClick = { onAction(SettingsHelpAction.OpenGuides) },
        )
        SettingsSectionActionRow(
            title = "Project news",
            description = state.projectNewsDescription,
            icon = NextcloudIcons.Activity,
            onClick = { onAction(SettingsHelpAction.OpenProjectNews) },
        )
    }
}

@Immutable
internal data class SettingsInstalledAppItem(
    val id: String,
    val name: String,
    val presentationLabel: String,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(presentationLabel.isNotBlank())
    }
}

@Immutable
internal data class SettingsAdministrationSectionState(
    val serverAppsDescription: String,
    val serverAppsStatus: String? = null,
    val installedApps: List<SettingsInstalledAppItem>,
)

@Composable
internal fun SettingsAdministrationSectionContent(
    state: SettingsAdministrationSectionState,
    onOpenServerApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        SettingsSectionActionRow(
            title = "Server apps",
            description = state.serverAppsDescription,
            icon = NextcloudIcons.Apps,
            trailing = state.serverAppsStatus,
            onClick = onOpenServerApps,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text("Installed workspaces", style = MaterialTheme.typography.titleSmall)
                state.installedApps.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumSettingsTargetDp),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            NextcloudIcons.app(app.id),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            app.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            app.presentationLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionActionRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumSettingsTargetDp)
            .semantics { role = Role.Button },
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
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.let { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

private enum class SettingsMessagePriority {
    Polite,
    Assertive,
}

@Composable
private fun SettingsLiveMessage(
    text: String,
    priority: SettingsMessagePriority,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.semantics {
            liveRegion = when (priority) {
                SettingsMessagePriority.Polite -> LiveRegionMode.Polite
                SettingsMessagePriority.Assertive -> LiveRegionMode.Assertive
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (priority == SettingsMessagePriority.Assertive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private val MinimumSettingsTargetDp = 48.dp
