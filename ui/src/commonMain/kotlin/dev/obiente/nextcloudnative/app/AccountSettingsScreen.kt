package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
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
    val isDesktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    var selectedSectionName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf<String?>(null)
    }
    val supportDrafts = rememberSaveable(
        session.serverUrl,
        session.loginName,
        saver = SupportSettingsDraftState.Saver,
    ) { SupportSettingsDraftState() }
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
    var trustRemovalConfirmationVisible by remember { mutableStateOf(false) }
    var trustRemovalError by remember { mutableStateOf<String?>(null) }
    val platformCapabilities = remember(services, capabilityRefresh, platformCapabilityRefreshRequest) {
        services.platformCapabilities()
    }
    val hasDesktopAppSettings = services.supportsStartOnLogin || services.supportsKeepRunningInBackground
    val visibleSections = visibleSettingsSections(
        isDesktop = isDesktop,
        hasDeviceSettings = platformCapabilities.isNotEmpty(),
        hasDesktopAppSettings = hasDesktopAppSettings,
    )
    val selectedSection = selectedSectionName?.let { restoredName ->
        resolveSettingsWorkspaceSection(restoredName, visibleSections)
    }

    if (trustRemovalConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { trustRemovalConfirmationVisible = false },
            title = { Text("Stop trusting this certificate?") },
            text = {
                Text(
                    "Nextcloud Native will return to Android's normal certificate checks. " +
                        "The account may stop connecting until the server uses a trusted certificate.",
                )
            },
            dismissButton = {
                TextButton(onClick = { trustRemovalConfirmationVisible = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        trustRemovalConfirmationVisible = false
                        trustRemovalError = null
                        if (services.removeTrustedServerCertificate(session.serverUrl)) {
                            trustedCertificate = null
                        } else {
                            trustRemovalError = "The certificate trust could not be removed."
                        }
                    },
                ) {
                    Text("Stop trusting")
                }
            },
        )
    }

    val deviceFeatures = platformCapabilities.map { status ->
        SettingsDeviceFeatureItem(
            id = SettingsDeviceFeatureId(status.capability.toString()),
            label = status.label,
            description = status.description,
            statusLabel = when (status.state) {
                PlatformCapabilityState.Granted -> "Enabled"
                PlatformCapabilityState.AvailableWithoutPermission -> "Available"
                PlatformCapabilityState.NeedsPermission -> "Enable"
                PlatformCapabilityState.Blocked -> "Open settings"
                PlatformCapabilityState.Unsupported -> "Unavailable"
            },
            actionLabel = when (status.state) {
                PlatformCapabilityState.NeedsPermission -> "Enable"
                PlatformCapabilityState.Blocked -> "Settings"
                else -> null
            },
            statusIsSuccess = status.state == PlatformCapabilityState.Granted,
        )
    }
    val deviceFeaturesById = platformCapabilities.associateBy { status ->
        SettingsDeviceFeatureId(status.capability.toString())
    }
    val desktopPreferences = settingsDesktopPreferences(
        keepRunningInBackground.takeIf { services.supportsKeepRunningInBackground },
        startOnLogin.takeIf { services.supportsStartOnLogin },
        startOnLoginMessage,
    )
    val sectionContent: @Composable ColumnScope.(SettingsWorkspaceSection) -> Unit = { section ->
        when (section) {
            SettingsWorkspaceSection.Account -> SettingsAccountSectionContent(
                state = SettingsAccountSectionState(
                    displayName = serverInfo?.displayName ?: session.loginName,
                    serverAddress = session.serverUrl,
                    serverVersionLabel = serverInfo?.version?.let { "Nextcloud $it" } ?: "Connected",
                    trustedCertificate = trustedCertificate?.let { certificate ->
                        SettingsTrustedCertificateState(
                            label = "Explicitly trusted server certificate",
                            description =
                                "Android could not verify this server through its certificate authorities. " +
                                    "Nextcloud Native accepts only this SHA-256 fingerprint: " +
                                    certificate.sha256Fingerprint,
                            removalError = trustRemovalError,
                        )
                    },
                    signingOut = loggingOut,
                    signOutError = logoutError,
                ),
                onRemoveTrustedCertificate = { trustRemovalConfirmationVisible = true },
                onSignOut = {
                    loggingOut = true
                    logoutError = null
                    scope.launch {
                        runCatchingPreservingCancellation { services.revokeSession(session) }
                        runCatchingPreservingCancellation { onLoggedOut() }
                            .onFailure { failure ->
                                logoutError = logoutCleanupFailureMessage(failure)
                                loggingOut = false
                            }
                    }
                },
            )

            SettingsWorkspaceSection.Appearance -> SettingsAppearanceSectionContent(
                selectedTheme = themePreference,
                onThemeSelected = onThemePreferenceChanged,
            )

            SettingsWorkspaceSection.SyncAndStorage -> SettingsSyncAndStorageSectionContent(
                state = SettingsSyncAndStorageSectionState(
                    workspaceDescription = if (services.supportsRecursiveFileOfflineStorage) {
                        "Manage sync pairs, rules, conflicts, virtual files, and storage"
                    } else {
                        "Manage pinned files, downloads, conflicts, and device storage"
                    },
                    workspaceStatus = if (services.supportsRecursiveFileOfflineStorage) "Ready" else null,
                    mediaTransfersVisible = services.supportsMediaTransferCenter,
                ),
                onAction = { action ->
                    when (action) {
                        SettingsSyncAction.OpenSyncWorkspace,
                        SettingsSyncAction.OpenOfflineAvailability,
                        -> onOfflineCenter()

                        SettingsSyncAction.OpenMediaTransfers -> onTransfers()
                    }
                },
            )

            SettingsWorkspaceSection.NotificationsAndDevice -> SettingsDeviceFeaturesSectionContent(
                features = deviceFeatures,
                onFeatureAction = { featureId ->
                    deviceFeaturesById[featureId]?.let { status ->
                        services.requestPlatformCapability(status.capability)
                        capabilityRefresh += 1
                    }
                },
            )

            SettingsWorkspaceSection.DesktopApp -> SettingsDesktopAppSectionContent(
                preferences = desktopPreferences,
                onPreferenceChanged = { preference, enabled ->
                    when (preference) {
                        SettingsDesktopPreferenceId.KeepRunningInBackground -> {
                            services.saveKeepRunningInBackgroundPreference(enabled)
                            keepRunningInBackground = services.loadKeepRunningInBackgroundPreference()
                        }

                        SettingsDesktopPreferenceId.StartOnLogin -> {
                            startOnLoginMessage = services.saveStartOnLoginPreference(enabled)
                            startOnLogin = services.loadStartOnLoginPreference()
                        }
                    }
                },
            )

            SettingsWorkspaceSection.Updates -> AppUpdateSettingsCard(
                services = services,
                platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
            )

            SettingsWorkspaceSection.Support -> SupportSettingsView(services, supportDrafts)

            SettingsWorkspaceSection.HelpAndGuides -> SettingsHelpSectionContent(
                state = SettingsHelpSectionState(
                    guidesDescription =
                        "Follow illustrated setup, sync, offline, photo, Calendar, and app workflows",
                    guidesTrailingLabel = "6 guides",
                ),
                onAction = { action ->
                    when (action) {
                        SettingsHelpAction.OpenGuides -> services.openExternalUrl(NEXTCLOUD_NATIVE_GUIDES_URL)
                        SettingsHelpAction.OpenProjectNews -> onProjectNews()
                    }
                },
            )

            SettingsWorkspaceSection.Administration -> SettingsAdministrationSectionContent(
                state = SettingsAdministrationSectionState(
                    serverAppsDescription = "Install, update, enable, or disable apps as an administrator",
                    serverAppsStatus = serverInfo?.apps?.size?.let { "$it active" },
                    installedApps = serverInfo?.apps.orEmpty()
                        .filterNot { app -> app.id == "dashboard" }
                        .take(8)
                        .map { app ->
                            SettingsInstalledAppItem(
                                id = app.id,
                                name = app.name,
                                presentationLabel = if (app.id in nativeAppIds) "Native" else "Adaptive",
                            )
                        },
                ),
                onOpenServerApps = onAdminApps,
            )
        }
    }
    val onSectionSelected: (SettingsWorkspaceSection?) -> Unit = { section ->
        selectedSectionName = section?.name
    }

    BoxWithConstraints {
        if (useExpandedSettingsWorkspace(isDesktop, maxWidth.value.toInt())) {
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
            visibleSections = visibleSections,
            selectedSection = selectedSection,
            onSectionSelected = onSectionSelected,
            content = sectionContent,
            )
        } else {
            MobileSettingsWorkspace(
                visibleSections = visibleSections,
                selectedSection = selectedSection,
                onSectionSelected = onSectionSelected,
                content = sectionContent,
            )
        }
    }
}
