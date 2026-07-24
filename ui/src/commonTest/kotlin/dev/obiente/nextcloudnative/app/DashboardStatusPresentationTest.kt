package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DashboardStatusPresentationTest {
    private val session = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "person",
        appPassword = "secret",
    )

    @Test
    fun internalDashboardLinksResolveToInstalledAppHints() {
        assertEquals(
            "calendar",
            dashboardAppIdForLink(session, "/index.php/apps/calendar/dayGridMonth/now"),
        )
        assertEquals(
            "files",
            dashboardAppIdForLink(session, "https://cloud.example.test/apps/files/?dir=/"),
        )
    }

    @Test
    fun foreignAbsoluteLinksNeverBecomeNativeAppHints() {
        assertNull(dashboardAppIdForLink(session, "https://other.example/apps/files"))
        assertNull(dashboardAppIdForLink(session, "https://cloud.example.test.evil/apps/files"))
    }

    @Test
    fun malformedAppSegmentsAreRejected() {
        assertNull(dashboardAppIdForLink(session, "/index.php/apps/not%2Fsafe"))
        assertNull(dashboardAppIdForLink(session, "/index.php/apps/../settings"))
    }

    @Test
    fun relativeBrowserLinksStayOnAuthenticatedServer() {
        assertEquals(
            "https://cloud.example.test/index.php/apps/deck",
            dashboardBrowserUrl(session, "/index.php/apps/deck"),
        )
    }

    @Test
    fun browserHandoffRejectsNonHttpsAndNonRelativeLinks() {
        assertFailsWith<IllegalArgumentException> {
            dashboardBrowserUrl(session, "javascript:alert(1)")
        }
    }

    @Test
    fun busyPresenceOnlyAppearsWhenAdvertised() {
        val withoutBusy = availableUserPresences(
            NativeUserStatusCapabilities(
                enabled = true,
                restore = false,
                supportsEmoji = true,
                supportsBusy = false,
            ),
        )
        val withBusy = availableUserPresences(
            NativeUserStatusCapabilities(
                enabled = true,
                restore = false,
                supportsEmoji = true,
                supportsBusy = true,
            ),
        )

        assertEquals(false, NativeUserPresence.Busy in withoutBusy)
        assertEquals(true, NativeUserPresence.Busy in withBusy)
    }

    @Test
    fun disabledStatusExposesNoEditablePresences() {
        assertEquals(
            emptyList(),
            availableUserPresences(
                NativeUserStatusCapabilities(
                    enabled = false,
                    restore = true,
                    supportsEmoji = true,
                    supportsBusy = true,
                ),
            ),
        )
    }

    @Test
    fun capabilityDiscoveryIsReadOnlyAndBounded() {
        val request = userStatusCapabilitiesRequest()

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/ocs/v2.php/cloud/capabilities", request.relativePath)
        assertEquals(mapOf("format" to "json"), request.queryParameters)
        assertEquals(null, request.body)
        assertEquals(true, request.ocsApiRequest)
        assertEquals(true, request.maximumResponseBytes in 1..(1024L * 1024L))
    }
}
