package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii

internal data class MediaBackupStatusPresentation(
    val label: String,
    val icon: ImageVector,
)

internal fun MediaBackupStatus.presentation(): MediaBackupStatusPresentation = when (this) {
    MediaBackupStatus.Pending -> MediaBackupStatusPresentation("Pending", NextcloudIcons.Schedule)
    MediaBackupStatus.Uploading -> MediaBackupStatusPresentation("Uploading", NextcloudIcons.Refresh)
    MediaBackupStatus.BackedUp -> MediaBackupStatusPresentation("Backed up", NextcloudIcons.CheckCircle)
    MediaBackupStatus.ChangedAfterBackup -> MediaBackupStatusPresentation("Changed", NextcloudIcons.Error)
    MediaBackupStatus.Failed -> MediaBackupStatusPresentation("Failed", NextcloudIcons.Error)
    MediaBackupStatus.CloudOnly -> MediaBackupStatusPresentation("Cloud only", NextcloudIcons.Cloud)
}

@Composable
internal fun MediaBackupStatusIndicator(
    status: MediaBackupStatus,
    modifier: Modifier = Modifier,
) {
    val presentation = status.presentation()
    val compact = status == MediaBackupStatus.BackedUp || status == MediaBackupStatus.CloudOnly
    val containerColor = when (status) {
        MediaBackupStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        MediaBackupStatus.ChangedAfterBackup -> MaterialTheme.colorScheme.tertiaryContainer
        MediaBackupStatus.Uploading -> MaterialTheme.colorScheme.primaryContainer
        MediaBackupStatus.BackedUp -> MaterialTheme.colorScheme.secondaryContainer
        MediaBackupStatus.Pending,
        MediaBackupStatus.CloudOnly,
        -> MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }
    val contentColor = when (status) {
        MediaBackupStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        MediaBackupStatus.ChangedAfterBackup -> MaterialTheme.colorScheme.onTertiaryContainer
        MediaBackupStatus.Uploading -> MaterialTheme.colorScheme.onPrimaryContainer
        MediaBackupStatus.BackedUp -> MaterialTheme.colorScheme.onSecondaryContainer
        MediaBackupStatus.Pending,
        MediaBackupStatus.CloudOnly,
        -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .semantics { contentDescription = presentation.label }
            .then(if (compact) Modifier.size(24.dp) else Modifier),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(NextcloudRadii.Pill),
        shadowElevation = 1.dp,
    ) {
        if (compact) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                modifier = Modifier.padding(5.dp),
            )
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = presentation.icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
                Text(presentation.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
