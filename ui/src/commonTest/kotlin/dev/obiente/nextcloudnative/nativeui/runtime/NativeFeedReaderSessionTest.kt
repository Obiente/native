package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFeedReaderSessionTest {
    @Test
    fun `a pending change and its error survive appending a page`() {
        val session = NativeFeedReaderSession()
        val first = listOf(article("1", unread = true), article("2", unread = true))
        session.reconcile(first)
        session.begin(first[0], markRead())
        session.failed(first[1].also { session.begin(it, markRead()) }, "The app did not accept this change.")

        session.reconcile(first + article("3", unread = true))
        assertEquals(NativeFeedArticleState(unread = false), session.overrides[first[0].key])
        assertEquals("The app did not accept this change.", session.actionError)
    }

    @Test
    fun `a confirmed change stays until the server copy of the article changes`() {
        val session = NativeFeedReaderSession()
        val first = listOf(article("1", unread = true))
        session.reconcile(first)
        session.begin(first[0], markRead())
        session.succeeded(first[0])

        // The same, not yet updated record arrives with the next page.
        session.reconcile(first + article("2", unread = true))
        assertEquals(NativeFeedArticleState(unread = false), session.overrides[first[0].key])

        // A changed server copy is authoritative, whatever it says.
        session.reconcile(listOf(article("1", unread = true, title = "Edited"), article("2", unread = true)))
        assertNull(session.overrides[first[0].key])
    }

    @Test
    fun `reading the list again clears the error but keeps changes still in flight`() {
        val session = NativeFeedReaderSession()
        val first = listOf(article("1", unread = true), article("2", unread = true))
        session.reconcile(first)
        session.begin(first[0], markRead())
        session.failed(first[1].also { session.begin(it, markRead()) }, "Rejected.")
        assertEquals("Rejected.", session.actionError)

        session.reconcile(listOf(article("2", unread = true), article("1", unread = true)))
        assertNull(session.actionError)
        assertEquals(NativeFeedArticleState(unread = false), session.overrides[first[0].key])
    }

    @Test
    fun `an article that leaves the list drops its local state`() {
        val session = NativeFeedReaderSession()
        val first = listOf(article("1", unread = true))
        session.reconcile(first)
        session.begin(first[0], markRead())
        session.reconcile(listOf(article("2", unread = true)))
        assertTrue(session.overrides.isEmpty())
    }

    @Test
    fun `automatic loading stops after pages that add nothing visible`() {
        val autoLoad = NativeFeedAutoLoad(maxUnproductivePages = 2)
        assertTrue(autoLoad.shouldLoad(loadedCount = 20, visibleCount = 3))
        assertTrue(autoLoad.shouldLoad(loadedCount = 20, visibleCount = 3), "returning to the end of the list is not a new page")
        assertTrue(autoLoad.shouldLoad(loadedCount = 40, visibleCount = 3))
        assertFalse(autoLoad.shouldLoad(loadedCount = 60, visibleCount = 3))
        assertFalse(autoLoad.shouldLoad(loadedCount = 60, visibleCount = 3))

        val unfiltered = NativeFeedAutoLoad(maxUnproductivePages = 2)
        listOf(20, 40, 60, 80).forEach { loaded -> assertTrue(unfiltered.shouldLoad(loaded, loaded)) }
    }

    @Test
    fun `filtered views say that they cover only the loaded pages`() {
        val plan = NativeFeedReaderPlan(
            articles = listOf(article("1", unread = true), article("2", unread = false)),
            feeds = listOf(NativeFeedSource(feeds, NativeRecord("3", mapOf("title" to "Example Feed")), "Example Feed", 1, null, folder = false)),
            folders = emptyList(),
            selectedArticle = null,
        )
        assertNull(nativeFeedFilterCaption(plan, NativeFeedFilter.All, feedId = null))
        assertEquals("Showing unread articles among the 2 loaded articles.", nativeFeedFilterCaption(plan, NativeFeedFilter.Unread, null))
        assertEquals("Showing starred articles among the 2 loaded articles.", nativeFeedFilterCaption(plan, NativeFeedFilter.Starred, null))
        assertEquals(
            "Showing articles from Example Feed among the 2 loaded articles.",
            nativeFeedFilterCaption(plan, NativeFeedFilter.All, feedId = "3"),
        )
    }

    private val items = ResourceSpec(id = "items", name = "Items", confidence = Confidence.high, fields = emptyList())
    private val feeds = ResourceSpec(id = "feeds", name = "Feeds", confidence = Confidence.high, fields = emptyList())

    private fun article(id: String, unread: Boolean, title: String = "Article $id") = NativeFeedArticle(
        resource = items,
        record = NativeRecord(id, mapOf("id" to id, "title" to title, "unread" to unread.toString())),
        title = title,
        link = "https://example.test/articles/$id",
        author = null,
        body = null,
        html = false,
        unread = unread,
        starred = false,
        sourceId = "3",
        publishedLabel = null,
    )

    private fun markRead() = NativeFeedItemActionPlan(
        kind = NativeFeedItemActionKind.MarkRead,
        action = ActionSpec(
            id = "items-read",
            label = "Read",
            resourceId = "items",
            binding = ApiBinding(method = HttpMethod.POST, path = "/apps/synthetic/api/items/{itemId}/read", operationId = "items-read"),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        values = mapOf("id" to "1"),
    )
}
