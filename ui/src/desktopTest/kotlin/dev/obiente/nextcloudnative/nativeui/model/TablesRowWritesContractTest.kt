package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeGridRowRequestResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.createRequest
import dev.obiente.nextcloudnative.nativeui.runtime.nativeGridRowWritePlan
import dev.obiente.nextcloudnative.nativeui.runtime.row
import dev.obiente.nextcloudnative.nativeui.runtime.updateRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generic grid row editor binds only operations present in a real published contract (the
 * Tables 2.4.0 OpenAPI excerpt). The app identity is replaced to prove that nothing depends on it.
 */
class TablesRowWritesContractTest {
    @Test
    fun tableAndViewGridsSelectTheContractRowOperationsWhateverTheAppIdentity() {
        for (app in listOf(AppIdentity("tables", "Tables", "2.4.0"), AppIdentity("sheetsy", "Sheetsy", "77.0.0"))) {
            val schema = compile(app).toNativeAppSchema()
            val tableGrid = schema.views.single { it.compositeDataGrid?.parentResourceId == "tables" }
            val tablePlan = assertNotNull(nativeGridRowWritePlan(schema, tableGrid, context("tables"), listOf(row())))
            assertEquals("POST", tablePlan.create?.action?.binding?.method?.name)
            assertEquals("PUT", tablePlan.update?.write?.action?.binding?.method?.name,
                "The replacement is offered because the contract declares the same-route row read.")
            assertEquals("DELETE", tablePlan.delete?.write?.action?.binding?.method?.name)
            assertEquals("data", tablePlan.cellField)
            val request = assertIs<NativeGridRowRequestResult.Ready>(tablePlan.createRequest(mapOf("1" to "Synthetic"))).request
            assertEquals(mapOf("1" to "Synthetic"), Json.decodeFromString<Map<String, String>>(request.values.getValue("data")))
            val stored = assertNotNull(tablePlan.row(row()))
            val update = assertIs<NativeGridRowRequestResult.Ready>(tablePlan.updateRequest(stored, mapOf("1" to "Changed"))).request
            assertNull(update.values["viewId"], "A table-scoped row names its table, so no view is sent.")

            val viewGrid = schema.views.single { it.compositeDataGrid?.parentResourceId == "views" }
            val viewPlan = assertNotNull(nativeGridRowWritePlan(schema, viewGrid, context("views"), listOf(row())))
            assertEquals(listOf("viewId", "rowId"), viewPlan.delete?.write?.placeholders)
            assertTrue(viewPlan.update?.global == true)
            assertEquals("viewId", viewPlan.update?.parentInput)
        }
    }

    @Test
    fun aRestoredReadOnlySchemaOffersNoRowWrites() {
        val schema = compile(AppIdentity("tables", "Tables", "2.4.0")).toNativeAppSchema()
        val readOnly = schema.copy(actions = schema.actions.filter { it.risk == ActionRisk.readOnly })
        val tableGrid = readOnly.views.single { it.compositeDataGrid?.parentResourceId == "tables" }
        assertNull(nativeGridRowWritePlan(readOnly, tableGrid, context("tables")))
    }

    private fun row() = NativeRecord("9", mapOf("id" to "9", "tableId" to "5",
        "data" to """[{"columnId":1,"value":"Synthetic"}]"""))

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

    private fun compile(app: AppIdentity): DynamicAppDescriptor {
        val document = javaClass.getResourceAsStream(FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Tables 2.4.0 row write fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = app,
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/index.php/apps/tables/api/1")),
                advertisedOpenApi = AdvertisedOpenApi("/apps/tables/openapi.json", document, OpenApiTrust.nextcloudSignedAppPackage),
            ),
        )
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/tables-2.4.0-row-writes-excerpt.json"
    }
}
