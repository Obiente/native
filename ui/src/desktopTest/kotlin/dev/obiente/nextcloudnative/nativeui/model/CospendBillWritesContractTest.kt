package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.nativeui.runtime.NativeChildRecordRequestResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.createRequest
import dev.obiente.nextcloudnative.nativeui.runtime.nativeChildRecordWritePlan
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generic child record editor against a real published expense contract (the Cospend OpenAPI
 * excerpt). The contract declares bill create and edit, but it sends who a bill was for as one
 * plain string and declares no same-route read for its replacement edit, so the generic rules
 * explain both writes as unavailable instead of guessing an encoding.
 */
class CospendBillWritesContractTest {
    @Test
    fun billListSelectsContractOperationsAndLeavesUndeclaredEncodingsUnavailable() {
        val schema = compile().toNativeAppSchema()
        val plan = assertNotNull(nativeChildRecordWritePlan(schema, billList(schema), context()))
        assertEquals("api-create-bill", plan.create?.write?.action?.id)
        assertEquals("api-edit-bill", plan.update?.write?.action?.id)
        assertNull(plan.delete, "The excerpt has no delete operation, so none is offered.")
        assertTrue(plan.expense, "Amount, payer, and the people it was paid for form an expense.")
        assertTrue(plan.create?.unavailableReason.orEmpty().contains("payed for"))
        assertIs<NativeChildRecordRequestResult.Unavailable>(plan.createRequest(mapOf("what" to "Groceries")))
        assertNotNull(plan.update?.unavailableReason, "A replacement without a declared same-route read is not offered.")
    }

    @Test
    fun aProjectThatStatesNoReadablePermissionOffersNoWrites() {
        val schema = compile().toNativeAppSchema()
        val numericLevelOnly = context().let { context ->
            context.copy(parentRecord = context.parentRecord?.let { project ->
                project.copy(values = project.values - "permissions" + ("myaccesslevel" to "2"))
            })
        }
        assertNull(nativeChildRecordWritePlan(schema, billList(schema), numericLevelOnly))
    }

    private fun billList(schema: NativeAppSchema): String =
        schema.actions.single { it.binding.method == HttpMethod.GET && it.binding.path.endsWith("/bills") }.id

    private fun context() = NativeDatasetContext(
        parentResourceId = "projects",
        parentRecord = NativeRecord(
            "synthetic-project",
            mapOf(
                "id" to "synthetic-project",
                "permissions" to """{"create":true,"update":true,"delete":true}""",
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
