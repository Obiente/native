package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
data class NextcloudNavigationItem(
    val destination: NextcloudDestination,
    val label: String,
)

val DefaultNextcloudNavigationItems = listOf(
    NextcloudNavigationItem(NextcloudDestination.Home, "Home"),
    NextcloudNavigationItem(NextcloudDestination.Apps, "Apps"),
    NextcloudNavigationItem(NextcloudDestination.Activity, "Activity"),
    NextcloudNavigationItem(NextcloudDestination.Settings, "Settings"),
)

val DesktopNextcloudNavigationItems = listOf(
    NextcloudNavigationItem(NextcloudDestination.Home, "Overview"),
    NextcloudNavigationItem(NextcloudDestination.FolderSync, "Folder sync"),
    NextcloudNavigationItem(NextcloudDestination.Activity, "Activity"),
    NextcloudNavigationItem(NextcloudDestination.Apps, "Apps"),
    NextcloudNavigationItem(NextcloudDestination.Settings, "Settings"),
)

@Composable
fun NextcloudBottomNavigation(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    modifier: Modifier = Modifier,
    items: List<NextcloudNavigationItem> = DefaultNextcloudNavigationItems,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
        )
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            items.forEach { item ->
                val icon = NextcloudIcons.destination(item.destination)
                NavigationBarItem(
                    selected = selected == item.destination,
                    onClick = { onSelected(item.destination) },
                    icon = {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected == item.destination) 28.dp else 26.dp),
                        )
                    },
                    label = {
                        Text(
                            item.label,
                            fontWeight = if (selected == item.destination) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                        unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }
    }
}

/** Wide-layout navigation using the same destinations and visual state as the bottom bar. */
@Composable
fun NextcloudNavigationRail(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    modifier: Modifier = Modifier,
    items: List<NextcloudNavigationItem> = DefaultNextcloudNavigationItems,
) {
    Box(
        modifier = modifier
            .width(88.dp)
            .fillMaxHeight(),
    ) {
        NavigationRail(
            modifier = Modifier.fillMaxSize(),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            items.forEach { item ->
                val icon = NextcloudIcons.destination(item.destination)
                NavigationRailItem(
                    selected = selected == item.destination,
                    onClick = { onSelected(item.destination) },
                    icon = {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected == item.destination) 28.dp else 26.dp),
                        )
                    },
                    label = {
                        Text(
                            item.label,
                            fontWeight = if (selected == item.destination) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                        unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.align(Alignment.TopCenter),
            thickness = 1.dp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
        )
        VerticalDivider(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            thickness = 1.dp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Reusable app-grid tile matching the compact native launcher direction. */
@Composable
fun NextcloudAppTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    accessibilityId: String = title,
    accessibilityDescription: String = "Open app $accessibilityId",
) {
    require(accessibilityId.isNotBlank()) { "App tile accessibility IDs must not be blank." }
    require(accessibilityDescription.isNotBlank()) {
        "App tile accessibility descriptions must not be blank."
    }
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = accessibilityDescription
        },
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = NextcloudTheme.colors.appTile,
            disabledContainerColor = NextcloudTheme.colors.appTile,
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (dense) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 10.dp),
        ) {
            androidx.compose.material3.Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NextcloudTheme.colors.appIcon,
                    modifier = Modifier
                        .padding(if (dense) 6.dp else NextcloudSpacing.Small)
                        .size(if (dense) 24.dp else 26.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingText?.let {
                    Text(
                        it,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Compact list item shared by files, conversations, settings, and search results. */
@Composable
fun NextcloudListItem(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    Row(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
            vertical = if (dense) NextcloudSpacing.Small else NextcloudSpacing.Medium,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (dense) NextcloudSpacing.Small else NextcloudSpacing.Medium,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (dense) 22.dp else 24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            supportingText?.let {
                Text(
                    it,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

object NextcloudContentPadding {
    val Screen = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium)
    val Grid = PaddingValues(NextcloudSpacing.Large)
}
