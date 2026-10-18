package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicContractFallbackCardActionTest {
    private val rejected = DynamicContractFallbackReason.ServerAuthenticationRequired.fallbackNotice()

    @Test
    fun `rejected credentials offer account settings, never the rediscovery retry`() {
        var checks = 0
        var settings = 0
        val actions = DynamicContractFallbackActions(onCheckAgain = { checks += 1 }, onOpenAccountSettings = { settings += 1 })

        val action = assertNotNull(actions.callbackFor(rejected))
        action()
        assertEquals(1, settings)
        assertEquals(0, checks)
        assertEquals("Open account settings", rejected.actionLabel)
    }

    @Test
    fun `without an account settings callback the card offers no action but still explains the route`() {
        val actions = DynamicContractFallbackActions(onCheckAgain = { error("A retry must not be offered.") })
        assertNull(actions.callbackFor(rejected))
        assertTrue("open Settings, then Account" in rejected.message, rejected.message)
        assertTrue("Sign out and revoke access" in rejected.message, rejected.message)
    }

    @Test
    fun `every other state checks again, including a denied permission`() {
        var checks = 0
        val actions = DynamicContractFallbackActions(onCheckAgain = { checks += 1 }, onOpenAccountSettings = { error("Wrong action.") })
        val others = (DynamicContractFallbackReason.entries - DynamicContractFallbackReason.ServerAuthenticationRequired) + null
        for (reason in others) {
            assertNotNull(actions.callbackFor(reason.fallbackNotice()), reason.toString()).invoke()
        }
        assertEquals(others.size, checks)
        assertEquals("Check again", DynamicContractFallbackReason.ServerPermissionDenied.fallbackNotice().actionLabel)
    }
}
