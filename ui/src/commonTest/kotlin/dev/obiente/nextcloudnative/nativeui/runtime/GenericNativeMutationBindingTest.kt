package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GenericNativeMutationBindingTest {
    @Test
    fun `required read only body identity may reuse the exact required path identity`() {
        val bodySchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "id":{"type":"string","readOnly":true},
                "name":{"type":"string"}
              },
              "required":["id","name"],
              "additionalProperties":false
            }""",
        )
        val resource = ResourceSpec(
            id = "recipes",
            name = "Recipes",
            confidence = Confidence.high,
            fields = listOf(
                field("id", required = true),
                field("name", required = true),
            ),
        )
        val update = ActionSpec(
            id = "recipes.update",
            label = "Update recipe",
            resourceId = resource.id,
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/recipes/{id}",
                operationId = "recipes.update",
                pathParameterNames = listOf("id"),
                requiredPathParameterNames = listOf("id"),
                bodyFieldNames = listOf("id", "name"),
                requiredBodyFieldNames = listOf("id", "name"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            inputSchema = bodySchema,
            confidence = Confidence.high,
        )
        val view = ViewSpec(
            id = "recipes.update.form",
            title = "Update recipe",
            resourceId = resource.id,
            component = NativeComponent.form,
            sourceActionId = update.id,
            confidence = Confidence.high,
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("cookbook", "Cookbook", "0.11.10"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(view),
            actions = listOf(update),
        )

        val ready = assertIs<NativeRequestBuildResult.Ready>(
            buildNativeSubmitRequest(
                schema = schema,
                view = view,
                values = mapOf("id" to "208", "name" to "Updated recipe"),
                confirmed = false,
            ),
        )
        assertEquals(
            mapOf("id" to "208", "name" to "Updated recipe"),
            assertIs<NativeActionRequest.Submit>(ready.request).values,
        )
        assertIs<NativeRequestBuildResult.Invalid>(
            buildNativeSubmitRequest(
                schema = schema,
                view = view,
                values = mapOf("name" to "Updated recipe"),
                confirmed = false,
            ),
        )
    }

    private fun field(id: String, required: Boolean) = FieldSpec(
        id = id,
        label = id.replaceFirstChar(Char::uppercase),
        kind = FieldKind.string,
        required = required,
        readOnly = false,
    )
}
