package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsApiTest {
    @Test
    fun `item pages map every selection to the documented type and id`() {
        fun query(selection: NewsSelection) = newsItemsRequest(selection).queryParameters
        assertEquals("3", query(NewsSelection.Unread)["type"])
        assertEquals("false", query(NewsSelection.Unread)["getRead"])
        assertEquals("3", query(NewsSelection.All)["type"])
        assertEquals("true", query(NewsSelection.All)["getRead"])
        assertEquals("2", query(NewsSelection.Starred)["type"])
        assertEquals("1", query(NewsSelection.Folder(7))["type"])
        assertEquals("7", query(NewsSelection.Folder(7))["id"])
        assertEquals("0", query(NewsSelection.Feed(9))["type"])
        assertEquals("9", query(NewsSelection.Feed(9))["id"])
        val request = newsItemsRequest(NewsSelection.All)
        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/news/api/v1-3/items", request.relativePath)
        assertEquals("0", request.queryParameters["offset"])
        assertEquals("false", request.queryParameters["oldestFirst"])
    }

    @Test
    fun `older pages use the smallest shown item id as the cursor`() {
        assertEquals("120", newsItemsRequest(NewsSelection.All, olderThanItemId = 120).queryParameters["offset"])
        assertFailsWith<IllegalArgumentException> { newsItemsRequest(NewsSelection.All, olderThanItemId = 0) }
        assertFailsWith<IllegalArgumentException> { newsItemsRequest(NewsSelection.All, batchSize = 0) }
    }

    @Test
    fun `state changes are bodiless POSTs to the per-article v1-3 routes`() {
        val read = newsItemStateRequest(42, NewsItemStateChange.Read)
        assertEquals(NextcloudApiMethod.POST, read.method)
        assertEquals("/index.php/apps/news/api/v1-3/items/42/read", read.relativePath)
        assertNull(read.body)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, read.cachePolicy)
        assertEquals("/index.php/apps/news/api/v1-3/items/42/unstar", newsItemStateRequest(42, NewsItemStateChange.Unstar).relativePath)
        assertFailsWith<IllegalArgumentException> { newsItemStateRequest(0, NewsItemStateChange.Star) }
    }

    @Test
    fun `api levels are read from the version index`() {
        assertEquals(setOf("v1-2", "v1-3", "v2"), parseNewsApiLevels("""{"apiLevels":["v1-2","v1-3","v2",7]}"""))
        assertFailsWith<NewsProtocolException> { parseNewsApiLevels("""{"levels":[]}""") }
        assertFailsWith<NewsProtocolException> { parseNewsApiLevels("not json") }
        assertFailsWith<NewsProtocolException> { parseNewsApiLevels("[]") }
    }

    @Test
    fun `feeds normalize untrusted titles and drop records without identity`() {
        val (feeds, starred) = parseNewsFeeds(
            """
            {"starredCount":3,"newestItemId":20,"feeds":[
              {"id":1,"title":"\n\tComments on: \t","url":"https://blog.example.test/feed/","link":"https://blog.example.test/",
               "faviconLink":"javascript:alert(1)","folderId":4,"unreadCount":null,"pinned":false,"updateErrorCount":2,"lastUpdateError":"timeout"},
              {"id":2,"title":"   ","url":"https://news.example.test/rss.xml","folderId":null,"unreadCount":5},
              {"id":0,"title":"No identity","url":"https://x.example.test"},
              {"id":3,"title":"No URL"},
              "not an object"
            ]}
            """.trimIndent(),
        )
        assertEquals(3, starred)
        assertEquals(listOf(1L, 2L), feeds.map(NewsFeed::id))
        assertEquals("Comments on:", feeds[0].title)
        assertNull(feeds[0].faviconLink, "Only http(s) favicons are retained.")
        assertEquals(0, feeds[0].unreadCount)
        assertEquals(4L, feeds[0].folderId)
        assertEquals(2, feeds[0].updateErrorCount)
        assertEquals("news.example.test", feeds[1].title, "A blank title falls back to the feed host.")
        assertNull(feeds[1].folderId)
    }

    @Test
    fun `items keep the body for the reader and a bounded plain excerpt for lists`() {
        val items = parseNewsItems(
            """
            {"items":[
              {"id":20,"feedId":3,"title":"Release notes","author":"Team","url":"https://example.test/a","pubDate":1792454400,
               "body":"<p>First <b>bold</b> paragraph.</p><script>steal()</script><p>Second</p>","unread":true,"starred":false,"rtl":false},
              {"id":19,"feedId":3,"title":null,"url":"ftp://example.test/b","pubDate":-5,"body":null,"unread":false,"starred":true,
               "enclosureLink":"https://example.test/a.mp3","enclosureMime":"audio/mpeg"},
              {"id":18,"title":"No feed"},
              {"id":20,"feedId":3,"title":"Duplicate"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(20L, 19L), items.map(NewsItem::id))
        val first = items[0]
        assertTrue(first.unread)
        assertEquals(1792454400L, first.publishedAtEpochSeconds)
        assertTrue(first.bodyHtml.contains("<b>bold</b>"))
        assertTrue(first.excerpt.startsWith("First bold paragraph."))
        assertFalse(first.excerpt.contains("<"))
        val second = items[1]
        assertEquals("Untitled article", second.title)
        assertNull(second.url, "Non-HTTP article links are not offered.")
        assertNull(second.publishedAtEpochSeconds)
        assertEquals("", second.excerpt)
        assertEquals("audio/mpeg", second.enclosureMime)
        assertTrue(second.starred)
    }

    @Test
    fun `excerpts are bounded and whitespace collapsed`() {
        val excerpt = newsExcerpt("<p>" + "word ".repeat(400) + "</p>")
        assertTrue(excerpt.length <= 280)
        assertFalse(excerpt.contains("  "))
        assertEquals("", newsExcerpt("   "))
    }

    @Test
    fun `missing lists are empty while wrong list types are malformed`() {
        assertTrue(parseNewsFolders("""{}""").isEmpty())
        assertTrue(parseNewsItems("""{"items":null}""").isEmpty())
        assertFailsWith<NewsProtocolException> { parseNewsFolders("""{"folders":{}}""") }
        assertEquals(
            listOf(NewsFolder(1, "Open source"), NewsFolder(2, "Folder 2")),
            parseNewsFolders("""{"folders":[{"id":1,"name":" Open   source "},{"id":2,"name":""},{"id":"x"}]}"""),
        )
    }
}
