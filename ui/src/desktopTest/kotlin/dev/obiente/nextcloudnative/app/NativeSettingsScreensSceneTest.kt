package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class NativeSettingsScreensSceneTest {
    private val session = NextcloudSession("https://cloud.example.test", "admin", "synthetic-token")

    @Test
    fun `administrator disables an account with password confirmation on phone and desktop`() {
        for ((width, height) in listOf(390 to 844, 1280 to 800)) {
            var bobEnabled = true
            val sent = CopyOnWriteArrayList<NextcloudApiRequest>()
            val passwords = CopyOnWriteArrayList<String>()
            val transport = AccountPasswordConfirmedTransport { _, request, secret ->
                sent += request
                passwords += secret.read { String(it) }
                bobEnabled = false
                PasswordConfirmedApiResult.Delivered(json(200, ocs("[]")))
            }
            val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
                when {
                    request.relativePath.endsWith("/cloud/users/details") -> json(200, ocs("""{"users":{"bob":${bob(bobEnabled)}}}"""))
                    request.relativePath.endsWith("/cloud/users/bob") -> json(200, ocs(bob(bobEnabled)))
                    request.relativePath.endsWith("/cloud/groups/details") -> json(200, ocs("""{"groups":[{"id":"staff","displayname":"Staff","usercount":1}]}"""))
                    else -> json(404, "")
                }
            }
            AdministrationTabRequests.request(AdministrationTab.Accounts)
            val desktop = width >= 900
            nativeSceneTest(width, height, content = {
                CompositionLocalProvider(
                    LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(desktop, desktop, desktop),
                    LocalAccountPasswordConfirmedTransport provides transport,
                ) {
                    AdminAppsScreen(
                        access = verifiedAccess(),
                        session = session,
                        signedInUserId = "admin",
                        execute = execute,
                        serverInfo = null,
                        onOpenApp = {},
                        onBack = {},
                    )
                }
            }) {
                settleUntil { has("Bob Example") }
                assertTrue(has("Accounts"))
                click("Bob Example")
                settleUntil { has("Last sign-in") }
                assertTrue(has("Staff") || has("staff"))
                click("Actions for Bob Example")
                click("Disable account")
                settleUntil { has("Account password") }
                assertTrue(has("Disable bob?"))
                replaceText("", "synthetic-password")
                click("Disable")
                settleUntil { has("bob was disabled.") }
                assertEquals(listOf("/ocs/v2.php/cloud/users/bob/disable"), sent.map { it.relativePath })
                assertEquals(listOf("synthetic-password"), passwords)
                assertFalse(has("Account password"))
                capture("admin-accounts-disable-$width")
            }
        }
    }

    @Test
    fun `personal info saves changed fields after password confirmation`() {
        var phone = "+1 555 0100"
        val sent = CopyOnWriteArrayList<NextcloudApiRequest>()
        val transport = AccountPasswordConfirmedTransport { _, request, _ ->
            sent += request
            phone = "+1 555 0199"
            PasswordConfirmedApiResult.Delivered(json(200, ocs("[]")))
        }
        val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
            if (request.relativePath.endsWith("/fields")) json(200, ocs("""["phone","displayname"]"""))
            else json(200, ocs("""{"id":"alice","displayname":"Alice Example","displaynameScope":"v2-federated",
                "phone":"$phone","phoneScope":"v2-local","email":"alice@example.test","emailScope":"v2-local","groups":[]}"""))
        }
        nativeSceneTest(900, 1200, content = {
            CompositionLocalProvider(LocalAccountPasswordConfirmedTransport provides transport) {
                val scope = rememberCoroutineScope()
                val controller = remember { AccountProfileController(scope, session, execute, transport) }
                androidx.compose.runtime.LaunchedEffect(controller) { controller.refresh() }
                PersonalInfoSettingsSection(controller, onOpenStatus = null)
            }
        }) {
            settleUntil { hasFieldText("+1 555 0100") }
            assertTrue(has("Managed by your server"))
            replaceText("+1 555 0100", "+1 555 0199")
            click("Save changes")
            settleUntil { has("Account password") }
            assertTrue(has("- Change Phone number"))
            replaceText("", "synthetic-password")
            click("Save")
            settleUntil { has("Saved") }
            assertEquals("key=phone&value=%2B1%20555%200199", sent.single().body?.decodeToString())
            capture("personal-info-saved")
        }
    }

    @Test
    fun `without a password transport profile changes stay read-only`() {
        nativeSceneTest(600, 900, content = {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AccountProfileController(scope, session, { request ->
                    if (request.relativePath.endsWith("/fields")) json(200, ocs("""["phone"]"""))
                    else json(200, ocs("""{"id":"alice","displayname":"Alice","phone":"1","phoneScope":"v2-local"}"""))
                }, transport = null)
            }
            androidx.compose.runtime.LaunchedEffect(controller) { controller.refresh() }
            PersonalInfoSettingsSection(controller, onOpenStatus = {})
        }) {
            settleUntil { has("Online status") && hasFieldText("1") }
            assertTrue(has(ConfirmedWriteRejection.TransportMissing.message))
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

    private fun bob(enabled: Boolean) =
        """{"id":"bob","displayname":"Bob Example","email":"bob@example.test","enabled":$enabled,"groups":["staff"],"subadmin":[],"lastLogin":0}"""

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""

    private fun json(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
}
