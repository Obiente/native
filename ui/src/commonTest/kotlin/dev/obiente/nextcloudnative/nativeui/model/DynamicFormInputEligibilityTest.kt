package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicFormInputEligibilityTest {
    @Test
    fun requiredUnionDataCannotBecomeAnOptionalViewOnlyForm() {
        val descriptor = DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(
            app = AppIdentity("inventory", "Inventory", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/inventory/api/1")),
            advertisedOpenApi = AdvertisedOpenApi("/contract.json", Json.parseToJsonElement("""{
              "openapi":"3.0.3","info":{"title":"Inventory","version":"1"},"paths":{
                "/apps/inventory/api/1/rows/{rowId}":{"put":{
                  "operationId":"updateRow","parameters":[{"name":"rowId","in":"path","required":true,"schema":{"type":"integer"}}],
                  "requestBody":{"required":true,"content":{"application/json":{"schema":{
                    "type":"object","required":["data"],"properties":{
                      "viewId":{"type":"integer","nullable":true},
                      "data":{"oneOf":[{"type":"string"},{"type":"object","additionalProperties":{"type":"object"}}]}
                    }}}}},"responses":{"200":{"description":"Updated"}}
                }}
              }}"""))))
        val action = descriptor.actions.single()
        val form = descriptor.forms.single()
        assertFalse(action.hasMaterializableRequiredFormInputs(form))
        for (unsupported in listOf(
            """{"type":"array","items":{"type":"string"}}""",
            """{"type":"string"}""",
            """{"allOf":[{"type":"object","required":["data"],"properties":{"data":{"type":"object"}}}]}""",
            """{"type":"object","required":"data","properties":{"data":{"type":"string"}}}""",
            """{"type":"object","required":[7],"properties":{"data":{"type":"string"}}}""",
        )) assertFalse(action.copy(binding = action.binding.copy(body = action.binding.body!!.copy(
            schema = Json.parseToJsonElement(unsupported)))).hasMaterializableRequiredFormInputs(form))
        val scalarBody = action.binding.body!!.copy(schema = Json.parseToJsonElement(
            """{"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}"""))
        assertTrue(action.copy(binding = action.binding.copy(body = scalarBody)).hasMaterializableRequiredFormInputs(
            form.copy(fields = listOf(FormField("title", "Title", FieldKind.string, true)))))
        assertTrue(action.copy(binding = action.binding.copy(body = null)).hasMaterializableRequiredFormInputs(form.copy(fields = emptyList())))
        // A server-assigned readOnly identifier is required only in responses, so a create form stays usable.
        val readOnlyIdBody = action.binding.body!!.copy(schema = Json.parseToJsonElement(
            """{"type":"object","required":["id","title"],"properties":{"id":{"type":"string","readOnly":true},"title":{"type":"string"}}}"""))
        assertTrue(action.copy(binding = action.binding.copy(body = readOnlyIdBody)).hasMaterializableRequiredFormInputs(
            form.copy(fields = listOf(FormField("title", "Title", FieldKind.string, true)))))
        assertFalse(action.copy(binding = action.binding.copy(body = readOnlyIdBody)).hasMaterializableRequiredFormInputs(
            form.copy(fields = emptyList())))
    }
}
