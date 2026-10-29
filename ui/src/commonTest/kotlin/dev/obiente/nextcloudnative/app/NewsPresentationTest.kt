package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewsPresentationTest {
    private val subscriptions = NewsSubscriptions(
        folders = listOf(NewsFolder(1, "Tech")),
        feeds = listOf(
            NewsFeed(5, "Example", "https://example.test/feed", null, null, folderId = 1, unreadCount = 3, pinned = false, updateErrorCount = 0, lastUpdateError = null),
            NewsFeed(6, "Broken", "https://broken.test/feed", null, null, folderId = null, unreadCount = 0, pinned = false, updateErrorCount = 2, lastUpdateError = "timeout"),
        ),
        starredCount = 4,
    )

    @Test
    fun `destinations list smart views, folders, then feeds with unread counts`() {
        val destinations = newsDestinations(subscriptions)
        assertEquals(listOf("Unread", "Starred", "All articles", "Tech", "Example", "Broken"), destinations.map { it.label })
        assertEquals(3, destinations[0].count)
        assertEquals(4, destinations[1].count)
        assertEquals(3, destinations[3].count)
        assertEquals("1 feed", destinations[3].supportingText)
        assertEquals("Tech", destinations[4].supportingText)
        assertNull(destinations[5].count, "A zero unread count is not shown as a badge.")
        assertEquals("Updates failing", destinations[5].supportingText)
        assertEquals(3, newsDestinations(null).size, "Smart views stay available before subscriptions load.")
    }

    @Test
    fun `destination ids round trip and reject malformed ids`() {
        listOf(NewsSelection.Unread, NewsSelection.Starred, NewsSelection.All, NewsSelection.Folder(1), NewsSelection.Feed(6))
            .forEach { selection -> assertEquals(selection, newsSelectionForDestination(selection.destinationId())) }
        assertNull(newsSelectionForDestination("news:feed:abc"))
        assertNull(newsSelectionForDestination("news:folder:0"))
        assertNull(newsSelectionForDestination("other"))
    }

    @Test
    fun `titles and subtitles describe the current selection`() {
        assertEquals("Example", NewsSelection.Feed(5).title(subscriptions))
        assertEquals("3 unread articles", NewsSelection.Feed(5).subtitle(subscriptions))
        assertEquals("Last update failed: timeout", NewsSelection.Feed(6).subtitle(subscriptions))
        assertEquals("2 feeds", NewsSelection.All.subtitle(subscriptions))
        assertEquals("Feed", NewsSelection.Feed(99).title(subscriptions))
        assertNull(NewsSelection.Unread.subtitle(null))
    }

    @Test
    fun `published labels are relative for a week and dated after`() {
        val now = 1_800_000_000L
        assertEquals("Just now", newsPublishedLabel(now - 30, now))
        assertEquals("5 min ago", newsPublishedLabel(now - 300, now))
        assertEquals("3 h ago", newsPublishedLabel(now - 3 * 3_600, now))
        assertEquals("Yesterday", newsPublishedLabel(now - 30 * 3_600, now))
        assertEquals("4 days ago", newsPublishedLabel(now - 4 * 86_400, now))
        assertEquals("2027-01-05", newsPublishedLabel(now - 10 * 86_400, now))
        assertEquals("2027-01-15", newsPublishedLabel(now + 3_600, now), "Future dates are shown as dates.")
    }
}
