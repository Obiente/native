package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
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
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class NativeCollectionPathIdentityTest {
    @Test
    fun `resource qualified context binds a generic parent id and rejects ambiguity`() {
        val resource = resource()
        val read = readAction("/houses/{houseId}/items", listOf("houseId"))
        val batch = batchAction("/houses/{id}/items/batch", listOf("id"))
        val records = records("11", "12")
        val schema = schema(resource, read, batch)

        val plan = assertNotNull(
            nativeCollectionActions(
                schema = schema,
                activeReadAction = read,
                resource = resource,
                records = records,
                navigationContext = mapOf("houseId" to "house-7"),
                collectionComplete = false,
            ).batches.singleOrNull(),
        )
        assertEquals(
            mapOf("id" to "house-7", "itemIds" to "[11,12]"),
            plan.request(listOf("11", "12")).values,
        )

        assertTrue(
            nativeCollectionActions(
                schema = schema,
                activeReadAction = read,
                resource = resource,
                records = records,
                navigationContext = mapOf(
                    "houseId" to "house-7",
                    "housesId" to "house-8",
                    "id" to "house-9",
                ),
                collectionComplete = false,
            ).batches.isEmpty(),
        )
    }

    @Test
    fun `generic identity nested under the collection resource remains a child identity`() {
        val resource = resource()
        val read = readAction("/items")
        val batch = batchAction("/items/{id}/batch", listOf("id"))

        assertTrue(
            nativeCollectionActions(
                schema = schema(resource, read, batch),
                activeReadAction = read,
                resource = resource,
                records = records("11", "12"),
                navigationContext = mapOf("id" to "11"),
                collectionComplete = false,
            ).batches.isEmpty(),
        )
    }

    private fun schema(
        resource: ResourceSpec,
        vararg actions: ActionSpec,
    ) = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity("fixture", "Fixture", "1"),
        confidence = Confidence.verified,
        resources = listOf(resource),
        actions = actions.toList(),
    )

    private fun resource() = ResourceSpec(
        id = "items",
        name = "Items",
        confidence = Confidence.verified,
        fields = listOf("canEdit", "canDelete").map { id ->
            FieldSpec(
                id = id,
                label = id,
                kind = FieldKind.boolean,
                readOnly = true,
                required = false,
            )
        },
    )

    private fun readAction(
        path: String,
        pathFields: List<String> = emptyList(),
    ) = action(
        id = "read-items",
        method = HttpMethod.GET,
        path = path,
        pathFields = pathFields,
        intent = ActionIntent.list,
        effect = ActionEffect.list,
        risk = ActionRisk.readOnly,
    )

    private fun batchAction(
        path: String,
        pathFields: List<String>,
    ) = action(
        id = "batch-items",
        method = HttpMethod.POST,
        path = path,
        pathFields = pathFields,
        intent = ActionIntent.execute,
        effect = ActionEffect.batch,
        risk = ActionRisk.mutating,
        bodyFieldNames = listOf("itemIds"),
    )

    private fun action(
        id: String,
        method: HttpMethod,
        path: String,
        pathFields: List<String>,
        intent: ActionIntent,
        effect: ActionEffect,
        risk: ActionRisk,
        bodyFieldNames: List<String> = emptyList(),
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "items",
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = pathFields,
            requiredPathParameterNames = pathFields,
            bodyFieldNames = bodyFieldNames,
            bodyContentType = bodyFieldNames.takeIf(List<String>::isNotEmpty)
                ?.let { "application/json" },
            bodySchema = bodyFieldNames.takeIf(List<String>::isNotEmpty)?.let {
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        put(
                            "itemIds",
                            buildJsonObject {
                                put("type", "array")
                                put("default", buildJsonArray {})
                                putJsonObject("items") {
                                    put("type", "integer")
                                    put("format", "int64")
                                }
                            },
                        )
                    }
                }
            },
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = false,
        confidence = Confidence.verified,
        effect = effect,
    )

    private fun records(vararg ids: String): List<NativeRecord> = ids.map { id ->
        NativeRecord(
            id = id,
            values = mapOf(
                "id" to id,
                "canEdit" to "true",
                "canDelete" to "true",
            ),
            bindingContext = mapOf("houseId" to "house-7"),
        )
    }
}
