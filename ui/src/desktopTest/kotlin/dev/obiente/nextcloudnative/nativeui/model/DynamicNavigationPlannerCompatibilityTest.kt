package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DynamicNavigationPlannerCompatibilityTest {
    @Test
    fun `Tables root opens tables and selected table opens columns and rows only`() {
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

        assertEquals(
            listOf("tables"),
            descriptor.planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::resourceId),
        )

        val selectedPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "tables",
                recordId = "record-table",
                parameterValues = mapOf("id" to "exact-table"),
            ),
        )
        assertEquals(
            listOf("columns", "rows"),
            selectedPlan.contextualChildDestinations.map(DynamicNavigationDestination::resourceId),
        )
        assertEquals(
            setOf("api1-index-table-columns", "api1-index-table-rows"),
            selectedPlan.contextualChildDestinations.map(DynamicNavigationDestination::actionId).toSet(),
        )
        assertEquals(
            setOf(mapOf("id" to "exact-table")),
            selectedPlan.contextualChildDestinations.map(DynamicNavigationDestination::pathParameterValues).toSet(),
        )
        assertFalse(selectedPlan.contextualChildDestinations.any { it.actionId.contains("view") })

        val linkedFallbackPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "tables", recordId = "record-table"),
        )
        assertEquals(
            setOf(mapOf("id" to "record-table")),
            linkedFallbackPlan.contextualChildDestinations
                .map(DynamicNavigationDestination::pathParameterValues)
                .toSet(),
        )
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/tables-2.2.0-hierarchy-excerpt.json"
    }
}
