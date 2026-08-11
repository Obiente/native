package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DesktopTrayActionFeedback(
    val message: String,
    val error: Boolean,
)

@Composable
fun DesktopFileSyncTrayPopup(
    snapshot: DesktopFileSyncTraySnapshot,
    onOpenApp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSyncCenter: () -> Unit,
    onSyncNow: () -> Unit,
    onTogglePaused: () -> Unit,
    onQuit: () -> Unit,
    actionFeedback: DesktopTrayActionFeedback? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp)
            .shadow(20.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            TrayHeader(snapshot = snapshot, onOpenSettings = onOpenSettings)
            snapshot.overallProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            TrayQuickActions(
                snapshot = snapshot,
                onSyncNow = onSyncNow,
                onTogglePaused = onTogglePaused,
                onOpenSyncCenter = onOpenSyncCenter,
            )
            actionFeedback?.let { feedback -> TrayActionFeedback(feedback) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TrayActivityList(snapshot = snapshot, onOpenApp = onOpenApp, modifier = Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenApp) { Text("Open Nextcloud Native") }
                TextButton(onClick = onQuit) { Text("Quit") }
            }
        }
    }
}

@Composable
private fun TrayActionFeedback(feedback: DesktopTrayActionFeedback) {
    val containerColor = if (feedback.error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (feedback.error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = feedback.message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TrayHeader(
    snapshot: DesktopFileSyncTraySnapshot,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource("nextcloud-native.png"),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                snapshot.accountLabel ?: "Nextcloud Native",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(snapshot.statusColor()),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    snapshot.compactStatus(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(NextcloudIcons.Settings, contentDescription = "Open settings")
        }
    }
}

@Composable
private fun TrayQuickActions(
    snapshot: DesktopFileSyncTraySnapshot,
    onSyncNow: () -> Unit,
    onTogglePaused: () -> Unit,
    onOpenSyncCenter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrayQuickAction(
            label = "Sync now",
            icon = NextcloudIcons.Refresh,
            enabled = snapshot.phase != DesktopFileSyncTrayPhase.Syncing &&
                snapshot.phase != DesktopFileSyncTrayPhase.Paused,
            onClick = onSyncNow,
            modifier = Modifier.weight(1f),
        )
        TrayQuickAction(
            label = if (snapshot.phase == DesktopFileSyncTrayPhase.Paused) "Resume" else "Pause",
            icon = if (snapshot.phase == DesktopFileSyncTrayPhase.Paused) {
                NextcloudIcons.Play
            } else {
                NextcloudIcons.Pause
            },
            enabled = true,
            onClick = onTogglePaused,
            modifier = Modifier.weight(1f),
        )
        TrayQuickAction(
            label = "Sync center",
            icon = NextcloudIcons.FolderOpen,
            enabled = true,
            onClick = onOpenSyncCenter,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrayQuickAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun TrayActivityList(
    snapshot: DesktopFileSyncTraySnapshot,
    onOpenApp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("File activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            snapshot.lastCheckedEpochMillis?.let { checkedAt ->
                Text(
                    "Checked ${formatTrayTime(checkedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (snapshot.activities.isEmpty()) {
            TrayEmptyActivity(snapshot)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(snapshot.activities, key = DesktopFileSyncTrayActivity::stableId) { activity ->
                    TrayActivityRow(activity, onOpenApp)
                }
            }
        }
    }
}

@Composable
private fun TrayEmptyActivity(snapshot: DesktopFileSyncTraySnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (snapshot.pairCount == 0) NextcloudIcons.Folder else NextcloudIcons.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (snapshot.pairCount == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                TraySuccessColor
            },
        )
        Text(
            if (snapshot.pairCount == 0) "No sync folders yet" else "Everything is up to date",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (snapshot.pairCount == 0) {
                "Add a folder mapping in the sync center."
            } else {
                "New changes will appear here as they sync."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrayActivityRow(activity: DesktopFileSyncTrayActivity, onOpenApp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenApp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                .background(activity.phase.containerColor()),
            contentAlignment = Alignment.Center,
        ) {
            if (activity.phase.isInProgress()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    activity.phase.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = activity.phase.contentColor(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                activity.relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                activity.pairLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                activity.phase.label(),
                style = MaterialTheme.typography.labelSmall,
                color = activity.phase.contentColor(),
            )
            Text(
                activity.detail ?: activity.sizeBytes?.let(::formatTrayBytes).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DesktopFileSyncTraySnapshot.statusColor(): Color = when (phase) {
    DesktopFileSyncTrayPhase.Idle -> TraySuccessColor
    DesktopFileSyncTrayPhase.Syncing -> MaterialTheme.colorScheme.primary
    DesktopFileSyncTrayPhase.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
    DesktopFileSyncTrayPhase.NeedsAttention -> MaterialTheme.colorScheme.error
}

private fun DesktopFileSyncTraySnapshot.compactStatus(): String = when (phase) {
    DesktopFileSyncTrayPhase.Syncing -> message ?: "Syncing changes"
    DesktopFileSyncTrayPhase.Paused -> "Sync paused"
    DesktopFileSyncTrayPhase.NeedsAttention -> when {
        conflictCount > 0 && failedCount > 0 -> "$conflictCount conflict, $failedCount failed"
        conflictCount > 0 -> "$conflictCount ${if (conflictCount == 1) "conflict" else "conflicts"}"
        failedCount > 0 -> "$failedCount failed ${if (failedCount == 1) "item" else "items"}"
        else -> "Sync needs attention"
    }
    DesktopFileSyncTrayPhase.Idle -> if (pairCount == 0) "Set up folder sync" else "Up to date"
}

private fun DesktopFileSyncTrayActivityPhase.isInProgress(): Boolean = when (this) {
    DesktopFileSyncTrayActivityPhase.Uploading,
    DesktopFileSyncTrayActivityPhase.Downloading,
    DesktopFileSyncTrayActivityPhase.Preparing,
    -> true
    else -> false
}

private fun DesktopFileSyncTrayActivityPhase.icon(): ImageVector = when (this) {
    DesktopFileSyncTrayActivityPhase.Conflict,
    DesktopFileSyncTrayActivityPhase.Failed,
    -> NextcloudIcons.Error
    DesktopFileSyncTrayActivityPhase.Completed -> NextcloudIcons.CheckCircle
    DesktopFileSyncTrayActivityPhase.Waiting -> NextcloudIcons.Schedule
    DesktopFileSyncTrayActivityPhase.Uploading,
    DesktopFileSyncTrayActivityPhase.Downloading,
    DesktopFileSyncTrayActivityPhase.Preparing,
    -> NextcloudIcons.Refresh
}

@Composable
private fun DesktopFileSyncTrayActivityPhase.containerColor(): Color = when (this) {
    DesktopFileSyncTrayActivityPhase.Conflict,
    DesktopFileSyncTrayActivityPhase.Failed,
    -> MaterialTheme.colorScheme.errorContainer
    DesktopFileSyncTrayActivityPhase.Completed -> TraySuccessContainerColor
    DesktopFileSyncTrayActivityPhase.Waiting -> MaterialTheme.colorScheme.surfaceContainerHighest
    else -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun DesktopFileSyncTrayActivityPhase.contentColor(): Color = when (this) {
    DesktopFileSyncTrayActivityPhase.Conflict,
    DesktopFileSyncTrayActivityPhase.Failed,
    -> MaterialTheme.colorScheme.error
    DesktopFileSyncTrayActivityPhase.Completed -> TraySuccessColor
    DesktopFileSyncTrayActivityPhase.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

private fun DesktopFileSyncTrayActivityPhase.label(): String = when (this) {
    DesktopFileSyncTrayActivityPhase.Uploading -> "Uploading"
    DesktopFileSyncTrayActivityPhase.Downloading -> "Downloading"
    DesktopFileSyncTrayActivityPhase.Preparing -> "Applying"
    DesktopFileSyncTrayActivityPhase.Waiting -> "Waiting"
    DesktopFileSyncTrayActivityPhase.Conflict -> "Conflict"
    DesktopFileSyncTrayActivityPhase.Failed -> "Failed"
    DesktopFileSyncTrayActivityPhase.Completed -> "Synced"
}

private fun formatTrayBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatTrayTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

private val TraySuccessColor = Color(0xFF2E7D32)
private val TraySuccessContainerColor = Color(0xFFE6F4E7)
