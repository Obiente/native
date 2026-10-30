package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationSettingsTest {
    private fun available(vararg endpoints: String) =
        NotificationsAvailability.Available(NotificationsCapabilities(endpoints.toSet()))

    private fun List<NotificationSettingsSection>.row(section: String, row: String) =
        single { it.key == section }.rows.single { it.key == row }

    @Test
    fun serverSettingsAreReadOnlyBecauseNoAppOffersAReadApi() {
        val sections = notificationSettingsSections(available("list", "delete", "delete-all"), activityInstalled = true)

        assertEquals(listOf("app", "notifications", "activity"), sections.map { it.key })
        sections.filter { it.key != "app" }.forEach { section ->
            assertTrue(section.rows.isNotEmpty())
            assertTrue(section.rows.all { it.support == NotificationSettingSupport.ReadOnly }, section.key)
            assertTrue(section.explanation.orEmpty().contains("no way to read"), section.key)
        }
        assertEquals(
            listOf("email-frequency", "sound", "talk-sound"),
            sections.single { it.key == "notifications" }.rows.map { it.key },
        )
    }

    @Test
    fun appCapabilitiesFollowTheAdvertisedEndpoints() {
        val full = notificationSettingsSections(available("list", "delete", "delete-all"), activityInstalled = false)
        listOf("inbox", "dismiss", "dismiss-all", "actions").forEach { key ->
            assertEquals(NotificationSettingSupport.Available, full.row("app", key).support, key)
        }

        val listOnly = notificationSettingsSections(available("list"), activityInstalled = false)
        assertEquals(NotificationSettingSupport.Available, listOnly.row("app", "inbox").support)
        assertEquals(NotificationSettingSupport.Unavailable, listOnly.row("app", "dismiss").support)
        assertEquals(NotificationSettingSupport.Unavailable, listOnly.row("app", "dismiss-all").support)
        assertEquals("Not offered by this server.", listOnly.row("app", "dismiss-all").description)
    }

    @Test
    fun missingAppsHideTheirSectionsAndNeverClaimSupport() {
        val notEnabled = notificationSettingsSections(NotificationsAvailability.NotEnabled, activityInstalled = true)
        assertEquals(listOf("app", "activity"), notEnabled.map { it.key })
        assertTrue(notEnabled.single { it.key == "app" }.rows.none { it.support == NotificationSettingSupport.Available })
        assertTrue(notEnabled.row("app", "inbox").description.contains("not enabled"))

        val checking = notificationSettingsSections(NotificationsAvailability.Unknown, activityInstalled = false)
        assertEquals(listOf("app"), checking.map { it.key })
        assertTrue(checking.single().rows.none { it.support == NotificationSettingSupport.Available })

        val unsupported = notificationSettingsSections(NotificationsAvailability.Unsupported, activityInstalled = false)
        assertEquals(listOf("app", "notifications"), unsupported.map { it.key })
        assertEquals(NotificationSettingSupport.Unavailable, unsupported.row("app", "inbox").support)
    }

    @Test
    fun thisDeviceNeverClaimsSystemNotificationsAndKeysStayUnique() {
        listOf(
            NotificationsAvailability.Unknown,
            NotificationsAvailability.NotEnabled,
            NotificationsAvailability.Unsupported,
            available("list", "delete", "delete-all"),
        ).forEach { availability ->
            val sections = notificationSettingsSections(availability, activityInstalled = true)
            assertEquals(NotificationSettingSupport.Unavailable, sections.row("app", "device").support)
            val keys = sections.flatMap { section -> section.rows.map { "${section.key}:${it.key}" } }
            assertEquals(keys.size, keys.toSet().size)
        }
        assertEquals("Read-only", notificationSettingSupportLabel(NotificationSettingSupport.ReadOnly))
    }
}
