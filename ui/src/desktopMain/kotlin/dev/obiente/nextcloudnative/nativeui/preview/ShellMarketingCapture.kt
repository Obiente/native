package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
import kotlinx.coroutines.delay

/** Let the real modal sheet finish entering before accepting its open-state capture. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal suspend fun settleShellSwitcherCapture(scenario: MarketingCaptureScenario, scene: ImageComposeScene) {
    if (scenario != MarketingCaptureScenario.ShellAppSwitcherMobile) return
    repeat(40) {
        scene.render(System.nanoTime()).close()
        delay(16)
    }
    fun hasVisibleSwitcher(node: SemanticsNode): Boolean =
        (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Switch app" } == true &&
            node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0 && node.boundsInRoot.top >= 0) ||
            node.children.any(::hasVisibleSwitcher)
    check(scene.semanticsOwners.any { hasVisibleSwitcher(it.rootSemanticsNode) }) {
        "The app switcher must be visible before its capture can be published."
    }
}
