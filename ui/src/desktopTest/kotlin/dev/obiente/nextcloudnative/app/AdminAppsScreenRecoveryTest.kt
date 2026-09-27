package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class AdminAppsScreenRecoveryTest {
    @Test
    fun failedRevalidationKeepsRecoveryControlsWithoutNavigatingAway() {
        val failures = listOf(
            NativeAppCatalogResult.Forbidden to AdminAppsContent.Failure.Forbidden,
            NativeAppCatalogResult.Unavailable to AdminAppsContent.Failure.Unavailable,
            NativeAppCatalogResult.InvalidResponse("Synthetic invalid response") to AdminAppsContent.Failure.InvalidResponse,
        )
        for ((width, height) in listOf(390 to 844, 1280 to 800)) {
            for ((result, expected) in failures) {
                val allowed = AdministrationAccessState(
                    result = NativeAppCatalogResult.Available(NativeAppCatalog(
                        contract = NativeAppCatalogContract.ProvisioningOcsV1,
                        apps = listOf(NativeManagedApp("sample_app", "Sample app", installed = true, enabled = true)),
                        administratorAuthorized = true,
                        includesAvailableApps = false, includesUpdateAvailability = false,
                    )),
                    checkedAt = TimeSource.Monotonic.markNow(),
                )
                val state = mutableStateOf(allowed)
                var backCalls = 0
                var retries = 0
                val desktop = width >= 900
                nativeSceneTest(width, height, content = {
                    CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides
                        NextcloudWorkspaceCapabilities(desktop, desktop, desktop)
                    ) {
                        AdminAppsScreen(
                            access = AdministrationAccessController(
                                state.value, permissionCheck = { state.value.canAdminister }, refresh = { retries++ },
                            ),
                            onContinueInBrowser = { error("A failed check must never open administration") },
                            serverInfo = null,
                            onOpenApp = {},
                            onBack = { backCalls++ },
                        )
                    }
                }) {
                    assertTrue(has("Sample app"))
                    state.value = AdministrationAccessState(checking = true)
                    settle()
                    assertFalse(has("Sample app"))
                    assertEquals(0, backCalls)
                    state.value = AdministrationAccessState(result = result)
                    settle()
                    assertTrue(has(expected.message))
                    assertFalse(has("Sample app"))
                    assertFalse(has("Find an app"))
                    assertEquals(0, backCalls)
                    capture("admin-revalidation-$width-${expected.name.lowercase()}")
                    click("Back")
                    assertEquals(1, backCalls)
                    click("Try again")
                    assertEquals(1, retries)
                    state.value = allowed
                    settle()
                    assertTrue(has("Sample app"))
                    assertEquals(1, backCalls)
                }
            }
        }
    }
}
