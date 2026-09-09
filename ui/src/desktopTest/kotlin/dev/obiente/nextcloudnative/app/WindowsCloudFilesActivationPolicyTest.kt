package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCloudFilesActivationPolicyTest {
    @Test
    fun `failed Windows activation waits for an explicit retry`() {
        assertFalse(
            windowsCloudFilesAutomaticActivationAllowed(
                windowsDesktop = true,
                previousFailure = "Synthetic Cloud Files activation failure",
            ),
        )
    }

    @Test
    fun `Windows activation can start automatically before a failure`() {
        assertTrue(
            windowsCloudFilesAutomaticActivationAllowed(
                windowsDesktop = true,
                previousFailure = null,
            ),
        )
    }

    @Test
    fun `Windows failure state does not change another desktop adapter`() {
        assertTrue(
            windowsCloudFilesAutomaticActivationAllowed(
                windowsDesktop = false,
                previousFailure = "Synthetic Cloud Files activation failure",
            ),
        )
    }
}
