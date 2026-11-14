package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
import kotlinx.coroutines.delay

/**
 * The conversation loads and parses its fixture off the render thread, so a fixed frame count can
 * finish before the messages are on screen. Keep rendering until the first fixture message is
 * shown, and refuse to publish an empty conversation.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal suspend fun settleConversationCapture(scenario: MarketingCaptureScenario, scene: ImageComposeScene) {
    if (scenario != MarketingCaptureScenario.HomepageConversationsDesktopDark &&
        scenario != MarketingCaptureScenario.HomepageConversationsDesktopLight
    ) {
        return
    }
    fun showsFirstMessage(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == FIRST_CONVERSATION_MESSAGE } == true ||
            node.children.any(::showsFirstMessage)
    fun loaded() = scene.semanticsOwners.any { showsFirstMessage(it.rootSemanticsNode) }
    val deadline = System.nanoTime() + CONVERSATION_SETTLE_TIMEOUT_NANOS
    while (!loaded() && System.nanoTime() < deadline) {
        delay(16)
        scene.render().close()
    }
    check(loaded()) { "The conversation must show its messages before its capture can be published." }
    // The messages arrived on the last frame; lay them out and scroll to the newest once more.
    repeat(CONVERSATION_SETTLED_FRAMES) {
        delay(16)
        scene.render().close()
    }
}

private const val FIRST_CONVERSATION_MESSAGE = "The product direction is ready for review."
private const val CONVERSATION_SETTLE_TIMEOUT_NANOS = 10_000_000_000L
private const val CONVERSATION_SETTLED_FRAMES = 3
