package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardStatusPresentationTest {
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
