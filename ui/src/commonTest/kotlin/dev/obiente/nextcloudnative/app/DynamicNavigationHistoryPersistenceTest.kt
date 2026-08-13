package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredScalarKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicNavigationHistoryPersistenceTest {
    @Test
    fun `restored root view marks automatic landing as already consumed`() {
        assertFalse(DynamicAppNavigationState().hasPersistedDynamicLocation())
        assertTrue(
            DynamicAppNavigationState(selectedViewId = "team.list")
                .hasPersistedDynamicLocation(),
        )
        assertTrue(
            DynamicAppNavigationState(
                history = listOf(
                    SavedDynamicNavigationSnapshot(
                        viewId = "chores.list",
                        resourceId = "chores",
                    ),
                ),
            ).hasPersistedDynamicLocation(),
        )
    }

    @Test
    fun `saved history is bounded and excludes complete record payloads`() {
        val largePayload = "private-record-payload-".repeat(2_000)
        val history = (0 until MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY + 7).map { index ->
            DynamicNavigationSnapshot(
                viewId = "view-$index",
                resourceId = "resource-$index",
                record = NativeRecord(
                    id = "record-$index",
                    values = mapOf("description" to largePayload),
                    displayValues = mapOf("rendered" to largePayload),
                    ephemeralFields = listOf(
                        FieldSpec(
                            id = "ephemeral",
                            label = largePayload,
                            kind = FieldKind.string,
                            required = false,
                            readOnly = true,
                        ),
                    ),
                    structuredValues = mapOf(
                        "nested" to NativeStructuredValue.Scalar(
                            value = largePayload,
                            kind = NativeStructuredScalarKind.string,
                        ),
                    ),
                    bindingContext = mapOf("completeBinding" to largePayload),
                ),
                recordResourceId = "resource-$index",
                pathParameterValues = mapOf("parentId" to "parent-$index"),
            )
        }

        val saved = saveDynamicNavigationHistory(history)
        val encoded = Json.encodeToString(saved)

        assertEquals(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY, saved.size)
        assertEquals("view-7", saved.first().viewId)
        assertEquals("view-22", saved.last().viewId)
        assertFalse(encoded.contains("private-record-payload"))
        assertFalse(encoded.contains("completeBinding"))
        assertTrue(encoded.length < 16_000)
    }

    @Test
    fun `restored history keeps navigation identity but cannot authorize writes`() {
        val saved = listOf(
            SavedDynamicNavigationSnapshot(
                viewId = "items.detail",
                resourceId = "items",
                recordId = "item-9",
                recordResourceId = "items",
                pathParameterValues = mapOf("collectionId" to "collection-4"),
            ),
        )

        val restored = restoreDynamicNavigationHistory(saved).single()

        assertEquals("items.detail", restored.viewId)
        assertEquals("items", restored.resourceId)
        assertEquals("item-9", restored.record?.id)
        assertFalse(requireNotNull(restored.record).actionSafeIdentity)
        assertTrue(restored.record.values.isEmpty())
        assertTrue(restored.record.bindingContext.isEmpty())
        assertEquals(mapOf("collectionId" to "collection-4"), restored.pathParameterValues)
    }

    @Test
    fun `unsafe or oversized saved locations are omitted instead of partially truncated`() {
        val valid = DynamicNavigationSnapshot(
            viewId = "items.list",
            resourceId = "items",
            record = null,
            recordResourceId = null,
            pathParameterValues = emptyMap(),
        )
        val oversizedContext = valid.copy(
            viewId = "items.children",
            pathParameterValues = (0..8).associate { index -> "parent$index" to index.toString() },
        )
        val oversizedView = valid.copy(viewId = "v".repeat(129))

        assertEquals(
            listOf("items.list"),
            saveDynamicNavigationHistory(listOf(oversizedContext, oversizedView, valid))
                .map(SavedDynamicNavigationSnapshot::viewId),
        )
    }

    @Test
    fun `restored history is bounded and rejects oversized serialized context`() {
        val history = (0 until MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY + 4).map { index ->
            SavedDynamicNavigationSnapshot(
                viewId = "view-$index",
                resourceId = "items",
            )
        } + SavedDynamicNavigationSnapshot(
            viewId = "items.children",
            resourceId = "items",
            pathParameterValues = mapOf("parentId" to "x".repeat(257)),
        )

        val restored = restoreDynamicNavigationHistory(history)

        assertEquals(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY - 1, restored.size)
        assertEquals("view-5", restored.first().viewId)
        assertEquals("view-19", restored.last().viewId)
    }

    @Test
    fun `saved dynamic app state retains bounded back history without record values`() {
        val history = (0 until MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY + 3).map { index ->
            SavedDynamicNavigationSnapshot(
                viewId = "view-$index",
                resourceId = "resource-$index",
                recordId = "record-$index",
                recordResourceId = "resource-$index",
                pathParameterValues = mapOf("parentId" to "parent-$index"),
            )
        }
        val saved = DynamicAppNavigationState(
            selectedViewId = "view-current",
            selectedRecord = NativeRecord(
                id = "record-current",
                values = mapOf("body" to "private body must not be saved"),
            ),
            selectedRecordResourceId = "resource-current",
            history = history,
        ).toSavedDynamicAppNavigationState()
        val encoded = Json.encodeToString(saved)
        val restored = saved.toDynamicAppNavigationState()

        assertEquals(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY, saved.history.size)
        assertEquals("view-3", saved.history.first().viewId)
        assertEquals("view-18", saved.history.last().viewId)
        assertEquals(saved.history, restored.history)
        assertFalse(encoded.contains("private body must not be saved"))
        assertTrue(encoded.length < 16_000)
    }
}
