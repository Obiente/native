package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AndroidMembershipRulePlanTest {
    @Test fun typedMembershipPlanningCompilesAndRunsThroughAndroidDependency() {
        val root = "/apps/board-test/cards/{cardId}"
        val parameter = """{"name":"cardId","in":"path","required":true,"schema":{"type":"integer"}}"""
        val paths = mutableListOf(""""$root":{"get":{"operationId":"read-card","parameters":[$parameter],"responses":{"200":{"description":"OK","content":{"application/json":{"schema":{"type":"object","properties":{"id":{"type":"integer"},"labels":{"type":"array","items":{"type":"integer"}}}}}}}}}}""")
        for (command in listOf("assignLabel", "removeLabel")) paths += """"$root/$command":{"post":{"operationId":"$command","parameters":[$parameter],"requestBody":{"required":true,"content":{"application/json":{"schema":{"type":"object","required":["labelId"],"properties":{"labelId":{"type":"integer"}}}}}},"responses":{"200":{"description":"OK"}}}}"""
        val descriptor = DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(AppIdentity("board-test", "Board test", "999.7-unknown"),
            EndpointPolicy("https://cloud.example.test", listOf("/apps/board-test")), advertisedOpenApi = AdvertisedOpenApi(
                "/apps/board-test/openapi.json", Json.parseToJsonElement("""{"openapi":"3.0.3","paths":{${paths.joinToString(",")}}}"""), OpenApiTrust.sameOriginAdvertisement)))
        val schema = descriptor.toNativeAppSchema()
        val actions = schema.actions + schema.dedicatedActions
        val read = actions.single { it.binding.method == HttpMethod.GET }
        val write = actions.single { it.binding.path.endsWith("/assignLabel") }
        val record = NativeRecord("41", mapOf("id" to "41"), wireValues = mapOf("id" to JsonPrimitive(41), "labels" to JsonArray(listOf(JsonPrimitive(3)))))
        val authority = NativeItemReadAuthority(NativeScreenState.Ready(listOf(record), 1), read.id, mapOf("cardId" to "41"), canEdit = true)
        val plan = assertNotNull(planNativeMembershipRule(schema, write.id, "41", JsonPrimitive(5), authority))
        assertEquals("41", plan.writeBindings["cardId"])
        assertEquals("5", plan.writeBindings["labelId"])
        assertNull(planNativeMembershipRule(schema, write.id, "41", JsonPrimitive("5"), authority))
        assertNull(planNativeMembershipRule(schema, write.id, "41", JsonPrimitive(5), authority.copy(currentGeneration = 2)))
    }
}
