package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminAppManagementTest {
    @Test
    fun `new app-store inventory exposes state updates and capability-gated actions`() = runBlocking {
        val requests = mutableListOf<NextcloudApiRequest>()
        val result = loadNativeAppCatalog { request ->
            requests += request
            jsonResponse(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
                  {
                    "id":"enabled_app","name":"Enabled app","summary":"Ready",
                    "version":"2.0.0","installed":true,"active":true,
                    "isCompatible":true,"internal":false,"removable":true,
                    "app_api":false,"groups":[],"update":"2.1.0"
                  },
                  {
                    "id":"available_app","name":"Available app","version":"1.0.0",
                    "installed":false,"active":false,"isCompatible":true,
                    "internal":false,"removable":false,"app_api":false,
                    "groups":[],"missingDependencies":[]
                  },
                  {
                    "id":"external_app","name":"External app","version":"1.0.0",
                    "installed":true,"active":true,"isCompatible":true,
                    "internal":false,"removable":true,"app_api":true,"groups":[]
                  }
                ]}}
                """.trimIndent(),
            )
        }

        val catalog = assertIs<NativeAppCatalogResult.Available>(result).catalog
        assertEquals(NativeAppCatalogContract.AppStoreOcsV1, catalog.contract)
        assertTrue(catalog.administratorAuthorized)
        assertTrue(catalog.includesAvailableApps)
        assertTrue(catalog.includesUpdateAvailability)
        assertEquals(1, requests.size)
        assertEquals(NextcloudApiMethod.GET, requests.single().method)
        assertEquals(mapOf("details" to "true", "format" to "json"), requests.single().queryParameters)

        val enabled = catalog.apps.single { app -> app.id == "enabled_app" }
        assertEquals("2.1.0", enabled.availableVersion)
        assertEquals(
            setOf(
                NativeAppLifecycleAction.Disable,
                NativeAppLifecycleAction.Update,
                NativeAppLifecycleAction.Uninstall,
            ),
            availableNativeAppLifecycleActions(catalog, enabled),
        )
        assertEquals(
            setOf(NativeAppLifecycleAction.InstallAndEnable),
            availableNativeAppLifecycleActions(catalog, catalog.apps.single { it.id == "available_app" }),
        )
        assertTrue(
            availableNativeAppLifecycleActions(catalog, catalog.apps.single { it.id == "external_app" }).isEmpty(),
        )
    }

    @Test
    fun `catalog surface filtering is reusable across state and search`() {
        val enabled = managedApp()
        val disabled = managedApp().copy(
            id = "disabled_app",
            name = "Disabled calendar",
            enabled = false,
            availableVersion = null,
        )
        val available = managedApp().copy(
            id = "available_app",
            name = "Available notes",
            installed = false,
            enabled = false,
            installedVersion = null,
            availableVersion = null,
        )
        val catalog = appStoreCatalog(enabled).copy(apps = listOf(enabled, disabled, available))

        assertEquals(
            listOf(enabled),
            filterNativeAppCatalog(catalog, "", NativeAppCatalogFilter.Enabled),
        )
        assertEquals(
            listOf(disabled),
            filterNativeAppCatalog(catalog, "calendar", NativeAppCatalogFilter.Disabled),
        )
        assertEquals(
            listOf(enabled),
            filterNativeAppCatalog(catalog, "", NativeAppCatalogFilter.Updates),
        )
        assertEquals(
            listOf(available),
            filterNativeAppCatalog(catalog, "available_app", NativeAppCatalogFilter.All),
        )
    }

    @Test
    fun `legacy inventory fallback remains read only and does not invent update support`() = runBlocking {
        val requests = mutableListOf<NextcloudApiRequest>()
        val result = loadNativeAppCatalog { request ->
            requests += request
            when {
                request.relativePath.startsWith("/ocs/v2.php/apps/appstore/") ->
                    jsonResponse("{}", status = 404)
                request.queryParameters["filter"] == "enabled" ->
                    legacyListResponse("files", "calendar")
                else -> legacyListResponse("notes")
            }
        }

        val catalog = assertIs<NativeAppCatalogResult.Available>(result).catalog
        assertEquals(NativeAppCatalogContract.ProvisioningOcsV1, catalog.contract)
        assertFalse(catalog.includesAvailableApps)
        assertFalse(catalog.includesUpdateAvailability)
        assertEquals(3, requests.size)
        assertTrue(requests.all { request -> request.method == NextcloudApiMethod.GET })
        assertTrue(catalog.apps.single { it.id == "calendar" }.enabled)
        assertFalse(catalog.apps.single { it.id == "notes" }.enabled)
        assertEquals(
            setOf(NativeAppLifecycleAction.Disable),
            availableNativeAppLifecycleActions(catalog, catalog.apps.single { it.id == "files" }),
        )
        assertEquals(
            setOf(NativeAppLifecycleAction.Enable),
            availableNativeAppLifecycleActions(catalog, catalog.apps.single { it.id == "notes" }),
        )
    }

    @Test
    fun `administrator permission is inferred only from successful endpoint access`() = runBlocking {
        var calls = 0
        val result = loadNativeAppCatalog {
            calls += 1
            jsonResponse("{}", status = 403)
        }

        assertIs<NativeAppCatalogResult.Forbidden>(result)
        assertEquals(1, calls)
    }

    @Test
    fun `legacy app details enrich only the matching inventory record`() {
        val inventory = managedApp().copy(name = "Sample app", installedVersion = null)
        val response = jsonResponse(
            """
            {"ocs":{"meta":{"status":"ok","statuscode":100},"data":{
              "id":"sample_app","name":"Localized sample","summary":"Details","version":"3.2.1"
            }}}
            """.trimIndent(),
        )

        val details = requireNotNull(parseNativeAppDetails(response, inventory))

        assertEquals("Localized sample", details.name)
        assertEquals("Details", details.summary)
        assertEquals("3.2.1", details.installedVersion)
        assertEquals(inventory.enabled, details.enabled)
        assertEquals(
            NextcloudApiMethod.GET,
            buildNativeAppDetailsRequest("sample_app").method,
        )
        assertEquals(
            "/ocs/v1.php/cloud/apps/sample_app",
            buildNativeAppDetailsRequest("sample_app").relativePath,
        )
    }

    @Test
    fun `confirmed app-store plans use exact official forms and never stored app-password authorization`() {
        val app = managedApp()
        val catalog = appStoreCatalog(app)

        val update = buildNativeAppMutationRequest(
            catalog = catalog,
            app = app,
            action = NativeAppLifecycleAction.Update,
            authorization = NativeAdminAuthorization.AccountPasswordOnRequest,
            approval = approval(app, NativeAppLifecycleAction.Update),
        )

        assertEquals(NextcloudApiMethod.POST, update.request.method)
        assertEquals("/ocs/v2.php/apps/appstore/api/v1/apps/update", update.request.relativePath)
        assertEquals("appId=sample_app", update.request.body?.decodeToString())
        assertTrue(update.requiresDedicatedAdminTransport)
        assertFalse(update.destructive)

        val uninstall = buildNativeAppMutationRequest(
            catalog = catalog,
            app = app,
            action = NativeAppLifecycleAction.Uninstall,
            authorization = NativeAdminAuthorization.AccountPasswordOnRequest,
            approval = approval(
                app,
                NativeAppLifecycleAction.Uninstall,
                destructiveImpactAccepted = true,
            ),
        )
        assertEquals("/ocs/v2.php/apps/appstore/api/v1/apps/uninstall", uninstall.request.relativePath)
        assertTrue(uninstall.destructive)
    }

    @Test
    fun `lifecycle builders reject stale weak unsupported and unsafe approvals`() {
        val app = managedApp()
        val catalog = appStoreCatalog(app)

        assertFailsWith<IllegalArgumentException> {
            buildNativeAppMutationRequest(
                catalog,
                app,
                NativeAppLifecycleAction.Uninstall,
                NativeAdminAuthorization.AccountPasswordOnRequest,
                approval(app, NativeAppLifecycleAction.Uninstall),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildNativeAppMutationRequest(
                catalog,
                app,
                NativeAppLifecycleAction.Update,
                NativeAdminAuthorization.RecentlyPasswordConfirmed,
                approval(app, NativeAppLifecycleAction.Update),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildNativeAppMutationRequest(
                catalog,
                app,
                NativeAppLifecycleAction.Update,
                NativeAdminAuthorization.AccountPasswordOnRequest,
                approval(app, NativeAppLifecycleAction.Update).copy(observedVersion = "stale"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildNativeAppMutationRequest(
                catalog.copy(contract = NativeAppCatalogContract.ProvisioningOcsV1),
                app,
                NativeAppLifecycleAction.Update,
                NativeAdminAuthorization.AccountPasswordOnRequest,
                approval(app, NativeAppLifecycleAction.Update),
            )
        }
        val external = app.copy(externalApp = true)
        assertFailsWith<IllegalArgumentException> {
            buildNativeAppMutationRequest(
                appStoreCatalog(external),
                external,
                NativeAppLifecycleAction.Update,
                NativeAdminAuthorization.AccountPasswordOnRequest,
                approval(external, NativeAppLifecycleAction.Update),
            )
        }
    }

    @Test
    fun `legacy enable and disable plans require their distinct official confirmation levels`() {
        val disabled = managedApp().copy(enabled = false, availableVersion = null, removable = false)
        val catalog = NativeAppCatalog(
            contract = NativeAppCatalogContract.ProvisioningOcsV1,
            apps = listOf(disabled),
            administratorAuthorized = true,
            includesAvailableApps = false,
            includesUpdateAvailability = false,
        )
        val enable = buildNativeAppMutationRequest(
            catalog,
            disabled,
            NativeAppLifecycleAction.Enable,
            NativeAdminAuthorization.AccountPasswordOnRequest,
            approval(disabled, NativeAppLifecycleAction.Enable),
        )
        assertEquals(NextcloudApiMethod.POST, enable.request.method)
        assertEquals("/ocs/v1.php/cloud/apps/sample_app", enable.request.relativePath)

        val enabled = disabled.copy(enabled = true)
        val enabledCatalog = catalog.copy(apps = listOf(enabled))
        val disable = buildNativeAppMutationRequest(
            enabledCatalog,
            enabled,
            NativeAppLifecycleAction.Disable,
            NativeAdminAuthorization.RecentlyPasswordConfirmed,
            approval(enabled, NativeAppLifecycleAction.Disable),
        )
        assertEquals(NextcloudApiMethod.DELETE, disable.request.method)
    }

    private fun managedApp() = NativeManagedApp(
        id = "sample_app",
        name = "Sample app",
        installedVersion = "1.0.0",
        availableVersion = "1.1.0",
        installed = true,
        enabled = true,
        compatible = true,
        removable = true,
    )

    private fun appStoreCatalog(app: NativeManagedApp) = NativeAppCatalog(
        contract = NativeAppCatalogContract.AppStoreOcsV1,
        apps = listOf(app),
        administratorAuthorized = true,
        includesAvailableApps = true,
        includesUpdateAvailability = true,
    )

    private fun approval(
        app: NativeManagedApp,
        action: NativeAppLifecycleAction,
        destructiveImpactAccepted: Boolean = false,
    ) = NativeAppMutationApproval(
        appId = app.id,
        action = action,
        observedVersion = app.installedVersion,
        confirmationChallenge = nativeAppConfirmationChallenge(app, action),
        destructiveImpactAccepted = destructiveImpactAccepted,
    )

    private fun legacyListResponse(vararg ids: String): NextcloudApiResponse =
        jsonResponse(
            """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"apps":[${
                ids.joinToString(",") { id -> "\"$id\"" }
            }]}}}""",
        )

    private fun jsonResponse(body: String, status: Int = 200) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
