package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSummaryFieldPolicyTest {
    private fun field(id: String, kind: FieldKind, label: String = id, format: String? = null, enumLabels: Map<String, String>? = null) =
        FieldSpec(id, label, kind, required = false, readOnly = true, format = format, enumLabels = enumLabels)

    @Test
    fun `timestamp-named integers without a declared unit stay out of summaries`() {
        listOf("lastUpdated", "lockedUntil", "created_at", "lastchanged", "expires", "modified").forEach { id ->
            assertTrue(field(id, FieldKind.integer).isUndeclaredTimestampInteger(), id)
        }
        assertFalse(field("lastUpdated", FieldKind.integer, format = "unix-seconds").isUndeclaredTimestampInteger())
        assertFalse(field("lastUpdated", FieldKind.dateTime).isUndeclaredTimestampInteger())
        listOf("prepTime", "repeat", "format", "seats", "quantity").forEach { id ->
            assertFalse(field(id, FieldKind.integer).isUndeclaredTimestampInteger(), id)
        }
    }

    @Test
    fun `integer codes need declared labels to be readable`() {
        assertTrue(field("state", FieldKind.integer).isUnlabeledIntegerCode())
        assertTrue(field("myaccesslevel", FieldKind.integer).isUnlabeledIntegerCode())
        assertFalse(field("state", FieldKind.integer, enumLabels = mapOf("0" to "Open")).isUnlabeledIntegerCode())
        assertFalse(field("state", FieldKind.string).isUnlabeledIntegerCode())
        assertFalse(field("reorderLevel", FieldKind.integer).isUnlabeledIntegerCode())
    }

    @Test
    fun `form list cards show readable facts instead of raw epochs and flags`() {
        val resource = ResourceSpec(
            id = "forms", name = "Forms", confidence = Confidence.verified,
            fields = listOf(
                field("title", FieldKind.string), field("state", FieldKind.integer), field("lastUpdated", FieldKind.integer, "Last Updated"),
                field("lockedUntil", FieldKind.integer, "Locked Until"), field("partial", FieldKind.boolean, "Partial"),
                field("submissionCount", FieldKind.integer, "Submission Count"), field("hash", FieldKind.string),
            ),
        )
        val record = NativeRecord(
            "1",
            mapOf(
                "title" to "Event feedback", "state" to "0", "lastUpdated" to "1792933817", "lockedUntil" to "1792934717",
                "partial" to "true", "submissionCount" to "2", "hash" to "NaEEgBS5XoEP8Zby",
            ),
        )
        val presentation = nativeRecordPresentation(resource, record)
        assertEquals("Event feedback", presentation.title)
        assertNull(presentation.subtitle, "An opaque state code is not a subtitle.")
        assertEquals(listOf("submissionCount"), nativeRecordFacts(resource, record).map { it.fieldId })
    }

    @Test
    fun `count labels drop API prefixes and totals do not repeat`() {
        assertEquals("Bills", nativeSummaryLabel("Nb Bills"))
        assertEquals("Votes", nativeSummaryLabel("number of votes"))
        assertEquals("Nb", nativeSummaryLabel("Nb"))
        assertEquals("Amount", nativeSummaryLabel("Amount"))
        assertEquals("Total spent", nativeTotalLabel("Total spent"))
        assertEquals("Total amount", nativeTotalLabel("Amount"))
    }

    @Test
    fun `project facts keep counts and drop access codes, trash counts, and change stamps`() {
        val resource = ResourceSpec(
            id = "projects", name = "Projects", confidence = Confidence.verified,
            fields = listOf(
                field("name", FieldKind.string), field("lastchanged", FieldKind.integer, "Lastchanged"),
                field("myaccesslevel", FieldKind.integer, "Myaccesslevel"), field("nb_bills", FieldKind.integer, "Nb Bills"),
                field("nb_trashbin_bills", FieldKind.integer, "Nb Trashbin Bills"),
            ),
        )
        val record = NativeRecord(
            "p",
            mapOf("name" to "Picnic", "lastchanged" to "1790558411", "myaccesslevel" to "4", "nb_bills" to "1", "nb_trashbin_bills" to "0"),
        )
        val facts = nativeRecordFacts(resource, record)
        assertEquals(listOf("nb_bills"), facts.map { it.fieldId })
        assertEquals("Bills", facts.single().label)
    }

    @Test
    fun `negative configuration flags and unlabeled item counts stay out of summaries`() {
        assertTrue(field("deletiondisabled", FieldKind.boolean).isInternalBookkeepingField())
        assertFalse(field("disabledReason", FieldKind.string).isInternalBookkeepingField())
        assertTrue("2 items".isUnlabeledItemCount())
        assertTrue("1 Item".isUnlabeledItemCount())
        assertFalse("2 members".isUnlabeledItemCount())
        assertFalse("items".isUnlabeledItemCount())
        assertTrue(field("categorysort", FieldKind.string).isInternalBookkeepingField())
    }

    @Test
    fun `single-letter codes never become a subtitle`() {
        val resource = ResourceSpec(
            id = "projects", name = "Projects", confidence = Confidence.verified,
            fields = listOf(field("name", FieldKind.string), field("autoexport", FieldKind.string), field("members", FieldKind.objectValue, "Items")),
        )
        val record = NativeRecord("p", mapOf("name" to "Picnic", "autoexport" to "n", "members" to "[1,2]"))
        assertNull(nativeRecordPresentation(resource, record).subtitle)
    }
}
