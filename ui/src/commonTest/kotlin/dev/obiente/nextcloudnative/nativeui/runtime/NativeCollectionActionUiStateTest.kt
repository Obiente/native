package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeCollectionActionUiStateTest {
    @Test
    fun `selection stays bounded safe and ordered like the active collection`() {
        val available = listOf("third", "first", "second")

        val selected = toggleNativeCollectionSelection(
            selectedRecordIds = listOf("missing", "first", "first"),
            recordId = "third",
            availableRecordIds = available,
            maximumSelectionSize = 2,
        )
        val fullSelection = toggleNativeCollectionSelection(
            selectedRecordIds = selected,
            recordId = "second",
            availableRecordIds = available,
            maximumSelectionSize = 2,
        )

        assertEquals(listOf("third", "first"), selected)
        assertEquals(selected, fullSelection)
    }

    @Test
    fun `selection can remove a selected record while at the bound`() {
        assertEquals(
            listOf("second"),
            toggleNativeCollectionSelection(
                selectedRecordIds = listOf("first", "second"),
                recordId = "first",
                availableRecordIds = listOf("first", "second", "third"),
                maximumSelectionSize = 2,
            ),
        )
    }

    @Test
    fun `unknown records and invalid bounds cannot enter selection`() {
        assertEquals(
            listOf("first"),
            toggleNativeCollectionSelection(
                selectedRecordIds = listOf("first"),
                recordId = "missing",
                availableRecordIds = listOf("first"),
                maximumSelectionSize = 2,
            ),
        )
        assertEquals(
            listOf("first"),
            toggleNativeCollectionSelection(
                selectedRecordIds = listOf("first"),
                recordId = "first",
                availableRecordIds = listOf("first"),
                maximumSelectionSize = 0,
            ),
        )
    }

    @Test
    fun `reorder movement is explicit bounded and identity preserving`() {
        assertEquals(
            listOf("second", "first", "third"),
            moveNativeCollectionRecord(
                orderedRecordIds = listOf("first", "second", "third"),
                recordId = "second",
                offset = -1,
            ),
        )
        assertEquals(
            listOf("first", "third", "second"),
            moveNativeCollectionRecord(
                orderedRecordIds = listOf("first", "second", "third"),
                recordId = "second",
                offset = 1,
            ),
        )
        assertEquals(
            listOf("first", "second"),
            moveNativeCollectionRecord(
                orderedRecordIds = listOf("first", "second"),
                recordId = "first",
                offset = -1,
            ),
        )
    }

    @Test
    fun `batch fields map only planner metadata to generic field controls`() {
        assertEquals(
            dev.obiente.nextcloudnative.nativeui.model.FieldSpec(
                id = "targetListIds",
                label = "Target List Ids",
                kind = FieldKind.integer,
                required = true,
                readOnly = false,
                format = DYNAMIC_INTEGER_ARRAY_FORMAT,
                enumValues = null,
            ),
            NativeCollectionBatchInputField(
                id = "targetListIds",
                kind = NativeCollectionBatchInputKind.IntegerArray,
                required = true,
                nullable = false,
                enumValues = null,
            ).toNativeCollectionFieldSpec(),
        )
        assertEquals(
            DYNAMIC_STRING_ARRAY_FORMAT,
            NativeCollectionBatchInputField(
                id = "roleIds",
                kind = NativeCollectionBatchInputKind.StringArray,
                required = false,
                nullable = true,
                enumValues = null,
            ).toNativeCollectionFieldSpec().format,
        )
    }

    @Test
    fun `batch request values encode line based arrays and omit blank optional values`() {
        val fields = listOf(
            NativeCollectionBatchInputField(
                id = "ids",
                kind = NativeCollectionBatchInputKind.IntegerArray,
                required = true,
                nullable = false,
                enumValues = null,
            ),
            NativeCollectionBatchInputField(
                id = "roles",
                kind = NativeCollectionBatchInputKind.StringArray,
                required = true,
                nullable = false,
                enumValues = null,
            ),
            NativeCollectionBatchInputField(
                id = "note",
                kind = NativeCollectionBatchInputKind.String,
                required = false,
                nullable = true,
                enumValues = null,
            ),
        )

        assertEquals(
            mapOf(
                "ids" to "[2,7]",
                "roles" to "[\"editor\",\"viewer\"]",
            ),
            nativeCollectionBatchRequestValues(
                fields = fields,
                draft = mapOf(
                    "ids" to "2\n7",
                    "roles" to " editor \nviewer",
                    "note" to " ",
                ),
            ),
        )
    }

    @Test
    fun `verified relationship arrays remain picker encoded`() {
        assertEquals(
            mapOf("roleIds" to "[\"admin\",\"viewer\"]"),
            nativeCollectionBatchRequestValues(
                fields = listOf(
                    NativeCollectionBatchInputField(
                        id = "roleIds",
                        kind = NativeCollectionBatchInputKind.StringArray,
                        required = true,
                        nullable = false,
                        enumValues = null,
                        relatedResourceId = "roles",
                    ),
                ),
                draft = mapOf("roleIds" to "[\"admin\",\"viewer\"]"),
            ),
        )
    }

    @Test
    fun `boolean batch fields receive an explicit false draft`() {
        assertEquals(
            mapOf("enabled" to "false"),
            initialNativeCollectionBatchDraft(
                listOf(
                    NativeCollectionBatchInputField(
                        id = "enabled",
                        kind = NativeCollectionBatchInputKind.Boolean,
                        required = true,
                        nullable = false,
                        enumValues = null,
                    ),
                    NativeCollectionBatchInputField(
                        id = "title",
                        kind = NativeCollectionBatchInputKind.String,
                        required = true,
                        nullable = false,
                        enumValues = null,
                    ),
                ),
            ),
        )
    }
}
