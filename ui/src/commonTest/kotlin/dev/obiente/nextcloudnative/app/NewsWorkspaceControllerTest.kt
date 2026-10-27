package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsWorkspaceControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private class FakeNewsServer {
        var apiLevels = """{"apiLevels":["v1-2","v1-3","v2"]}"""
        var apiLevelsStatus = 200
        var foldersStatus = 200
        var stateStatus = 200
        var stateFailure: Exception? = null
        var feedsBody = """{"starredCount":1,"feeds":[{"id":5,"title":"Example","url":"https://example.test/feed","folderId":1,"unreadCount":30}]}"""
        var stateGate: CompletableDeferred<Unit>? = null
        var itemPages: (Map<String, String>) -> String = { query ->
            if (query["offset"] == "0") items(30L downTo 1L) else items(LongRange.EMPTY)
        }
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val offsetGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val requests = mutableListOf<NextcloudApiRequest>()

        suspend fun execute(request: NextcloudApiRequest): NextcloudApiResponse {
            requests += request
            gates[request.queryParameters["type"] + ":" + request.queryParameters["id"]]?.await()
            request.queryParameters["offset"]?.let { offset -> offsetGates[offset]?.await() }
            val path = request.relativePath.removePrefix("/index.php/apps/news/api")
            return when {
                path.isEmpty() -> response(apiLevelsStatus, apiLevels)
                path == "/v1-3/folders" -> response(foldersStatus, """{"folders":[{"id":1,"name":"Tech"}]}""")
                path == "/v1-3/feeds" -> response(200, feedsBody)
                path == "/v1-3/items" -> response(200, itemPages(request.queryParameters))
                request.method == NextcloudApiMethod.POST -> {
                    stateGate?.await()
                    stateFailure?.let { throw it }
                    response(stateStatus, "[]")
                }
                else -> response(404, "")
            }
        }

        companion object {
            fun items(ids: LongProgression): String = ids.joinToString(prefix = """{"items":[""", postfix = "]}") { id ->
                """{"id":$id,"feedId":5,"title":"Article $id","body":"<p>Body $id</p>","unread":true,"starred":false}"""
            }

            fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
        }
    }

    private fun controller(server: FakeNewsServer, initialSelection: NewsSelection = NewsSelection.Unread) =
        NewsWorkspaceController(scope, server::execute, parseDispatcher = Dispatchers.Unconfined, initialSelection = initialSelection)

    @Test
    fun `start loads subscriptions and the unread list`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val state = controller.state
        assertFalse(state.refreshing)
        assertEquals(30, state.subscriptions?.totalUnread)
        assertEquals(1, state.subscriptions?.starredCount)
        val articles = assertIs<NewsArticlesState.Loaded>(state.articles)
        assertEquals(30, articles.items.size)
        assertTrue(articles.endReached, "A short first page has no further pages.")
        assertEquals("false", server.requests.last { it.relativePath.endsWith("/items") }.queryParameters["getRead"])
    }

    @Test
    fun `servers without the v1-3 API are reported as unsupported and not retried`() {
        val server = FakeNewsServer().apply { apiLevels = """{"apiLevels":["v1-2"]}""" }
        val controller = controller(server)
        controller.start()
        assertEquals(NewsFailureKind.UnsupportedVersion, controller.state.subscriptionsFailure?.kind)
        assertFalse(controller.state.subscriptionsFailure!!.canRetry)
    }

    @Test
    fun `missing version index and folders means unsupported, not offline`() {
        val server = FakeNewsServer().apply { apiLevelsStatus = 404; foldersStatus = 404 }
        val controller = controller(server)
        controller.start()
        assertEquals(NewsFailureKind.UnsupportedVersion, controller.state.subscriptionsFailure?.kind)
    }

    @Test
    fun `a slow response for an earlier selection cannot replace the current list`() {
        val server = FakeNewsServer()
        server.itemPages = { query ->
            when (query["type"]) {
                "0" -> FakeNewsServer.items(500L downTo 499L)
                else -> FakeNewsServer.items(30L downTo 1L)
            }
        }
        val controller = controller(server)
        controller.start()
        val slowFeed = CompletableDeferred<Unit>()
        server.gates["0:5"] = slowFeed
        controller.select(NewsSelection.Feed(5))
        controller.select(NewsSelection.Starred)
        slowFeed.complete(Unit)
        assertEquals(NewsSelection.Starred, controller.state.selection)
        val articles = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertEquals(30, articles.items.size, "The cancelled feed page must not overwrite the starred list.")
    }

    @Test
    fun `opening an unread article marks it read and updates the feed count`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        controller.open(item)
        assertEquals(item.id, controller.state.openItemId)
        assertFalse(controller.state.openItem!!.unread)
        assertEquals(29, controller.state.subscriptions?.totalUnread)
        assertTrue(controller.state.pendingItemIds.isEmpty())
        assertTrue(server.requests.any { it.relativePath.endsWith("/items/${item.id}/read") })
    }

    @Test
    fun `a rejected change rolls back only the affected article`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val items = (controller.state.articles as NewsArticlesState.Loaded).items
        controller.toggleStar(items[1])
        assertTrue(controller.state.openItemId == null)
        server.stateStatus = 403
        controller.toggleRead(items[0])
        val after = (controller.state.articles as NewsArticlesState.Loaded).items
        assertTrue(after[0].unread, "The rejected read is rolled back.")
        assertTrue(after[1].starred, "The earlier confirmed star is kept.")
        assertEquals(30, controller.state.subscriptions?.totalUnread)
        assertEquals(2, controller.state.subscriptions?.starredCount)
        assertTrue(controller.state.stateChangeNotice!!.startsWith("Couldn't mark the article as read."))
    }

    @Test
    fun `counts that already include an in-flight change are not adjusted twice`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        val reply = CompletableDeferred<Unit>()
        server.stateGate = reply
        controller.toggleRead(item)
        assertEquals(29, controller.state.subscriptions?.totalUnread)
        server.feedsBody = server.feedsBody.replace("\"unreadCount\":30", "\"unreadCount\":29")
        controller.refresh()
        assertEquals(29, controller.state.subscriptions?.totalUnread, "The server already applied the read.")
        reply.complete(Unit)
        assertEquals(29, controller.state.subscriptions?.totalUnread)
        val countReads = server.requests.count { it.relativePath.endsWith("/feeds") }
        assertEquals(3, countReads, "Counts are read once more after the change settles, and only once.")
    }

    @Test
    fun `the open article stays in the reader when a refresh drops it from the list`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        controller.open(item)
        server.itemPages = { query -> if (query["offset"] == "0") FakeNewsServer.items(29L downTo 1L) else FakeNewsServer.items(LongRange.EMPTY) }
        controller.refresh()
        val loaded = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertTrue(loaded.items.none { it.id == item.id })
        assertEquals(item.id, controller.state.openItem?.id, "Reading continues until the article is closed.")
        controller.toggleStar(controller.state.openItem!!)
        assertTrue(controller.state.openItem!!.starred, "Changes still reach the article in the reader.")
        controller.closeArticle()
        assertNull(controller.state.openItem)
    }

    @Test
    fun `a rejected change restores counts after the user moved to another view`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        val reply = CompletableDeferred<Unit>()
        server.stateGate = reply
        controller.toggleRead(item)
        assertEquals(29, controller.state.subscriptions?.totalUnread)
        server.itemPages = { FakeNewsServer.items(LongRange.EMPTY) }
        controller.select(NewsSelection.Starred)
        server.stateStatus = 403
        reply.complete(Unit)
        assertEquals(30, controller.state.subscriptions?.totalUnread, "The rejected read no longer counts.")
        assertTrue(controller.state.pendingItemIds.isEmpty())
    }

    @Test
    fun `an unconfirmed change is rolled back and never claimed as done`() {
        val server = FakeNewsServer().apply { stateFailure = IllegalStateException("connection reset") }
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        controller.toggleStar(item)
        assertFalse((controller.state.articles as NewsArticlesState.Loaded).items.first().starred)
        assertTrue(controller.state.stateChangeNotice!!.contains("did not confirm"))
        controller.dismissNotice()
        assertNull(controller.state.stateChangeNotice)
    }

    @Test
    fun `a refresh that races a pending change keeps the requested state`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        val confirm = CompletableDeferred<Unit>()
        server.stateGate = confirm
        controller.toggleStar(item)
        controller.refresh()
        val refreshed = (controller.state.articles as NewsArticlesState.Loaded).items.first { it.id == item.id }
        assertTrue(refreshed.starred, "The stale page must not erase the pending star.")
        server.feedsBody = server.feedsBody.replace("\"starredCount\":1", "\"starredCount\":2")
        confirm.complete(Unit)
        val confirmed = (controller.state.articles as NewsArticlesState.Loaded).items.first { it.id == item.id }
        assertTrue(confirmed.starred)
        assertTrue(controller.state.pendingItemIds.isEmpty())
        assertEquals(2, controller.state.subscriptions?.starredCount, "Counts are read again once the change settles.")
        controller.refresh()
        val afterConfirm = (controller.state.articles as NewsArticlesState.Loaded).items.first { it.id == item.id }
        assertFalse(afterConfirm.starred, "Once confirmed, later pages are shown as the server sends them.")
    }

    @Test
    fun `a removed feed falls back to Unread and loads it instead of the stale feed page`() {
        val server = FakeNewsServer()
        server.itemPages = { query ->
            when (query["type"]) {
                "0" -> FakeNewsServer.items(LongRange.EMPTY)
                else -> FakeNewsServer.items(30L downTo 1L)
            }
        }
        val controller = controller(server, initialSelection = NewsSelection.Feed(5))
        val subscriptionReads = CompletableDeferred<Unit>()
        val removedFeedPage = CompletableDeferred<Unit>()
        server.gates["null:null"] = subscriptionReads
        server.gates["0:5"] = removedFeedPage
        server.feedsBody = """{"starredCount":0,"feeds":[]}"""
        controller.start()
        subscriptionReads.complete(Unit)
        assertEquals(NewsSelection.Unread, controller.state.selection)
        removedFeedPage.complete(Unit)
        val articles = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertEquals(30, articles.items.size, "The removed feed's empty page must not replace the Unread list.")
        assertEquals("3", server.requests.last { it.relativePath.endsWith("/items") }.queryParameters["type"])
    }

    @Test
    fun `a restored selection is loaded first`() {
        val server = FakeNewsServer()
        val controller = controller(server, initialSelection = NewsSelection.Starred)
        controller.start()
        assertEquals(NewsSelection.Starred, controller.state.selection)
        assertEquals("2", server.requests.first { it.relativePath.endsWith("/items") }.queryParameters["type"])
    }

    @Test
    fun `paging appends older articles once and stops at the end`() {
        val server = FakeNewsServer()
        server.itemPages = { query ->
            when (query["offset"]) {
                "0" -> FakeNewsServer.items(100L downTo 61L)
                "61" -> FakeNewsServer.items(60L downTo 50L)
                else -> FakeNewsServer.items(LongRange.EMPTY)
            }
        }
        val controller = controller(server)
        controller.start()
        assertFalse((controller.state.articles as NewsArticlesState.Loaded).endReached)
        controller.loadMore()
        val loaded = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertEquals(51, loaded.items.size)
        assertEquals(loaded.items.map(NewsItem::id).distinct(), loaded.items.map(NewsItem::id))
        assertTrue(loaded.endReached)
        val pageRequests = server.requests.count { it.relativePath.endsWith("/items") }
        controller.loadMore()
        assertEquals(pageRequests, server.requests.count { it.relativePath.endsWith("/items") })
    }

    @Test
    fun `transport failures keep visible articles during refresh`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        server.itemPages = { throw IllegalStateException("offline") }
        controller.refresh()
        val loaded = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertEquals(30, loaded.items.size)
        assertEquals(NewsFailureKind.Offline, loaded.refreshFailure?.kind)
        assertNull(loaded.pageFailure, "A failed refresh is retried by refreshing, not by paging.")
        server.itemPages = { FakeNewsServer.items(30L downTo 1L) }
        controller.refresh()
        assertNull((controller.state.articles as NewsArticlesState.Loaded).refreshFailure)
    }

    @Test
    fun `a refresh that cancels an older-page load leaves paging usable even when it fails`() {
        val server = FakeNewsServer()
        val olderPages: (Map<String, String>) -> String = { query ->
            when (query["offset"]) {
                "0" -> FakeNewsServer.items(100L downTo 61L)
                "61" -> FakeNewsServer.items(60L downTo 50L)
                else -> FakeNewsServer.items(LongRange.EMPTY)
            }
        }
        server.itemPages = olderPages
        val controller = controller(server)
        controller.start()
        server.offsetGates["61"] = CompletableDeferred()
        controller.loadMore()
        assertTrue((controller.state.articles as NewsArticlesState.Loaded).loadingMore)
        server.itemPages = { throw IllegalStateException("offline") }
        controller.refresh()
        val afterFailure = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertFalse(afterFailure.loadingMore, "The cancelled page must not leave a spinner behind.")
        assertEquals(NewsFailureKind.Offline, afterFailure.refreshFailure?.kind)
        server.itemPages = olderPages
        server.offsetGates.clear()
        controller.loadMore()
        assertEquals(51, (controller.state.articles as NewsArticlesState.Loaded).items.size)
    }

    @Test
    fun `a failed refresh keeps changes made while it was running`() {
        val server = FakeNewsServer()
        val controller = controller(server)
        controller.start()
        val item = (controller.state.articles as NewsArticlesState.Loaded).items.first()
        val refreshPage = CompletableDeferred<Unit>()
        server.gates["3:0"] = refreshPage
        server.itemPages = { throw IllegalStateException("offline") }
        controller.refresh()
        controller.toggleStar(item)
        refreshPage.complete(Unit)
        val loaded = assertIs<NewsArticlesState.Loaded>(controller.state.articles)
        assertTrue(loaded.items.first { it.id == item.id }.starred, "The confirmed star must survive the failed refresh.")
        assertEquals(NewsFailureKind.Offline, loaded.refreshFailure?.kind)
    }

    @Test
    fun `http statuses map to typed failures`() {
        assertNull(newsFailureForStatus(204))
        assertEquals(NewsFailureKind.Unauthorized, newsFailureForStatus(401)?.kind)
        assertEquals(NewsFailureKind.Forbidden, newsFailureForStatus(403)?.kind)
        assertEquals(NewsFailureKind.Unavailable, newsFailureForStatus(404)?.kind)
        assertEquals(NewsFailureKind.Throttled, newsFailureForStatus(429)?.kind)
        assertEquals(NewsFailureKind.Server, newsFailureForStatus(500)?.kind)
    }
}
