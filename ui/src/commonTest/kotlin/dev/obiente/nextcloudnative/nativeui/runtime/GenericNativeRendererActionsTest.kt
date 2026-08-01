package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericNativeRendererActionsTest {
    @Test
    fun `repeatable object fields retain typed controls and schema stable automation identity`() {
        val enumeration = RepeatableObjectInputFieldSpec(
            id = "role",
            label = "Role",
            kind = RepeatableObjectInputScalarKind.Enumeration,
            required = true,
            enumValues = listOf("viewer", "editor"),
        ).toNativeRepeatableObjectFieldSpec()
        val enabled = RepeatableObjectInputFieldSpec(
            id = "enabled",
            label = "Enabled",
            kind = RepeatableObjectInputScalarKind.Boolean,
            required = false,
        ).toNativeRepeatableObjectFieldSpec()
        val quantity = RepeatableObjectInputFieldSpec(
            id = "quantity",
            label = "Quantity",
            kind = RepeatableObjectInputScalarKind.Integer,
            required = true,
        ).toNativeRepeatableObjectFieldSpec()
        val amount = RepeatableObjectInputFieldSpec(
            id = "amount",
            label = "Amount",
            kind = RepeatableObjectInputScalarKind.Decimal,
            required = true,
        ).toNativeRepeatableObjectFieldSpec()
        val note = RepeatableObjectInputFieldSpec(
            id = "note",
            label = "Note",
            kind = RepeatableObjectInputScalarKind.String,
            required = false,
        ).toNativeRepeatableObjectFieldSpec()

        assertEquals(FieldKind.enumeration, enumeration.kind)
        assertEquals(listOf("viewer", "editor"), enumeration.enumValues)
        assertEquals(FieldKind.boolean, enabled.kind)
        assertEquals(FieldKind.integer, quantity.kind)
        assertEquals(FieldKind.decimal, amount.kind)
        assertEquals(FieldKind.string, note.kind)
        assertEquals(
            "assignments row 2 role",
            nativeRepeatableObjectAutomationFieldId(
                fieldId = "assignments",
                rowIndex = 1,
                itemFieldId = "role",
            ),
        )
    }

    @Test
    fun `pending form identity and bounded draft round trip through saveable primitives`() {
        val identity = RestorableNativeRecordFormAction(
            actionId = "items.update",
            resourceId = "items",
            kind = NativeRecordFormActionKind.Edit,
            recordId = "item-7",
        )
        assertEquals(identity, decodeRestorableNativeRecordFormAction(assertNotNull(identity.encode())))

        val draft = linkedMapOf(
            "title" to "Restored title",
            "notes" to "A bounded draft survives process recreation.",
        )
        assertEquals(draft, decodeNativeRecordFormDraft(assertNotNull(encodeNativeRecordFormDraft(draft))))

        val tooManyFields = (0..64).associate { index -> "field$index" to "value" }
        assertNull(encodeNativeRecordFormDraft(tooManyFields))
        assertNull(encodeNativeRecordFormDraft(mapOf("notes" to "x".repeat(64 * 1024 + 1))))
        assertNull(decodeRestorableNativeRecordFormAction("not-json"))
    }

    @Test
    fun `large relation selectors expose only a bounded searchable option window`() {
        val options = (0 until 5_000).map { index ->
            NativeRelationOption(
                value = index.toString(),
                label = "Choice ${index.toString().padStart(4, '0')}",
                supportingText = "Group ${index % 100}",
            )
        }

        val initial = nativeRelationOptionWindow(options, query = "")
        assertEquals(NATIVE_RELATION_OPTION_WINDOW_SIZE, initial.options.size)
        assertTrue(initial.hasMore)

        val exact = nativeRelationOptionWindow(options, query = "Choice 4999")
        assertEquals(listOf(options.last()), exact.options)
        assertFalse(exact.hasMore)

        val broadSearch = nativeRelationOptionWindow(options, query = "Group 17")
        assertTrue(broadSearch.options.size <= NATIVE_RELATION_OPTION_WINDOW_SIZE)
        assertTrue(broadSearch.hasMore)
        assertTrue(broadSearch.options.all { option -> option.supportingText == "Group 17" })
    }

    @Test
    fun `paged relation window retains selected labels within a separate strict bound`() {
        val firstWindow = (1..500).map { index ->
            NativeRelationOption(
                value = "choice-$index",
                label = "Choice $index",
                supportingText = null,
            )
        }
        val retained = retainSelectedNativeRelationOptions(
            retained = emptyList(),
            available = firstWindow,
            selectedValues = listOf("choice-10", "choice-490"),
        )
        val laterWindow = (501..1_000).map { index ->
            NativeRelationOption(
                value = "choice-$index",
                label = "Choice $index",
                supportingText = null,
            )
        }

        assertEquals(
            listOf("choice-10", "choice-490"),
            retainSelectedNativeRelationOptions(
                retained = retained,
                available = laterWindow,
                selectedValues = listOf("choice-10", "choice-490"),
            ).map(NativeRelationOption::value),
        )
        assertTrue(
            retainSelectedNativeRelationOptions(
                retained = firstWindow,
                available = laterWindow,
                selectedValues = (1..100).map { index -> "choice-$index" },
            ).size <= NATIVE_RELATION_RETAINED_SELECTION_LIMIT,
        )
        assertTrue(
            retainSelectedNativeRelationOptions(
                retained = retained,
                available = laterWindow,
                selectedValues = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `unknown command outcomes require reconciliation while explicit rejections may retry`() {
        assertTrue(NativeActionFailureOutcome.Unknown.requiresCommandReconciliation())
        assertFalse(NativeActionFailureOutcome.Rejected.requiresCommandReconciliation())
    }

    @Test
    fun `unknown form outcomes reconcile without retry while explicit rejections remain retryable`() {
        assertTrue(NativeActionFailureOutcome.Unknown.requiresMutationReconciliation())
        assertFalse(NativeActionFailureOutcome.Unknown.allowsGenericFormRetry())

        assertFalse(NativeActionFailureOutcome.Rejected.requiresMutationReconciliation())
        assertTrue(NativeActionFailureOutcome.Rejected.allowsGenericFormRetry())
    }

    @Test
    fun `unknown delete outcomes reconcile without retry while explicit rejections remain retryable`() {
        assertTrue(NativeActionFailureOutcome.Unknown.requiresMutationReconciliation())
        assertFalse(NativeActionFailureOutcome.Unknown.allowsGenericDeleteRetry())

        assertFalse(NativeActionFailureOutcome.Rejected.requiresMutationReconciliation())
        assertTrue(NativeActionFailureOutcome.Rejected.allowsGenericDeleteRetry())
    }

    @Test
    fun `unknown completion outcomes suppress retry until refresh while rejection stays retryable`() {
        val originalRecords = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        val originalKey = NativeAuthoritativeRecordsKey(originalRecords)
        val reconciliation = mutableMapOf<String, NativeAuthoritativeRecordsKey>()

        val refreshRequired = reconciliation.recordNativeCompletionFailure(
            recordId = "task-1",
            authoritativeRecordsKey = originalKey,
            outcome = NativeActionFailureOutcome.Unknown,
        )

        assertTrue(refreshRequired)
        assertTrue(reconciliation.isNativeCompletionReconciling("task-1", originalKey))

        reconciliation["task-2"] = originalKey
        val rejectedRefreshRequired = reconciliation.recordNativeCompletionFailure(
            recordId = "task-2",
            authoritativeRecordsKey = originalKey,
            outcome = NativeActionFailureOutcome.Rejected,
        )
        assertFalse(rejectedRefreshRequired)
        assertFalse(reconciliation.isNativeCompletionReconciling("task-2", originalKey))

        val refreshedKey = NativeAuthoritativeRecordsKey(
            listOf(NativeRecord("task-1", mapOf("completed" to "false"))),
        )
        assertEquals(setOf("task-1"), reconciliation.reconcileNativeCompletionFailures(refreshedKey))
        assertFalse(reconciliation.isNativeCompletionReconciling("task-1", refreshedKey))
    }

    @Test
    fun `authoritative refresh wins over completion override even when records are unchanged`() {
        val originalRecords = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        val unchangedRefreshedRecords = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        assertEquals(originalRecords, unchangedRefreshedRecords)
        assertFalse(originalRecords === unchangedRefreshedRecords)

        val originalKey = NativeAuthoritativeRecordsKey(originalRecords)
        val sameSnapshotKey = NativeAuthoritativeRecordsKey(originalRecords)
        val refreshedKey = NativeAuthoritativeRecordsKey(unchangedRefreshedRecords)
        val override = NativeCompletionOverride(completed = true, sourceRecordsKey = originalKey)

        assertEquals(originalKey, sameSnapshotKey)
        assertTrue(
            effectiveNativeCompletion(
                override = override,
                authoritativeRecordsKey = sameSnapshotKey,
                authoritativeCompleted = false,
            ),
        )
        assertFalse(
            effectiveNativeCompletion(
                override = override,
                authoritativeRecordsKey = refreshedKey,
                authoritativeCompleted = false,
            ),
        )

        val overrides = mutableMapOf("task-1" to override)
        overrides.reconcileNativeCompletionOverrides(refreshedKey)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `only optional scalar relations expose an explicit clear choice`() {
        val optionalScalar = FieldSpec(
            id = "collectionId",
            label = "Collection",
            kind = FieldKind.integer,
            required = false,
            readOnly = false,
        )

        assertEquals(
            NativeRelationOption(value = "", label = "None", supportingText = "Clear selection"),
            nativeScalarRelationClearChoice(optionalScalar),
        )
        assertNull(nativeScalarRelationClearChoice(optionalScalar.copy(required = true)))
        assertNull(
            nativeScalarRelationClearChoice(
                optionalScalar.copy(format = DYNAMIC_INTEGER_ARRAY_FORMAT),
            ),
        )
    }

    @Test
    fun `reversible record commands use concise non destructive labels`() {
        val expectations = mapOf(
            ActionEffect.archive to "Archive",
            ActionEffect.unarchive to "Unarchive",
            ActionEffect.restore to "Restore",
            ActionEffect.copy to "Copy",
        )

        expectations.forEach { (effect, label) ->
            val ui = nativeRecordCommandUi(effect, "Example record")

            assertEquals(label, ui.label)
            assertFalse(ui.destructive)
            assertNull(ui.confirmationTitle)
            assertNull(ui.confirmationMessage)
        }
    }

    @Test
    fun `destructive record commands describe the exact effect and item`() {
        val permanentDelete = nativeRecordCommandUi(
            ActionEffect.permanentDelete,
            "Example record",
        )
        val clear = nativeRecordCommandUi(ActionEffect.clear, "Example record")
        val leave = nativeRecordCommandUi(ActionEffect.leave, "Example record")

        assertEquals("Delete permanently", permanentDelete.label)
        assertEquals("Delete Example record permanently?", permanentDelete.confirmationTitle)
        assertTrue(requireNotNull(permanentDelete.confirmationMessage).contains("cannot be undone"))

        assertEquals("Clear", clear.label)
        assertEquals("Clear Example record?", clear.confirmationTitle)
        assertTrue(requireNotNull(clear.confirmationMessage).contains("cannot be undone"))

        assertEquals("Leave", leave.label)
        assertEquals("Leave Example record?", leave.confirmationTitle)
        assertTrue(requireNotNull(leave.confirmationMessage).contains("lose access"))

        assertTrue(permanentDelete.destructive)
        assertTrue(clear.destructive)
        assertTrue(leave.destructive)
    }
}
