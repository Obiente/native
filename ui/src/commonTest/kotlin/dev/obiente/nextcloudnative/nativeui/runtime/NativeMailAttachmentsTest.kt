package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeMailAttachmentsTest {
    @Test
    fun sparseBodyAttachmentMetadataRendersWithoutPromotingObservedIdentity() {
        val record = sparseMailBodyFixture()
        assertEquals(listOf(NativeMailAttachment("Synthetic review.pdf", "application/pdf", "642 B")),
            nativeMailAttachments(record).items)
        assertEquals(null, record.values["id"])
        assertEquals(false, record.actionSafeIdentity)
    }

    @Test
    fun malformedOrUnrelatedStructuresDoNotCreateAttachmentCards() {
        val record = NativeRecord("message", emptyMap(), structuredValues = mapOf(
            "attachments" to NativeStructuredValue.ListValue(listOf(
                attachment("fileName" to "one", "filename" to "two"),
                attachment("id" to "2"), attachment("name" to "bad\nname"),
            )),
            "unrelated" to NativeStructuredValue.ListValue(listOf(attachment("name" to "hidden"))),
        ))
        assertTrue(nativeMailAttachments(record).items.isEmpty())
    }

    @Test
    fun inlineMetadataIsBoundedAndNegativeSizesAreNotShown() {
        val record = NativeRecord("message", emptyMap(), structuredValues = mapOf(
            "inlineAttachments" to NativeStructuredValue.ListValue(List(70) {
                attachment("name" to "Synthetic image.png", "size" to "-1")
            }),
        ))
        val items = nativeMailAttachments(record).items
        assertEquals(64, items.size)
        assertTrue(nativeMailAttachments(record).incomplete)
        assertTrue(items.all { it.size == null })
    }

    private fun attachment(vararg values: Pair<String, String>) = NativeStructuredValue.ObjectValue(
        values.map { (key, value) -> NativeStructuredEntry(key, key,
            NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.string)) },
    )
}

/** Mirrors the sparse Mail body response; observed attachment IDs remain display-only. */
internal fun sparseMailBodyFixture(): NativeRecord = dev.obiente.nextcloudnative.app.parseDynamicRecords(
    dev.obiente.nextcloudnative.nativeui.model.DynamicAction(
        id = "body", label = "Body", resourceId = "messages",
        intent = dev.obiente.nextcloudnative.nativeui.model.ActionIntent.read,
        risk = dev.obiente.nextcloudnative.nativeui.model.ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding(
            dev.obiente.nextcloudnative.nativeui.model.HttpMethod.GET, "/apps/mail/api/messages/2/body"),
        confidence = dev.obiente.nextcloudnative.nativeui.model.Confidence.verified,
    ),
    dev.obiente.nextcloudnative.app.NextcloudApiResponse(200, """
        {"databaseId":2,"body":"Synthetic attachment review","attachments":[
          {"id":"2","messageId":2,"fileName":"Synthetic review.pdf","mime":"application/pdf",
           "size":642,"cid":null,"disposition":"attachment","isImage":false,
           "isCalendarEvent":false,"downloadUrl":"https://fixture.invalid/private"}
        ],"inlineAttachments":[]}
    """.trimIndent().encodeToByteArray(), "application/json", null),
    declaredFieldIds = setOf("body"),
).single()
