package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class NativeSettingsScreensSceneTest {
    private val session = NextcloudSession("https://cloud.example.test", "admin", "synthetic-token")

    @Test
    fun `administration shows accounts read-only with the handoff explanation on phone and desktop`() {
        for ((width, height) in listOf(390 to 844, 1280 to 800)) {
            val requests = CopyOnWriteArrayList<NextcloudApiRequest>()
            val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
                requests += request
                when {
                    request.relativePath.endsWith("/cloud/users/details") -> json(200, ocs("""{"users":{"bob":$BOB}}"""))
                    request.relativePath.endsWith("/cloud/users/bob") -> json(200, ocs(BOB))
                    else -> json(404, "")
                }
            }
            AdministrationTabRequests.request(AdministrationTab.Accounts)
            val desktop = width >= 900
            nativeSceneTest(width, height, content = {
                CompositionLocalProvider(
                    LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(desktop, desktop, desktop),
                ) {
                    AdminAppsScreen(verifiedAccess(), session, execute, serverInfo = null, onOpenApp = {}, onBack = {})
                }
            }) {
                settleUntil { has("Bob Example") }
                click("Bob Example")
                settleUntil { has("Last sign-in") }
                assertTrue(has("staff (group administrator)"))
                assertTrue(has(PASSWORD_CONFIRMATION_HANDOFF_TITLE))
                assertFalse(has("Actions for Bob Example"))
                assertFalse(has("Account password"))
                assertTrue(requests.all { it.method == NextcloudApiMethod.GET })
                capture("admin-accounts-read-only-$width")
            }
        }
    }

    @Test
    fun `app catalog offers no lifecycle actions without an authenticated handoff`() {
        nativeSceneTest(1280, 800, content = {
            AdminAppsScreen(verifiedAccess(), session, { json(404, "") }, serverInfo = null, onOpenApp = {}, onBack = {})
        }) {
            settleUntil { has("Sample app") }
            assertTrue(has(PASSWORD_CONFIRMATION_HANDOFF_TITLE))
            assertFalse(has("Actions for Sample app"))
        }
    }

    @Test
    fun `personal info shows values, visibility, and the handoff explanation`() {
        val requests = CopyOnWriteArrayList<NextcloudApiRequest>()
        nativeSceneTest(900, 1200, content = {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AccountProfileController(scope) { request ->
                    requests += request
                    json(200, ocs("""{"id":"alice","displayname":"Alice Example","displaynameScope":"v2-federated",
                        "phone":"+1 555 0100","phoneScope":"v2-local","email":"alice@example.test","emailScope":"v2-local","groups":[]}"""))
                }
            }
            LaunchedEffect(controller) { controller.refresh() }
            PersonalInfoSettingsSection(controller, onOpenStatus = {})
        }) {
            settleUntil { has("+1 555 0100") }
            assertTrue(has("Online status"))
            assertTrue(has("Federated"))
            assertTrue(has(PASSWORD_CONFIRMATION_HANDOFF_MESSAGE))
            assertFalse(hasFieldText("+1 555 0100"))
            assertTrue(requests.all { it.method == NextcloudApiMethod.GET })
            capture("personal-info-read-only")
        }
    }

    private fun verifiedAccess() = AdministrationAccessState(
        result = NativeAppCatalogResult.Available(
            NativeAppCatalog(
                contract = NativeAppCatalogContract.ProvisioningOcsV1,
                apps = listOf(NativeManagedApp("sample_app", "Sample app", installed = true, enabled = true)),
                administratorAuthorized = true,
                includesAvailableApps = false,
                includesUpdateAvailability = false,
            ),
        ),
        checkedAt = TimeSource.Monotonic.markNow(),
    ).let { state -> AdministrationAccessController(state, permissionCheck = { true }, refresh = {}) }

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""

    private fun json(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)

    private companion object {
        const val BOB =
            """{"id":"bob","displayname":"Bob Example","email":"bob@example.test","enabled":true,"groups":["staff"],"subadmin":["staff"],"lastLogin":0}"""
    }
}
