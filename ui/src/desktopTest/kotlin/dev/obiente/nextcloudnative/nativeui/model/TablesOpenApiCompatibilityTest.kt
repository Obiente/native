package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCellEditPlan
import dev.obiente.nextcloudnative.nativeui.runtime.nativeTableProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TablesOpenApiCompatibilityTest {
    @Test
    fun `signed package shape retains nested reads and synthesizes the joined table`() {
        val document = javaClass.getResourceAsStream(LIVE_SHAPE_FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing signed-package Tables OpenAPI fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("tables", "Tables", "2.2.0"),
                endpointPolicy = EndpointPolicy(
                    "https://cloud.example.test",
                    listOf("/index.php/apps/tables/api/1", "/ocs/v2.php/apps/tables/api/2"),
                ),
                advertisedOpenApi = AdvertisedOpenApi("/apps/tables/openapi.json", document),
            ),
        )

        val tableChildren = descriptor.links.mapNotNull { link ->
            if (link.resourceId != "tables") return@mapNotNull null
            val target = link.target as? DynamicLinkTarget.Action ?: return@mapNotNull null
            descriptor.actions.firstOrNull { it.id == target.actionId }?.resourceId
        }
        assertTrue("columns" in tableChildren)
        assertTrue("rows" in tableChildren)

        val nativeSchema = descriptor.toNativeAppSchema()
        val tableView = nativeSchema.views.single { it.id == "tables.table" }
        val composite = requireNotNull(tableView.compositeDataGrid)
        assertEquals("tables", composite.parentResourceId)
        assertEquals("columns", composite.columnResourceId)
        assertEquals("rows", composite.rowResourceId)
        assertEquals("data", composite.rowCellMapFieldId)
        assertEquals(NativeComponent.dataTable, tableView.component)
    }

    @Test
    fun groupsVersionedControllerTagsIntoAResourceHierarchy() {
        val document = javaClass.getResourceAsStream(FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Tables OpenAPI fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("tables", "Tables", "2.2.0"),
                endpointPolicy = EndpointPolicy(
                    "https://cloud.example.test",
                    listOf("/index.php/apps/tables/api/1", "/ocs/v2.php/apps/tables/api/2"),
                ),
                advertisedOpenApi = AdvertisedOpenApi("/apps/tables/openapi.json", document),
            ),
        )

        assertEquals(listOf("columns", "overview", "rows", "tables"), descriptor.resources.map(DynamicResource::id))
        assertEquals(
            listOf(
                "columns.detail|api-columns-show",
                "columns.list|api1-index-table-columns",
                "overview.detail|api-general-index",
                "rows.detail|api1-get-row",
                "rows.list|api1-index-table-rows",
                "tables.detail|api-tables-show",
                "tables.list|api-tables-index",
            ),
            descriptor.layouts.map { "${it.id}|${it.sourceActionId}" },
        )
        assertEquals("columns", descriptor.actions.single { it.id == "api-columns-index" }.resourceId)
        assertEquals("columns", descriptor.actions.single { it.id == "api1-index-view-columns" }.resourceId)
        assertEquals("rows", descriptor.actions.single { it.id == "api1-index-view-rows" }.resourceId)
        assertEquals(
            listOf("id"),
            descriptor.actions.single { it.id == "api1-index-table-columns" }
                .binding.pathParameters.map(HttpParameter::name),
        )
        assertEquals(
            listOf("id"),
            descriptor.actions.single { it.id == "api1-index-table-rows" }
                .binding.pathParameters.map(HttpParameter::name),
        )
        assertEquals(
            listOf(
                "tables.id->api1-index-table-columns",
                "tables.id->api1-index-table-rows",
            ),
            descriptor.links.mapNotNull { link ->
                val target = link.target as? DynamicLinkTarget.Action ?: return@mapNotNull null
                "${link.resourceId}.${link.sourceFieldId}->${target.actionId}"
            },
        )
        assertTrue(descriptor.resources.none { it.label.startsWith("Api ") })
        assertTrue(descriptor.validationErrors().isEmpty())

        val nativeSchema = descriptor.toNativeAppSchema()
        assertEquals(NativeComponent.dataTable, nativeSchema.views.single { it.id == "rows.list" }.component)
        assertEquals(NativeComponent.collectionList, nativeSchema.views.single { it.id == "tables.list" }.component)
        val tableView = nativeSchema.views.single { it.compositeDataGrid != null }
        val composite = requireNotNull(tableView.compositeDataGrid)
        assertEquals("tables.table", tableView.id)
        assertEquals("Table", tableView.title)
        assertEquals("columns", composite.columnResourceId)
        assertEquals("rows", composite.rowResourceId)
        assertEquals("api1-index-table-columns", composite.columnSourceActionId)
        assertEquals("api1-index-table-rows", composite.rowSourceActionId)
        assertEquals("id", composite.columnIdentityFieldId)
        assertEquals("technicalName", composite.columnAliasFieldId)
        assertEquals("title", composite.columnTitleFieldId)
        assertEquals("type", composite.columnTypeFieldId)
        assertEquals("orderWeight", composite.columnOrderFieldId)
        assertEquals("dataByAlias", composite.rowCellMapFieldId)

        val rows = nativeSchema.resources.single { it.id == "rows" }
        val columns = nativeSchema.resources.single { it.id == "columns" }
        val record = NativeRecord(
            "17",
            mapOf(
                "id" to "17",
                "dataByAlias" to """{"price":{"columnId":4,"value":12.5},"item":{"columnId":3,"value":"Tea"}}""",
            ),
        )
        val columnRecords = listOf(
            NativeRecord(
                "4",
                mapOf(
                    "id" to "4",
                    "technicalName" to "price",
                    "title" to "Unit price",
                    "type" to "number",
                    "orderWeight" to "20",
                ),
            ),
            NativeRecord(
                "3",
                mapOf(
                    "id" to "3",
                    "technicalName" to "item",
                    "title" to "Product",
                    "type" to "text",
                    "orderWeight" to "10",
                ),
            ),
        )
        val projection = nativeTableProjection(rows, listOf(record), columns, columnRecords, composite)
        assertEquals(listOf("Product", "Unit price"), projection.resource.fields.take(2).map(FieldSpec::label))
        assertEquals(listOf("dataByAlias.item", "dataByAlias.price"), projection.projectedFieldIds.toList())
        assertEquals(FieldKind.decimal, projection.resource.fields.single { it.id == "dataByAlias.price" }.kind)
        assertNull(
            projection.frozenFieldId,
            "A joined grid without a semantic row title must not waste a frozen column on its technical ID.",
        )
        assertTrue(projection.composite)
        val price = projection.resource.fields.single { it.id == "dataByAlias.price" }
        assertNull(nativeCellEditPlan(nativeSchema, rows, projection, record, price))
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/tables-2.2.0-hierarchy-excerpt.json"
        const val LIVE_SHAPE_FIXTURE_PATH = "/fixtures/tables-2.2.0-live-shape-excerpt.json"
    }
}
