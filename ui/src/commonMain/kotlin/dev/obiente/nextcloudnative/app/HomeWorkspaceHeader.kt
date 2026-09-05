package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import androidx.compose.material3.AssistChip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun DashboardHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onCustomize: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Medium,
            vertical = NextcloudSpacing.Small,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(NextcloudIcons.Back, contentDescription = "Back")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(NextcloudIcons.Search, contentDescription = "Search Nextcloud")
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(NextcloudIcons.Refresh, contentDescription = dashboardRefreshDescription(title))
        }
        HomeWorkspaceActions(onCustomize, onSettings)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal fun dashboardRefreshDescription(title: String): String = "Refresh $title"

@Composable
internal fun DashboardQuickActionsCard(
    installedApps: List<NextcloudAppEntry>,
    pinnedAppIds: List<String>,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    val quickApps = remember(installedApps, pinnedAppIds) {
        installedApps
            .filter { canonicalAppWorkspaceId(it.id) in pinnedAppIds }
            .distinctBy { canonicalAppWorkspaceId(it.id) }
            .sortedBy { pinnedAppIds.indexOf(canonicalAppWorkspaceId(it.id)) }
            .take(MAX_APP_WORKSPACE_PINS)
    }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large)) {
            Text(
                "Quick actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (quickApps.isEmpty()) {
                Text(
                    if (pinnedAppIds.isEmpty()) {
                        "Pin apps from Apps to add quick actions."
                    } else {
                        "Your pinned apps are not currently available."
                    },
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    quickApps.forEach { app ->
                        AssistChip(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { onOpenApp(app) },
                            label = { Text(app.name, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.app(app.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

