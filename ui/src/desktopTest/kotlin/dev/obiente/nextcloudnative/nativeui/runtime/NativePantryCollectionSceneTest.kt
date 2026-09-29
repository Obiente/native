package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Column
import dev.obiente.nextcloudnative.app.nativeSceneTest
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class NativePantryCollectionSceneTest {
    private val resource = ResourceSpec("houses", "Households", Confidence.high, listOf(
        FieldSpec("name", "Name", FieldKind.string, false, false),
        FieldSpec("trashRetentionDays", "Trash retention days", FieldKind.integer, false, false),
        FieldSpec("fieldReminderTime", "Reminder time", FieldKind.string, false, false)))

    @Test fun taskDispatchRetainsPantryQuantityDescriptionAndExistingCompletion() {
        val itemsResource = ResourceSpec("items", "Items", Confidence.high, listOf(
            FieldSpec("name", "Name", FieldKind.string, false, false),
            FieldSpec("quantity", "Quantity", FieldKind.string, false, false),
            FieldSpec("description", "Description", FieldKind.string, false, false),
            FieldSpec("done", "Done", FieldKind.boolean, false, false)),
            listOf(Evidence(EvidenceSource.verifiedAppPackage, "Synthetic reviewed contract")))
        val action = ActionSpec("items.list", "Items", "items",
            ApiBinding(HttpMethod.GET, "/synthetic/items", "items.list"), ActionIntent.list,
            ActionRisk.readOnly, false, Confidence.high)
        val view = ViewSpec("items", "Items", "items", NativeComponent.collectionList, action.id, Confidence.high)
        val schema = NativeAppSchema("test", AppIdentity("pantry", "Pantry", "0.34.0"), Confidence.high,
            resources = listOf(itemsResource), actions = listOf(action), views = listOf(view))
        val record = NativeRecord("item-7", mapOf("name" to "Synthetic lentils", "quantity" to "2 cans",
            "description" to "For the shared dinner", "done" to "true", "listId" to "list-3"))
        assertNotNull(nativeTaskCollectionPresentations(itemsResource, listOf(record)), "Fixture must use task dispatch")
        var selected: NativeRecord? = null
        nativeSceneTest(390, 844, fontScale = 1.5f, content = {
            GenericNativeAppScreen(schema, view, NativeScreenState.Ready(listOf(record)),
                NativeActionExecutor { error("No mutation is authorized by this fixture") }, onSelectRecord = { selected = it })
        }) {
            assertTrue(has("Quantity: 2 cans"))
            assertTrue(has("For the shared dinner"))
            assertTrue(has("Completed"))
            click("Synthetic lentils")
            assertSame(record, selected)
        }
    }

    @Test fun householdCardKeepsExactOpenAndOverflowWhileSettingsLeavePrimaryContent() {
        val record = NativeRecord("house-7", mapOf("name" to "Shared household for the autumn community dinner",
            "description" to "Meals and shopping for everyone", "trashRetentionDays" to "30", "fieldReminderTime" to "08:00"))
        var opened: NativeRecord? = null
        var edits = 0
        nativeSceneTest(390, 844, fontScale = 1.5f, content = {
            GenericCollectionCard(resource, record, { opened = it },
                secondaryActions = listOf(NextcloudCardAction("Edit household", onClick = { edits++ })),
                primaryContent = { NativePantryRecordContent(NativePantryCollectionKind.Household, record) })
        }) {
            assertTrue(has("Meals and shopping for everyone"))
            assertFalse(has("Trash retention days"))
            assertFalse(has("08:00"))
            click("Open Shared household for the autumn community dinner")
            assertSame(record, opened)
            click("Actions for Shared household for the autumn community dinner")
            click("Edit household")
            assertEquals(1, edits)
        }
    }

    @Test fun listAndItemHierarchyShowsQuantityAndCompletionWithoutInventingAToggle() {
        val list = NativeRecord("list-3", mapOf("name" to "Shopping for this week's shared meals", "description" to "Fresh food"))
        val items = listOf(
            NativeRecord("item-1", mapOf("name" to "Lentils", "quantity" to "2 cans", "done" to "false")),
            NativeRecord("item-2", mapOf("name" to "Tomatoes", "quantity" to "500 g", "done" to "true")),
            NativeRecord("item-3", mapOf("name" to "Seasonal fruit", "done" to "unknown")),
        )
        nativeSceneTest(390, 844, fontScale = 1.5f, content = {
            Column {
                NativePantryRecordContent(NativePantryCollectionKind.List, list)
                items.forEach { NativePantryRecordContent(NativePantryCollectionKind.Item, it) }
            }
        }) {
            assertTrue(has("Fresh food"))
            assertTrue(has("Quantity: 2 cans"))
            assertTrue(has("To do"))
            assertTrue(has("Completed"))
            assertTrue(has("Status unavailable"))
        }
    }
}
