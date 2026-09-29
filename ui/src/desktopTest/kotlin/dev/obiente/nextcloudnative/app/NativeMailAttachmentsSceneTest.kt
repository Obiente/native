package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeMailAttachmentsSceneTest {
    @Test
    fun sparseBodyShowsAttachmentMetadataAndHonestPreviewLimitation() {
        val resource = ResourceSpec("messages", "Messages", Confidence.verified,
            fields = listOf(FieldSpec("body", "Body", FieldKind.longText, false, true)))
        val schema = NativeAppSchema("1", AppIdentity("mail", "Mail", "5.10.0"),
            Confidence.verified, resources = listOf(resource))
        val record = sparseMailBodyFixture()
        nativeSceneTest(390, 844, content = {
            GenericMailMessageDetail(schema, resource, record,
                NativeMailMessageDetailPresentation("Attachment review", "Synthetic sender", null,
                    null, "Synthetic attachment review", false, 1),
                NativeDatasetContext(), NativeActionExecutor { error("Reading must not send an action") },
                null, null)
        }) {
            assertTrue(has("Attachments"))
            assertTrue(has("Synthetic review.pdf"))
            assertTrue(has("Attachment previews are not available here."))
        }
    }
}
