package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicMetadataFallbackNoticeTest {
    @Test
    fun `fallback copy names the app and states that nothing changed`() {
        assertEquals("Bookmarks can't be shown natively yet", dynamicMetadataFallbackTitle("Bookmarks"))
        val message = dynamicMetadataFallbackMessage("Bookmarks")
        assertTrue("Your Bookmarks data on the server is unchanged." in message)
        assertTrue("could not be loaded" !in message, "A missing contract is not a load failure.")
    }

    @Test
    fun `an app without a verifiable interface gets the named copy and a check again action`() {
        val notice = dynamicMetadataFallbackNotice("Bookmarks", DynamicContractFallbackReason.NoVerifiedContract)
        assertEquals(dynamicMetadataFallbackTitle("Bookmarks"), notice.title)
        assertEquals(dynamicMetadataFallbackMessage("Bookmarks"), notice.message)
        assertEquals(DynamicContractFallbackAction.CheckAgain, notice.action)
    }

    @Test
    fun `every other reason keeps its own explanation and action`() {
        val others = (DynamicContractFallbackReason.entries - DynamicContractFallbackReason.NoVerifiedContract) + null
        for (reason in others) {
            val notice = dynamicMetadataFallbackNotice("Bookmarks", reason)
            assertEquals(reason.fallbackNotice(), notice, reason.toString())
            // An outage or a sign-in problem is not a missing interface.
            assertTrue("does not publish an interface" !in notice.message, reason.toString())
        }
        assertEquals(
            DynamicContractFallbackAction.OpenAccountSettings,
            dynamicMetadataFallbackNotice("Bookmarks", DynamicContractFallbackReason.ServerAuthenticationRequired).action,
        )
    }

    @Test
    fun `no notice claims that app details are shown, because the identity record is not rendered`() {
        for (reason in DynamicContractFallbackReason.entries + null) {
            val message = dynamicMetadataFallbackNotice("Bookmarks", reason).message
            assertTrue("app details are shown" !in message, reason.toString())
        }
    }
}
