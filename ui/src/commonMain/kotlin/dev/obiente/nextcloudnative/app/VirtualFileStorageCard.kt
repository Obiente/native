package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
internal fun VirtualFileStorageCard(
    snapshot: VirtualFileStorageSnapshot?,
    loading: Boolean,
    busy: Boolean,
    onManage: () -> Unit,
    onFreeUp: () -> Unit,
    onActivateProvider: () -> Unit,
    onDeactivateProvider: () -> Unit,
    onAcknowledgeRecovery: () -> Unit,
    onChangeLocation: () -> Unit,
    onChangeCacheTiers: () -> Unit,
    onChoosePinnedFolder: () -> Unit,
    onReleaseFolder: (String) -> Unit,
    onRetryFolder: (String) -> Unit,
) {
    var managementExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Storage on this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (snapshot?.integration) {
                            VirtualFilePlatformIntegration.AndroidDocumentsProvider ->
                                "Browse everything in System Files. Content downloads only when opened."
                            VirtualFilePlatformIntegration.LinuxFilesystemMount ->
                                "Browse placeholders in your Linux file manager. Content downloads when opened."
                            VirtualFilePlatformIntegration.InAppOnDemandCache ->
                                "Files opened in Nextcloud Native are kept in a managed on-demand cache."
                            VirtualFilePlatformIntegration.WindowsCloudFiles ->
                                "Browse everything in File Explorer. Files download when opened and local edits sync back."
                            VirtualFilePlatformIntegration.AppleFileProvider ->
                                "Files download through the system File Provider."
                            null -> "Loading on-demand storage status..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading || busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            snapshot?.virtualStorageStatusLabel() ?: "Checking",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            if (snapshot != null) {
                if (snapshot.virtualStorageEditsNeedReview()) {
                    Text(
                        "Local edits need review. Your copies are retained. Review the recovery details below before changing the connection.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val maximum = snapshot.policy.maximumCacheBytes
                if (maximum != null) {
                    val automaticBytes = managedAutomaticCacheBytesForProgress(snapshot)
                    LinearProgressIndicator(
                        progress = {
                            (automaticBytes.toDouble() / maximum.toDouble())
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    VirtualFileStorageMetric(
                        label = "Cached locally",
                        value = formatVirtualFileBytes(snapshot.cachedBytes),
                        modifier = Modifier.weight(1f),
                    )
                    VirtualFileStorageMetric(
                        label = "Kept offline",
                        value = formatVirtualFileBytes(snapshot.pinnedBytes),
                        modifier = Modifier.weight(1f),
                    )
                    VirtualFileStorageMetric(
                        label = "Device free",
                        value = snapshot.availableFreeBytes?.let(::formatVirtualFileBytes) ?: "Unknown",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (snapshot.policy.automaticCleanup) {
                        buildString {
                            append("Auto cleanup keeps at least ")
                            append(formatVirtualFileBytes(snapshot.policy.minimumFreeSpaceBytes))
                            append(" free")
                            snapshot.policy.unusedFileAgeMillis?.let { age ->
                                append(
                                    if (snapshot.cacheTiers?.overflowPath != null) {
                                        " and moves unused cached files to overflow after "
                                    } else {
                                        " and removes unused cached files after "
                                    },
                                )
                                append(formatVirtualFileAge(age))
                            }
                            append(". Pins and active work are always kept.")
                        }
                    } else {
                        "Automatic cleanup is off. Pins and active work are always kept."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snapshot.cacheTiers?.let {
                    VirtualFileCacheTierRow("Fast cache", snapshot.primaryCache)
                    if (it.overflowPath != null) {
                        VirtualFileCacheTierRow("Overflow", snapshot.overflowCache)
                    } else {
                        Text(
                            "Overflow storage is off. Unused cached files are removed when the fast cache needs space.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = !busy && !snapshot.providerActive,
                        onClick = onChangeCacheTiers,
                    ) {
                        Text("Change cache drives")
                    }
                }
                if (snapshot.providerState != VirtualFileProviderState.NotApplicable) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(NextcloudRadii.Small),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                when (snapshot.providerState) {
                                    VirtualFileProviderState.Active -> "Available in your file manager"
                                    VirtualFileProviderState.Inactive -> "File-manager integration is off"
                                    VirtualFileProviderState.Starting -> "Starting file-manager integration"
                                    VirtualFileProviderState.NeedsAttention -> "File-manager integration needs attention"
                                    VirtualFileProviderState.NotApplicable -> ""
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            snapshot.providerLocation?.let { location ->
                                Text(
                                    location,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            snapshot.providerRecoveryNotice?.let { notice ->
                                Text(
                                    notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(enabled = !busy, onClick = onAcknowledgeRecovery) {
                                    Text("I've reviewed the preserved folder")
                                }
                            }
                            if (snapshot.pendingWritebackCount > 0) {
                                Text(
                                    if (snapshot.virtualStorageEditsNeedReview()) {
                                        "${snapshot.pendingWritebackCount} local edit(s) are retained for recovery."
                                    } else {
                                        "${snapshot.pendingWritebackCount} local edit(s) are waiting to sync. Local copies remain on this device."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (snapshot.virtualStorageEditsNeedReview()) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            snapshot.limitations.forEach { limitation ->
                                Text(
                                    limitation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (snapshot.providerLocationCanChange) {
                                TextButton(enabled = !busy, onClick = onChangeLocation) {
                                    Text("Change drive or folder")
                                }
                            }
                        }
                    }
                    if (!snapshot.providerActive) {
                        Button(enabled = !busy, onClick = onActivateProvider) {
                            Text("Connect to file manager")
                        }
                    }
                }
                if (snapshot.integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        Text("Folders kept on this device", style = MaterialTheme.typography.titleSmall)
                        val pinnedFolders = snapshot.folderRetentionRules.filter { rule ->
                            rule.retention == VirtualFolderRetention.KeepOnDevice
                        }
                        if (pinnedFolders.isEmpty()) {
                            Text(
                                "Everything stays visible. Choose only the albums or folders that should also work offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val hydrationByPath = snapshot.folderHydrationStatuses.associateBy(
                                VirtualFolderHydrationStatus::relativePath,
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                items(
                                    items = pinnedFolders,
                                    key = VirtualFolderRetentionRule::relativePath,
                                ) { rule ->
                                    val status = hydrationByPath[rule.relativePath]
                                    var menuExpanded by remember(rule.relativePath) { mutableStateOf(false) }
                                    val menuActions = buildList {
                                        if (
                                            status?.phase == VirtualFolderHydrationPhase.Failed ||
                                            status?.refreshFailure != null
                                        ) {
                                            add(
                                                NextcloudCardAction(
                                                    label = "Retry",
                                                    enabled = !busy,
                                                    onClick = { onRetryFolder(rule.relativePath) },
                                                ),
                                            )
                                        }
                                        add(
                                            NextcloudCardAction(
                                                label = "Make online-only",
                                                destructive = true,
                                                enabled = !busy,
                                                onClick = { onReleaseFolder(rule.relativePath) },
                                            ),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
                                            onOpen = null,
                                            onShowActions = { menuExpanded = true },
                                            actionsLabel = "Show actions for ${rule.relativePath}",
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                rule.relativePath,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                when (status?.phase) {
                                                    VirtualFolderHydrationPhase.Queued -> "Waiting to download"
                                                    VirtualFolderHydrationPhase.Downloading -> "Downloading for offline use"
                                                    VirtualFolderHydrationPhase.AvailableOffline -> when {
                                                        status.refreshing -> "Available offline. Checking for updates"
                                                        status.refreshFailure != null -> status.refreshFailure.let { failure ->
                                                            "Available offline. Latest refresh needs attention: $failure"
                                                        }
                                                        else -> "Available offline"
                                                    }
                                                    VirtualFolderHydrationPhase.Failed ->
                                                        status.detail ?: "Download needs attention"
                                                    null -> "Waiting to check offline content"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (status?.phase == VirtualFolderHydrationPhase.Failed) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        NextcloudCardOverflow(
                                            itemLabel = rule.relativePath,
                                            actions = menuActions,
                                            expanded = menuExpanded,
                                            onExpandedChange = { menuExpanded = it },
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(enabled = !busy, onClick = onChoosePinnedFolder) {
                            Text("Keep a folder on this device")
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(enabled = !busy, onClick = onManage) { Text("Manage storage") }
                    if (snapshot.providerActive) {
                        NextcloudCardOverflow(
                            itemLabel = "file-manager connection",
                            actions = listOf(NextcloudCardAction(label = "Disconnect from file manager", destructive = true, enabled = !busy, onClick = onDeactivateProvider)),
                            expanded = managementExpanded,
                            onExpandedChange = { managementExpanded = it },
                        )
                    }
                    OutlinedButton(
                        enabled = !busy && snapshot.reclaimableBytes > 0L,
                        onClick = onFreeUp,
                    ) {
                        Text(
                            if (snapshot.reclaimableBytes > 0L) {
                                "Free up ${formatVirtualFileBytes(snapshot.reclaimableBytes)}"
                            } else {
                                "Nothing to free"
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun managedAutomaticCacheBytesForProgress(snapshot: VirtualFileStorageSnapshot): Long =
    snapshot.primaryCache?.managedAutomaticBytes
        ?: (snapshot.cachedBytes - snapshot.pinnedBytes).coerceAtLeast(0L)

@Composable
private fun VirtualFileCacheTierRow(label: String, tier: VirtualFileCacheTierSnapshot?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (tier?.available == true) formatVirtualFileBytes(tier.cachedBytes) else "Unavailable",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (tier?.available == true) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            tier?.let {
                Text(
                    it.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Pinned ${formatVirtualFileBytes(it.pinnedBytes)} - Free ${it.availableFreeBytes?.let(::formatVirtualFileBytes) ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VirtualFileStorageMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
