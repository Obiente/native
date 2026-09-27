package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class AdminAppsContentTest {
    @Test
    fun failuresRemainVisibleWithoutExposingTheCatalog() {
        val cases = listOf(
            NativeAppCatalogResult.Forbidden to AdminAppsContent.Failure.Forbidden,
            NativeAppCatalogResult.Unavailable to AdminAppsContent.Failure.Unavailable,
            NativeAppCatalogResult.InvalidResponse("Synthetic invalid response") to AdminAppsContent.Failure.InvalidResponse,
        )
        for ((result, expected) in cases) {
            assertEquals(expected, adminAppsContent(AdministrationAccessState(result = result), canAdminister = false))
        }
    }

    @Test
    fun checkingAndRestoredUnknownStateNeverShowCatalogControls() {
        assertEquals(AdminAppsContent.Checking, adminAppsContent(AdministrationAccessState(), false))
        assertEquals(
            AdminAppsContent.Checking,
            adminAppsContent(AdministrationAccessState(checking = true), false),
        )
    }

    @Test
    fun expiredOrRevokedLiveAccessCannotRenderACachedCatalog() {
        val clock = TestTimeSource()
        val state = AdministrationAccessState(
            result = NativeAppCatalogResult.Available(NativeAppCatalog(
                contract = NativeAppCatalogContract.ProvisioningOcsV1,
                apps = emptyList(), administratorAuthorized = true,
                includesAvailableApps = false, includesUpdateAvailability = false,
            )),
            checkedAt = clock.markNow(),
        )
        assertIs<AdminAppsContent.Catalog>(adminAppsContent(state, true))
        assertEquals(AdminAppsContent.Failure.Expired, adminAppsContent(state, false))
        clock += 5.minutes
        assertEquals(AdminAppsContent.Failure.Expired, adminAppsContent(state, true))
    }
}
