package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsWorkspaceTest {
    @Test
    fun `width below 600 uses compact category and detail flow`() {
        val compact = resolveSettingsWorkspaceLayout(599)

        assertEquals(SettingsWorkspaceMode.Compact, compact.mode)
        assertEquals(206, compact.categoryWidthDp)
        assertFalse(compact.showSummaryPane)
    }

    @Test
    fun `minimum desktop content width prioritizes actionable settings detail`() {
        val medium = resolveSettingsWorkspaceLayout(670)

        assertEquals(SettingsWorkspaceMode.TwoPane, medium.mode)
        assertEquals(206, medium.categoryWidthDp)
        assertFalse(medium.showSummaryPane)
    }

    @Test
    fun `category width expands at 820 without showing summary`() {
        val medium = resolveSettingsWorkspaceLayout(820)

        assertEquals(SettingsWorkspaceMode.TwoPane, medium.mode)
        assertEquals(246, medium.categoryWidthDp)
        assertFalse(medium.showSummaryPane)
    }

    @Test
    fun `wide settings workspace restores account summary`() {
        val wide = resolveSettingsWorkspaceLayout(1_200)

        assertEquals(SettingsWorkspaceMode.ThreePane, wide.mode)
        assertEquals(246, wide.categoryWidthDp)
        assertTrue(wide.showSummaryPane)
    }

    @Test
    fun `layout rejects negative width`() {
        assertFailsWith<IllegalArgumentException> {
            resolveSettingsWorkspaceLayout(-1)
        }
    }

    @Test
    fun `android phone keeps the overview and detail flow`() {
        assertFalse(useExpandedSettingsWorkspace(isDesktop = false, availableWidthDp = 599))
    }

    @Test
    fun `android tablet uses the persistent section list`() {
        assertTrue(useExpandedSettingsWorkspace(isDesktop = false, availableWidthDp = 600))
    }

    @Test
    fun `compact desktop window still delegates to the adaptive workspace`() {
        assertTrue(useExpandedSettingsWorkspace(isDesktop = true, availableWidthDp = 480))
    }

    @Test
    fun `mobile hides desktop integration and an empty device section`() {
        val sections = visibleSettingsSections(
            isDesktop = false,
            hasDeviceSettings = false,
        )

        assertFalse(SettingsWorkspaceSection.DesktopApp in sections)
        assertFalse(SettingsWorkspaceSection.NotificationsAndDevice in sections)
        assertTrue(SettingsWorkspaceSection.Support in sections)
    }

    @Test
    fun `desktop includes available desktop and device sections`() {
        val sections = visibleSettingsSections(
            isDesktop = true,
            hasDeviceSettings = true,
            hasDesktopAppSettings = true,
        )

        assertTrue(SettingsWorkspaceSection.DesktopApp in sections)
        assertTrue(SettingsWorkspaceSection.NotificationsAndDevice in sections)
    }

    @Test
    fun `desktop hides desktop app section when it has no available settings`() {
        val sections = visibleSettingsSections(
            isDesktop = true,
            hasDeviceSettings = true,
            hasDesktopAppSettings = false,
        )

        assertFalse(SettingsWorkspaceSection.DesktopApp in sections)
        assertTrue(SettingsWorkspaceSection.NotificationsAndDevice in sections)
    }

    @Test
    fun `legacy diagnostics state restores the renamed support section`() {
        val sections = visibleSettingsSections(
            isDesktop = false,
            hasDeviceSettings = false,
        )

        assertEquals(
            SettingsWorkspaceSection.Support,
            resolveSettingsWorkspaceSection("Diagnostics", sections),
        )
    }

    @Test
    fun `hidden restored section falls back to first visible section`() {
        val sections = listOf(
            SettingsWorkspaceSection.Appearance,
            SettingsWorkspaceSection.Support,
        )

        assertEquals(
            SettingsWorkspaceSection.Appearance,
            resolveSettingsWorkspaceSection(SettingsWorkspaceSection.DesktopApp.name, sections),
        )
    }

    @Test
    fun `unknown restored section falls back to first visible section`() {
        val sections = listOf(
            SettingsWorkspaceSection.Appearance,
            SettingsWorkspaceSection.Support,
        )

        assertEquals(
            SettingsWorkspaceSection.Appearance,
            resolveSettingsWorkspaceSection("RemovedSection", sections),
        )
    }

    @Test
    fun `empty visible section list falls back safely to account`() {
        assertEquals(
            SettingsWorkspaceSection.Account,
            resolveSettingsWorkspaceSection("RemovedSection", emptyList()),
        )
    }
}
