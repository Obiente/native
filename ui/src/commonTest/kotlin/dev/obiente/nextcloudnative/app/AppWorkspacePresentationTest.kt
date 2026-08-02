package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppWorkspacePresentationTest {
    private val apps = listOf(
        NextcloudAppEntry("dashboard", "Dashboard", null),
        NextcloudAppEntry("files", "Files", null),
        NextcloudAppEntry("talk", "Talk", null),
        NextcloudAppEntry("calendar", "Calendar", null),
        NextcloudAppEntry("deck", "Deck", null),
        NextcloudAppEntry("tables", "Tables", null),
        NextcloudAppEntry("cookbook", "Cookbook", null),
    )

    @Test
    fun `workspace excludes dashboard and prioritizes the last opened app`() {
        val presentation = buildAppWorkspacePresentation(apps, lastOpenedAppId = "deck")

        assertEquals(6, presentation.totalCount)
        assertEquals("deck", presentation.recentEntries.first().app.id)
        assertFalse(presentation.entries.any { it.app.id == "dashboard" })
        assertTrue(presentation.pinnedEntries.map { it.app.id }.containsAll(listOf("files", "talk", "calendar")))
    }

    @Test
    fun `query searches descriptions while category scopes the result`() {
        val queried = buildAppWorkspacePresentation(
            apps = apps,
            lastOpenedAppId = null,
            query = "boards",
            category = AppWorkspaceCategory.Planning,
        )

        assertEquals(listOf("deck"), queried.entries.map { it.app.id })
    }

    @Test
    fun `workspace reports native and adaptive coverage honestly`() {
        val presentation = buildAppWorkspacePresentation(apps, lastOpenedAppId = null)

        assertEquals(4, presentation.nativeWorkspaceCount)
        assertEquals(AppWorkspaceCategory.Productivity, appWorkspaceCategory("tables"))
        assertEquals(AppWorkspaceCategory.More, appWorkspaceCategory("unknown_app"))
    }
}
