package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeatableObjectEditableFieldsTest {
    @Test
    fun `declared repeatable object body remains editable in a root form`() {
        val structured = RepeatableObjectInputSpec(
            minimumItems = 1,
            maximumItems = 1,
            fields = listOf(
                RepeatableObjectInputFieldSpec(
                    id = "name",
                    label = "Name",
                    kind = RepeatableObjectInputScalarKind.String,
                    required = true,
                ),
            ),
        )
        val field = FieldSpec(
            id = "chores",
            label = "Chores",
            kind = FieldKind.objectValue,
            required = true,
            readOnly = false,
            repeatableObjectInput = structured,
        )
        val bodySchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "chores":{
                  "type":"array",
                  "format":"nextcloud-repeatable-object-array",
                  "minItems":1,
                  "maxItems":1,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["name"],
                    "properties":{"name":{"type":"string"}}
                  }
                }
              },
              "required":["chores"]
            }""",
        )
        val action = ActionSpec(
            id = "create-chores",
            label = "Add chore",
            resourceId = "chores",
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/apps/chores/api/v1.0/team/{teamId}/chores",
                operationId = "create-chores",
                pathParameterNames = listOf("teamId"),
                requiredPathParameterNames = listOf("teamId"),
                bodyFieldNames = listOf("chores"),
                requiredBodyFieldNames = listOf("chores"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            inputSchema = bodySchema,
        )
        val resource = ResourceSpec(
            id = action.resourceId,
            name = "Chores",
            confidence = Confidence.verified,
            fields = listOf(field),
        )

        val editable = editableNativeFields(resource, action)
        assertEquals(listOf(field), editable)
        assertTrue(uneditableNativeBodyFieldIds(action, editable, mapOf("teamId" to "1")).isEmpty())
    }
}
