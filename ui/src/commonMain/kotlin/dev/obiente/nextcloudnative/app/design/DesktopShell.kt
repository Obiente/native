package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp

@Immutable
data class NextcloudDesktopIdentity(
    val displayName: String,
    val cloudName: String,
    val avatar: ImageBitmap? = null,
    val connectionLabel: String = "Connected",
    val serverVersion: String? = null,
    val shortcuts: List<NextcloudDesktopSidebarApp> = emptyList(),
    val recentApp: NextcloudDesktopSidebarApp? = null,
    val syncSummary: String? = null,
    val storageLabel: String? = null,
    val storageProgress: Float? = null,
    val availableApps: List<NextcloudDesktopSidebarApp> = emptyList(),
    val accountScopeKey: String? = null,
)

@Immutable
data class NextcloudDesktopSidebarApp(
    val id: String,
    val label: String,
    val badge: String? = null,
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
    onOpenApp: (String) -> Unit = {},
    activeAppId: String? = null,
    workspaceKind: NextcloudDesktopWorkspaceKind = NextcloudDesktopWorkspaceKind.Root,
    navigationEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val focusRequester = remember { FocusRequester() }
        var sidebarExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val layout = resolveNextcloudRootShellLayout(
            presentation = NextcloudPresentation.Desktop,
            availableWidthDp = maxWidth.value.toInt(),
            destination = selected,
            desktopWorkspaceKind = workspaceKind,
            desktopSidebarExpanded = sidebarExpanded,
        )
        val canExpand = maxWidth.value >= NextcloudWorkspaceBreakpoints.DesktopSidebarDp
        val reducedMotion = LocalNextcloudReducedMotion.current
        val navigationWidth by animateDpAsState(
            layout.navigationWidthDp.dp,
            NextcloudMotion.content(reducedMotion),
            label = "desktop sidebar width",
        )
        val margin by animateDpAsState(
            layout.workspaceMarginDp.dp,
            NextcloudMotion.content(reducedMotion),
            label = "desktop workspace margin",
        )
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }

        val destinationKey = nextcloudShellDestinationKey(selected, activeAppId)
        NextcloudAppAccentScope(destinationKey) {
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
                            if (!navigationEnabled) return@onPreviewKeyEvent true
                            onSelected(destination)
                            true
                        }
                        .focusable(),
                    horizontalArrangement = Arrangement.spacedBy(margin),
                ) {
                    NextcloudDesktopNavigation(
                        selected = selected,
                        onSelected = onSelected,
                        identity = identity,
                        onOpenApp = onOpenApp,
                        activeAppId = activeAppId,
                        expanded = desktopSidebarShowsExpandedContent(navigationWidth.value),
                        canExpand = canExpand,
                        onToggleExpanded = {
                            sidebarExpanded = layout.navigationStyle != NextcloudNavigationStyle.ExpandedSidebar
                        },
                        navigationEnabled = navigationEnabled,
                        modifier = Modifier.width(navigationWidth).fillMaxHeight(),
                    )

                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(NextcloudRadii.Large),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        // A hairline separates the workspace from the window background in light themes.
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    ) {
                        NextcloudDestinationEntrance(
                            destinationKey = destinationKey,
                            modifier = Modifier.fillMaxSize(),
                            content = content,
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
