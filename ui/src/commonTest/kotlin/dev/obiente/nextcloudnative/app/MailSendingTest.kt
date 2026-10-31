package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSendingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private val account = MailAccount(
        id = 3, name = "Ada", email = "ada@example.test", signature = null, signatureAboveQuote = false,
        editorMode = MailEditorMode.Plain, draftsMailboxId = null, sentMailboxId = 12, trashMailboxId = 13,
        archiveMailboxId = null, junkMailboxId = null, aliases = emptyList(), delegated = false,
    )

    private fun payload(sendAt: Long = 1_800_000_000, attachments: List<MailOutgoingAttachment> = emptyList()) = MailOutgoingPayload(
        accountId = 3, aliasId = null, subject = "Hello", bodyPlain = "Hi", bodyHtml = null,
        to = listOf(MailRecipient("bob@example.test")), cc = emptyList(), bcc = emptyList(),
        attachments = attachments, inReplyToMessageId = null, sendAtEpochSeconds = sendAt, replacesDraftMessageId = null,
    )

    @Test
    fun `a confirmed store reports attachments the server dropped`() = runBlocking<Unit> {
        val server = MailFakeServer().apply { storedAttachmentLimit = 1 }
        val result = MailSender(server.transport()).store(
            payload(attachments = listOf(MailOutgoingAttachment.Local(1), MailOutgoingAttachment.Cloud("/missing.pdf"))),
        )
        val stored = assertIs<MailStoreResult.Stored>(result)
        assertEquals(1, stored.missingAttachments)
    }

    @Test
    fun `a lost store response is verified through the Outbox instead of retried`() = runBlocking<Unit> {
        val server = MailFakeServer().apply { lostResponses += "POST /outbox" }
        val result = MailSender(server.transport()).store(payload())
        assertIs<MailStoreResult.Stored>(result)
        assertEquals(1, server.count("POST /outbox"), "The store is never repeated.")
        assertEquals(1, server.count("GET /outbox"))
    }

    @Test
    fun `an unreached store is confirmed absent, and an unverifiable one stays unknown`() = runBlocking<Unit> {
        val absent = MailFakeServer().apply { unreachable += "POST /outbox" }
        assertEquals(MailStoreResult.NotStored, MailSender(absent.transport()).store(payload()))
        val unknown = MailFakeServer().apply {
            unreachable += "POST /outbox"
            unreachable += "GET /outbox"
        }
        assertEquals(MailStoreResult.Unknown, MailSender(unknown.transport()).store(payload()))
    }

    @Test
    fun `two identical Outbox messages are never guessed between`() = runBlocking<Unit> {
        val server = MailFakeServer()
        val sender = MailSender(server.transport())
        assertIs<MailStoreResult.Stored>(sender.store(payload()))
        assertIs<MailStoreResult.Stored>(sender.store(payload()))
        server.lostResponses += "POST /outbox"
        assertEquals(MailStoreResult.Unknown, sender.store(payload()))
    }

    @Test
    fun `a rejected store is definite`() = runBlocking<Unit> {
        val server = MailFakeServer().apply { forcedStatus["POST /outbox"] = 400 }
        val result = assertIs<MailStoreResult.Rejected>(MailSender(server.transport()).store(payload()))
        assertEquals(MailFailureKind.Rejected, result.failure.kind)
        assertEquals(0, server.count("GET /outbox"))
    }

    @Test
    fun `a failed send is read back from the Outbox with its status`() = runBlocking<Unit> {
        val server = MailFakeServer().apply { outboxSendStatus = MailOutboxStatus.SmtpFailed }
        val sender = MailSender(server.transport())
        val stored = assertIs<MailStoreResult.Stored>(sender.store(payload()))
        val failed = assertIs<MailSendResult.Failed>(sender.sendNow(stored.message.id))
        assertEquals(MailOutboxStatus.SmtpFailed, failed.message.status)
    }

    @Test
    fun `a lost send response is confirmed when the message left the Outbox`() = runBlocking<Unit> {
        val server = MailFakeServer()
        val sender = MailSender(server.transport())
        val stored = assertIs<MailStoreResult.Stored>(sender.store(payload()))
        server.lostResponses += "POST /outbox/${stored.message.id}"
        assertEquals(MailSendResult.Sent, sender.sendNow(stored.message.id))
        assertEquals(1, server.count("POST /outbox/${stored.message.id}"))
    }

    @Test
    fun `cancelling a message that already left reports that it may have been sent`() = runBlocking<Unit> {
        val server = MailFakeServer()
        assertEquals(MailCancelResult.AlreadyGone, MailSender(server.transport()).cancel(999))
    }

    @Test
    fun `cancellation is not turned into a failure`() {
        val server = MailFakeServer()
        val transport = MailTransport(
            execute = { throw CancellationException("left the screen") },
            uploadFile = server::upload,
            parseDispatcher = Dispatchers.Unconfined,
        )
        assertFailsWith<CancellationException> { runBlocking { MailSender(transport).store(payload()) } }
    }

    private class MemoryDraftStore : MailComposeDraftStore {
        val saved = mutableMapOf<String, MailComposeDraft>()
        val history = mutableListOf<MailComposeDraft>()
        override suspend fun list() = saved.values.toList()
        override suspend fun save(draft: MailComposeDraft) {
            saved[draft.slotId] = draft
            history += draft
        }
        override suspend fun remove(slotId: String) {
            saved.remove(slotId)
        }
    }

    private class ManualClock(var now: Long = 1_700_000_000) {
        val waits = mutableListOf<CompletableDeferred<Unit>>()
        suspend fun wait(millis: Long) {
            val gate = CompletableDeferred<Unit>()
            waits += gate
            gate.await()
            now += millis / 1_000L
        }
        fun releaseAll() = waits.toList().forEach { it.complete(Unit) }
    }

    private fun composer(
        server: MailFakeServer,
        store: MemoryDraftStore,
        clock: ManualClock,
        initial: MailComposeDraft = newMailCompose(account, slotId = "s".repeat(24)).copy(
            to = listOf(MailRecipient("bob@example.test")),
            subject = "Hello",
            body = "Hi",
        ),
    ) = MailComposerController(
        scope = scope,
        transport = server.transport(),
        store = store,
        account = { id -> account.takeIf { it.id == id } },
        chooseLocalFile = { LocalUploadSelectionResult.Cancelled },
        releaseLocalFile = {},
        nowEpochSeconds = { clock.now },
        initial = initial,
        undoDelay = MailUndoDelay.Medium,
        wait = clock::wait,
    )

    @Test
    fun `send records the attempt before storing, holds for undo, then sends and forgets the draft`() {
        val server = MailFakeServer()
        val store = MemoryDraftStore()
        val clock = ManualClock()
        val composer = composer(server, store, clock)
        composer.send()
        val undoable = assertIs<MailSendPhase.Undoable>(composer.state.phase)
        assertEquals(clock.now + 10, undoable.sendAtEpochSeconds)
        assertTrue(store.history.any { it.storeAttemptSendAt == undoable.sendAtEpochSeconds && it.outboxMessageId == null },
            "The attempt is saved before the store request leaves.")
        assertEquals(0, server.count("POST /outbox/${undoable.outboxId}"))
        clock.releaseAll()
        assertEquals(MailSendPhase.Sent, composer.state.phase)
        assertTrue(store.saved.isEmpty())
        assertTrue(server.outbox.isEmpty())
    }

    @Test
    fun `undo removes the message from the Outbox and returns to editing`() {
        val server = MailFakeServer()
        val store = MemoryDraftStore()
        val composer = composer(server, store, ManualClock())
        composer.send()
        assertIs<MailSendPhase.Undoable>(composer.state.phase)
        composer.undo()
        assertEquals(MailSendPhase.Editing, composer.state.phase)
        assertTrue(server.outbox.isEmpty())
        assertNull(composer.state.draft.outboxMessageId)
        assertNull(composer.state.draft.storeAttemptSendAt)
        assertEquals("Hi", store.saved.values.single().body, "The draft is kept for editing.")
    }

    @Test
    fun `a scheduled message stays in the Outbox and is not sent by the client`() {
        val server = MailFakeServer()
        val store = MemoryDraftStore()
        val clock = ManualClock()
        val composer = composer(server, store, clock)
        composer.send(scheduledAtEpochSeconds = clock.now + 86_400)
        assertIs<MailSendPhase.Scheduled>(composer.state.phase)
        assertEquals(clock.now + 86_400, server.outbox.single().sendAtEpochSeconds)
        assertTrue(server.requests.none { it.method == NextcloudApiMethod.POST && it.relativePath.matches(Regex(".*/outbox/\\d+")) })
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `an unknown store is never repeated by the composer`() {
        val server = MailFakeServer().apply {
            unreachable += "POST /outbox"
            unreachable += "GET /outbox"
        }
        val composer = composer(server, MemoryDraftStore(), ManualClock())
        composer.send()
        assertIs<MailSendPhase.Unknown>(composer.state.phase)
        composer.send()
        assertEquals(1, server.count("POST /outbox"))
    }

    @Test
    fun `after a restart an earlier attempt is adopted from the Outbox instead of stored twice`() {
        val server = MailFakeServer()
        val clock = ManualClock()
        val stored = runBlocking {
            assertIs<MailStoreResult.Stored>(
                MailSender(server.transport()).store(
                    newMailCompose(account, slotId = "s".repeat(24))
                        .copy(to = listOf(MailRecipient("bob@example.test")), subject = "Hello", body = "Hi")
                        .toOutgoingPayload(account, clock.now + 10),
                ),
            )
        }
        val recovered = newMailCompose(account, slotId = "s".repeat(24)).copy(
            to = listOf(MailRecipient("bob@example.test")), subject = "Hello", body = "Hi", storeAttemptSendAt = clock.now + 10,
        )
        val composer = composer(server, MemoryDraftStore(), clock, recovered)
        composer.send()
        val phase = assertIs<MailSendPhase.Undoable>(composer.state.phase)
        assertEquals(stored.message.id, phase.outboxId)
        assertEquals(1, server.count("POST /outbox"))
    }

    @Test
    fun `an earlier attempt whose time passed may have been sent and needs explicit confirmation`() {
        val server = MailFakeServer()
        val clock = ManualClock()
        val recovered = newMailCompose(account, slotId = "s".repeat(24)).copy(
            to = listOf(MailRecipient("bob@example.test")), subject = "Hello", body = "Hi", storeAttemptSendAt = clock.now - 60,
        )
        val composer = composer(server, MemoryDraftStore(), clock, recovered)
        composer.send()
        val unknown = assertIs<MailSendPhase.Unknown>(composer.state.phase)
        assertTrue(unknown.mayAlreadyBeSent)
        assertEquals(0, server.count("POST /outbox"))
        composer.send(acknowledgeMayBeSent = true)
        assertEquals(1, server.count("POST /outbox"))
    }

    @Test
    fun `a dropped attachment holds the message until the user decides`() {
        val server = MailFakeServer().apply { storedAttachmentLimit = 0 }
        val clock = ManualClock()
        val composer = composer(
            server,
            MemoryDraftStore(),
            clock,
            newMailCompose(account, slotId = "s".repeat(24)).copy(
                to = listOf(MailRecipient("bob@example.test")),
                attachments = listOf(MailComposeAttachment.FromFiles("/Docs/a.pdf", "a.pdf")),
            ),
        )
        composer.send()
        val attention = assertIs<MailSendPhase.NeedsAttention>(composer.state.phase)
        assertTrue(attention.canSendAnyway)
        assertEquals(1, server.outbox.size)
        composer.cancelHeld()
        assertEquals(MailSendPhase.Editing, composer.state.phase)
        assertTrue(server.outbox.isEmpty())
    }

    @Test
    fun `a recovered message already in the Outbox is checked, not stored again`() {
        val server = MailFakeServer()
        val composer = composer(
            server,
            MemoryDraftStore(),
            ManualClock(),
            newMailCompose(account, slotId = "s".repeat(24)).copy(to = listOf(MailRecipient("bob@example.test")), outboxMessageId = 777),
        )
        assertEquals(MailSendPhase.Sent, composer.state.phase, "A message that left the Outbox was sent.")
        assertEquals(0, server.count("POST /outbox"))
    }

    @Test
    fun `typed recipients and suggestions update the draft`() {
        val server = MailFakeServer()
        val clock = ManualClock()
        val composer = composer(server, MemoryDraftStore(), clock, newMailCompose(account, slotId = "s".repeat(24)))
        val rest = composer.commitTypedRecipients(MailRecipientField.Cc, "carol@example.test, half")
        assertEquals("half", rest)
        assertEquals(listOf("carol@example.test"), composer.state.draft.cc.map { it.email })
        composer.suggest(MailRecipientField.To, "bo")
        clock.releaseAll()
        assertEquals(listOf("bob@example.test"), composer.state.suggestions.map { it.email })
        composer.addRecipient(MailRecipientField.To, composer.state.suggestions.single().toRecipient())
        assertEquals(listOf("bob@example.test"), composer.state.draft.to.map { it.email })
        assertTrue(composer.state.suggestions.isEmpty())
    }

    @Test
    fun `a device attachment is uploaded with the account identity`() {
        val server = MailFakeServer()
        val file = localUploadFile("abcdefghijklmnop", "photo.jpg", "image/jpeg", 10)
        var released = false
        val composer = MailComposerController(
            scope = scope,
            transport = server.transport(),
            store = null,
            account = { account },
            chooseLocalFile = { LocalUploadSelectionResult.Selected(file) },
            releaseLocalFile = { released = true },
            nowEpochSeconds = { 1_700_000_000 },
            initial = newMailCompose(account, slotId = "s".repeat(24)),
            wait = {},
        )
        composer.attachFromDevice()
        assertEquals(listOf("photo.jpg"), composer.state.draft.attachments.map { it.fileName })
        assertEquals("3", server.uploads.single().textFields.single { it.name == "accountId" }.value)
        assertTrue(released)
    }
}
