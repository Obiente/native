package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun PhotoAdaptiveNavigationLayout(
    intent: PhotoNavigationIntent,
    onDestinationSelected: (PhotoDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val movableContent = remember {
        movableContentOf {
            latestContent.value()
        }
    }
    when (intent.placement) {
        PhotoNavigationPlacement.BottomBar -> Column(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                movableContent()
            }
            PhotoBottomNavigation(intent, onDestinationSelected)
        }
        PhotoNavigationPlacement.CompactMenu -> Column(modifier.fillMaxSize()) {
            PhotoCompactNavigationMenu(intent, onDestinationSelected)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                movableContent()
            }
        }
        PhotoNavigationPlacement.NavigationRail -> Row(modifier.fillMaxSize()) {
            PhotoNavigationRail(intent, onDestinationSelected)
            Box(Modifier.fillMaxHeight().weight(1f)) {
                movableContent()
            }
        }
        PhotoNavigationPlacement.Sidebar -> Row(modifier.fillMaxSize()) {
            PhotoNavigationSidebar(intent, onDestinationSelected)
            Box(Modifier.fillMaxHeight().weight(1f)) {
                movableContent()
            }
        }
    }
}

@Composable
private fun PhotoBottomNavigation(
    intent: PhotoNavigationIntent,
    onDestinationSelected: (PhotoDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        NavigationBar {
            intent.destinations.forEach { destination ->
                NavigationBarItem(
                    selected = destination == intent.activeDestination,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        Icon(
                            photoDestinationIcon(destination),
                            contentDescription = null,
                        )
                    },
                    label = { Text(photoDestinationLabel(destination)) },
                )
            }
        }
    }
}

@Composable
private fun PhotoCompactNavigationMenu(
    intent: PhotoNavigationIntent,
    onDestinationSelected: (PhotoDestination) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
    ) {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(
                photoDestinationIcon(intent.activeDestination),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                photoDestinationLabel(intent.activeDestination),
                modifier = Modifier.padding(start = NextcloudSpacing.Small),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            intent.destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text(photoDestinationLabel(destination)) },
                    leadingIcon = {
                        Icon(
                            photoDestinationIcon(destination),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onDestinationSelected(destination)
                    },
                )
            }
        }
    }
}

@Composable
private fun PhotoNavigationRail(
    intent: PhotoNavigationIntent,
    onDestinationSelected: (PhotoDestination) -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            intent.destinations.forEach { destination ->
                NavigationRailItem(
                    selected = destination == intent.activeDestination,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        Icon(
                            photoDestinationIcon(destination),
                            contentDescription = null,
                        )
                    },
                    label = { Text(photoDestinationLabel(destination)) },
                    alwaysShowLabel = true,
                )
            }
        }
        VerticalDivider()
    }
}

@Composable
private fun PhotoNavigationSidebar(
    intent: PhotoNavigationIntent,
    onDestinationSelected: (PhotoDestination) -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(216.dp)
                .fillMaxHeight()
                .selectableGroup()
                .padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                "Library",
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Medium,
                    vertical = NextcloudSpacing.Small,
                ),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            intent.destinations.forEach { destination ->
                val selected = destination == intent.activeDestination
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onDestinationSelected(destination) },
                        ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(NextcloudRadii.Medium),
                    color = if (selected) {
                        androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Medium,
                            vertical = 12.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Icon(
                            photoDestinationIcon(destination),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            photoDestinationLabel(destination),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        VerticalDivider()
    }
}

internal fun photoDestinationLabel(destination: PhotoDestination): String = when (destination) {
    PhotoDestination.Timeline -> "Timeline"
    PhotoDestination.Folders -> "Folders"
    PhotoDestination.Albums -> "Albums"
    PhotoDestination.People -> "People"
    PhotoDestination.Favorites -> "Favorites"
}

internal fun photoDestinationSubtitle(destination: PhotoDestination): String = when (destination) {
    PhotoDestination.Timeline -> "Recent server media"
    PhotoDestination.Folders -> "Browse media by server folder"
    PhotoDestination.Albums -> "Albums and tags"
    PhotoDestination.People -> "Recognized people"
    PhotoDestination.Favorites -> "Favorite photos and videos"
}

private fun photoDestinationIcon(destination: PhotoDestination): ImageVector = when (destination) {
    PhotoDestination.Timeline -> NextcloudIcons.Photo
    PhotoDestination.Folders -> NextcloudIcons.FolderOpen
    PhotoDestination.Albums -> NextcloudIcons.Tag
    PhotoDestination.People -> NextcloudIcons.People
    PhotoDestination.Favorites -> NextcloudIcons.Favorite
}
