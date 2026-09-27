package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdministrationNavigationTest {
    @Test
    fun onlyVisibleSettingsOrServerAppsPollPermissions() {
        for (destination in NextcloudDestination.entries) {
            assertEquals(
                destination == NextcloudDestination.Settings,
                administrationAccessPollingActive(Screen.Root, destination),
            )
            assertTrue(administrationAccessPollingActive(Screen.AdminApps, destination))
        }
    }

    @Test
    fun settingsChildScreensDoNotPollWithRetainedSettingsDestination() {
        val screens = listOf(
            Screen.OfflineCenter, Screen.Transfers, Screen.ProjectNews,
            Screen.Files(""), Screen.Media, Screen.Notes,
            Screen.AppInfo(NextcloudAppEntry("sample_app", "Sample app", null)),
        )
        for (screen in screens) {
            assertFalse(administrationAccessPollingActive(screen, NextcloudDestination.Settings))
        }
    }
}
