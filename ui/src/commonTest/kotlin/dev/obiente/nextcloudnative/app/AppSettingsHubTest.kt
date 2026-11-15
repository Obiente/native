package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsHubTest {
    private val apps = listOf(
        NextcloudAppEntry("dashboard", "Dashboard", null),
        NextcloudAppEntry("spreed", "Talk", null),
        NextcloudAppEntry("user_status", "Status", null),
        NextcloudAppEntry("files", "Files", null),
        NextcloudAppEntry("cookbook", "Cookbook", null),
        NextcloudAppEntry("files", "Files duplicate", null),
    )

    @Test
    fun `hub routes owned settings to sections and other apps to their workspace`() {
        val entries = appSettingsHubEntries(apps, nativeWorkspaceAppIds = setOf("files", "spreed"), talkSectionVisible = true)
        assertEquals(listOf("spreed", "user_status", "files", "cookbook"), entries.map { it.appId })
        assertEquals(AppSettingsTarget.Section(SettingsWorkspaceSection.Talk), entries[0].target)
        assertEquals(AppSettingsTarget.Section(SettingsWorkspaceSection.PersonalInfo), entries[1].target)
        assertEquals(AppSettingsTarget.OpenApp(apps[3]), entries[2].target)
        assertTrue("not available natively" in entries[3].description)
    }

    @Test
    fun `talk opens its workspace when the Talk section is hidden`() {
        val entries = appSettingsHubEntries(apps.take(2), nativeWorkspaceAppIds = setOf("spreed"), talkSectionVisible = false)
        assertEquals(AppSettingsTarget.OpenApp(apps[1]), entries.single().target)
    }

    @Test
    fun `talk and personal info sections follow installed apps`() {
        val withoutTalk = visibleSettingsSections(isDesktop = false, hasDeviceSettings = false)
        assertFalse(SettingsWorkspaceSection.Talk in withoutTalk)
        assertTrue(SettingsWorkspaceSection.PersonalInfo in withoutTalk)
        assertTrue(SettingsWorkspaceSection.AppSettings in withoutTalk)
        assertTrue(SettingsWorkspaceSection.Talk in visibleSettingsSections(isDesktop = false, hasDeviceSettings = false, hasTalk = true))
    }
}
