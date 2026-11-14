package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.NOW
import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.REFERENCE_ID
import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.SELF
import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.TOKEN
import dev.obiente.nextcloudnative.app.FakeTalkServer.WriteFault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkChatControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val waits = mutableListOf<Long>()

    @AfterTest
    fun cancelScope() = scope.cancel()

    private fun controller(
        server: FakeTalkServer,
        unconfirmed: TalkUnconfirmedSend? = null,
        memory: TalkChatMemory? = null,
        store: TalkUnconfirmedSendStore? = null,
    ) = TalkChatController(
        scope = scope,
        token = TOKEN,
        ownActorId = SELF,
        execute = server::execute,
        memory = memory,
        initialUnconfirmed = unconfirmed,
        unconfirmedStore = store,
        parseDispatcher = Dispatchers.Unconfined,
        nowSeconds = { server.nowSeconds },
        newReferenceId = { REFERENCE_ID },
        wait = { waits += it },
    )

    private fun serverWithHistory() = FakeTalkServer().apply {
        post("alice", "Hello from a synthetic account")
        post(SELF, "A reply from this account")
    }

    @Test
    fun `start loads access and history and reads without writing`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        val state = controller.state
        assertEquals(listOf(1L, 2L), state.messages?.map(TalkMessage::id))
        assertTrue(state.abilities.canPost)
        assertTrue(server.writes().isEmpty())
        val history = server.requests.single { it.queryParameters.toMap()["lookIntoFuture"] == "0" }
        assertEquals("0", history.queryParameters.toMap()["setReadMarker"])
        assertEquals("1", history.queryParameters.toMap()["noStatusUpdate"])
        assertEquals(TalkLiveState.Paused, state.live, "Polling waits until the screen is visible.")
    }

    @Test
    fun `missing capabilities hide every optional write`() {
        val server = serverWithHistory().apply { features = emptyList() }
        val controller = controller(server).apply { start() }
        val own = requireNotNull(controller.state.timeline?.message(2L))
        val other = requireNotNull(controller.state.timeline?.message(1L))
        assertTrue(controller.state.abilities.canPost, "Plain chat is part of every Talk version.")
        assertFalse(controller.state.abilities.canReply(other))
        assertFalse(controller.state.abilities.canReact)
        assertFalse(controller.canEdit(own))
        assertFalse(controller.canDelete(own))
        controller.toggleReaction(other, "+1")
        controller.reply(other)
        controller.startEdit(own)
        controller.delete(own)
        assertNull(controller.state.replyTo)
        assertNull(controller.state.editing)
        assertTrue(server.writes().isEmpty())
    }

    @Test
    fun `read only lobby and missing chat permission disable sending`() {
        listOf(
            FakeTalkServer(readOnly = 1),
            FakeTalkServer(lobbyState = 1),
            FakeTalkServer(permissions = 1 or 2 or 4),
            FakeTalkServer(permissions = null),
        ).forEach { server ->
            server.post("alice", "Synthetic")
            val controller = controller(server).apply { start() }
            assertFalse(controller.state.abilities.canPost)
            controller.send("Not allowed")
            assertTrue(server.writes().isEmpty())
        }
        val moderator = FakeTalkServer(lobbyState = 1, participantType = 2).apply { post("alice", "Synthetic") }
        assertTrue(controller(moderator).apply { start() }.state.abilities.canPost, "Moderators can write in the lobby.")
    }

    @Test
    fun `an unreadable conversation keeps writes disabled`() {
        val server = serverWithHistory().apply { roomFailure = true }
        val controller = controller(server).apply { start() }
        assertEquals(2, controller.state.messages?.size)
        assertFalse(controller.state.abilities.canPost)
    }

    @Test
    fun `react permission is required once the server advertises it`() {
        val server = serverWithHistory().apply { permissions = 254 }
        assertFalse(controller(server).apply { start() }.state.abilities.canReact)
        server.features = server.features - "react-permission"
        assertTrue(controller(server).apply { start() }.state.abilities.canReact, "Older servers use the chat permission.")
    }

    @Test
    fun `live updates append messages and apply edits deletes and reactions without showing them`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        controller.setLiveUpdates(true)
        assertEquals(TalkLiveState.Connected, controller.state.live)
        assertEquals(1, server.pollsWaiting)
        val poll = server.requests.last()
        assertEquals("1", poll.queryParameters.toMap()["lookIntoFuture"])
        assertEquals("2", poll.queryParameters.toMap()["lastKnownMessageId"])
        assertEquals(TALK_POLL_WAIT_SECONDS, poll.serverWaitSeconds)

        server.post("alice", "A new synthetic message")
        server.stored.first { it.id == 1L }.apply {
            message = "Edited synthetic text"
            lastEditTimestamp = NOW
        }
        server.stored += FakeTalkServer.StoredMessage(4, "alice", "Alice", "message_edited", NOW, systemMessage = "message_edited", systemParent = 1)
        server.releasePoll()

        val messages = requireNotNull(controller.state.messages)
        assertEquals(listOf(1L, 2L, 3L), messages.map(TalkMessage::id), "Update entries are not shown.")
        assertEquals("Edited synthetic text", messages.first().message)
        assertEquals(4L, controller.state.timeline?.newestKnownId)
        assertEquals("4", server.requests.last().queryParameters.toMap()["lastKnownMessageId"])
        assertEquals(1, server.pollsWaiting)
    }

    @Test
    fun `an idle long poll restarts after a short pause and keeps the cursor`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        controller.setLiveUpdates(true)
        server.releasePoll()
        assertEquals(listOf(TALK_POLL_RESTART_DELAY_MILLIS), waits)
        assertEquals(TalkLiveState.Connected, controller.state.live)
        assertEquals("2", server.requests.last().queryParameters.toMap()["lastKnownMessageId"])
    }

    @Test
    fun `offline polls back off and recover while a removed conversation stops polling`() {
        val server = serverWithHistory().apply { pollFailure = IllegalStateException("Synthetic offline") }
        val controller = controller(server).apply { start() }
        controller.setLiveUpdates(true)
        server.releasePoll()
        server.releasePoll()
        assertEquals(listOf(2_000L, 4_000L), waits)
        assertIs<TalkLiveState.Reconnecting>(controller.state.live)

        server.pollFailure = null
        server.post("alice", "Back online")
        server.releasePoll()
        assertEquals(TalkLiveState.Connected, controller.state.live)
        assertEquals(3, controller.state.messages?.size)

        server.pollStatus = 404
        server.releasePoll()
        val stopped = assertIs<TalkLiveState.Stopped>(controller.state.live)
        assertEquals(TalkFailureKind.NotFound, stopped.failure.kind)
        assertEquals(0, server.pollsWaiting, "A removed conversation is not polled again.")
    }

    @Test
    fun `hiding the screen cancels the long poll and a stale answer is dropped`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        controller.setLiveUpdates(true)
        controller.setLiveUpdates(false)
        assertEquals(TalkLiveState.Paused, controller.state.live)
        server.post("alice", "Arrived while hidden")
        server.releasePoll()
        assertEquals(2, controller.state.messages?.size, "The cancelled poll does not write state.")

        controller.setLiveUpdates(true)
        server.releasePoll()
        assertEquals(3, controller.state.messages?.size, "The next visible poll catches up from the cursor.")
    }

    @Test
    fun `a refresh fences an older long poll`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        controller.setLiveUpdates(true)
        val pollsBefore = server.requests.count { it.queryParameters.toMap()["lookIntoFuture"] == "1" }
        controller.refresh()
        server.post("alice", "Arrived after the refresh")
        server.releasePoll()
        assertEquals(2, controller.state.messages?.size, "The replaced poll is ignored.")
        server.releasePoll()
        assertEquals(3, controller.state.messages?.size)
        assertEquals(pollsBefore + 2, server.requests.count { it.queryParameters.toMap()["lookIntoFuture"] == "1" })
    }

    @Test
    fun `sending a reply carries the reference and clears the reply on confirmation`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        val sent = mutableListOf<TalkUnconfirmedSend>()
        controller.onSent = { sent += it }
        controller.reply(requireNotNull(controller.state.timeline?.message(1L)))
        controller.send("  A synthetic answer  ")
        val request = server.writes().single()
        assertEquals("A synthetic answer", request.formFields.toMap()["message"])
        assertEquals("1", request.formFields.toMap()["replyTo"])
        assertEquals(REFERENCE_ID, request.formFields.toMap()["referenceId"])
        assertEquals(TalkSendState.Idle, controller.state.send)
        assertNull(controller.state.unconfirmed)
        assertNull(controller.state.replyTo)
        assertEquals(listOf(TalkUnconfirmedSend("A synthetic answer", REFERENCE_ID, 1L)), sent)
        val created = requireNotNull(controller.state.messages?.last())
        assertEquals(1L, created.parent?.id)
        assertEquals(2L, controller.state.timeline?.newestKnownId, "A write response does not move the poll cursor.")
    }

    @Test
    fun `a lost send response is confirmed by its reference without sending twice`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostAfterApplying }
        val controller = controller(server).apply { start() }
        val sent = mutableListOf<TalkUnconfirmedSend>()
        controller.onSent = { sent += it }
        controller.send("Synthetic message")
        assertEquals(1, server.writes().size)
        assertEquals(TalkSendState.Idle, controller.state.send)
        assertNull(controller.state.unconfirmed)
        assertEquals(1, sent.size)
        assertEquals("Synthetic message", controller.state.messages?.last()?.message)
    }

    @Test
    fun `a send that never arrived requires an explicit resend after checking`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostBeforeApplying }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        assertEquals(TalkSendState.Unconfirmed(checked = true), controller.state.send)
        assertNotNull(controller.state.unconfirmed)

        server.writeFault = WriteFault.None
        controller.send("Synthetic message")
        assertEquals(1, server.writes().size, "No resend without confirmation.")
        controller.send("Synthetic message", confirmedResend = true)
        assertEquals(2, server.writes().size)
        assertEquals(TalkSendState.Idle, controller.state.send)
    }

    @Test
    fun `a refused resend keeps the earlier send unconfirmed`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostBeforeApplying }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        val earlier = assertNotNull(controller.state.unconfirmed)

        server.writeFault = WriteFault.None
        server.writeStatus = 413
        controller.send("Synthetic message", confirmedResend = true)
        assertEquals(2, server.writes().size)
        assertEquals(TalkSendState.Unconfirmed(checked = true), controller.state.send, "The refusal answers only the resend.")
        assertEquals(earlier, controller.state.unconfirmed)
        assertNotNull(controller.state.notice)
        controller.send("Synthetic message")
        assertEquals(2, server.writes().size, "Another send still needs confirmation.")
    }

    @Test
    fun `a text that is too long does not strand an unconfirmed send`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostBeforeApplying }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        server.writeFault = WriteFault.None
        controller.send("x".repeat(controller.state.abilities.capabilities.maxMessageLength + 1), confirmedResend = true)
        assertEquals(1, server.writes().size)
        assertEquals(TalkSendState.Unconfirmed(checked = true), controller.state.send)
        assertNotNull(controller.state.notice)
        controller.send("Shorter synthetic message", confirmedResend = true)
        assertEquals(2, server.writes().size)
        assertEquals(TalkSendState.Idle, controller.state.send)
    }

    @Test
    fun `an unconfirmed send is recorded before it leaves and survives leaving the conversation`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostBeforeApplying }
        val store = MemoryUnconfirmedSends(writes = { server.writes().size })
        val first = controller(server, store = store).apply { start() }
        first.send("Synthetic message")
        val recorded = assertNotNull(store.value, "The attempt is stored, not only kept by the screen.")
        assertEquals(0, store.writesAtSave.first(), "The record exists before the request is sent.")

        // Another conversation was opened and this one reopened: a new controller, the same store.
        server.writeFault = WriteFault.None
        val reopened = controller(server, store = store).apply { start() }
        assertEquals(recorded, reopened.state.unconfirmed)
        reopened.send("Synthetic message")
        assertEquals(1, server.writes().size, "Sending again still needs confirmation.")
        reopened.send("Synthetic message", confirmedResend = true)
        assertEquals(2, server.writes().size)
        assertNull(store.value, "A confirmed send resolves the record.")
    }

    @Test
    fun `nothing is sent when the attempt cannot be recorded or an earlier record cannot be read`() {
        val server = serverWithHistory()
        val unwritable = controller(server, store = MemoryUnconfirmedSends(failSave = true)).apply { start() }
        unwritable.send("Synthetic message")
        assertEquals(0, server.writes().size)
        assertNull(unwritable.state.unconfirmed)
        assertNotNull(unwritable.state.notice)

        val unreadable = controller(server, store = MemoryUnconfirmedSends(failLoad = true)).apply { start() }
        unreadable.send("Synthetic message")
        assertEquals(0, server.writes().size)
        assertNotNull(unreadable.state.notice)
    }

    private class MemoryUnconfirmedSends(
        private val failSave: Boolean = false,
        private val failLoad: Boolean = false,
        private val writes: () -> Int = { 0 },
    ) : TalkUnconfirmedSendStore {
        var value: TalkUnconfirmedSend? = null

        /** How many writes the server had received each time an attempt was stored. */
        val writesAtSave = mutableListOf<Int>()

        override suspend fun load(): TalkUnconfirmedSend? {
            check(!failLoad) { "Synthetic storage is unreadable." }
            return value
        }

        override suspend fun save(attempt: TalkUnconfirmedSend) {
            check(!failSave) { "Synthetic storage is full." }
            writesAtSave += writes()
            value = attempt
        }

        override suspend fun clear() {
            value = null
        }
    }

    @Test
    fun `an unchecked unconfirmed send cannot be resent`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.LostBeforeApplying; historyStatus = 503 }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        assertEquals(TalkSendState.Unconfirmed(checked = false), controller.state.send)
        controller.send("Synthetic message", confirmedResend = true)
        assertEquals(1, server.writes().size)
    }

    @Test
    fun `a gateway timeout after storing the message is not reported as unsent`() {
        val server = serverWithHistory().apply { writeFault = WriteFault.GatewayTimeoutAfterApplying }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        assertEquals(TalkSendState.Idle, controller.state.send, "The reference ID found the stored message.")
        assertEquals(1, server.writes().size)
    }

    @Test
    fun `a definitive rejection keeps the draft and allows a corrected send`() {
        val server = serverWithHistory().apply { writeStatus = 413 }
        val controller = controller(server).apply { start() }
        controller.send("Synthetic message")
        val rejected = assertIs<TalkSendState.Rejected>(controller.state.send)
        assertEquals(TalkFailureKind.TooLong, rejected.failure.kind)
        assertNull(controller.state.unconfirmed)
        server.writeStatus = null
        controller.send("Shorter synthetic message")
        assertEquals(TalkSendState.Idle, controller.state.send)
    }

    @Test
    fun `a restored unconfirmed send is checked against the conversation on start`() {
        val server = serverWithHistory()
        server.stored += FakeTalkServer.StoredMessage(3, SELF, "Self", "Saved draft", NOW, referenceId = REFERENCE_ID)
        val sent = mutableListOf<TalkUnconfirmedSend>()
        val restored = TalkUnconfirmedSend("Saved draft", REFERENCE_ID, null)
        val controller = controller(server, unconfirmed = restored)
        controller.onSent = { sent += it }
        assertEquals(TalkSendState.Unconfirmed(checked = false), controller.state.send)
        controller.start()
        assertEquals(TalkSendState.Idle, controller.state.send)
        assertEquals(listOf(restored), sent)
        assertTrue(server.writes().isEmpty())
    }

    @Test
    fun `cancelling a send keeps it unconfirmed for recovery`() {
        val server = serverWithHistory()
        val blockedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = TalkChatController(
            scope = blockedScope,
            token = TOKEN,
            ownActorId = SELF,
            execute = { request ->
                if (request.isRead) server.execute(request) else kotlinx.coroutines.awaitCancellation()
            },
            parseDispatcher = Dispatchers.Unconfined,
            nowSeconds = { NOW },
            newReferenceId = { REFERENCE_ID },
        )
        controller.start()
        controller.send("Synthetic message")
        assertEquals(TalkSendState.Sending, controller.state.send)
        blockedScope.cancel()
        assertEquals(TalkUnconfirmedSend("Synthetic message", REFERENCE_ID, null), controller.state.unconfirmed)
    }

    @Test
    fun `editing applies the server copy and an ambiguous edit is verified`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        val own = requireNotNull(controller.state.timeline?.message(2L))
        assertTrue(controller.canEdit(own))
        controller.startEdit(own)
        controller.saveEdit("Edited by this account")
        assertNull(controller.state.editing)
        assertEquals("Edited by this account", controller.state.timeline?.message(2L)?.message)
        assertNotNull(controller.state.timeline?.message(2L)?.editedAt)

        server.writeFault = WriteFault.LostAfterApplying
        controller.startEdit(requireNotNull(controller.state.timeline?.message(2L)))
        controller.saveEdit("Edited again")
        assertNull(controller.state.editing, "The context read confirmed the edit.")
        assertEquals("Edited again", controller.state.timeline?.message(2L)?.message)
        assertNull(controller.state.notice)

        server.writeFault = WriteFault.LostBeforeApplying
        controller.startEdit(requireNotNull(controller.state.timeline?.message(2L)))
        controller.saveEdit("Never stored")
        assertNotNull(controller.state.editing, "The draft stays open after an edit that was not saved.")
        assertEquals("Edited again", controller.state.timeline?.message(2L)?.message)
        assertNotNull(controller.state.notice)
    }

    @Test
    fun `edit and delete follow the documented time windows and ownership`() {
        val server = FakeTalkServer().apply {
            post(SELF, "Recent", timestamp = NOW - 60)
            post(SELF, "Seven hours old", timestamp = NOW - 7 * 3_600)
            post(SELF, "Two days old", timestamp = NOW - 48 * 3_600)
            post("alice", "Someone else", timestamp = NOW - 60)
        }
        val controller = controller(server).apply { start() }
        val (recent, sevenHours, twoDays, other) = requireNotNull(controller.state.messages)
        assertTrue(controller.canEdit(recent) && controller.canDelete(recent))
        assertTrue(controller.canEdit(sevenHours))
        assertFalse(controller.canDelete(sevenHours))
        assertFalse(controller.canEdit(twoDays) || controller.canDelete(twoDays))
        assertFalse(controller.canEdit(other) || controller.canDelete(other))

        server.features = server.features + "delete-messages-unlimited"
        val unlimited = controller(server).apply { start() }
        assertTrue(unlimited.canDelete(requireNotNull(unlimited.state.timeline?.message(twoDays.id))))
    }

    @Test
    fun `deleting replaces the text locally and an ambiguous delete is verified`() {
        val server = FakeTalkServer().apply {
            post(SELF, "First private draft")
            post(SELF, "Second private draft")
        }
        val controller = controller(server).apply { start() }
        controller.delete(requireNotNull(controller.state.timeline?.message(1L)))
        val first = requireNotNull(controller.state.timeline?.message(1L))
        assertTrue(first.deleted)
        assertFalse("First private draft" in first.message)

        server.writeFault = WriteFault.LostAfterApplying
        controller.delete(requireNotNull(controller.state.timeline?.message(2L)))
        assertEquals(true, controller.state.timeline?.message(2L)?.deleted)
        assertNull(controller.state.notice)
        assertEquals(2, server.writes().size, "The ambiguous delete is verified, not repeated.")
    }

    @Test
    fun `reactions use the authoritative map and verify a lost response`() {
        val server = serverWithHistory()
        val controller = controller(server).apply { start() }
        val other = requireNotNull(controller.state.timeline?.message(1L))
        controller.toggleReaction(other, "+1")
        assertEquals(listOf(TalkReaction("+1", 1, true)), controller.state.timeline?.message(1L)?.reactions)
        assertTrue(controller.state.pendingMessageIds.isEmpty())

        server.writeFault = WriteFault.LostAfterApplying
        controller.toggleReaction(requireNotNull(controller.state.timeline?.message(1L)), "+1")
        assertEquals(emptyList(), controller.state.timeline?.message(1L)?.reactions)
        assertNull(controller.state.notice)
        val writes = server.writes()
        assertEquals(TalkTransportMethod.DELETE, writes.last().method)
        assertEquals(2, writes.size)

        server.writeFault = WriteFault.LostBeforeApplying
        controller.toggleReaction(requireNotNull(controller.state.timeline?.message(1L)), "+1")
        assertEquals(emptyList(), controller.state.timeline?.message(1L)?.reactions)
        assertEquals("The reaction was not saved.", controller.state.notice)
    }

    @Test
    fun `older history pages keep the poll cursor and skip update entries`() {
        val server = FakeTalkServer()
        repeat(150) { server.post("alice", "Synthetic $it") }
        val controller = controller(server).apply { start() }
        assertEquals(100, controller.state.messages?.size)
        assertTrue(controller.state.hasMoreHistory)
        controller.loadEarlier()
        assertEquals(150, controller.state.messages?.size)
        assertEquals(150L, controller.state.timeline?.newestKnownId)
        controller.loadEarlier()
        assertFalse(controller.state.hasMoreHistory)
    }

    @Test
    fun `cached messages paint first and a fresh page replaces them`() {
        val server = serverWithHistory()
        var stored: List<TalkMessage>? = null
        val memory = object : TalkChatMemory {
            override fun load(): List<TalkMessage>? = listOf(syntheticMessage(id = 99, text = "Stale cached copy"))
            override fun store(messages: List<TalkMessage>) { stored = messages }
        }
        val controller = controller(server, memory = memory)
        assertEquals(listOf(99L), controller.state.messages?.map(TalkMessage::id))
        controller.start()
        assertEquals(listOf(1L, 2L), controller.state.messages?.map(TalkMessage::id))
        assertEquals(listOf(1L, 2L), stored?.map(TalkMessage::id))
    }
}

internal fun syntheticMessage(
    id: Long,
    text: String = "Synthetic $id",
    actorId: String = "alice",
    timestamp: Long = NOW,
    reactions: List<TalkReaction> = emptyList(),
) = TalkMessage(
    id = id,
    actorDisplayName = actorId,
    actorId = actorId,
    actorType = "users",
    message = text,
    timestamp = timestamp,
    messageType = TalkMessageType.Comment,
    systemMessage = TalkSystemMessageType.Unknown,
    systemMessageName = null,
    parameters = emptyMap(),
    content = TalkMessageContent.Text(text, markdown = false),
    isReplyable = true,
    reactions = reactions,
)
