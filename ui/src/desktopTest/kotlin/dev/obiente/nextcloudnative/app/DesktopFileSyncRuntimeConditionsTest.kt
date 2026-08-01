package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileSyncRuntimeConditionsTest {
    @Test
    fun `metered probe fails closed for unknown connected costs`() {
        assertTrue(
            parseNmcliMeteredProbe("GENERAL.STATE:100 (connected)\nGENERAL.METERED:no (guessed)") == true,
        )
        assertFalse(
            parseNmcliMeteredProbe("GENERAL.STATE:100 (connected)\nGENERAL.METERED:guess-yes") == true,
        )
        assertNull(parseNmcliMeteredProbe("GENERAL.STATE:30 (disconnected)\nGENERAL.METERED:unknown"))
    }

    @Test
    fun `configured network and power policies gate automatic sync`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "desktop",
            networkPolicy = FileSyncNetworkPolicy.Unmetered,
            powerPolicy = FileSyncPowerPolicy.Charging,
        )
        assertTrue(DesktopFileSyncRuntimeConditions(true, true, 40, true).allows(configuration))
        assertFalse(DesktopFileSyncRuntimeConditions(false, true, 40, true).allows(configuration))
        assertFalse(DesktopFileSyncRuntimeConditions(true, true, 40, false).allows(configuration))
    }
}
