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

class MailWorkspaceControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private fun started(server: MailFakeServer = MailFakeServer(), version: String? = "5.12.6"): Pair<MailFakeServer, MailWorkspaceController> {
        val controller = MailWorkspaceController(scope, server.transport()) { version }
        controller.start()
        return server to controller
    }

    private val MailWorkspaceController.items: List<MailMessageSummary>
        get() = assertIs<MailMessagesState.Loaded>(state.messages).items

    @Test
    fun `start opens the inbox of the first account`() {
        val (_, controller) = started()
        val state = controller.state
        assertTrue(state.writesAllowed)
        assertEquals(3L, state.selectedAccountId)
        assertEquals(21L, state.selectedMailboxId)
        assertEquals(listOf(101L, 102L, 103L), controller.items.map { it.id })
        assertEquals(listOf("INBOX", "Sent", "Archive", "Trash", "Projects"), state.orderedMailboxes.map { it.name })
    }

    @Test
    fun `an unconfirmed version keeps every write off`() {
        val (server, controller) = started(version = null)
        assertFalse(controller.state.writesAllowed)
        controller.toggleFlagged(controller.items.first())
        controller.delete(controller.items.first())
        controller.createMailbox("New", null)
        assertTrue(server.requests.none { it.method != NextcloudApiMethod.GET })
        assertTrue(mailMessageActions(controller.items.first(), controller.state, controller) {}.isEmpty())
    }

    @Test
    fun `opening a message loads its body and marks it read once`() {
        val (server, controller) = started()
        controller.open(controller.items.first())
        assertEquals("Body 101", controller.state.opened?.detail?.bodyText)
        assertTrue(controller.items.first().flags.seen)
        assertEquals(1, server.count("PUT /messages/101/flags"))
    }

    @Test
    fun `a slow body for an earlier message never replaces the current one`() {
        val server = MailFakeServer()
        val (_, controller) = started(server)
        val gate = CompletableDeferred<Unit>()
        server.gates["GET /messages/101/body"] = gate
        controller.open(controller.items[0])
        controller.open(controller.items[1])
        gate.complete(Unit)
        assertEquals(102L, controller.state.opened?.summary?.id)
    }

    @Test
    fun `a body still loading does not reopen its message after switching folders`() {
        val server = MailFakeServer()
        val (_, controller) = started(server)
        val gate = CompletableDeferred<Unit>()
        server.gates["GET /messages/101/body"] = gate
        controller.open(controller.items[0])
        controller.selectMailbox(30)
        gate.complete(Unit)
        assertEquals(null, controller.state.opened)
        assertEquals(30L, controller.state.selectedMailboxId)
    }

    @Test
    fun `an unknown move in a folder that was left does not replace the folder on screen`() {
        val server = MailFakeServer().apply { unreachable += "POST /messages/101/move" }
        val (_, controller) = started(server)
        val gate = CompletableDeferred<Unit>()
        server.gates["POST /messages/101/move"] = gate
        controller.move(controller.items.first(), 30)
        controller.selectMailbox(30)
        assertIs<MailMessagesState.Loaded>(controller.state.messages)
        gate.complete(Unit)
        assertIs<MailMessagesState.Loaded>(controller.state.messages)
        assertEquals(30L, controller.state.selectedMailboxId)
    }

    @Test
    fun `a rejected flag change rolls back only that message`() {
        val server = MailFakeServer().apply { forcedStatus["PUT /messages/102/flags"] = 403 }
        val (_, controller) = started(server)
        controller.toggleFlagged(controller.items[1])
        assertFalse(controller.items[1].flags.flagged)
        assertTrue(controller.state.notice?.message.orEmpty().contains("not changed"))
        controller.toggleFlagged(controller.items[0])
        assertTrue(controller.items[0].flags.flagged)
    }

    @Test
    fun `an unknown move reloads the folder instead of retrying`() {
        val server = MailFakeServer().apply { unreachable += "POST /messages/101/move" }
        val (_, controller) = started(server)
        val before = server.count("GET /messages")
        controller.move(controller.items.first(), 30)
        assertEquals(1, server.count("POST /messages/101/move"))
        assertEquals(before + 1, server.count("GET /messages"))
    }

    @Test
    fun `a confirmed delete removes the message and closes it`() {
        val (server, controller) = started()
        controller.open(controller.items.first())
        controller.delete(controller.items.first())
        assertEquals(listOf(102L, 103L), controller.items.map { it.id })
        assertNull(controller.state.opened)
        assertEquals("Moved to Trash.", controller.state.notice?.message)
        assertEquals(1, server.count("DELETE /messages/101"))
    }

    @Test
    fun `archive moves to the account's archive folder`() {
        val (server, controller) = started()
        controller.archive(controller.items.first())
        val move = server.requests.single { it.route() == "POST /messages/101/move" }
        assertEquals("""{"destFolderId":14}""", move.body?.decodeToString())
    }

    @Test
    fun `folder changes are verified by listing the folders again`() {
        val server = MailFakeServer()
        val (_, controller) = started(server)
        server.mailboxesBody = MailFakeServer.MAILBOXES.replace("]}", """,{"databaseId":31,"accountId":3,"name":"Projects/2026"}]}""")
        controller.createMailbox("2026", controller.state.mailbox(30))
        assertEquals("""{"accountId":3,"name":"Projects/2026"}""", server.requests.single { it.route() == "POST /mailboxes" }.body?.decodeToString())
        assertEquals("Folder created.", controller.state.notice?.message)
        server.unreachable += "DELETE /mailboxes/31"
        controller.deleteMailbox(controller.state.mailbox(31)!!)
        assertEquals("The folder was not deleted.", controller.state.notice?.message, "The re-read still lists the folder.")
    }

    @Test
    fun `special folders cannot be renamed or deleted`() {
        val (server, controller) = started()
        controller.deleteMailbox(controller.state.mailbox(21)!!)
        assertTrue(server.requests.none { it.method == NextcloudApiMethod.DELETE })
    }

    @Test
    fun `paging continues from the oldest message and stops when nothing new arrives`() {
        val server = MailFakeServer().apply {
            messagesBody = { query ->
                when (query["cursor"]) {
                    null -> MailFakeServer.messages(1L..40L)
                    else -> MailFakeServer.messages(1L..2L)
                }
            }
        }
        val (_, controller) = started(server)
        controller.loadMore()
        val loaded = assertIs<MailMessagesState.Loaded>(controller.state.messages)
        assertEquals(40, loaded.items.size)
        assertTrue(loaded.endReached)
        assertEquals((1_700_000_001L).toString(), server.requests.last { it.route() == "GET /messages" }.queryParameters["cursor"])
    }

    @Test
    fun `tags are created, remembered, and applied`() {
        val (server, controller) = started()
        controller.createTagAndApply(controller.items.first(), "Custom", "#0082C9")
        assertEquals(listOf("\$custom"), controller.items.first().tags.map { it.imapLabel })
        assertTrue(controller.state.knownTags.any { it.imapLabel == "\$custom" })
        assertEquals(1, server.count("PUT /messages/101/tags/%24custom"))
    }

    @Test
    fun `an unreachable account list is shown as a typed failure`() {
        val server = MailFakeServer().apply { unreachable += "GET /accounts" }
        val (_, controller) = started(server)
        assertEquals(MailFailureKind.Offline, controller.state.accountsFailure?.kind)
        assertNull(controller.state.accounts)
    }
}
