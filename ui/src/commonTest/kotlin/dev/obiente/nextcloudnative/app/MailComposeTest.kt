package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailComposeTest {
    private val account = MailAccount(
        id = 3, name = "Ada", email = "ada@example.test", signature = "Ada L.", signatureAboveQuote = false,
        editorMode = MailEditorMode.Plain, draftsMailboxId = 11, sentMailboxId = 12, trashMailboxId = 13,
        archiveMailboxId = null, junkMailboxId = null,
        aliases = listOf(MailAlias(7, "help@example.test", "Support", signature = "The support team", provisioned = false)),
        delegated = false,
    )

    private fun original(
        from: List<MailRecipient> = listOf(MailRecipient("bob@example.test", "Bob")),
        to: List<MailRecipient> = listOf(MailRecipient("ada@example.test"), MailRecipient("carol@example.test")),
        cc: List<MailRecipient> = listOf(MailRecipient("dan@example.test"), MailRecipient("help@example.test")),
        replyTo: List<MailRecipient> = emptyList(),
        subject: String = "Plans",
        html: String? = null,
        text: String? = "Line one\n\nLine two",
    ) = MailMessageDetail(
        summary = MailMessageSummary(
            id = 101, uid = 5, mailboxId = 21, subject = subject, from = from, to = to, cc = cc,
            sentAtEpochSeconds = 1_700_000_000, flags = MailMessageFlags(), tags = emptyList(), previewText = null,
            messageIdHeader = "<m1@example.test>", threadRootId = null,
        ),
        accountId = 3,
        replyTo = replyTo,
        bcc = emptyList(),
        bodyHtml = html,
        bodyText = text,
        attachments = listOf(MailMessageAttachment("2", "plan.pdf", "application/pdf", 100)),
    )

    private fun respond(mode: MailComposeMode, message: MailMessageDetail = original()) =
        mailResponseCompose(mode, message, account, slotId = "a".repeat(24), formatDate = { "2023-11-14 22:13" })

    @Test
    fun `reply threads to the original and goes to the sender`() {
        val reply = respond(MailComposeMode.Reply)
        assertEquals(listOf("bob@example.test"), reply.to.map { it.email })
        assertTrue(reply.cc.isEmpty())
        assertEquals("Re: Plans", reply.subject)
        assertEquals("<m1@example.test>", reply.inReplyToMessageId)
        assertTrue(reply.attachments.isEmpty())
        assertEquals(7L, reply.aliasId, "The alias the message was addressed to is used to answer.")
    }

    @Test
    fun `reply honors Reply-To`() {
        val reply = respond(MailComposeMode.Reply, original(replyTo = listOf(MailRecipient("list@example.test"))))
        assertEquals(listOf("list@example.test"), reply.to.map { it.email })
    }

    @Test
    fun `reply all adds everyone except this account's own addresses`() {
        val reply = respond(MailComposeMode.ReplyAll)
        assertEquals(listOf("bob@example.test", "carol@example.test"), reply.to.map { it.email })
        assertEquals(listOf("dan@example.test"), reply.cc.map { it.email })
    }

    @Test
    fun `replying to my own message answers its recipients`() {
        val sent = original(from = listOf(MailRecipient("ada@example.test")), to = listOf(MailRecipient("bob@example.test")), cc = emptyList())
        assertEquals(listOf("bob@example.test"), respond(MailComposeMode.Reply, sent).to.map { it.email })
    }

    @Test
    fun `subjects are prefixed once`() {
        assertEquals("Re: Plans", "Re: Plans".withSubjectPrefix("Re:", setOf("re")))
        assertEquals("AW: Plans", "AW: Plans".withSubjectPrefix("Re:", setOf("re", "aw")))
        assertEquals("Re:", "".withSubjectPrefix("Re:", setOf("re")))
        assertEquals("Fwd: Re: Plans", respond(MailComposeMode.Forward, original(subject = "Re: Plans")).subject)
    }

    @Test
    fun `forward carries attachments by reference and does not thread`() {
        val forward = respond(MailComposeMode.Forward)
        assertTrue(forward.to.isEmpty())
        assertNull(forward.inReplyToMessageId)
        val attachment = forward.attachments.single() as MailComposeAttachment.Forwarded
        assertEquals(21L, attachment.mailboxId)
        assertEquals(5L, attachment.messageUid)
        assertEquals("2", attachment.partId)
        assertTrue(forward.quote!!.header.startsWith("---------- Forwarded message ----------"))
        assertTrue("From: Bob <bob@example.test>" in forward.quote!!.header)
    }

    @Test
    fun `plain reply quotes each line and puts the signature where the account wants it`() {
        val reply = respond(MailComposeMode.Reply).copy(body = "Sounds good.")
        val below = composeMailBody(reply, signature = "Ada L.", signatureAboveQuote = false)
        assertNull(below.html)
        assertEquals(
            "Sounds good.\n\nOn 2023-11-14 22:13, Bob wrote:\n> Line one\n>\n> Line two\n\n-- \nAda L.",
            below.plain,
        )
        val above = composeMailBody(reply, signature = "Ada L.", signatureAboveQuote = true)
        assertTrue(above.plain.indexOf("-- \nAda L.") < above.plain.indexOf("wrote:"))
        val bare = composeMailBody(reply.copy(includeQuote = false, includeSignature = false), "Ada L.", false)
        assertEquals("Sounds good.", bare.plain)
    }

    @Test
    fun `rich replies quote the sanitized original in a blockquote`() {
        val reply = respond(MailComposeMode.Reply, original(html = "<p>Hi <script>alert(1)</script><b>there</b></p>", text = null))
            .copy(rich = true, body = "<p>Thanks</p>")
        val body = composeMailBody(reply, signature = null, signatureAboveQuote = false)
        val html = assertNotNull(body.html)
        assertTrue("<blockquote type=\"cite\">" in html)
        assertFalse("<script" in html)
        assertTrue("Thanks" in body.plain, "A plain alternative is always produced.")
    }

    @Test
    fun `the outgoing payload uses the alias signature and maps attachments`() {
        val reply = respond(MailComposeMode.Forward).copy(to = listOf(MailRecipient("eve@example.test")), aliasId = 7)
        val payload = reply.toOutgoingPayload(account, sendAtEpochSeconds = 1_800_000_000)
        assertEquals(7L, payload.aliasId)
        assertTrue(payload.bodyPlain.endsWith("-- \nThe support team"))
        assertEquals(MailOutgoingAttachment.FromMessage(21, 5, "2", "plan.pdf"), payload.attachments.single())
        assertEquals(1_800_000_000L, payload.sendAtEpochSeconds)
    }

    @Test
    fun `editing a draft replaces it on send`() {
        val draft = mailEditDraftCompose(original(), account, slotId = "b".repeat(24))
        assertEquals(101L, draft.replacesDraftMessageId)
        assertEquals("Line one\n\nLine two", draft.body)
        assertEquals(MailComposeMode.EditDraft, draft.mode)
    }

    @Test
    fun `problems explain why a draft cannot be sent`() {
        val empty = newMailCompose(account, slotId = "c".repeat(24))
        assertTrue(mailComposeProblems(empty, account).any { "recipient" in it })
        assertTrue(mailComposeProblems(empty.copy(to = listOf(MailRecipient("bob@example.test"))), account).isEmpty())
        assertTrue(mailComposeProblems(empty.copy(to = listOf(MailRecipient("bob@example.test")), aliasId = 99), account).isNotEmpty())
        assertTrue(mailComposeProblems(empty, null).isNotEmpty())
    }

    @Test
    fun `typed recipients become addresses and the rest stays in the field`() {
        val (valid, rest) = parseMailRecipientInput("Bob Example <bob@example.test>, carol@example.test; nope, BOB@example.test")
        assertEquals(listOf("bob@example.test", "carol@example.test"), valid.map { it.email })
        assertEquals("Bob Example", valid.first().label)
        assertEquals(listOf("nope"), rest)
    }

    @Test
    fun `slot identities are random and never derived from content`() {
        val first = newMailComposeSlotId(Random(1))
        assertEquals(24, first.length)
        assertTrue(first != newMailComposeSlotId(Random(2)))
    }

    private class MemoryTextDrafts {
        val records = mutableMapOf<String, TextEditorDraft>()

        fun bind(path: String): TextEditorDraftStore = object : TextEditorDraftStore {
            override suspend fun load(): TextEditorDraft? = records[path]
            override suspend fun save(draft: TextEditorDraft) {
                records[path] = draft
            }
            override suspend fun remove() {
                records.remove(path)
            }
        }
    }

    @Test
    fun `drafts round trip through the scoped store and are listed after a restart`() = runBlocking<Unit> {
        val memory = MemoryTextDrafts()
        val store = TextDraftBackedMailComposeDraftStore(memory::bind)
        val draft = respond(MailComposeMode.Forward).copy(
            to = listOf(MailRecipient("eve@example.test", "Eve")),
            body = "See below",
            attachments = respond(MailComposeMode.Forward).attachments +
                MailComposeAttachment.Uploaded(4, "photo.jpg") + MailComposeAttachment.FromFiles("/Docs/a.pdf", "a.pdf"),
            outboxMessageId = 55,
            storeAttemptSendAt = 1_800_000_000,
        )
        store.save(draft)
        val restarted = TextDraftBackedMailComposeDraftStore(memory::bind)
        assertEquals(listOf(draft), restarted.list())
        restarted.remove(draft.slotId)
        assertTrue(restarted.list().isEmpty())
        assertTrue(memory.records.isEmpty(), "Removing the last draft removes the index too.")
    }

    @Test
    fun `the store is bounded and reports unreadable state instead of replacing it`() = runBlocking<Unit> {
        val memory = MemoryTextDrafts()
        val store = TextDraftBackedMailComposeDraftStore(memory::bind)
        repeat(MAX_MAIL_COMPOSE_DRAFTS) { index ->
            store.save(newMailCompose(account, slotId = index.toString().padStart(24, 'x')).copy(subject = "Draft $index"))
        }
        assertFailsWith<IllegalStateException> { store.save(newMailCompose(account, slotId = "y".repeat(24)).copy(subject = "One more")) }
        memory.records.entries.first { it.key.endsWith("index") }.let { entry ->
            memory.records[entry.key] = entry.value.copy(text = "../../etc")
        }
        assertFailsWith<IllegalStateException> { store.list() }
    }

    @Test
    fun `corrupt or foreign draft records decode to nothing`() {
        assertNull(decodeMailComposeDraft("not json"))
        assertNull(decodeMailComposeDraft("""{"version":2,"slotId":"${"a".repeat(24)}","mode":"New","accountId":3}"""))
        assertNull(decodeMailComposeDraft("""{"version":1,"slotId":"short","mode":"New","accountId":3}"""))
        assertNull(decodeMailComposeDraft("""{"version":1,"slotId":"${"a".repeat(24)}","mode":"New","accountId":3,"to":[{"email":"bad"}]}"""))
        assertNull(decodeMailComposeDraft("""{"version":1,"slotId":"${"a".repeat(24)}","mode":"New","accountId":3,"extra":true}"""))
        assertNotNull(decodeMailComposeDraft("""{"version":1,"slotId":"${"a".repeat(24)}","mode":"New","accountId":3}"""))
    }

    @Test
    fun `folder names nest under their parent and reject the separator`() {
        val parent = MailMailbox(30, 3, "Projects", "/", emptySet(), 0, MailMailboxRights(null), shared = false)
        assertEquals("Projects/2026", mailboxFullName(" 2026 ", parent))
        assertEquals("Top", mailboxFullName("Top", null))
        assertNull(mailboxFullName("a/b", parent))
        assertNull(mailboxFullName("   ", null))
    }

    @Test
    fun `scheduled times are validated before conversion`() {
        assertNull(mailScheduledEpochSeconds("2026-13-01", "08:00"))
        assertNull(mailScheduledEpochSeconds("2026-01-01", "24:00"))
        assertNull(mailScheduledEpochSeconds("tomorrow", "08:00"))
        assertNotNull(mailScheduledEpochSeconds("2030-01-15", "08:30"))
    }
}
