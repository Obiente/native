package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCommentsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private data class Comment(val id: Long, val actor: String, val message: String, val unread: Boolean = false)

    private class FakeComments {
        val comments = mutableListOf(Comment(2, "bob", "Looks good @alice"), Comment(1, "alice", "First draft", unread = true))
        var nextId = 10L
        var postStatus = 201
        var postThrowsAfterApply = false
        var postThrowsBeforeApply = false
        var listFails = false
        var deleteStatus = 204
        var deleteThrowsAfterApply = false
        /** When set, a listing returns only this many comments, as a paged server does. */
        var pageLimit: Int? = null
        val requests = mutableListOf<FilesDavRequest>()

        suspend fun execute(request: FilesDavRequest): NextcloudApiResponse {
            requests += request
            return when (request.method) {
                FilesDavMethod.REPORT -> {
                    if (listFails) throw IllegalStateException("offline")
                    response(207, listing())
                }
                FilesDavMethod.POST -> {
                    if (postThrowsBeforeApply) throw IllegalStateException("reset")
                    if (postStatus == 201) {
                        val body = request.body!!.decodeToString()
                        val message = Regex("\"message\":\"([^\"]*)\"").find(body)!!.groupValues[1]
                        comments.add(0, Comment(nextId++, "alice", message))
                    }
                    if (postThrowsAfterApply) throw IllegalStateException("reset after apply")
                    response(postStatus)
                }
                FilesDavMethod.PROPPATCH -> {
                    val id = request.relativePath.substringAfterLast('/').toLongOrNull()
                    val message = request.body!!.decodeToString().substringAfter("<oc:message>", "").substringBefore("</oc:message>")
                    if (id != null && message.isNotEmpty()) {
                        val index = comments.indexOfFirst { it.id == id }
                        comments[index] = comments[index].copy(message = message)
                    }
                    response(207, """<d:multistatus xmlns:d="DAV:"><d:response><d:href>x</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                }
                FilesDavMethod.DELETE -> {
                    val id = request.relativePath.substringAfterLast('/').toLong()
                    if (deleteStatus == 204) comments.removeAll { it.id == id }
                    if (deleteThrowsAfterApply) throw IllegalStateException("reset after apply")
                    response(deleteStatus)
                }
                else -> error("unexpected")
            }
        }

        fun listing() = buildString {
            append("""<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
            if (pageLimit == null) {
                append("<d:response><d:href>/remote.php/dav/comments/files/42/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            comments.take(pageLimit ?: comments.size).forEach { c ->
                append("<d:response><d:href>/remote.php/dav/comments/files/42/${c.id}</d:href><d:propstat><d:prop>")
                append("<oc:id>${c.id}</oc:id><oc:verb>comment</oc:verb><oc:actorType>users</oc:actorType><oc:actorId>${c.actor}</oc:actorId>")
                append("<oc:actorDisplayName>${c.actor.replaceFirstChar(Char::uppercaseChar)}</oc:actorDisplayName>")
                append("<oc:message>${c.message.escapeDavXml()}</oc:message><oc:isUnread>${c.unread}</oc:isUnread>")
                append("<oc:creationDateTime>Tue, 05 Mar 2024 10:00:00 GMT</oc:creationDateTime>")
                if (c.message.contains("@alice")) {
                    append("<oc:mentions><oc:mention><oc:mentionType>user</oc:mentionType><oc:mentionId>alice</oc:mentionId><oc:mentionDisplayName>Alice A</oc:mentionDisplayName></oc:mention></oc:mentions>")
                }
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }

        fun posts() = requests.count { it.method == FilesDavMethod.POST }
    }

    private fun controller(fake: FakeComments, attempts: FileCommentPostAttemptStore? = null) =
        FileCommentsController(scope, "alice", 42L, fake::execute, attempts)

    private class MemoryPostAttempts(private val failSave: Boolean = false) : FileCommentPostAttemptStore {
        var value: String? = null
        val postsAtSave = mutableListOf<Int>()
        var posts: () -> Int = { 0 }

        override suspend fun load(): String? = value

        override suspend fun save(message: String) {
            check(!failSave) { "Synthetic storage is full." }
            postsAtSave += posts()
            value = message
        }

        override suspend fun clear() {
            value = null
        }
    }

    @Test
    fun anUnknownPostIsOnRecordBeforeItLeavesAndStillBlocksAfterTheFileIsReopened() {
        val fake = FakeComments().apply {
            comments.clear()
            repeat(FILE_COMMENTS_PAGE_SIZE + 50) { index -> comments += Comment(1_000L + index, "bob", "Comment $index") }
            pageLimit = FILE_COMMENTS_PAGE_SIZE
            postThrowsBeforeApply = true
        }
        val store = MemoryPostAttempts().apply { posts = { fake.posts() } }
        controller(fake, store).also { it.start() }.post("Hello") {}
        assertEquals("Hello", store.value)
        assertEquals(0, store.postsAtSave.first(), "The comment is on record before the request is sent.")

        // The file was closed and opened again: a new controller, the same device record.
        val reopened = controller(fake, store).also { it.start() }
        assertTrue(reopened.state.postUnconfirmed, "Opening the thread again does not make the earlier post safe to repeat.")
        reopened.post("Hello") {}
        assertEquals(1, fake.posts())
        reopened.refresh()
        assertTrue(reopened.state.canPost, "Refreshing after checking the comments is the decision to post again.")
        assertNull(store.value)
    }

    @Test
    fun aRecordedPostThatIsFoundOnReopeningIsResolvedAndAnUnrecordedOneIsNotSent() {
        val fake = FakeComments().apply { postThrowsAfterApply = true }
        val store = MemoryPostAttempts()
        val first = controller(fake, store).also { it.start() }
        fake.listFails = true
        first.post("Hello") {}
        assertEquals("Hello", store.value, "The post reached the server but could not be verified.")

        fake.listFails = false
        val reopened = controller(fake, store).also { it.start() }
        assertFalse(reopened.state.postUnconfirmed, "The comment is in the thread, so nothing is unresolved.")
        assertNull(store.value)

        val unwritable = controller(fake, MemoryPostAttempts(failSave = true)).also { it.start() }
        val before = fake.posts()
        unwritable.post("Another") {}
        assertEquals(before, fake.posts(), "Nothing is posted without a record.")
        assertTrue(unwritable.state.notice.orEmpty().contains("could not keep a record"))
    }

    @Test
    fun aRefreshThatBeganBeforeAPostDoesNotSettleIt() {
        val fake = FakeComments()
        val store = MemoryPostAttempts()
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        var holdRead = false
        val controller = FileCommentsController(scope, "alice", 42L, { request ->
            val held = request.method == FilesDavMethod.REPORT && holdRead
            if (held) holdRead = false
            val answer = fake.execute(request)
            if (held) hold.await()
            answer
        }, store)
        controller.start()
        // The refresh reads the thread, then the post is lost and cannot be verified.
        holdRead = true
        controller.refresh()
        fake.postThrowsBeforeApply = true
        fake.listFails = true
        controller.post("Hello") {}
        assertTrue(controller.state.postUnconfirmed)
        hold.complete(Unit)
        assertTrue(controller.state.postUnconfirmed, "A read from before the post says nothing about it.")
        assertEquals("Hello", store.value, "The record survives for a later reopening.")
    }

    @Test
    fun anOlderPageThatARefreshOvertookIsNotAppended() {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fake = FakeComments().apply {
            comments.clear()
            repeat(FILE_COMMENTS_PAGE_SIZE * 3) { index -> comments += Comment(1_000L + index, "bob", "Comment $index") }
            pageLimit = FILE_COMMENTS_PAGE_SIZE
        }
        var holdOlder = false
        val controller = FileCommentsController(scope, "alice", 42L, { request ->
            val older = request.method == FilesDavMethod.REPORT && holdOlder
            val answer = fake.execute(request)
            if (older) { holdOlder = false; hold.await() }
            answer
        })
        controller.start()
        holdOlder = true
        controller.loadMore()
        controller.refresh()
        hold.complete(Unit)
        assertEquals(FILE_COMMENTS_PAGE_SIZE, controller.state.serverOffset, "The overtaken page does not move the offset past a page that was never shown.")
        assertFalse(controller.state.loadingMore)
    }

    @Test
    fun requestsAreBoundToTheFileAndValidated() {
        val list = fileCommentsListRequest(42, offset = 100)
        assertEquals("/remote.php/dav/comments/files/42", list.relativePath)
        assertTrue(list.body!!.decodeToString().contains("<oc:offset>100</oc:offset>"))
        assertFailsWith<IllegalArgumentException> { fileCommentsListRequest(42, offset = 0, limit = 101) }
        assertFailsWith<IllegalArgumentException> { fileCommentCreateRequest(42, "   ") }
        assertFailsWith<IllegalArgumentException> { fileCommentCreateRequest(42, "x".repeat(1001)) }
        assertFailsWith<IllegalArgumentException> { fileCommentDeleteRequest(0, 1) }
        val edit = fileCommentEditRequest(42, 7, "a < b & c")
        assertTrue(edit.body!!.decodeToString().contains("a &lt; b &amp; c"))
    }

    @Test
    fun parsesCommentsReplacingMentionsAndSkippingForeignEntries() {
        val fake = FakeComments()
        val page = parseFileCommentsPage(response(207, fake.listing()), 42, 100)
        assertEquals(listOf(2L, 1L), page.comments.map { it.id })
        assertEquals("Looks good @Alice A", page.comments.first().displayMessage)
        assertEquals("Looks good @alice", page.comments.first().message, "An edit starts from the stored text, so the mention keeps its account id.")
        assertEquals(1709632800L, page.comments.first().createdAtEpochSeconds)
        assertFalse(page.mayHaveMore)
        val foreign = parseFileCommentsPage(response(207, fake.listing().replace("/files/42/2", "/files/43/2")), 42, 100)
        assertEquals(1, foreign.skippedEntries)
    }

    @Test
    fun hiddenVerbsStillAdvanceTheServerOffset() {
        val fake = FakeComments()
        val body = fake.listing().replaceFirst("<oc:verb>comment</oc:verb>", "<oc:verb>reaction</oc:verb>")
        val page = parseFileCommentsPage(response(207, body), 42, 2)
        assertEquals(listOf(1L), page.comments.map { it.id })
        assertEquals(2, page.serverEntries)
        assertTrue(page.mayHaveMore)
    }

    @Test
    fun httpDateParserRejectsMalformedValues() {
        assertEquals(0L, parseHttpDateEpochSeconds("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertNull(parseHttpDateEpochSeconds("2024-03-05T10:00:00Z"))
        assertNull(parseHttpDateEpochSeconds("Tue, 45 Mar 2024 10:00:00 GMT"))
    }

    @Test
    fun loadingMarksUnreadCommentsRead() {
        val fake = FakeComments()
        controller(fake).start()
        assertTrue(fake.requests.any { it.method == FilesDavMethod.PROPPATCH && it.relativePath.endsWith("/files/42") })
    }

    @Test
    fun postConfirmedClearsDraftAndShowsComment() {
        val fake = FakeComments()
        val controller = controller(fake).also { it.start() }
        var cleared = false
        controller.post("New note") { cleared = true }
        assertTrue(cleared)
        assertEquals("New note", controller.state.comments!!.first().message)
    }

    @Test
    fun unknownPostThatReachedTheServerIsDetectedWithoutReposting() {
        val fake = FakeComments().apply { postThrowsAfterApply = true }
        val controller = controller(fake).also { it.start() }
        var cleared = false
        controller.post("Hello") { cleared = true }
        assertTrue(cleared)
        assertEquals(1, fake.posts())
        assertNull(controller.state.notice)
    }

    @Test
    fun anUnknownPostIsNotCalledUnsentWhenTheFirstPageIsFull() {
        val fake = FakeComments().apply {
            comments.clear()
            repeat(FILE_COMMENTS_PAGE_SIZE + 50) { index -> comments += Comment(1_000L + index, "bob", "Comment $index") }
            pageLimit = FILE_COMMENTS_PAGE_SIZE
        }
        val controller = controller(fake).also { it.start() }
        fake.postThrowsBeforeApply = true
        controller.post("Hello") {}
        assertTrue(controller.state.postUnconfirmed, "More comments may exist, so not seeing it on the first page proves nothing.")
        controller.post("Hello") {}
        assertEquals(1, fake.posts())
    }

    @Test
    fun unknownPostThatCannotBeVerifiedBlocksPostingUntilRefresh() {
        val fake = FakeComments()
        val controller = controller(fake).also { it.start() }
        fake.postThrowsBeforeApply = true
        fake.listFails = true
        var cleared = false
        controller.post("Hello") { cleared = true }
        assertFalse(cleared)
        assertTrue(controller.state.postUnconfirmed)
        assertFalse(controller.state.canPost)
        controller.post("Hello") { cleared = true }
        assertEquals(1, fake.posts())
        fake.listFails = false
        controller.refresh()
        assertTrue(controller.state.canPost)
    }

    @Test
    fun onlyTheAuthorCanEditOrDelete() {
        val fake = FakeComments()
        val controller = controller(fake).also { it.start() }
        val bobs = controller.state.comments!!.first { it.actorId == "bob" }
        controller.requestDelete(bobs)
        assertNull(controller.state.confirmDeleteId)
        val own = controller.state.comments!!.first { it.actorId == "alice" }
        var saved = false
        controller.edit(own, "Second draft") { saved = true }
        assertTrue(saved)
        assertEquals("Second draft", controller.state.comments!!.first { it.id == own.id }.message)
        assertEquals("Second draft", controller.state.comments!!.first { it.id == own.id }.displayMessage, "The saved text is what is shown.")
        controller.requestDelete(own)
        assertEquals(own.id, controller.state.confirmDeleteId)
        controller.confirmDelete()
        assertTrue(controller.state.comments!!.none { it.id == own.id })
    }

    @Test
    fun rejectedDeleteKeepsTheComment() {
        val fake = FakeComments().apply { deleteStatus = 403 }
        val controller = controller(fake).also { it.start() }
        val own = controller.state.comments!!.first { it.actorId == "alice" }
        controller.requestDelete(own)
        controller.confirmDelete()
        assertTrue(controller.state.comments!!.any { it.id == own.id })
        assertTrue(controller.state.notice!!.startsWith("Couldn't delete"))
    }

    @Test
    fun anUnconfirmedDeleteIsNotConfirmedByAFullFirstPage() {
        val fake = FakeComments().apply {
            comments.clear()
            repeat(FILE_COMMENTS_PAGE_SIZE + 50) { index -> comments += Comment(1_000L + index, "alice", "Comment $index") }
            pageLimit = FILE_COMMENTS_PAGE_SIZE
            deleteThrowsAfterApply = true
        }
        val controller = controller(fake).also { it.start() }
        val own = controller.state.comments!!.first()
        controller.requestDelete(own)
        controller.confirmDelete()
        assertTrue(own.id in controller.state.unconfirmedIds, "More comments may exist, so absence from the first page proves nothing.")
        assertTrue(controller.state.notice!!.startsWith("Couldn't confirm"))
    }

    @Test
    fun missingCommentsEndpointShowsUnavailable() {
        val controller = FileCommentsController(scope, "alice", 42L, { response(404) })
        controller.start()
        assertTrue(controller.state.unavailable)
        assertFalse(controller.state.canPost)
    }
}
