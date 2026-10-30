package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudReducedMotion
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudSharedElementHost
import dev.obiente.nextcloudnative.app.design.NextcloudSharedElements
import dev.obiente.nextcloudnative.app.design.nextcloudSharedElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedElementAndTabMotionTest {
    @Test
    fun aTitleShownOnTheNextScreenFliesFromWhereItWasAndLands() {
        val registry = NextcloudSharedElements()
        val detail = mutableStateOf(false)
        nativeSceneTest(500, 600, content = { TwoScreens(registry, detail.value, reduced = false) }) {
            assertEquals(0, registry.flights.size)
            detail.value = true
            frames()
            assertEquals(1, registry.flights.size, "The shared title flies to the detail header")
            assertTrue(has("Quarterly report"), "The title stays available to screen readers in its new place")
            settleUntil { registry.flights.isEmpty() }
            assertEquals(0, registry.flights.size, "The flight lands and the title draws in place again")
            detail.value = false
            frames()
            assertEquals(1, registry.flights.size, "Going back carries the title home again")
            settleUntil { registry.flights.isEmpty() }
        }
    }

    @Test
    fun anElementAppearingWithoutNavigationDoesNotFly() {
        val registry = NextcloudSharedElements()
        val shown = mutableStateOf(true)
        nativeSceneTest(500, 600, content = {
            NextcloudSharedElementHost(Modifier.fillMaxSize(), registry) {
                Column(Modifier.padding(top = if (shown.value) 0.dp else 120.dp)) {
                    if (shown.value) Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                }
            }
        }) {
            shown.value = false
            frames()
            shown.value = true
            frames()
            assertEquals(0, registry.flights.size, "A row scrolling back into view is not a navigation")
        }
    }

    @Test
    fun aDetailThatShowsItsTitleOnlyAfterLoadingStillFliesFromTheRow() {
        val registry = NextcloudSharedElements()
        val detail = mutableStateOf(false)
        val loading = mutableStateOf(true)
        nativeSceneTest(500, 600, content = {
            NextcloudSharedElementHost(Modifier.fillMaxSize(), registry) {
                NextcloudContentEntrance(detail.value, NextcloudEntranceAxis.Horizontal, Modifier.fillMaxSize(), directional = true) {
                    if (!detail.value) {
                        Column(Modifier.padding(start = 160.dp, top = 320.dp)) {
                            Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                        }
                    } else {
                        NextcloudContentEntrance(loading.value, NextcloudEntranceAxis.Fade, Modifier.fillMaxSize()) {
                            if (loading.value) Text("Loading") else Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                        }
                    }
                }
            }
        }) {
            detail.value = true
            frames()
            val loadingStarted = System.nanoTime()
            // Longer than a plain navigation window, as a detail read often is.
            while (System.nanoTime() - loadingStarted < 1_000_000_000L) settle()
            assertEquals(0, registry.flights.size)
            loading.value = false
            frames()
            assertEquals(1, registry.flights.size, "The row's title is still waiting for the loaded detail")
            settleUntil { registry.flights.isEmpty() }
        }
    }

    @Test
    fun aRowThatScrolledAwayBeforeANavigationDoesNotFlyIntoTheNextScreen() {
        val registry = NextcloudSharedElements()
        val rowVisible = mutableStateOf(true)
        val screen = mutableStateOf(0)
        nativeSceneTest(500, 600, content = {
            NextcloudSharedElementHost(Modifier.fillMaxSize(), registry) {
                NextcloudContentEntrance(screen.value, NextcloudEntranceAxis.Horizontal, Modifier.fillMaxSize(), directional = true) {
                    if (screen.value == 0) {
                        if (rowVisible.value) Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                    } else {
                        Column(Modifier.padding(top = 300.dp)) { Text("Quarterly report", Modifier.nextcloudSharedElement("report")) }
                    }
                }
            }
        }) {
            rowVisible.value = false
            frames()
            val scrolledAway = System.nanoTime()
            while (System.nanoTime() - scrolledAway < 300_000_000L) settle()
            screen.value = 1
            frames()
            assertEquals(0, registry.flights.size, "Only elements removed by the navigation itself carry across")
        }
    }

    @Test
    fun reducedMotionNeverFlies() {
        val registry = NextcloudSharedElements()
        val detail = mutableStateOf(false)
        nativeSceneTest(500, 600, content = { TwoScreens(registry, detail.value, reduced = true) }) {
            detail.value = true
            frames()
            assertEquals(0, registry.flights.size)
            assertTrue(has("Quarterly report"))
        }
    }

    @Test
    fun aLaterTabArrivesFromTheEndAndAnEarlierOneFromTheStart() {
        val tab = mutableStateOf(0)
        nativeSceneTest(400, 300, content = {
            NextcloudContentEntrance(tab.value, NextcloudEntranceAxis.Horizontal, order = tab.value) {
                Text("Tab ${tab.value}")
            }
        }) {
            val rest = left("Tab 0")
            tab.value = 2
            frames()
            assertTrue(left("Tab 2") > rest, "A tab to the right slides in from the right")
            settleUntil { left("Tab 2") == rest }
            tab.value = 1
            frames()
            assertTrue(left("Tab 1") < rest, "A tab to the left slides in from the left, even though it was never shown")
        }
    }

    @androidx.compose.runtime.Composable
    private fun TwoScreens(registry: NextcloudSharedElements, detail: Boolean, reduced: Boolean) {
        CompositionLocalProvider(LocalNextcloudReducedMotion provides reduced) {
            NextcloudSharedElementHost(Modifier.fillMaxSize(), registry) {
                NextcloudContentEntrance(detail, NextcloudEntranceAxis.Horizontal, Modifier.fillMaxSize(), directional = true) {
                    if (detail) {
                        Column(Modifier.padding(start = 24.dp, top = 24.dp)) {
                            Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                            Text("Details")
                        }
                    } else {
                        Column(Modifier.padding(start = 160.dp, top = 320.dp)) {
                            Text("Quarterly report", Modifier.nextcloudSharedElement("report"))
                        }
                    }
                }
            }
        }
    }

    private fun NativeSceneTestDriver.frames() {
        scene.render(System.nanoTime()).close()
        scene.render(System.nanoTime() + 16_000_000L).close()
    }

    private fun NativeSceneTestDriver.left(label: String): Float = assertNotNull(node(label), label).boundsInRoot.left
}
