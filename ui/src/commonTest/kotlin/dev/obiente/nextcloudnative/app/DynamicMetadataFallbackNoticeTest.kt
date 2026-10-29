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
}
