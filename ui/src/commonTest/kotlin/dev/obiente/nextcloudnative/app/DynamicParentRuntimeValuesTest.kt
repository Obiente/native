package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DynamicParentRuntimeValuesTest {
    @Test
    fun `selected parent supplies exact declared child action path identity`() {
        val values = assertNotNull(selectedDynamicRecordRuntimeValues(
            record = NativeRecord(
                id = "7",
                values = mapOf("id" to "7", "name" to "Home"),
                actionSafeIdentity = true,
            ),
            resourceId = "collections",
            parameterNames = listOf("collectionId"),
        ))

        assertEquals("7", values["id"])
        assertEquals("7", values["collectionId"])
    }

    @Test
    fun `selected parent does not satisfy an unrelated path identity`() {
        val values = assertNotNull(selectedDynamicRecordRuntimeValues(
            record = NativeRecord(
                id = "7",
                values = mapOf("id" to "7"),
                actionSafeIdentity = true,
            ),
            resourceId = "collections",
            parameterNames = listOf("entryId"),
        ))

        assertFalse("entryId" in values)
    }

    @Test
    fun `conflicting restored parent identity fails closed`() {
        val values = selectedDynamicRecordRuntimeValues(
            record = NativeRecord(
                id = "7",
                values = mapOf("id" to "7"),
                bindingContext = mapOf("collectionId" to "11"),
                actionSafeIdentity = true,
            ),
            resourceId = "collections",
            parameterNames = listOf("collectionId"),
        )

        assertNull(values)
    }

    @Test
    fun `ambiguous selected record provenance fails closed`() {
        val values = selectedDynamicRecordRuntimeValues(
            record = NativeRecord(
                id = "7",
                values = mapOf("id" to "7"),
                actionSafeIdentity = true,
                actionBindingProvenanceValid = false,
            ),
            resourceId = "collections",
            parameterNames = listOf("collectionId"),
        )

        assertNull(values)
    }

    @Test
    fun `form binding receives only declared technical runtime values`() {
        val values = dynamicDatasetBindingValues(
            component = NativeComponent.form,
            declaredParameterNames = listOf("collectionId", "sort"),
            selectedPathParameterValues = mapOf(
                "id" to "unrelated-parent-id",
                "sort" to "custom",
            ),
            runtimeValues = mapOf(
                "id" to "7",
                "collectionId" to "7",
                "name" to "Shared home",
                "sort" to "stale",
            ),
        )

        assertEquals(
            mapOf("collectionId" to "7", "sort" to "custom"),
            values,
        )
    }

    @Test
    fun `collection binding keeps its exact navigation context`() {
        val values = dynamicDatasetBindingValues(
            component = NativeComponent.collectionList,
            declaredParameterNames = listOf("collectionId"),
            selectedPathParameterValues = mapOf("collectionId" to "7"),
            runtimeValues = mapOf("collectionId" to "8"),
        )

        assertEquals(mapOf("collectionId" to "7"), values)
    }
}
