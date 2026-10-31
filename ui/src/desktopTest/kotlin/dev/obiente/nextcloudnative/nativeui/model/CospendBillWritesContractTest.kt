package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.nativeui.runtime.NativeCospendBillDraft
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCospendBillRequestResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.createRequest
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCospendBillPlan
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The reviewed bill editor binds only bill operations present in the Cospend contract. */
class CospendBillWritesContractTest {
    @Test
    fun billListSelectsContractCreateAndUpdateAndNoInventedDelete() {
        val schema = compile().toNativeAppSchema()
        val bills = schema.resources.single { it.id == "bills" }
        val plan = assertNotNull(nativeCospendBillPlan(schema, bills, context()))
        assertEquals("api-create-bill", plan.create?.action?.id)
        assertEquals("api-edit-bill", plan.update?.action?.id)
        assertNull(plan.delete, "The excerpt has no delete operation, so none is offered.")
        val draft = NativeCospendBillDraft("Groceries", "12.40", "2026-01-02", "1", setOf("2", "1"))
        val request = assertIs<NativeCospendBillRequestResult.Ready>(plan.createRequest(draft)).request
        assertEquals("synthetic-project", request.values["projectId"])
        assertEquals("1,2", request.values["payedFor"])
        assertEquals("n", request.values["repeat"])
    }

    private fun context() = NativeDatasetContext(
        parentResourceId = "projects",
        parentRecord = NativeRecord(
            "synthetic-project",
            mapOf(
                "id" to "synthetic-project",
                "myaccesslevel" to "2",
                "members" to """[{"id":1,"name":"Ada","activated":true},{"id":2,"name":"Lin","activated":true}]""",
                "categories" to "{}",
                "paymentmodes" to "[]",
            ),
        ),
    )

    private fun compile(): DynamicAppDescriptor {
        val document = javaClass.getResourceAsStream(FIXTURE_PATH).use { stream ->
            Json.parseToJsonElement(requireNotNull(stream).bufferedReader().readText())
        }
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("cospend", "Cospend", "4.3.0"),
                endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/ocs/v2.php/apps/cospend/api")),
                advertisedOpenApi = AdvertisedOpenApi("/apps/cospend/openapi.json", document),
            ),
        )
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/cospend-openapi-private-excerpt.json"
    }
}
