package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeTablesRowRequestResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeTablesRowScope
import dev.obiente.nextcloudnative.nativeui.runtime.createRequest
import dev.obiente.nextcloudnative.nativeui.runtime.nativeTablesRowWritePlan
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The reviewed Tables row editor binds only operations present in the Tables 2.4.0 contract. */
class TablesRowWritesContractTest {
    @Test
    fun tableAndViewGridsSelectTheContractRowOperations() {
        val schema = compile(OpenApiTrust.nextcloudSignedAppPackage).toNativeAppSchema()
        val tableGrid = schema.views.single { it.compositeDataGrid?.parentResourceId == "tables" }
        val tablePlan = assertNotNull(nativeTablesRowWritePlan(schema, tableGrid, context("tables")))
        assertEquals(NativeTablesRowScope.Table(5), tablePlan.scope)
        assertEquals("POST", tablePlan.create?.action?.binding?.method?.name)
        assertEquals("PUT", tablePlan.update?.action?.binding?.method?.name)
        assertEquals("DELETE", tablePlan.delete?.action?.binding?.method?.name)
        val request = assertIs<NativeTablesRowRequestResult.Ready>(tablePlan.createRequest(mapOf(1L to "Synthetic"))).request
        assertEquals(mapOf("1" to "Synthetic"), Json.decodeFromString<Map<String, String>>(request.values.getValue("data")))

        val viewGrid = schema.views.single { it.compositeDataGrid?.parentResourceId == "views" }
        val viewPlan = assertNotNull(nativeTablesRowWritePlan(schema, viewGrid, context("views")))
        assertEquals(NativeTablesRowScope.View(5), viewPlan.scope)
        assertEquals(listOf("viewId", "rowId"), viewPlan.delete?.placeholders)
    }

    @Test
    fun aRestoredReadOnlySchemaOffersNoRowWrites() {
        val schema = compile(OpenApiTrust.nextcloudSignedAppPackage).toNativeAppSchema()
        val readOnly = schema.copy(actions = schema.actions.filter { it.risk == ActionRisk.readOnly })
        val tableGrid = readOnly.views.single { it.compositeDataGrid?.parentResourceId == "tables" }
        assertNull(nativeTablesRowWritePlan(readOnly, tableGrid, context("tables")))
    }

    private fun context(parent: String) = NativeDatasetContext(
        parentResourceId = parent,
        parentRecord = NativeRecord("5", mapOf("id" to "5", "isShared" to "false")),
        relatedRecords = mapOf(
            "columns" to listOf(
                NativeRecord("1", mapOf("id" to "1", "title" to "Item", "type" to "text", "subtype" to "line",
                    "tableId" to "5", "mandatory" to "false")),
            ),
        ),
    )

    private fun compile(trust: OpenApiTrust): DynamicAppDescriptor {
        val document = javaClass.getResourceAsStream(FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Tables 2.4.0 row write fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("tables", "Tables", "2.4.0"),
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/index.php/apps/tables/api/1")),
                advertisedOpenApi = AdvertisedOpenApi("/apps/tables/openapi.json", document, trust),
            ),
        )
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/tables-2.4.0-row-writes-excerpt.json"
    }
}
