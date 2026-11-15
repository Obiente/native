package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudListItem
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** The account's notification settings: what this app does, and the server settings it can only describe. */
@Composable
internal fun NotificationSettingsScreen(
    sections: List<NotificationSettingsSection>,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
            Text(
                "Notification settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = NextcloudSpacing.Small).semantics { heading() },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                sections.forEach { section ->
                    item(key = "section:${section.key}") { NotificationSettingsSectionHeader(section) }
                    items(section.rows, key = { row -> "row:${section.key}:${row.key}" }) { row ->
                        NextcloudListItem(
                            title = row.title,
                            icon = when (row.support) {
                                NotificationSettingSupport.Available -> NextcloudIcons.CheckCircle
                                NotificationSettingSupport.Unavailable -> NextcloudIcons.Close
                                NotificationSettingSupport.ReadOnly -> NextcloudIcons.Info
                            },
                            supportingText = row.description,
                            trailing = {
                                Text(
                                    notificationSettingSupportLabel(row.support),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsSectionHeader(section: NotificationSettingsSection) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = NextcloudSpacing.Large,
            end = NextcloudSpacing.Large,
            top = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Small,
        ),
    ) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        section.explanation?.let { explanation ->
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = NextcloudSpacing.Small),
            )
        }
    }
}
