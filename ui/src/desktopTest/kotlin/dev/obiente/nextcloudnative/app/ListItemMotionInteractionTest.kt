package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.nextcloudItemMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ListItemMotionInteractionTest {
    @Test
    fun reorderedItemsKeepTheirStateAndGlideOnlyWithFullMotion() {
        for (reduced in listOf(false, true)) {
            val order = mutableStateOf(listOf("alpha", "beta", "gamma"))
            val created = mutableMapOf<String, Int>()
            nativeSceneTest(400, 600, content = {
                CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
                    LazyColumn {
                        items(order.value, key = { it }) { name ->
                            val instance = remember { created.merge(name, 1, Int::plus) }
                            Text(
                                "$name instance $instance",
                                modifier = nextcloudItemMotion().fillMaxWidth().height(48.dp),
                            )
                        }
                    }
                }
            }) {
                val gammaRest = top("gamma")
                order.value = listOf("gamma", "alpha", "beta")
                scene.render(System.nanoTime()).close()
                scene.render(System.nanoTime() + 16_000_000L).close()
                val firstFrames = top("gamma")
                if (reduced) {
                    assertEquals(0f, firstFrames, "Reduced motion places a moved item at once")
                } else {
                    assertTrue(firstFrames > 0f, "A moved item glides from where it was")
                }
                settleUntil { top("gamma") == 0f }
                assertEquals(0f, top("gamma"))
                assertTrue(gammaRest > 0f)
                assertEquals(mapOf("alpha" to 1, "beta" to 1, "gamma" to 1), created, "Stable keys keep each item's state")
            }
        }
    }

    private fun NativeSceneTestDriver.top(name: String): Float =
        assertNotNull(nodes().firstOrNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.any { it.text.startsWith("$name instance") } == true
        }, "Missing $name").boundsInRoot.top
}
