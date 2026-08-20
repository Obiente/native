package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    serverInfo: NextcloudServerInfo?,
    themePreference: ThemePreference,
    platformCapabilityRefreshRequest: Long,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onAdminApps: () -> Unit,
    onOfflineCenter: () -> Unit,
    onTransfers: () -> Unit,
    onProjectNews: () -> Unit,
    onLoggedOut: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    var logoutError by remember { mutableStateOf<String?>(null) }
    var capabilityRefresh by remember { mutableStateOf(0) }
    var startOnLogin by remember(services) { mutableStateOf(services.loadStartOnLoginPreference()) }
    var startOnLoginMessage by remember(services) { mutableStateOf<String?>(null) }
    var keepRunningInBackground by remember(services) {
        mutableStateOf(services.loadKeepRunningInBackgroundPreference())
    }
    var trustedCertificate by remember(services, session.serverUrl) {
        mutableStateOf(services.trustedServerCertificate(session.serverUrl))
    }
    var trustRemovalError by remember { mutableStateOf<String?>(null) }
    val platformCapabilities = remember(services, capabilityRefresh, platformCapabilityRefreshRequest) {
        services.platformCapabilities()
    }
    if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
        DesktopSettingsWorkspace(
            summary = SettingsWorkspaceSummary(
                displayName = serverInfo?.displayName ?: session.loginName,
                cloudName = serverInfo?.themeName ?: "Nextcloud",
                serverUrl = session.serverUrl,
                serverVersion = serverInfo?.version,
                installedApps = serverInfo?.apps?.count { it.id != "dashboard" } ?: 0,
                syncLabel = if (services.supportsRecursiveFileOfflineStorage) {
                    "Folder sync available"
                } else {
                    "Offline files available"
                },
            ),
        ) { section ->
            when (section) {
                SettingsWorkspaceSection.Account -> {
                    SettingsActionCard(
                        title = serverInfo?.displayName ?: session.loginName,
                        description = "${session.serverUrl} · ${serverInfo?.version?.let { "Nextcloud $it" } ?: "Connected"}",
                        icon = NextcloudIcons.Profile,
                        onClick = {},
                        trailing = "Primary account",
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Security", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "This device uses an app password. Signing out revokes its access without changing other sessions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            trustedCertificate?.let { certificate ->
                                TrustedCertificateSettings(
                                    certificate = certificate,
                                    error = trustRemovalError,
                                    onRemove = {
                                        trustRemovalError = null
                                        if (services.removeTrustedServerCertificate(session.serverUrl)) {
                                            trustedCertificate = null
                                        } else {
                                            trustRemovalError = "The certificate trust could not be removed."
                                        }
                                    },
                                )
                            }
                            OutlinedButton(
                                enabled = !loggingOut,
                                onClick = {
                                    loggingOut = true
                                    logoutError = null
                                    scope.launch {
                                        runCatching { services.revokeSession(session) }
                                        runCatching { onLoggedOut() }
                                            .onFailure { failure ->
                                                logoutError = logoutCleanupFailureMessage(failure)
                                                loggingOut = false
                                            }
                                    }
                                },
                            ) {
                                Icon(NextcloudIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(if (loggingOut) "Signing out..." else "Sign out and revoke access")
                            }
                            logoutError?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                SettingsWorkspaceSection.Appearance -> {
                    Text("Color theme", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        ThemePreference.entries.forEach { preference ->
                            FilterChip(
                                selected = themePreference == preference,
                                onClick = { onThemePreferenceChanged(preference) },
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
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Designed for this screen", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Desktop workspaces use persistent navigation, dense controls, and detail panes. Compact windows adapt automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SettingsWorkspaceSection.SyncAndStorage -> {
                    SettingsActionCard(
                        title = "Folder sync workspace",
                        description = if (services.supportsRecursiveFileOfflineStorage) {
                            "Manage sync pairs, rules, conflicts, virtual files, and storage"
                        } else {
                            "Manage pinned files, downloads, conflicts, and device storage"
                        },
                        icon = NextcloudIcons.Cloud,
                        onClick = onOfflineCenter,
                        trailing = if (services.supportsRecursiveFileOfflineStorage) "Ready" else null,
                    )
                    if (services.supportsMediaTransferCenter) {
                        SettingsActionCard(
                            title = "Media transfers",
                            description = "Review pending, active, failed, and completed uploads",
                            icon = NextcloudIcons.Refresh,
                            onClick = onTransfers,
                        )
                    }
                    SettingsActionCard(
                        title = "Offline availability",
                        description = "Choose what stays available when this device is offline",
                        icon = NextcloudIcons.FolderOpen,
                        onClick = onOfflineCenter,
                    )
                }

                SettingsWorkspaceSection.NotificationsAndDevice -> {
                    if (platformCapabilities.isEmpty()) {
                        Text("No device permissions are required on this platform.")
                    } else {
                        platformCapabilities.forEach { status ->
                            SettingsActionCard(
                                title = status.label,
                                description = status.description,
                                icon = NextcloudIcons.Settings,
                                trailing = when (status.state) {
                                    PlatformCapabilityState.Granted -> "Enabled"
                                    PlatformCapabilityState.AvailableWithoutPermission -> "Available"
                                    PlatformCapabilityState.NeedsPermission -> "Enable"
                                    PlatformCapabilityState.Blocked -> "Open settings"
                                    PlatformCapabilityState.Unsupported -> "Unavailable"
                                },
                                onClick = {
                                    if (status.state == PlatformCapabilityState.NeedsPermission ||
                                        status.state == PlatformCapabilityState.Blocked
                                    ) {
                                        services.requestPlatformCapability(status.capability)
                                        capabilityRefresh += 1
                                    }
                                },
                            )
                        }
                    }
                }

                SettingsWorkspaceSection.DesktopApp -> {
                    if (services.supportsKeepRunningInBackground) {
                        DesktopBackgroundSettingsCard(
                            enabled = keepRunningInBackground,
                            onEnabledChanged = { enabled ->
                                services.saveKeepRunningInBackgroundPreference(enabled)
                                keepRunningInBackground = services.loadKeepRunningInBackgroundPreference()
                            },
                        )
                    }
                    if (services.supportsStartOnLogin) {
                        DesktopStartOnLoginSettingsCard(
                            enabled = startOnLogin,
                            message = startOnLoginMessage,
                            onEnabledChanged = { enabled ->
                                startOnLoginMessage = services.saveStartOnLoginPreference(enabled)
                                startOnLogin = services.loadStartOnLoginPreference()
                            },
                        )
                    }
                }

                SettingsWorkspaceSection.Updates -> AppUpdateSettingsCard(
                    services = services,
                    platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
                )

                SettingsWorkspaceSection.Diagnostics -> SupportDiagnosticsSettingsCard(services)

                SettingsWorkspaceSection.HelpAndGuides -> {
                    SettingsActionCard(
                        title = "Guides",
                        description = "Follow illustrated setup, sync, offline, photo, Calendar, and app workflows",
                        icon = NextcloudIcons.Info,
                        onClick = { services.openExternalUrl(NEXTCLOUD_NATIVE_GUIDES_URL) },
                        trailing = "6 guides",
                    )
                    SettingsActionCard(
                        title = "Project news",
                        description = "Read release notes and development updates in a cached native view",
                        icon = NextcloudIcons.Activity,
                        onClick = onProjectNews,
                    )
                }

                SettingsWorkspaceSection.Administration -> {
                    SettingsActionCard(
                        title = "Server apps",
                        description = "Install, update, enable, or disable apps as an administrator",
                        icon = NextcloudIcons.Apps,
                        onClick = onAdminApps,
                        trailing = serverInfo?.apps?.size?.let { "$it active" },
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Installed workspaces", style = MaterialTheme.typography.titleSmall)
                            serverInfo?.apps.orEmpty().filterNot { it.id == "dashboard" }.take(8).forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(NextcloudIcons.app(app.id), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(app.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (app.id in nativeAppIds) "Native" else "Adaptive",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Settings", showSettings = false)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                SectionTitle("Appearance")
                Row(
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    ThemePreference.entries.forEach { preference ->
                        FilterChip(
                            selected = themePreference == preference,
                            onClick = { onThemePreferenceChanged(preference) },
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
            }
            if (services.supportsStartOnLogin || services.supportsKeepRunningInBackground) {
                item {
                    SectionTitle("Desktop")
                    if (services.supportsKeepRunningInBackground) {
                        DesktopBackgroundSettingsCard(
                            enabled = keepRunningInBackground,
                            onEnabledChanged = { enabled ->
                                services.saveKeepRunningInBackgroundPreference(enabled)
                                keepRunningInBackground = services.loadKeepRunningInBackgroundPreference()
                            },
                        )
                    }
                    if (services.supportsStartOnLogin) {
                        DesktopStartOnLoginSettingsCard(
                            enabled = startOnLogin,
                            message = startOnLoginMessage,
                            onEnabledChanged = { enabled ->
                                startOnLoginMessage = services.saveStartOnLoginPreference(enabled)
                                startOnLogin = services.loadStartOnLoginPreference()
                            },
                        )
                    }
                }
            }
            item {
                SectionTitle("Account")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Profile,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(serverInfo?.displayName ?: session.loginName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                session.serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            serverInfo?.version?.let {
                                Text(
                                    "Nextcloud $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            trustedCertificate?.let { certificate ->
                item {
                    TrustedCertificateSettings(
                        certificate = certificate,
                        error = trustRemovalError,
                        onRemove = {
                            trustRemovalError = null
                            if (services.removeTrustedServerCertificate(session.serverUrl)) {
                                trustedCertificate = null
                            } else {
                                trustRemovalError = "The certificate trust could not be removed."
                            }
                        },
                    )
                }
            }
            item {
                SectionTitle("Files")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onOfflineCenter,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Cloud,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync & offline", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (services.supportsFileOfflineStorage) {
                                    if (services.supportsRecursiveFileOfflineStorage) {
                                        "Folder sync, offline files, conflicts, and storage"
                                    } else {
                                        "Pinned files, downloads, conflicts, and device storage"
                                    }
                                } else {
                                    "Review this platform's offline file support and limitations"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open Sync & offline",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (services.supportsMediaTransferCenter) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onTransfers,
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                                Icon(
                                    NextcloudIcons.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(12.dp).size(26.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Media transfers", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Pending, active, failed, and completed uploads",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                NextcloudIcons.ChevronRight,
                                contentDescription = "Open media transfers",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (platformCapabilities.isNotEmpty()) {
                item {
                    SectionTitle("Device features")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        platformCapabilities.forEach { status ->
                            Surface(
                                color = NextcloudTheme.colors.appTile,
                                shape = RoundedCornerShape(NextcloudRadii.Card),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        NextcloudIcons.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(status.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            status.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    when (status.state) {
                                        PlatformCapabilityState.NeedsPermission,
                                        PlatformCapabilityState.Blocked,
                                        -> TextButton(
                                            onClick = {
                                                services.requestPlatformCapability(status.capability)
                                                capabilityRefresh += 1
                                            },
                                        ) {
                                            Text(if (status.state == PlatformCapabilityState.Blocked) "Settings" else "Enable")
                                        }
                                        PlatformCapabilityState.Granted -> Text("Enabled", color = NextcloudTheme.colors.success)
                                        PlatformCapabilityState.AvailableWithoutPermission -> Text("Available")
                                        PlatformCapabilityState.Unsupported -> Text("Unavailable")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Nextcloud Native")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = { services.openExternalUrl(NEXTCLOUD_NATIVE_GUIDES_URL) },
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Info,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Help & guides", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Illustrated setup, sync, offline, photo, Calendar, and app workflows",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open Nextcloud Native guides",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onProjectNews,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Activity,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Project news", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Read development notes in a native, cached view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open project news",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                AppUpdateSettingsCard(
                    services = services,
                    platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
                )
                Spacer(Modifier.height(NextcloudSpacing.Medium))
                SupportDiagnosticsSettingsCard(services)
            }
            item {
                SectionTitle("Administration")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onAdminApps,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Apps,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server apps", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Install, update, enable, or disable apps as an administrator",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open server app management",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    OutlinedButton(
                        enabled = !loggingOut,
                        onClick = {
                            loggingOut = true
                            logoutError = null
                            scope.launch {
                                runCatching { services.revokeSession(session) }
                                runCatching { onLoggedOut() }
                                    .onFailure { failure ->
                                        logoutError = logoutCleanupFailureMessage(failure)
                                        loggingOut = false
                                    }
                            }
                        },
                    ) {
                        Icon(NextcloudIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (loggingOut) "Signing out..." else "Sign out and revoke access")
                    }
                    logoutError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustedCertificateSettings(
    certificate: TrustedServerCertificate,
    error: String?,
    onRemove: () -> Unit,
) {
    var confirmRemoval by remember { mutableStateOf(false) }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Stop trusting this certificate?") },
            text = {
                Text(
                    "Nextcloud Native will return to Android's normal certificate checks. " +
                        "The account may stop connecting until the server uses a trusted certificate.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRemoval = false
                        onRemove()
                    },
                ) { Text("Stop trusting") }
            },
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text("Explicitly trusted server certificate", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android could not verify this server through its certificate authorities. " +
                    "Nextcloud Native accepts only the exact SHA-256 fingerprint below for this server address.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(certificate.sha256Fingerprint, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmRemoval = true }) { Text("Stop trusting") }
            error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

