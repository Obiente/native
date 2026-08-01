package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.AppUpdatePreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAppUpdateWorkTest {
    @Test
    fun automaticChecksRespectEnabledAndMeteredNetworkPreferences() {
        val defaults = AppUpdatePreferences()
        assertTrue(automaticAndroidUpdateCheckAllowed(defaults, networkMetered = false))
        assertFalse(automaticAndroidUpdateCheckAllowed(defaults, networkMetered = true))
        assertTrue(
            automaticAndroidUpdateCheckAllowed(
                defaults.copy(unmeteredNetworkOnly = false),
                networkMetered = true,
            ),
        )
        assertFalse(
            automaticAndroidUpdateCheckAllowed(
                defaults.copy(automaticChecks = false, unmeteredNetworkOnly = false),
                networkMetered = false,
            ),
        )
    }

    @Test
    fun eachDiscoveredVersionCanNotifyOnlyAfterThePreviousVersion() {
        assertFalse(shouldNotifyAppUpdate(0, 10, enabled = false))
        assertTrue(shouldNotifyAppUpdate(0, 10, enabled = true))
        assertFalse(shouldNotifyAppUpdate(10, 10, enabled = true))
        assertFalse(shouldNotifyAppUpdate(11, 10, enabled = true))
        assertTrue(shouldNotifyAppUpdate(10, 11, enabled = true))
    }

    @Test
    fun onlyTransientUpdateHttpFailuresUseWorkManagerBackoff() {
        assertTrue(isRetryableAppUpdateHttpStatus(408))
        assertTrue(isRetryableAppUpdateHttpStatus(429))
        assertTrue(isRetryableAppUpdateHttpStatus(500))
        assertTrue(isRetryableAppUpdateHttpStatus(503))
        assertFalse(isRetryableAppUpdateHttpStatus(400))
        assertFalse(isRetryableAppUpdateHttpStatus(404))
    }
}
