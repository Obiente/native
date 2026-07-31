package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
data class NextcloudDesktopIdentity(
    val displayName: String,
    val cloudName: String,
    val avatar: ImageBitmap? = null,
)

internal fun accountAvatarContentDescription(displayName: String?): String {
    val name = displayName?.trim().orEmpty()
    return if (name.isEmpty()) "Account avatar" else "Account avatar for $name"
}

@Immutable
data class NextcloudWorkspaceCapabilities(
    val isDesktop: Boolean,
    val usesDenseControls: Boolean,
    val supportsAuxiliaryPane: Boolean,
)

val LocalNextcloudWorkspaceCapabilities = staticCompositionLocalOf {
    NextcloudWorkspaceCapabilities(
        isDesktop = false,
        usesDenseControls = false,
        supportsAuxiliaryPane = false,
    )
}

/**
 * Desktop application chrome. The navigation and account context remain visible while the
 * workspace changes, matching desktop information architecture instead of a phone tab bar.
 */
@Composable
fun NextcloudDesktopShell(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    workspaceKind: NextcloudDesktopWorkspaceKind = NextcloudDesktopWorkspaceKind.Root,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val focusRequester = remember { FocusRequester() }
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Desktop,
            availableWidthDp = maxWidth.value.toInt(),
            destination = selected,
            desktopWorkspaceKind = workspaceKind,
        )
        val margin = layout.workspaceMarginDp.dp
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }

        CompositionLocalProvider(
            LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                isDesktop = true,
                usesDenseControls = true,
                supportsAuxiliaryPane = layout.supportsAuxiliaryPane,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(margin)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val shortcut = when (event.key) {
                            Key.One -> NextcloudDesktopShortcutKey.One
                            Key.Two -> NextcloudDesktopShortcutKey.Two
                            Key.Three -> NextcloudDesktopShortcutKey.Three
                            Key.Four -> NextcloudDesktopShortcutKey.Four
                            Key.Comma -> NextcloudDesktopShortcutKey.Comma
                            else -> null
                        } ?: return@onPreviewKeyEvent false
                        val destination = destinationForNextcloudDesktopShortcut(
                            key = shortcut,
                            primaryModifierPressed = event.isCtrlPressed || event.isMetaPressed,
                        ) ?: return@onPreviewKeyEvent false
                        onSelected(destination)
                        true
                    }
                    .focusable(),
                horizontalArrangement = Arrangement.spacedBy(margin),
            ) {
                when (layout.navigationStyle) {
                    NextcloudNavigationStyle.ExpandedSidebar -> NextcloudDesktopSidebar(
                        selected = selected,
                        onSelected = onSelected,
                        identity = identity,
                        modifier = Modifier.width(layout.navigationWidthDp.dp).fillMaxHeight(),
                    )

                    NextcloudNavigationStyle.CompactRail -> NextcloudDesktopCompactRail(
                        selected = selected,
                        onSelected = onSelected,
                        modifier = Modifier.width(layout.navigationWidthDp.dp).fillMaxHeight(),
                    )

                    NextcloudNavigationStyle.BottomBar -> Unit
                }

                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}

@Composable
private fun NextcloudDesktopSidebar(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = NextcloudTheme.colors.appIconContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Icon(
                        imageVector = NextcloudIcons.Cloud,
                        contentDescription = null,
                        tint = NextcloudTheme.colors.appIcon,
                        modifier = Modifier.padding(9.dp).size(25.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Nextcloud Native",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Desktop workspace",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = "WORKSPACE",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DefaultNextcloudNavigationItems.forEach { item ->
                NextcloudDesktopNavigationRow(
                    item = item,
                    selected = selected == item.destination,
                    onClick = { onSelected(item.destination) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            identity?.let { account ->
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NextcloudContextMenuArea(
                    items = {
                        listOf(
                            NextcloudContextMenuItem("Open Settings") {
                                onSelected(NextcloudDestination.Settings)
                            },
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = NextcloudTheme.colors.appIconContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (account.avatar != null) {
                                    Image(
                                        bitmap = account.avatar,
                                        contentDescription =
                                            accountAvatarContentDescription(account.displayName),
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(
                                        imageVector = NextcloudIcons.Profile,
                                        contentDescription = null,
                                        tint = NextcloudTheme.colors.appIcon,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = account.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = account.cloudName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextcloudDesktopNavigationRow(
    item: NextcloudNavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NextcloudRadii.Small)
    NextcloudContextMenuArea(
        items = {
            listOf(NextcloudContextMenuItem("Open ${item.label}", onClick = onClick))
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = NextcloudIcons.destination(item.destination),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun NextcloudDesktopCompactRail(
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Icon(
                    imageVector = NextcloudIcons.Cloud,
                    contentDescription = "Nextcloud Native",
                    tint = NextcloudTheme.colors.appIcon,
                    modifier = Modifier.padding(9.dp).size(25.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DefaultNextcloudNavigationItems.forEach { item ->
                val isSelected = selected == item.destination
                NextcloudContextMenuArea(
                    items = {
                        listOf(
                            NextcloudContextMenuItem("Open ${item.label}") {
                                onSelected(item.destination)
                            },
                        )
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(NextcloudRadii.Small))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { onSelected(item.destination) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NextcloudIcons.destination(item.destination),
                            contentDescription = item.label,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable desktop master-detail surface for Files, Mail, Tables and other semantic data views.
 * The caller owns selection and data state; this component only supplies desktop pane geometry.
 */
@Composable
fun NextcloudDesktopMasterDetail(
    modifier: Modifier = Modifier,
    masterWidthDp: Int = 340,
    master: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(masterWidthDp.dp).fillMaxHeight()) { master() }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}
