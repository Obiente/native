package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityWorkspaceTest {
    @Test
    fun repeatedBackgroundEventsCollapseWithoutHidingHumanActivity() {
        val activities = listOf(
            activity(1, "files", "file_created", "Mara created Roadmap.md"),
            activity(2, "systemtags", "tagged", "System tag was added to IMG_001.jpg"),
            activity(3, "systemtags", "tagged", "System tag was added to IMG_002.jpg"),
            activity(4, "systemtags", "tagged", "System tag was added to IMG_003.jpg"),
            activity(5, "spreed", "mention", "You were mentioned by Kai"),
        )

        val entries = bundleDesktopActivities(activities)

        assertEquals(3, entries.size)
        assertIs<DesktopActivityEntry.Single>(entries[0])
        assertEquals(3, assertIs<DesktopActivityEntry.AutomationBundle>(entries[1]).activities.size)
        assertIs<DesktopActivityEntry.Single>(entries[2])
    }

    @Test
    fun attentionClassificationIsNarrowAndActionable() {
        assertTrue(activity(1, "files", "sync", "Upload failed for Camera.jpg").needsDesktopAttention())
        assertTrue(activity(2, "files", "share", "Public link expires tomorrow").needsDesktopAttention())
        assertFalse(activity(3, "files", "file_created", "Mara created Roadmap.md").needsDesktopAttention())
    }

    private fun activity(id: Long, app: String, type: String, subject: String) = NextcloudActivity(
        id = id,
        app = app,
        type = type,
        subject = subject,
        message = null,
        objectType = null,
        objectId = null,
        objectName = null,
        link = null,
        icon = null,
        dateTime = "2026-08-02T10:42:00Z",
    )
}
