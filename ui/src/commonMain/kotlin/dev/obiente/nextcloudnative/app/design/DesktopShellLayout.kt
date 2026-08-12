package dev.obiente.nextcloudnative.app.design

/**
 * Presentation is selected by the platform entry point, not inferred from window width.
 *
 * A large Android device should keep Android navigation conventions, while a resized desktop
 * window should keep desktop chrome. Width only selects a compact or expanded variant inside the
 * chosen presentation.
 */
enum class NextcloudPresentation {
    Adaptive,
    Desktop,
}

enum class NextcloudNavigationStyle {
    BottomBar,
    CompactRail,
    ExpandedSidebar,
}

/**
 * Identifies the content presented beside the persistent desktop navigation.
 *
 * The global Nextcloud sidebar remains useful in both cases: pinned and recent apps stay reachable
 * while an app is open, so switching workspaces never requires returning to the Apps destination.
 * Contextual app navigation belongs inside the app content area rather than replacing the global
 * workspace switcher.
 */
enum class NextcloudDesktopWorkspaceKind {
    Root,
    AppWorkspace,
}

data class NextcloudRootShellLayout(
    val navigationStyle: NextcloudNavigationStyle,
    val navigationWidthDp: Int,
    val workspaceMarginDp: Int,
    val contentMaximumWidthDp: Int?,
    val supportsAuxiliaryPane: Boolean,
)

/** Desktop always keeps its shell; adaptive layouts keep it for root and top-level app workspaces. */
fun shouldUseNextcloudRootShell(
    presentation: NextcloudPresentation,
    isRootOrAppWorkspace: Boolean,
): Boolean = presentation == NextcloudPresentation.Desktop || isRootOrAppWorkspace

/** Pure, platform-neutral layout policy used by the Compose shell and unit tests. */
fun resolveNextcloudRootShellLayout(
    presentation: NextcloudPresentation,
    availableWidthDp: Int,
    destination: NextcloudDestination,
    desktopWorkspaceKind: NextcloudDesktopWorkspaceKind = NextcloudDesktopWorkspaceKind.Root,
): NextcloudRootShellLayout = when (presentation) {
    NextcloudPresentation.Adaptive -> {
        if (availableWidthDp < NextcloudWorkspaceBreakpoints.AdaptiveRailDp) {
            NextcloudRootShellLayout(
                navigationStyle = NextcloudNavigationStyle.BottomBar,
                navigationWidthDp = 0,
                workspaceMarginDp = 0,
                contentMaximumWidthDp = null,
                supportsAuxiliaryPane = false,
            )
        } else {
            NextcloudRootShellLayout(
                navigationStyle = NextcloudNavigationStyle.CompactRail,
                navigationWidthDp = AdaptiveRailWidthDp,
                workspaceMarginDp = 0,
                contentMaximumWidthDp = when (destination) {
                    NextcloudDestination.Apps -> 1_120
                    NextcloudDestination.FolderSync -> 1_120
                    else -> 720
                },
                supportsAuxiliaryPane = false,
            )
        }
    }

    NextcloudPresentation.Desktop -> {
        val expanded = availableWidthDp >= NextcloudWorkspaceBreakpoints.DesktopSidebarDp
        NextcloudRootShellLayout(
            navigationStyle = if (expanded) {
                NextcloudNavigationStyle.ExpandedSidebar
            } else {
                NextcloudNavigationStyle.CompactRail
            },
            navigationWidthDp = if (expanded) DesktopSidebarWidthDp else DesktopRailWidthDp,
            workspaceMarginDp = if (expanded) 12 else 8,
            // Desktop workspace content is allowed to use the available width. Individual reading
            // surfaces may still apply their own readable line length.
            contentMaximumWidthDp = null,
            supportsAuxiliaryPane = availableWidthDp >= DesktopAuxiliaryPaneBreakpointDp,
        )
    }
}

private const val AdaptiveRailWidthDp = 88
private const val DesktopAuxiliaryPaneBreakpointDp = 1_180
private const val DesktopSidebarWidthDp = 252
private const val DesktopRailWidthDp = 76
