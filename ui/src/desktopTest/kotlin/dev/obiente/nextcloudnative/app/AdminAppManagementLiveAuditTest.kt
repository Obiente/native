package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminAppManagementLiveAuditTest {
    @Test
    fun `live administrator app inventory audit is GET only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_ADMIN_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val observedRequests = mutableListOf<NextcloudApiRequest>()

        val result = loadNativeAppCatalog { request ->
            assertEquals(NextcloudApiMethod.GET, request.method)
            observedRequests += request
            services.executeNextcloudApi(session, request)
        }
        val catalog = assertIs<NativeAppCatalogResult.Available>(result).catalog
        assertTrue(catalog.administratorAuthorized)
        assertTrue(catalog.apps.isNotEmpty())

        val inventoryRecord = catalog.apps.first { app -> app.installed }
        val detailsRequest = buildNativeAppDetailsRequest(inventoryRecord.id)
        assertEquals(NextcloudApiMethod.GET, detailsRequest.method)
        observedRequests += detailsRequest
        val detailsResponse = services.executeNextcloudApi(session, detailsRequest)
        assertNotNull(parseNativeAppDetails(detailsResponse, inventoryRecord))

        assertTrue(observedRequests.all { request -> request.method == NextcloudApiMethod.GET })
        println(
            "admin-app-audit outcome=success requests=get-only inventory=verified details=verified",
        )
    }
}
