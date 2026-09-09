package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell
import dev.obiente.nextcloudnative.app.design.NextcloudAppSwitcher
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation

/** Production shell controls around the existing synthetic Calendar editor. */
@Composable
internal fun MarketingShellCaptureScenario(scenario: MarketingCaptureScenario, assets: MarketingCaptureAssets) {
    val identity = marketingDesktopIdentity(avatar = assets.avatar)
    Box(Modifier.fillMaxSize()) {
        if (scenario.presentation == NextcloudPresentation.Desktop) {
            NextcloudDesktopShell(
                selected = NextcloudDestination.Apps,
                onSelected = {}, identity = identity, activeAppId = "calendar",
                workspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
            ) { MarketingCalendarEventEditorCapture() }
        } else {
            NextcloudAdaptiveShell(
                selected = NextcloudDestination.Apps,
                onSelected = {}, identity = identity, activeAppId = "calendar",
            ) { MarketingCalendarEventEditorCapture() }
        }
        if (scenario == MarketingCaptureScenario.ShellAppSwitcherMobile) {
            NextcloudAppSwitcher(
                identity, "calendar", NextcloudDestination.Apps, true, {}, {}, {},
            )
        }
    }
}
