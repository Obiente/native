package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppsWorkspaceSceneTest {
    @Test
    fun compactSearchFiltersAndOpensExactAppWithoutPlaceholderCopy() {
        var opened: String? = null
        nativeSceneTest(390, 844, content = {
            NativeAppsWorkspace(appsFixture(), null, null, pinnedAppIds = emptyList(),
                onRetry = {}, onSettings = {}, onSearch = {}, onOpenApp = { opened = it.id })
        }) {
            assertFalse(has("Open app"))
            replaceText("", "budget")
            assertTrue(has("Budget"))
            assertFalse(has("Calendar"))
            click("Budget")
            assertEquals("budget", opened)
            replaceText("budget", "missing")
            assertTrue(has("No app matches \"missing\"."))
        }
    }

    @Test
    fun categoryFilterAndOverflowPinPreserveExistingCallbacks() {
        val pins = mutableStateOf(emptyList<String>())
        nativeSceneTest(390, 844, content = {
            NativeAppsWorkspace(appsFixture(), null, null, pinnedAppIds = pins.value,
                onTogglePinnedApp = { pins.value = listOf(it); null },
                onRetry = {}, onSettings = {}, onSearch = {}, onOpenApp = {})
        }) {
            click("Planning")
            assertTrue(has("Calendar"))
            assertFalse(has("Budget"))
            click("Actions for Calendar")
            click("Pin to shortcuts")
            assertEquals(listOf("calendar"), pins.value)
        }
    }

    private fun appsFixture() = NextcloudServerInfo("https://fixture.invalid", "Synthetic user", "fixture",
        null, null, null, listOf(NextcloudAppEntry("budget", "Budget", null),
            NextcloudAppEntry("calendar", "Calendar", null), NextcloudAppEntry("custom", "Custom", null)))
}
