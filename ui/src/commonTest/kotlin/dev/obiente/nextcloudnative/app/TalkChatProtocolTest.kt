package dev.obiente.nextcloudnative.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkChatProtocolTest {
    private fun response(status: Int, body: String, lastGiven: Long? = null) =
        TalkTransportResponse(status, body.encodeToByteArray(), lastGiven)

    @Test
    fun `long poll waits on the server and never writes read state`() {
        val request = TalkChatRequests.poll("room/1", lastKnownMessageId = 41)
        assertEquals(TalkTransportMethod.GET, request.method)
        assertEquals(TALK_POLL_WAIT_SECONDS, request.serverWaitSeconds)
        assertEquals(
            "https://cloud.example.test/ocs/v2.php/apps/spreed/api/v1/chat/room%2F1?format=json" +
                "&lookIntoFuture=1&limit=200&lastKnownMessageId=41&includeLastKnown=0&timeout=30" +
                "&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1",
            request.url("https://cloud.example.test/"),
        )
    }

    @Test
    fun `history has no wait and validates its cursor and size`() {
        val request = TalkChatRequests.history("room", olderCursor = 73, limit = 25)
        assertEquals(0, request.serverWaitSeconds)
        val query = request.queryParameters.toMap()
        assertEquals("0", query["lookIntoFuture"])
        assertEquals("73", query["lastKnownMessageId"])
        assertNull(query["timeout"])
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.history("room", olderCursor = -1) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.history("room", null, MAX_TALK_MESSAGE_PAGE_SIZE + 1) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.poll("room", lastKnownMessageId = -1) }
    }

    @Test
    fun `writes encode their form and reject unresolved targets`() {
        val send = TalkChatRequests.send("room", "a & b = c", replyTo = 7, referenceId = FakeTalkServer.REFERENCE_ID)
        assertEquals(TalkTransportMethod.POST, send.method)
        assertEquals(
            "message=a%20%26%20b%20%3D%20c&replyTo=7&referenceId=${FakeTalkServer.REFERENCE_ID}",
            send.encodedFormBody()?.decodeToString(),
        )
        val edit = TalkChatRequests.edit("room", 9, "new text")
        assertEquals(TalkTransportMethod.PUT, edit.method)
        assertTrue(edit.relativePath.endsWith("/chat/room/9"))
        assertEquals(TalkTransportMethod.DELETE, TalkChatRequests.delete("room", 9).method)

        assertFailsWith<IllegalArgumentException> { TalkChatRequests.send("", "text", null, null) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.send("room token", "text", null, null) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.send("room", "  ", null, null) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.send("room", "text", replyTo = 0, referenceId = null) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.send("room", "text", null, referenceId = "not-a-reference") }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.delete("room", 0) }
        assertFailsWith<IllegalArgumentException> { TalkChatRequests.react("room", 1, "two words", add = true) }
    }

    @Test
    fun `reaction add posts a form while removal uses the query`() {
        val add = TalkChatRequests.react("room", 5, "👍", add = true)
        assertEquals(TalkTransportMethod.POST, add.method)
        assertEquals(listOf("reaction" to "👍"), add.formFields)
        assertTrue(add.relativePath.endsWith("/reaction/room/5"))
        val remove = TalkChatRequests.react("room", 5, "👍", add = false)
        assertEquals(TalkTransportMethod.DELETE, remove.method)
        assertTrue(remove.formFields.isEmpty())
        assertTrue(remove.url("https://cloud.example.test").endsWith("&reaction=%F0%9F%91%8D"))
    }

    @Test
    fun `capabilities parse features and the message limit`() {
        val parsed = parseTalkCapabilities(
            response(
                200,
                """{"ocs":{"data":{"capabilities":{"spreed":{"features":["chat-replies","reactions",""],
                "config":{"chat":{"max-length":4000}}}}}}}""",
            ),
        )
        assertEquals(setOf("chat-replies", "reactions"), parsed?.features)
        assertEquals(4000, parsed?.maxMessageLength)
        assertEquals(
            TalkCapabilities.None,
            parseTalkCapabilities(response(200, """{"ocs":{"data":{"capabilities":{"core":{}}}}}""")),
        )
        assertNull(parseTalkCapabilities(response(200, "not json")))
        assertNull(parseTalkCapabilities(response(503, "")))
        val withoutLimit = parseTalkCapabilities(response(200, """{"ocs":{"data":{"capabilities":{"spreed":{"features":[]}}}}}"""))
        assertEquals(DEFAULT_TALK_MAX_MESSAGE_LENGTH, withoutLimit?.maxMessageLength)
    }

    @Test
    fun `conversation access treats missing read only state as read only`() {
        val access = parseTalkConversationAccess(
            response(200, """{"ocs":{"data":{"token":"room","type":6,"readOnly":0,"lobbyState":0,"participantType":1,"permissions":254}}}"""),
        )
        assertEquals(TalkConversationType.NoteToSelf, access?.type)
        assertFalse(requireNotNull(access).readOnly)
        assertEquals(254, access.permissions)
        val missing = parseTalkConversationAccess(response(200, """{"ocs":{"data":{"token":"room"}}}"""))
        assertTrue(requireNotNull(missing).readOnly)
        assertNull(missing.permissions)
        val lobby = parseTalkConversationAccess(response(200, """{"ocs":{"data":{"token":"room","readOnly":0,"lobbyState":1,"participantType":3}}}"""))
        assertTrue(requireNotNull(lobby).lobbyBlocksParticipant)
        assertNull(parseTalkConversationAccess(response(200, """{"ocs":{"data":[]}}""")))
    }

    @Test
    fun `chat reads classify not modified failures and malformed bodies`() {
        assertEquals(TalkChatRead.NotModified, parseTalkChatRead(response(304, "")))
        assertEquals(TalkFailureKind.Lobby, assertIs<TalkChatRead.Failed>(parseTalkChatRead(response(412, ""))).failure.kind)
        assertEquals(TalkFailureKind.Unauthorized, assertIs<TalkChatRead.Failed>(parseTalkChatRead(response(401, ""))).failure.kind)
        assertEquals(TalkFailureKind.Malformed, assertIs<TalkChatRead.Failed>(parseTalkChatRead(response(200, "<html>"))).failure.kind)
        assertEquals(TalkFailureKind.Malformed, assertIs<TalkChatRead.Failed>(parseTalkChatRead(response(200, """{"ocs":{"data":{}}}"""))).failure.kind)
        val read = assertIs<TalkChatRead.Messages>(
            parseTalkChatRead(response(200, """{"ocs":{"data":[{"id":0},{"id":3,"message":"ok","actorId":"a"}]}}""", lastGiven = 3)),
        )
        assertEquals(listOf(3L), read.entries.map { it.message.id }, "Entries without an ID are dropped.")
        assertEquals(3L, read.lastGiven)
    }

    @Test
    fun `update system messages carry the parent and record whether own reactions are known`() {
        val read = assertIs<TalkChatRead.Messages>(
            parseTalkChatRead(
                response(
                    200,
                    """{"ocs":{"data":[
                        {"id":9,"systemMessage":"reaction","messageType":"system","message":"{reaction}",
                         "parent":{"id":4,"message":"Synthetic","messageType":"comment","actorId":"a","reactions":{"x":2}}},
                        {"id":10,"systemMessage":"message_deleted","messageType":"system","message":"deleted",
                         "parent":{"id":5,"message":"Message deleted by you","messageType":"comment_deleted","reactionsSelf":[]}},
                        {"id":11,"message":"A reply","messageType":"comment","parent":{"id":4,"deleted":true}}
                    ]}}""",
                ),
            ),
        )
        val (reaction, deletion, reply) = read.entries
        assertTrue(reaction.isTimelineUpdate)
        assertEquals(4L, reaction.updatedParent?.id)
        assertFalse(reaction.parentIncludesOwnReactions)
        assertEquals(true, deletion.updatedParent?.deleted)
        assertTrue(deletion.parentIncludesOwnReactions)
        assertFalse(reply.isTimelineUpdate)
        assertNull(reply.updatedParent, "A reply's parent is a quote, not an update.")
        assertEquals(true, reply.message.parent?.deleted)
    }

    @Test
    fun `write outcomes separate decisions from unknown results`() {
        assertIs<TalkWriteOutcome.Confirmed<Unit>>(talkWriteOutcome(response(201, "")) {})
        assertIs<TalkWriteOutcome.Confirmed<Unit>>(talkWriteOutcome(response(202, "")) {})
        listOf(400 to TalkFailureKind.NotAllowed, 403 to TalkFailureKind.Forbidden, 404 to TalkFailureKind.NotFound,
            405 to TalkFailureKind.NotAllowed, 412 to TalkFailureKind.Lobby, 413 to TalkFailureKind.TooLong,
            429 to TalkFailureKind.Throttled, 502 to TalkFailureKind.Server).forEach { (status, kind) ->
            assertEquals(kind, assertIs<TalkWriteOutcome.Rejected>(talkWriteOutcome(response(status, "")) {}).failure.kind)
        }
        listOf(500, 503, 504, 302).forEach { status ->
            assertEquals(TalkWriteOutcome.Unknown, talkWriteOutcome(response(status, "")) {}, "HTTP $status")
        }
    }

    @Test
    fun `reaction maps mark this account and reject malformed data`() {
        val map = """{"ocs":{"data":{"a":[{"actorType":"users","actorId":"self"},{"actorType":"guests","actorId":"self"}],
            "b":[{"actorType":"users","actorId":"other"}],"c":[]}}}"""
        assertEquals(
            listOf(TalkReaction("a", 2, true), TalkReaction("b", 1, false)),
            parseTalkReactions(response(200, map), "self"),
        )
        assertEquals(emptyList(), parseTalkReactions(response(200, """{"ocs":{"data":[]}}"""), "self"))
        assertNull(parseTalkReactions(response(200, """{"ocs":{"data":{"a":3}}}"""), "self"))
        assertNull(parseTalkReactions(response(200, """{"ocs":{"data":["a"]}}"""), "self"))
        assertNull(parseTalkReactions(response(404, ""), "self"))
    }

    @Test
    fun `reference ids are 64 lowercase hex characters`() {
        val id = newTalkReferenceId(Random(7))
        assertTrue(id.isTalkReferenceId())
        assertFalse(id.uppercase().isTalkReferenceId())
        assertFalse("abc".isTalkReferenceId())
    }

    @Test
    fun `message length counts a surrogate pair once`() {
        assertEquals(3, talkMessageLength("a👍b"))
    }

    @Test
    fun `transport requests reject paths outside the Talk API`() {
        assertFailsWith<IllegalArgumentException> {
            TalkTransportRequest(TalkTransportMethod.GET, "/remote.php/dav/files/x", emptyList(), emptyList(), 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            TalkTransportRequest(TalkTransportMethod.GET, "$TALK_API_PREFIX/chat/x", emptyList(), listOf("a" to "b"), 1024)
        }
    }
}
