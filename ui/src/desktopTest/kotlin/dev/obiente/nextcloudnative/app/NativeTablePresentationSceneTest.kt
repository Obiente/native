package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlin.test.*

class NativeTablePresentationSceneTest {
    @Test
    fun compiledInventoryCollectionDispatchShowsSemanticSummaryAtNarrowAndWideSizes() {
        val document = javaClass.getResourceAsStream("/fixtures/tables-2.2.0-live-shape-excerpt.json").use {
            kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(it).bufferedReader().readText())
        }
        val schema = DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(
            app = AppIdentity("tables", "Tables", "2.2.0"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test",
                listOf("/index.php/apps/tables/api/1", "/ocs/v2.php/apps/tables/api/2")),
            advertisedOpenApi = AdvertisedOpenApi("/apps/tables/openapi.json", document),
        )).toNativeAppSchema()
        val view = schema.views.single { it.id == "tables.list" }
        assertEquals("api-tables-index", view.sourceActionId)
        val record = NativeRecord("table-7", mapOf("id" to "7", "title" to "Equipment inventory",
            "rowsCount" to "5", "columnsCount" to "3", "ownership" to "Synthetic owner",
            "archived" to "false", "favorite" to "false"))
        for (width in listOf(390, 1200)) {
            var selected: NativeRecord? = null
            nativeSceneTest(width, 844, fontScale = 1.3f, content = {
                GenericNativeAppScreen(schema, view, NativeScreenState.Ready(listOf(record)),
                    NativeActionExecutor { error("No mutation is authorized by this fixture") },
                    onSelectRecord = { selected = it })
            }) {
                val bounds = assertNotNull(node("5 rows | 3 columns")).boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 844)
                assertFalse(has("Synthetic owner"))
                assertFalse(has("Archived: No"))
                assertFalse(has("Favorite: No"))
                click("Equipment inventory")
                assertEquals(record, selected)
                capture("tables-inventory-dispatch-$width")
            }
        }
    }
    @Test
    fun multipleTableCardsKeepCountsReadableAndOwnerBehindDetails() {
        val resource = ResourceSpec("tables", "Tables", Confidence.verified,
            listOf("title", "rowsCount", "columnsCount", "owner", "archived", "favorite")
                .map { FieldSpec(it, it, FieldKind.string, false, true) })
        val schema = NativeAppSchema("1", AppIdentity("tables", "Tables", "1"), Confidence.verified)
        val records = listOf("Inventory", "Project work", "Empty table").mapIndexed { index, title ->
            NativeRecord("table-$index", mapOf("title" to title, "rowsCount" to listOf("12", "8", "0")[index],
                "columnsCount" to "4", "owner" to "Synthetic owner $index", "archived" to "false", "favorite" to "false"))
        }
        var selected: NativeRecord? = null
        nativeSceneTest(390, 1000, content = {
            GenericRecordList(resource, records, { selected = it }, primaryContent = { record ->
                NativeTableInventoryContent(requireNotNull(nativeTableInventoryPresentation(schema, resource, record)))
            })
        }) {
            assertTrue(has("12 rows | 4 columns"))
            assertTrue(has("0 rows | 4 columns"))
            assertFalse(has("owner: Synthetic owner 2"))
            click("Details")
            assertTrue(has("owner: Synthetic owner 2"))
            click("Inventory")
            assertEquals(records.first(), selected)
            val bounds = assertNotNull(node("12 rows | 4 columns")).boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= 390)
        }
    }

    @Test
    fun rowDetailUsesColumnLabelsWithoutDuplicatingThePrimaryCell() {
        val detail = NativeTableRecordDetailPresentation("Camera kit", listOf(
            NativeDetailFieldPresentation("item", NativeFormattedField("Item", "Camera kit")),
            NativeDetailFieldPresentation("quantity", NativeFormattedField("Quantity", "3")),
            NativeDetailFieldPresentation("condition", NativeFormattedField("Condition", "Ready")),
            NativeDetailFieldPresentation("checked", NativeFormattedField("Checked", "No"))), "Equipment inventory")
        nativeSceneTest(390, 1000, fontScale = 1.5f, content = { NativeTableRecordDetail(detail) }) {
            assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == "Camera kit" } == true })
            assertTrue(has("Equipment inventory"))
            assertTrue(has("Quantity"))
            assertTrue(has("3"))
            assertTrue(has("No"))
            val bounds = assertNotNull(node("Camera kit")).boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= 390 && bounds.bottom <= 1000)
        }
    }
}
