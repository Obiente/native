package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericCollectionSearchPolicyTest {
    @Test
    fun `technical record fields are not exposed through collection search`() {
        val resource = ResourceSpec(
            id = "documents",
            name = "Documents",
            confidence = Confidence.high,
            fields = listOf(
                FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = true),
                FieldSpec("etag", "ETag", FieldKind.string, required = false, readOnly = true),
            ),
        )
        val record = NativeRecord(
            id = "document-1",
            values = mapOf(
                "title" to "Release checklist",
                "etag" to "private-version-token",
            ),
        )

        assertTrue(nativeRecordMatchesCollectionQuery(resource, record, "release checklist"))
        assertFalse(nativeRecordMatchesCollectionQuery(resource, record, "private-version-token"))
    }
}
