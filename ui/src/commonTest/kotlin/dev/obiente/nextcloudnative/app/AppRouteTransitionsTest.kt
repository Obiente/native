package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudEntranceHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppRouteTransitionsTest {
    private val file = NextcloudFile(
        path = "/Documents/Plan.md", name = "Plan.md", isDirectory = false, mimeType = "text/markdown",
        size = 12, lastModified = null, fileId = 7, hasPreview = false,
    )

    @Test
    fun everyScreenInsideTheShellIsOnePage() {
        assertEquals(Screen.Root.pageTransitionKey(), Screen.Files("/Documents").pageTransitionKey())
        assertEquals(Screen.Talk.pageTransitionKey(), Screen.Calendar.pageTransitionKey())
        assertNotEquals(Screen.Root.pageTransitionKey(), Screen.FileInfo(file, "/Documents").pageTransitionKey())
    }

    @Test
    fun routeKeysFollowWhatIsOpenAndIgnoreStateWithinTheSamePage() {
        assertNotEquals(Screen.Files("/Documents").routeTransitionKey(), Screen.Files("/Photos").routeTransitionKey())
        assertEquals(
            Screen.FileInfo(file, "/Documents").routeTransitionKey(),
            Screen.FileInfo(file, "/Documents", showVersions = true).routeTransitionKey(),
            "Showing versions keeps the same page",
        )
        assertEquals(
            Screen.MediaViewer("album", 0, 0, Screen.Media).routeTransitionKey(),
            Screen.MediaViewer("album", 5, 5, Screen.Media).routeTransitionKey(),
            "Swiping in the viewer is not a new page",
        )
        val app = NextcloudAppEntry("tables", "Tables", null)
        assertEquals(
            Screen.AppInfo(app).routeTransitionKey(),
            Screen.AppInfo(app, lastKnownServerVersion = "32.0.0").routeTransitionKey(),
            "A dynamic app's own navigation is animated by the app, not as a new route",
        )
        assertNotEquals(Screen.TextEditor(file, "/Documents").routeTransitionKey(), Screen.FileInfo(file, "/Documents").routeTransitionKey())
    }

    @Test
    fun returningToThePreviousKeyIsABackStepAndAnythingElseMovesForward() {
        val history = NextcloudEntranceHistory("/")
        assertFalse(history.visit("/"), "Recomposing the same key is not navigation")
        assertFalse(history.visit("/Documents"))
        assertFalse(history.visit("/Documents/Work"))
        assertTrue(history.visit("/Documents"), "Up to the parent is back")
        assertTrue(history.visit("/"))
        assertFalse(history.visit("/Photos"), "A sibling opened from the root is forward")
    }

    @Test
    fun historyStaysBounded() {
        val history = NextcloudEntranceHistory(0)
        repeat(200) { history.visit(it + 1) }
        assertTrue(history.visit(199), "The most recent parent is still known after a long session")
    }
}
