package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

@Composable
internal fun LoggedOutAppUpdateReviewScreen(
    services: NextcloudPlatformServices,
    platformCapabilityRefreshRequest: Long,
    onContinueToSignIn: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ProductHeader(title = "App update", showSettings = false)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                Text(
                    "Review this app update without connecting a Nextcloud account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AppUpdateSettingsCard(
                    services = services,
                    platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
                )
            }
            item {
                OutlinedButton(onClick = onContinueToSignIn) {
                    Text("Continue to sign in")
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateSettingsCard(
    services: NextcloudPlatformServices,
    platformCapabilityRefreshRequest: Long,
) {
    val scope = rememberCoroutineScope()
    val support = remember(services) { services.appUpdateSupport() }
    var updateChannel by remember(services) {
        mutableStateOf(services.loadAppUpdateChannel())
    }
    val channelPresentation = remember(support, updateChannel) {
        appUpdateChannelPresentation(support, updateChannel)
    }
    val updateState by remember(services) {
        services.observeAppUpdateInstallState()
    }.collectAsState(AppUpdateInstallState.Idle)
    val observedCheckResult by remember(services) {
        services.observeAppUpdateCheckResult()
    }.collectAsState(null)
    var updatePreferences by remember(services) {
        mutableStateOf(services.loadAppUpdatePreferences())
    }
    val notificationCapability = remember(services, platformCapabilityRefreshRequest) {
        services.platformCapabilities().firstOrNull { status ->
            status.capability == PlatformCapability.Notifications
        }
    }
    val appUpdateNotificationDeliveryAllowed = remember(services, platformCapabilityRefreshRequest) {
        services.appUpdateNotificationDeliveryAllowed()
    }
    var notificationEnablePending by remember(services) { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var pendingInstallConfirmation by remember { mutableStateOf<AppUpdateRelease?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        appUpdateNotificationDeliveryAllowed,
        notificationCapability?.state,
        notificationEnablePending,
        platformCapabilityRefreshRequest,
    ) {
        if (notificationEnablePending && appUpdateNotificationDeliveryAllowed) {
            val updated = updatePreferences.copy(notifications = true)
            if (services.saveAppUpdatePreferences(updated)) {
                updatePreferences = updated
            }
            notificationEnablePending = false
            installMessage = null
        } else if (
            notificationEnablePending &&
            notificationCapability?.state in setOf(
                PlatformCapabilityState.Blocked,
                PlatformCapabilityState.Unsupported,
            )
        ) {
            notificationEnablePending = false
        }
    }
    fun beginInstall(release: AppUpdateRelease) {
        installing = true
        installMessage = null
        scope.launch {
            val refreshed = services.checkForAppUpdate(updateChannel)
            val latest = (refreshed as? AppUpdateCheckResult.Available)?.release
            if (latest == null) {
                installMessage = when (refreshed) {
                    is AppUpdateCheckResult.Current -> "This installation is already current."
                    is AppUpdateCheckResult.Failed ->
                        "The latest release could not be confirmed. Check again before installing."
                    is AppUpdateCheckResult.Unavailable ->
                        "Direct updates are no longer available for this installation."
                    is AppUpdateCheckResult.Available -> error("Handled above")
                }
                installing = false
                return@launch
            }
            if (latest != release) {
                installMessage =
                    "The available release changed. Review its details before installing."
                installing = false
                return@launch
            }
            installMessage = when (val install = services.beginAppUpdate(latest)) {
                AppUpdateInstallResult.ConfirmationOpened ->
                    "The system installer opened the update confirmation."
                AppUpdateInstallResult.Installed ->
                    "The update was installed. Restart Nextcloud Native to use the new version."
                is AppUpdateInstallResult.Cancelled ->
                    if (install.canResume) {
                        "Download paused. You can resume it without starting over."
                    } else {
                        "Download stopped. The next attempt will start from the beginning."
                    }
                is AppUpdateInstallResult.PermissionRequired -> install.message
                is AppUpdateInstallResult.Rejected -> install.message
            }
            installing = false
        }
    }
    fun requestInstall(release: AppUpdateRelease) {
        if (support.channel == AppDistributionChannel.DirectDesktopPackage) {
            pendingInstallConfirmation = release
        } else {
            beginInstall(release)
        }
    }
    pendingInstallConfirmation?.let { release ->
        AlertDialog(
            onDismissRequest = { pendingInstallConfirmation = null },
            title = { Text("Install app update?") },
            text = {
                Text(
                    "Nextcloud Native will download and verify version ${release.versionName}, then ask " +
                        "the system package service to install it. Restart the app after installation.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingInstallConfirmation = null
                        beginInstall(release)
                    },
                ) {
                    Text("Install update")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingInstallConfirmation = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
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
                    Text("App updates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (channelPresentation.selectorVisible) {
                            "Version ${support.currentVersionName} - ${updateChannel.name} channel"
                        } else {
                            "Version ${support.currentVersionName} - ${support.channel.name}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (support.canCheckDirectUpdates) {
                    TextButton(
                        enabled = !checking && updateChannel.available,
                        onClick = {
                            checking = true
                            installMessage = null
                            scope.launch {
                                services.checkForAppUpdate(updateChannel)
                                checking = false
                            }
                        },
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Check")
                        }
                    }
                }
            }
            Text(
                support.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (channelPresentation.selectorVisible) {
                Text(
                    "Update channel",
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    channelPresentation.options.forEach { option ->
                        val enabled = option.enabled && !checking && !installing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option.selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (services.saveAppUpdateChannel(option.channel)) {
                                            updateChannel = option.channel
                                            installMessage = null
                                        }
                                    },
                                )
                                .padding(vertical = NextcloudSpacing.Small),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.selected,
                                enabled = enabled,
                                onClick = null,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                                    option.availabilityLabel?.let { label ->
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (channelPresentation.selectorVisible) {
                UpdatePreferenceRow(
                    label = "Check automatically",
                    description = if (support.channel == AppDistributionChannel.DirectDesktopPackage) {
                        "Check the selected channel periodically while Nextcloud Native is running."
                    } else {
                        "Check the selected channel in the background without downloading packages."
                    },
                    checked = updatePreferences.automaticChecks,
                    onCheckedChange = { enabled ->
                        val updated = updatePreferences.copy(automaticChecks = enabled)
                        if (services.saveAppUpdatePreferences(updated)) {
                            updatePreferences = updated
                            if (enabled) {
                                scope.launch { services.checkForAppUpdate(automatic = true) }
                            }
                        }
                    },
                )
                if (support.channel == AppDistributionChannel.DirectApk) {
                    UpdatePreferenceRow(
                        label = "Use unmetered networks only",
                        description = "Automatic Android checks wait for an unmetered connection.",
                        checked = updatePreferences.unmeteredNetworkOnly,
                        enabled = updatePreferences.automaticChecks,
                        onCheckedChange = { enabled ->
                            val updated = updatePreferences.copy(unmeteredNetworkOnly = enabled)
                            if (services.saveAppUpdatePreferences(updated)) updatePreferences = updated
                        },
                    )
                    UpdatePreferenceRow(
                        label = "Notify when available",
                        description = when {
                            notificationCapability?.state == PlatformCapabilityState.Granted &&
                                appUpdateNotificationDeliveryAllowed ->
                                "Post one Android notification for each newly discovered version."
                            notificationCapability?.state == PlatformCapabilityState.Granted ->
                                "The App updates notification channel is blocked. Turn it on in Android settings."
                            notificationCapability?.state == PlatformCapabilityState.NeedsPermission ->
                                "Allow Android notifications to be notified about newly discovered versions."
                            notificationCapability?.state == PlatformCapabilityState.Blocked ->
                                "Notifications are blocked. Turn them on in Android app settings to use this option."
                            notificationCapability?.state == PlatformCapabilityState.Unsupported ->
                                "Android notifications are unavailable on this device."
                            else ->
                                "Android notification permission status is unavailable."
                        },
                        checked = updatePreferences.notifications && appUpdateNotificationDeliveryAllowed,
                        enabled = updatePreferences.automaticChecks,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                notificationEnablePending = false
                                val updated = updatePreferences.copy(notifications = false)
                                if (services.saveAppUpdatePreferences(updated)) updatePreferences = updated
                            } else if (appUpdateNotificationDeliveryAllowed) {
                                val updated = updatePreferences.copy(notifications = true)
                                if (services.saveAppUpdatePreferences(updated)) updatePreferences = updated
                            } else if (services.requestAppUpdateNotificationDelivery()) {
                                notificationEnablePending = true
                                installMessage = "Allow notifications in Android to finish enabling update alerts."
                            } else {
                                installMessage = "Android could not open notification permission settings."
                            }
                        },
                    )
                }
            }
            when (val checked = observedCheckResult) {
                is AppUpdateCheckResult.Available -> {
                    val release = checked.release
                    val changes = remember(support.currentVersionCode, release) {
                        appUpdateChangesSince(support.currentVersionCode, release)
                    }
                    val releaseState = updateState.takeIf { state ->
                        when (state) {
                            is AppUpdateInstallState.Downloading -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Verifying -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Installing -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.PermissionRequired -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Cancelled -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Failed -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.ConfirmationOpened -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Installed -> state.versionCode == release.versionCode
                            AppUpdateInstallState.Idle -> false
                        }
                    } ?: AppUpdateInstallState.Idle
                    Text(
                        "Version ${release.versionName} is available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (changes.isNotEmpty()) {
                        Text("Changes since your version", style = MaterialTheme.typography.titleSmall)
                        changes.forEach { change ->
                            Text(
                                "- ${change.summary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { services.openExternalUrl(release.releaseNotesUrl) }) {
                        Text("Open full release notes")
                    }
                    when (releaseState) {
                        is AppUpdateInstallState.Downloading -> {
                            val progress =
                                (releaseState.downloadedBytes.toDouble() / releaseState.totalBytes.toDouble())
                                    .coerceIn(0.0, 1.0)
                                    .toFloat()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Text(
                                buildString {
                                    append(formatBytes(releaseState.downloadedBytes))
                                    append(" of ")
                                    append(formatBytes(releaseState.totalBytes))
                                    if (releaseState.resumedFromBytes > 0) append(" - resumed")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { services.cancelAppUpdate() }) {
                                Text(appUpdateDownloadCancellationLabel(support.channel))
                            }
                        }
                        is AppUpdateInstallState.Verifying -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                if (release is AndroidDirectRelease) {
                                    "Download complete. Verifying package and signing certificate..."
                                } else {
                                    "Download complete. Checking the package checksum..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is AppUpdateInstallState.Installing -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Waiting for the system package service to finish installation...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is AppUpdateInstallState.Cancelled -> {
                            Text(
                                if (releaseState.canResume) {
                                    "${formatBytes(releaseState.downloadedBytes)} saved for resume."
                                } else {
                                    "The download was paused before any data was saved."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { requestInstall(release) },
                                enabled = !installing,
                            ) {
                                Text(if (releaseState.canResume) "Resume download" else "Retry download")
                            }
                        }
                        is AppUpdateInstallState.Failed -> {
                            Text(
                                releaseState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { requestInstall(release) },
                                enabled = !installing,
                            ) {
                                Text(if (releaseState.canResume) "Resume download" else "Retry download")
                            }
                        }
                        is AppUpdateInstallState.PermissionRequired -> {
                            Text(
                                releaseState.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { beginInstall(release) },
                                enabled = !installing,
                            ) {
                                Text("Continue update")
                            }
                        }
                        is AppUpdateInstallState.ConfirmationOpened -> Text(
                            "The system installer opened the update confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NextcloudTheme.colors.success,
                        )
                        is AppUpdateInstallState.Installed -> Text(
                            "The update was installed. Restart Nextcloud Native to use the new version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NextcloudTheme.colors.success,
                        )
                        AppUpdateInstallState.Idle -> Button(
                            onClick = { requestInstall(release) },
                            enabled = !installing,
                        ) {
                            if (installing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Download, verify, and install")
                            }
                        }
                    }
                }
                is AppUpdateCheckResult.Current -> Text(
                    "This installation is up to date.",
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AppUpdateCheckResult.Failed -> Text(
                    checked.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AppUpdateCheckResult.Unavailable, null -> Unit
            }
            installMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UpdatePreferenceRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {}
            .padding(vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}
