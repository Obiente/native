package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogoutRecoveryTest {
    @Test
    fun `logout cleanup failures are actionable bounded and safe to display`() {
        val message = logoutCleanupFailureMessage(
            IllegalStateException("Unmount failed.\n${"detail".repeat(100)}"),
        )

        assertTrue(message.startsWith("Could not finish signing out. Unmount failed."))
        assertTrue(message.endsWith("You can retry safely."))
        assertFalse(message.contains('\n'))
        assertTrue(message.length <= 330)
    }

    @Test
    fun `logout cleanup failures without a detail receive a useful fallback`() {
        assertEquals(
            "Could not finish signing out. Local desktop cleanup did not complete. You can retry safely.",
            logoutCleanupFailureMessage(IllegalStateException()),
        )
    }
}
