package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudSharedElement

/**
 * Header for a full-page screen with Back, a title, and an optional subtitle. Give
 * [titleSharedKey] when the same title was just shown elsewhere, such as the item opened from a
 * list, so the title carries across the navigation.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    compact: Boolean = false,
    titleSharedKey: String? = null,
    trailingContent: @Composable () -> Unit = {},
) {
    val workspace = LocalNextcloudWorkspaceCapabilities.current
    val desktop = workspace.isDesktop
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(
                min = when {
                    desktop -> 62.dp
                    compact -> 54.dp
                    else -> 76.dp
                },
            )
            .padding(horizontal = if (desktop) NextcloudSpacing.Large else NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) NextcloudSpacing.Medium else NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (desktop) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        NextcloudIcons.Back,
                        contentDescription = "Back",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.nextcloudSharedElement(titleSharedKey),
                style = when {
                    desktop -> MaterialTheme.typography.titleLarge
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
