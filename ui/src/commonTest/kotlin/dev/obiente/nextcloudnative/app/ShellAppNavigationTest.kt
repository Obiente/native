package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.DefaultNextcloudNavigationItems
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.filterNextcloudShellApps
import dev.obiente.nextcloudnative.app.design.nextcloudShellAppItems
import dev.obiente.nextcloudnative.app.design.nextcloudShellNavigationItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellAppNavigationTest {
    @Test
    fun activeAppSelectsOnlyTheSharedAppsSlotRegardlessOfItsLaunchOrigin() {
        for (origin in listOf(NextcloudDestination.Home, NextcloudDestination.Apps)) {
            val items = DefaultNextcloudNavigationItems.map {
                nextcloudShellNavigationItem(it, origin, app("calendar", "Calendar"))
            }
            assertEquals(listOf("Calendar"), items.filter { it.selected }.map { it.label })
            assertEquals(listOf("Calendar"), items.filter { it.switchesApps }.map { it.label })
        }
    }

    @Test
    fun folderSyncSharesTheAppsSlotWhileHomeHasNoFalseSwitcher() {
        val sync = DefaultNextcloudNavigationItems.map {
            nextcloudShellNavigationItem(it, NextcloudDestination.FolderSync, activeApp = null)
        }
        assertEquals(listOf("Sync"), sync.filter { it.selected }.map { it.label })
        assertEquals(listOf("Sync"), sync.filter { it.switchesApps }.map { it.label })

        val home = DefaultNextcloudNavigationItems.map {
            nextcloudShellNavigationItem(it, NextcloudDestination.Home, activeApp = null)
        }
        assertEquals(listOf("Home"), home.filter { it.selected }.map { it.label })
        assertTrue(home.none { it.switchesApps })
    }

    @Test
    fun talkAliasesProduceOnePinnedEntryAndNoDuplicateRecent() {
        val apps = nextcloudShellAppItems(
            identity(
                pinned = listOf(app("spreed", "Talk"), app("talk", "Talk alias")),
                recent = app("talk", "Talk"),
                available = listOf(app("talk", "Talk"), app("spreed", "Talk alias")),
            ),
            activeAppId = "spreed",
        )

        assertEquals(listOf("spreed"), apps.pinned.map { it.id })
        assertNull(apps.recent)
        assertEquals("Talk", apps.active?.label)
        assertEquals(listOf("spreed"), apps.ordered.map { it.id })
        assertTrue(apps.others.isEmpty())
    }

    @Test
    fun pinnedOrderAndExactCallbackIdsArePreservedWhileOtherAppsSortByLabel() {
        val apps = nextcloudShellAppItems(
            identity(
                pinned = listOf(app("notes", "Notes"), app("calendar", "Calendar")),
                recent = app("spreed", "Talk"),
                available = listOf(
                    app("tables", "Tables"), app("mail", "Mail"), app("calendar", "Calendar"),
                    app("bookmarks", "Bookmarks"), app("talk", "Talk"), app("deck", "Deck"),
                ),
            ),
            activeAppId = "deck",
        )

        assertEquals(listOf("notes", "calendar"), apps.pinned.map { it.id })
        assertEquals("spreed", apps.recent?.id)
        assertEquals("deck", apps.active?.id)
        assertEquals(listOf("Bookmarks", "Mail", "Tables"), apps.others.map { it.label })
        assertEquals(listOf("notes", "calendar", "spreed", "deck", "bookmarks", "mail", "tables"),
            apps.ordered.map { it.id })
        assertEquals(apps.ordered.size, apps.ordered.map { canonicalAppWorkspaceId(it.id) }.distinct().size)
    }

    @Test
    fun activeRecentAppAppearsOnlyOnceInTheOrderedSwitcher() {
        val apps = nextcloudShellAppItems(
            identity(recent = app("spreed", "Talk"), available = listOf(app("talk", "Talk"))),
            activeAppId = "talk",
        )

        assertEquals("spreed", apps.recent?.id)
        assertEquals(listOf("spreed"), apps.ordered.map { it.id })
        assertTrue(apps.others.isEmpty())
    }

    @Test
    fun unknownActiveAppUsesSafeLabelWithoutRevealingItsIdentifier() {
        val id = "unrecognized-internal-workspace-id"
        val apps = nextcloudShellAppItems(identity(), activeAppId = id)

        assertEquals(id, apps.active?.id)
        assertEquals("Current app", apps.active?.label)
        assertEquals(listOf("Current app"), apps.ordered.map { it.label })
    }

    @Test
    fun absentIdentityDoesNotInventAvailableApps() {
        val apps = nextcloudShellAppItems(null, null)

        assertTrue(apps.ordered.isEmpty())
        assertTrue(apps.pinned.isEmpty())
        assertNull(apps.recent)
        assertNull(apps.active)
    }

    @Test
    fun searchMatchesDisplayLabelsCaseInsensitivelyAndTrimsWhitespace() {
        val apps = listOf(app("spreed", "Talk"), app("calendar", "Team calendar"), app("notes", "Notes"))

        assertEquals(listOf(apps[1]), filterNextcloudShellApps(apps, "  CALENDAR  "))
        assertEquals(listOf(apps[0]), filterNextcloudShellApps(apps, "taL"))
        assertEquals(apps, filterNextcloudShellApps(apps, "   "))
        assertTrue(filterNextcloudShellApps(apps, "spreed").isEmpty(), "Internal IDs are not display labels")
        assertTrue(filterNextcloudShellApps(apps, "missing").isEmpty())
    }

    private fun app(id: String, label: String) = NextcloudDesktopSidebarApp(id, label)

    private fun identity(
        pinned: List<NextcloudDesktopSidebarApp> = emptyList(),
        recent: NextcloudDesktopSidebarApp? = null,
        available: List<NextcloudDesktopSidebarApp> = emptyList(),
    ) = NextcloudDesktopIdentity(
        displayName = "Demo user", cloudName = "Demo cloud", shortcuts = pinned,
        recentApp = recent, availableApps = available, accountScopeKey = "synthetic-shell-account",
    )
}
