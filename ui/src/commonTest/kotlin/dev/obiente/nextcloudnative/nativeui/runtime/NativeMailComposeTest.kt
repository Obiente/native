package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMailComposeTest {
    private val messages = ResourceSpec(id = "messages", name = "Messages", confidence = Confidence.high, fields = emptyList())

    private fun original(vararg values: Pair<String, String>) =
        assertNotNull(nativeMailOriginal(messages, NativeRecord("1", mapOf("subject" to "Plans", "body" to "Line one\n\nLine two", *values))))

    @Test
    fun `reply goes to reply-to when the sender set one`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Reply,
            original("from" to "Grace <grace@example.test>", "replyTo" to "list@example.test"),
            senderId = "7",
            ownAddresses = setOf("ada@example.test"),
        )
        assertEquals("list@example.test", draft.to)
        assertEquals("Re: Plans", draft.subject)
    }

    @Test
    fun `a display name that reads as recipients never adds or breaks a reply recipient`() {
        val tricky = nativeMailResponseDraft(
            NativeMailComposeMode.Reply,
            original("from" to "grace@example.test"),
            senderId = "7",
            ownAddresses = setOf("ada@example.test"),
        ).copy()
        assertEquals("grace@example.test", tricky.to)
        listOf("extra@example.test, Alice", "Doe, Alice", "Alice; Bob", "A <b>").forEach { label ->
            val form = NativeMailAddress("alice@example.test", label).recipientFieldForm
            val (valid, invalid) = parseNativeMailRecipients(form)
            assertEquals(listOf("alice@example.test"), valid.map { it.email }, label)
            assertTrue(invalid.isEmpty(), label)
        }
        assertEquals("Alice Doe <alice@example.test>", NativeMailAddress("alice@example.test", "Alice Doe").recipientFieldForm)
    }

    @Test
    fun `reply all leaves out the own address and duplicates`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.ReplyAll,
            original(
                "from" to "grace@example.test",
                "to" to "ADA@example.test, lin@example.test",
                "cc" to "lin@example.test, kai@example.test",
            ),
            senderId = null,
            ownAddresses = setOf("ada@example.test"),
        )
        assertEquals("grace@example.test, lin@example.test", draft.to)
        assertEquals("kai@example.test", draft.cc)
    }

    @Test
    fun `a reply to an own message goes to its recipients`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Reply,
            original("from" to "ada@example.test", "to" to "grace@example.test"),
            senderId = null,
            ownAddresses = setOf("ada@example.test"),
        )
        assertEquals("grace@example.test", draft.to)
    }

    @Test
    fun `forward keeps the header unquoted and never threads`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Forward,
            original("from" to "grace@example.test", "messageId" to "<a@example.test>"),
            senderId = null,
            ownAddresses = emptySet(),
        )
        assertEquals("Fwd: Plans", draft.subject)
        assertEquals("", draft.to)
        assertNull(draft.inReplyTo)
        val body = nativeMailComposedBody(draft.copy(body = "FYI"))
        assertTrue(body.startsWith("FYI\n\n---------- Forwarded message ----------"))
        assertTrue(body.endsWith("Line one\n\nLine two"))
    }

    @Test
    fun `reply quotes each line and can leave the quote out`() {
        val draft = nativeMailResponseDraft(NativeMailComposeMode.Reply, original("from" to "grace@example.test"), null, emptySet())
        assertEquals("Thanks\n\ngrace@example.test wrote:\n> Line one\n>\n> Line two", nativeMailComposedBody(draft.copy(body = "Thanks")))
        assertEquals("Thanks", nativeMailComposedBody(draft.copy(body = "Thanks", includeQuote = false)))
    }

    @Test
    fun `subject prefixes are not stacked`() {
        assertEquals("RE: Plans", "RE: Plans".withNativeMailSubjectPrefix("Re:", setOf("re")))
        assertEquals("AW: Plans", "AW: Plans".withNativeMailSubjectPrefix("Re:", setOf("re", "aw")))
        assertEquals("Fwd:", "".withNativeMailSubjectPrefix("Fwd:", setOf("fwd")))
    }

    @Test
    fun `addresses are parsed conservatively`() {
        val (valid, invalid) = parseNativeMailRecipients("Ada <ada@example.test>; bob@example.test, not-an-address, ada@example.test")
        assertEquals(listOf(NativeMailAddress("ada@example.test", "Ada"), NativeMailAddress("bob@example.test")), valid)
        assertEquals(listOf("not-an-address"), invalid)
        assertFalse(isPlausibleNativeMailAddress("a b@example.test"))
        assertFalse(isPlausibleNativeMailAddress("ada@localhost"))
        assertFalse(isPlausibleNativeMailAddress("ada@example..test"))
    }

    @Test
    fun `malformed message ids are not used for threading`() {
        assertNull(original("messageId" to "plain-id").messageIdHeader)
        assertEquals("<x@example.test>", original("messageId" to "<x@example.test>").messageIdHeader)
    }

    @Test
    fun `structured address lists keep labels and drop invalid entries`() {
        val record = NativeRecord(
            "1",
            mapOf("subject" to "S", "body" to "B"),
            structuredValues = mapOf(
                "from" to NativeStructuredValue.ListValue(
                    listOf(
                        NativeStructuredValue.ObjectValue(
                            listOf(
                                NativeStructuredEntry("label", "Label", NativeStructuredValue.Scalar("Grace", NativeStructuredScalarKind.string)),
                                NativeStructuredEntry("email", "Email", NativeStructuredValue.Scalar("grace@example.test", NativeStructuredScalarKind.string)),
                            ),
                        ),
                        NativeStructuredValue.Scalar("broken", NativeStructuredScalarKind.string),
                    ),
                ),
            ),
        )
        assertEquals(listOf(NativeMailAddress("grace@example.test", "Grace")), record.nativeMailAddresses("from"))
    }

    @Test
    fun `a reply carries the original references chain followed by its message id`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Reply,
            original(
                "from" to "grace@example.test",
                "messageId" to "<c@example.test>",
                "references" to "<a@example.test> <b@example.test>",
            ),
            senderId = "7",
            ownAddresses = emptySet(),
        )
        assertEquals("<c@example.test>", draft.inReplyTo)
        assertEquals("<a@example.test> <b@example.test> <c@example.test>", draft.references)
    }

    @Test
    fun `a long references chain keeps the thread root and the newest ids`() {
        val chain = (1..30).map { "<m$it@example.test>" }
        val ids = nativeMailReferences(chain, "<new@example.test>").split(' ')
        assertEquals(20, ids.size)
        assertEquals("<m1@example.test>", ids.first())
        assertEquals("<new@example.test>", ids.last())
        assertEquals("<m13@example.test>", ids[1])
    }

    @Test
    fun `a forward carries no threading headers`() {
        val draft = nativeMailResponseDraft(
            NativeMailComposeMode.Forward,
            original("from" to "grace@example.test", "messageId" to "<c@example.test>", "references" to "<a@example.test>"),
            senderId = "7",
            ownAddresses = emptySet(),
        )
        assertNull(draft.inReplyTo)
        assertNull(draft.references)
    }
}
