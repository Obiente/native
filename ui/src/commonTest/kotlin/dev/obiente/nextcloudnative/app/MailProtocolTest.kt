package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailProtocolTest {
    @Test
    fun `writes are allowed only for a confirmed reviewed version`() {
        assertTrue(mailContractFor("5.0.0").writesAllowed)
        assertTrue(mailContractFor("5.12.6").writesAllowed)
        assertTrue(mailContractFor("5.12.0-rc.2").writesAllowed, "A prerelease counts as its release.")
        assertEquals(MailContractStatus.UnsupportedVersion, mailContractFor("4.3.1").status)
        assertEquals(MailContractStatus.UnsupportedVersion, mailContractFor("5.13.0").status)
        assertEquals(MailContractStatus.UnsupportedVersion, mailContractFor("5.13.0-beta.1").status)
        assertEquals(MailContractStatus.UnverifiedVersion, mailContractFor(null).status)
        assertEquals(MailContractStatus.UnverifiedVersion, mailContractFor("five").status)
        assertEquals(MailContractStatus.UnverifiedVersion, mailContractFor("5.x.1").status)
        assertFalse(mailContractFor(null).writesAllowed)
        assertTrue(mailContractFor("6.0.0").writeBlockedReason.orEmpty().contains("6.0.0"))
    }

    @Test
    fun `accounts parse the fields the client needs and skip invalid entries`() {
        val accounts = parseMailAccounts(
            """
            [
              {"accountId":3,"id":3,"name":"Work","emailAddress":"ada@example.test","imapHost":"imap.example.test",
               "imapUser":"ada","signature":"Ada","signatureAboveQuote":true,"editorMode":"richtext",
               "draftsMailboxId":11,"sentMailboxId":12,"trashMailboxId":13,"archiveMailboxId":null,
               "aliases":[{"id":7,"name":"Support","alias":"help@example.test","signature":null,"provisioned":false},
                          {"id":8,"alias":"not an address"}],
               "isDelegated":false},
              {"accountId":0,"emailAddress":"zero@example.test"},
              {"accountId":4,"emailAddress":"broken"}
            ]
            """.trimIndent().encodeToByteArray(),
        )
        val account = accounts.single()
        assertEquals(3L, account.id)
        assertEquals(MailEditorMode.Rich, account.editorMode)
        assertTrue(account.signatureAboveQuote)
        assertEquals(12L, account.sentMailboxId)
        assertNull(account.archiveMailboxId)
        assertEquals(listOf("help@example.test"), account.aliases.map { it.email })
    }

    @Test
    fun `mailboxes of another account never enter this account's tree`() {
        val mailboxes = parseMailMailboxes(
            """
            {"id":3,"delimiter":"/","mailboxes":[
              {"databaseId":21,"accountId":3,"name":"INBOX","specialUse":["\\Inbox"],"unread":4,"myAcls":"lrswipkxtea"},
              {"databaseId":22,"accountId":3,"name":"Projects/2026","unread":-3,"myAcls":"lr"},
              {"databaseId":23,"accountId":9,"name":"Elsewhere"},
              {"accountId":3,"name":"No identity"}
            ]}
            """.trimIndent().encodeToByteArray(),
            accountId = 3,
        )
        assertEquals(listOf(21L, 22L), mailboxes.map { it.id })
        val inbox = mailboxes[0]
        assertEquals(MailMailboxRole.Inbox, inbox.role(null))
        assertTrue(inbox.rights.canRename)
        val project = mailboxes[1]
        assertEquals("2026", project.displayName)
        assertEquals("Projects", project.parentName)
        assertEquals(0, project.unread, "A negative count is clamped.")
        assertFalse(project.rights.canDelete)
        assertFalse(project.rights.canInsert)
        assertTrue(MailMailboxRights(null).canDelete, "A server without ACL support allows the operation.")
    }

    @Test
    fun `message pages accept tags as an object or an empty array`() {
        val page = parseMailMessagePage(
            """
            [
              {"databaseId":101,"uid":5,"mailboxId":21,"subject":"Hello","dateInt":1700000000,
               "flags":{"seen":false,"flagged":true,"hasAttachments":true,"${'$'}junk":false},
               "tags":{"${'$'}label1":{"id":1,"imapLabel":"${'$'}label1","displayName":"Important","color":"#FF7A66"}},
               "from":[{"label":"Bob","email":"bob@example.test"}],"to":[{"email":"ada@example.test"}],"cc":[],
               "messageId":"<m1@example.test>","previewText":"Hi"},
              {"databaseId":102,"mailboxId":21,"subject":"Second","tags":[],"from":[],"to":[],"cc":[]},
              {"databaseId":103,"mailboxId":99,"subject":"Wrong folder"}
            ]
            """.trimIndent().encodeToByteArray(),
            mailboxId = 21,
            requestedLimit = 3,
        )
        assertEquals(listOf(101L, 102L), page.messages.map { it.id })
        assertFalse(page.endReached, "A full page may have more messages after it.")
        val first = page.messages.first()
        assertEquals(5L, first.uid)
        assertTrue(first.flags.flagged)
        assertFalse(first.flags.seen)
        assertEquals("Important", first.tags.single().displayName)
        assertEquals("<m1@example.test>", first.messageIdHeader)
        assertTrue(page.messages[1].tags.isEmpty())
    }

    @Test
    fun `a message body must belong to the message that was opened`() {
        val body = """
            {"databaseId":101,"mailboxId":21,"accountId":3,"subject":"Hello","hasHtmlBody":true,"body":"<p>Hi</p>",
             "replyTo":[{"email":"list@example.test"}],"attachments":[{"id":"2","fileName":"a.pdf","mime":"application/pdf","size":120}]}
        """.trimIndent().encodeToByteArray()
        val detail = parseMailMessageDetail(body, expectedMessageId = 101)
        assertEquals("<p>Hi</p>", detail.bodyHtml)
        assertNull(detail.bodyText)
        assertEquals("a.pdf", detail.attachments.single().fileName)
        assertEquals("list@example.test", detail.replyTo.single().email)
        assertFailsWith<MailProtocolException> { parseMailMessageDetail(body, expectedMessageId = 102) }
    }

    @Test
    fun `malformed, empty, and unconfirmed responses are protocol errors`() {
        assertFailsWith<MailProtocolException> { parseMailAccounts(ByteArray(0)) }
        assertFailsWith<MailProtocolException> { parseMailAccounts("{not json".encodeToByteArray()) }
        assertFailsWith<MailProtocolException> { parseMailAccounts("{}".encodeToByteArray()) }
        assertFailsWith<MailProtocolException> { parseMailOutbox("""{"status":"fail","data":{"messages":[]}}""".encodeToByteArray()) }
        assertFailsWith<MailProtocolException> { parseMailLocalMessage("""{"status":"success","data":{"id":0}}""".encodeToByteArray()) }
    }

    @Test
    fun `outbox messages carry their status and attachment count`() {
        val messages = parseMailOutbox(
            """
            {"status":"success","data":{"messages":[
              {"id":5,"accountId":3,"subject":"Later","sendAt":1800000000,"to":[{"label":"A","email":"a@example.test"}],
               "cc":[],"bcc":[],"attachments":[{"id":1},{"id":2}],"failed":false,"status":0},
              {"id":6,"accountId":3,"subject":"Broken","status":10,"failed":true}
            ]}}
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals(2, messages[0].attachmentCount)
        assertEquals(MailOutboxStatus.Pending, messages[0].status)
        assertEquals(MailOutboxStatus.SmtpFailed, messages[1].status)
        assertEquals(MailOutboxStatus.Unknown, MailOutboxStatus.fromWire(99))
    }

    @Test
    fun `autocomplete keeps addresses and drops group identifiers`() {
        val matches = parseMailAutocomplete(
            """[{"label":"Ada","email":"ada@example.test","source":"contacts"},
                {"label":"Team","email":"namespace:team","source":"groups"},
                {"label":"ADA","email":"ADA@example.test","source":"collector"}]""".encodeToByteArray(),
        )
        assertEquals(listOf("ada@example.test"), matches.map { it.email })
    }

    @Test
    fun `requests reject unknown identities before a path exists`() {
        assertFailsWith<IllegalArgumentException> { mailMessageBodyRequest(0) }
        assertFailsWith<IllegalArgumentException> { mailMoveMessageRequest(5, -1) }
        assertFailsWith<IllegalArgumentException> { mailSetTagRequest(5, "bad label/../x", applied = true) }
        assertFailsWith<IllegalArgumentException> { mailSetFlagsRequest(5, mapOf("deleted" to true)) }
        assertFailsWith<IllegalArgumentException> { mailCreateMailboxRequest(3, "  ") }
        assertFailsWith<IllegalArgumentException> { mailCreateAliasRequest(3, "not-an-address", "x") }
        assertFailsWith<IllegalArgumentException> { mailMessagesRequest(21, limit = 500) }
    }

    @Test
    fun `requests carry the CSRF replacement header and exact paths`() {
        val tag = mailSetTagRequest(101, "\$label1", applied = false)
        assertEquals(NextcloudApiMethod.DELETE, tag.method)
        assertEquals("/index.php/apps/mail/api/messages/101/tags/%24label1", tag.relativePath)
        assertTrue(tag.ocsApiRequest)
        val flags = mailSetFlagsRequest(101, mapOf("seen" to true))
        assertEquals("""{"flags":{"seen":true}}""", flags.body?.decodeToString())
        val page = mailMessagesRequest(21, cursorEpochSeconds = 1700000000, filter = "  invoice ")
        assertEquals(mapOf("mailboxId" to "21", "limit" to "40", "view" to "singleton", "cursor" to "1700000000", "filter" to "invoice"), page.queryParameters)
        val upload = mailAttachmentUploadRequest(3, localUploadFile("abcdefghijklmnop", "report.pdf", "application/pdf", 10))
        assertEquals("attachment", upload.fileFieldName)
        assertEquals("/index.php/apps/mail/api/attachments", upload.relativePath)
    }

    @Test
    fun `outgoing payload maps every attachment kind to its reviewed type`() {
        val request = mailCreateOutboxRequest(
            MailOutgoingPayload(
                accountId = 3,
                aliasId = 7,
                subject = "Hi",
                bodyPlain = "Hello",
                bodyHtml = null,
                to = listOf(MailRecipient("bob@example.test", "Bob")),
                cc = emptyList(),
                bcc = listOf(MailRecipient("carol@example.test")),
                attachments = listOf(
                    MailOutgoingAttachment.Local(4),
                    MailOutgoingAttachment.Cloud("/Documents/a.pdf"),
                    MailOutgoingAttachment.FromMessage(21, 5, "2", "b.png"),
                ),
                inReplyToMessageId = "<m1@example.test>",
                sendAtEpochSeconds = 1800000000,
                replacesDraftMessageId = null,
            ),
        )
        val json = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        assertEquals("false", json["isHtml"]!!.jsonPrimitive.content)
        assertEquals("<m1@example.test>", json["inReplyToMessageId"]!!.jsonPrimitive.content)
        assertEquals("1800000000", json["sendAt"]!!.jsonPrimitive.content)
        val attachments = json["attachments"] as JsonArray
        assertEquals(listOf("local", "cloud", "message-attachment"), attachments.map { (it as JsonObject)["type"]!!.jsonPrimitive.content })
        assertEquals("carol@example.test", (json["bcc"] as JsonArray).single().jsonObject["label"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { mailCreateOutboxRequest(
            MailOutgoingPayload(3, null, "x", "x", null, emptyList(), emptyList(), emptyList(),
                listOf(MailOutgoingAttachment.Cloud("/../etc")), null, null, null),
        ) }
    }

    @Test
    fun `failure statuses map to typed kinds`() {
        assertNull(mailFailureForStatus(204))
        assertEquals(MailFailureKind.Rejected, mailFailureForStatus(400)?.kind)
        assertEquals(MailFailureKind.Forbidden, mailFailureForStatus(403)?.kind)
        assertEquals(MailFailureKind.Conflict, mailFailureForStatus(428)?.kind)
        assertEquals(MailFailureKind.Throttled, mailFailureForStatus(429)?.kind)
    }

    @Test
    fun `address check accepts ordinary addresses and rejects malformed ones`() {
        assertTrue(isPlausibleMailAddress("ada.lovelace+news@mail.example.test"))
        listOf("ada", "@example.test", "ada@", "ada@example", "a b@example.test", "ada@@example.test", "ada@-x.test", "a..b@example.test")
            .forEach { assertFalse(isPlausibleMailAddress(it), it) }
    }
}
