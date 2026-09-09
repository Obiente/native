package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityWorkspaceTest {
    @Test
    fun highlightedEventsAppearOnceWithoutHidingUnhighlightedIssues() {
        val shown = activity(1, "files", "sync", "Upload failed")
        val anotherIssue = activity(2, "files", "sync", "Another upload failed")
        val normal = activity(3, "files", "created", "Created notes")
        val groups = listOf(
            ActivityFeedDayGroup("today", "Today", listOf(shown)),
            ActivityFeedDayGroup("yesterday", "Yesterday", listOf(anotherIssue, normal)),
        )
        val history = activityHistoryGroups(groups, listOf(shown))
        assertEquals(listOf("yesterday"), history.map { it.dateKey })
        assertEquals(listOf(2L, 3L), history.single().activities.map { it.id })
        assertEquals("2 events", activityHistoryCountLabel(history.single().activities))
        assertEquals(groups, activityHistoryGroups(groups, emptyList()))
    }

    @Test
    fun historyCountsDistinguishVisibleEntriesFromBundledEventsWithoutCountingHighlights() {
        val attention = activity(1, "files", "sync", "Upload failed")
        val human = activity(2, "files", "created", "Created notes")
        val background = (3L..6L).map { id -> activity(id, "recognize", "classified", "Classified photo $id") }
        val original = listOf(attention, human) + background
        val history = activityHistoryGroups(
            listOf(ActivityFeedDayGroup("today", "Today", original)), listOf(attention),
        ).single()

        assertEquals("2 entries / 5 events", activityHistoryCountLabel(history.activities))
        assertEquals("1 entry / 4 events", activityHistoryCountLabel(background))
        assertEquals("1 event", activityHistoryCountLabel(listOf(human)))
        assertEquals(6, original.size, "History presentation must not mutate the source attention/feed totals")
    }

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

    @Test
    fun activityPagingPrefetchesNearTheEndWithoutRetryLoops() {
        assertTrue(
            shouldAutoLoadActivityPage(
                hasMore = true,
                loadingMore = false,
                refreshing = false,
                error = null,
                totalItemCount = 30,
                lastVisibleItemIndex = 26,
            ),
        )
        assertFalse(
            shouldAutoLoadActivityPage(
                hasMore = true,
                loadingMore = true,
                refreshing = false,
                error = null,
                totalItemCount = 30,
                lastVisibleItemIndex = 29,
            ),
        )
        assertFalse(
            shouldAutoLoadActivityPage(
                hasMore = true,
                loadingMore = false,
                refreshing = false,
                error = "Could not load more activity.",
                totalItemCount = 30,
                lastVisibleItemIndex = 29,
            ),
        )
        assertFalse(
            shouldAutoLoadActivityPage(
                hasMore = false,
                loadingMore = false,
                refreshing = false,
                error = null,
                totalItemCount = 30,
                lastVisibleItemIndex = 29,
            ),
        )
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
