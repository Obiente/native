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
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class NativeCollectionActionsTest {
    @Test
    fun `bodyless collection empty command requires exact active read and confirmation`() {
        val resource = resource("items")
        val read = readAction(
            resourceId = resource.id,
            path = "/groups/{groupId}/items/trash",
            pathFields = listOf("groupId"),
        )
        val empty = action(
            id = "empty-items",
            resourceId = resource.id,
            method = HttpMethod.DELETE,
            path = read.binding.path,
            pathFields = listOf("groupId"),
            intent = ActionIntent.delete,
            effect = ActionEffect.empty,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
        )

        val plan = assertNotNull(
            nativeCollectionActions(
                schema = schema(resource, read, empty),
                activeReadAction = read,
                resource = resource,
                records = emptyList(),
                navigationContext = mapOf("groupId" to "7"),
                collectionComplete = true,
            ).commands.singleOrNull(),
        )

        assertEquals(ActionEffect.empty, plan.effect)
        assertFailsWith<IllegalArgumentException> { plan.request(confirmed = false) }
        assertEquals(
            mapOf("groupId" to "7"),
            plan.request(confirmed = true).values,
        )
    }

    @Test
    fun `presentation-only observed fields cannot suppress canonical collection actions`() {
        val canonicalResource = resource("items")
        val presentationResource = canonicalResource.copy(
            fields = listOf(
                FieldSpec(
                    id = "observedLabel",
                    label = "Observed label",
                    kind = FieldKind.string,
                    readOnly = true,
                    required = false,
                ),
            ),
        )
        val read = readAction(
            resourceId = canonicalResource.id,
            path = "/groups/{groupId}/items",
            pathFields = listOf("groupId"),
        )
        val batch = action(
            id = "batch-items",
            resourceId = canonicalResource.id,
            method = HttpMethod.POST,
            path = "/groups/{groupId}/items/batch",
            pathFields = listOf("groupId"),
            bodyFields = listOf("itemIds"),
            bodySchema = batchBody("itemIds" to integerArraySchema()),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )

        val plan = assertNotNull(
            nativeCollectionActions(
                schema = schema(canonicalResource, read, batch),
                activeReadAction = read,
                resource = presentationResource,
                records = records("11", "12"),
                navigationContext = mapOf("groupId" to "7"),
                collectionComplete = true,
            ).batches.singleOrNull(),
        )

        assertEquals(
            mapOf("groupId" to "7", "itemIds" to "[11,12]"),
            plan.request(listOf("11", "12")).values,
        )
    }

    @Test
    fun `generic parent id from a read route binds its proven resource specific write alias`() {
        val resource = resource("categories")
        val read = readAction(
            resourceId = resource.id,
            path = "/houses/{id}/categories/trash",
            pathFields = listOf("id"),
        )
        val empty = action(
            id = "empty-categories",
            resourceId = resource.id,
            method = HttpMethod.DELETE,
            path = "/houses/{houseId}/categories/trash",
            pathFields = listOf("houseId"),
            intent = ActionIntent.delete,
            effect = ActionEffect.empty,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
        )

        val plan = assertNotNull(
            nativeCollectionActions(
                schema = schema(resource, read, empty),
                activeReadAction = read,
                resource = resource,
                records = emptyList(),
                navigationContext = mapOf("id" to "house-7"),
                collectionComplete = true,
            ).commands.singleOrNull(),
        )

        assertEquals(
            mapOf("houseId" to "house-7"),
            plan.request(confirmed = true).values,
        )
    }

    @Test
    fun `generic parent id does not replace child identities in batch selection`() {
        val resource = resource("items")
        val read = readAction(
            resourceId = resource.id,
            path = "/houses/{id}/items",
            pathFields = listOf("id"),
        )
        val batch = action(
            id = "batch-items",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/houses/{houseId}/items/batch",
            pathFields = listOf("houseId"),
            bodyFields = listOf("itemIds"),
            bodySchema = batchBody("itemIds" to integerArraySchema()),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val records = listOf("11", "12").map { recordId ->
            NativeRecord(
                id = recordId,
                values = mapOf("id" to recordId),
                bindingContext = mapOf("id" to "house-7"),
            )
        }

        val plan = assertNotNull(
            nativeCollectionActions(
                schema = schema(resource, read, batch),
                activeReadAction = read,
                resource = resource,
                records = records,
                navigationContext = mapOf("id" to "house-7"),
                collectionComplete = false,
            ).batches.singleOrNull(),
        )

        assertEquals(
            mapOf("houseId" to "house-7", "itemIds" to "[11,12]"),
            plan.request(listOf("11", "12")).values,
        )
    }

    @Test
    fun `collection command is withheld from a sibling read and ambiguous context`() {
        val resource = resource("items")
        val active = readAction(
            resourceId = resource.id,
            path = "/groups/{groupId}/items",
            pathFields = listOf("groupId"),
        )
        val empty = action(
            id = "empty-items",
            resourceId = resource.id,
            method = HttpMethod.DELETE,
            path = "/groups/{groupId}/items/trash",
            pathFields = listOf("groupId"),
            intent = ActionIntent.delete,
            effect = ActionEffect.clear,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
        )
        val schema = schema(resource, active, empty)

        assertTrue(
            nativeCollectionActions(
                schema,
                active,
                resource,
                emptyList(),
                mapOf("groupId" to "7"),
                collectionComplete = true,
            ).commands.isEmpty(),
        )
        assertEquals(
            NativeCollectionActionCapabilities(emptyList(), null, emptyList()),
            nativeCollectionActions(
                schema,
                active,
                resource,
                emptyList(),
                mapOf("groupId" to "7", "group_id" to "8"),
                collectionComplete = true,
            ),
        )
    }

    @Test
    fun `collection actions reject malformed templates and record context conflicts`() {
        val resource = resource("items")
        val malformedRead = readAction(
            resourceId = resource.id,
            path = "/groups/{groupId/items",
            pathFields = listOf("groupId"),
        )
        val malformedCommand = action(
            id = "empty-items",
            resourceId = resource.id,
            method = HttpMethod.DELETE,
            path = malformedRead.binding.path,
            pathFields = listOf("groupId"),
            intent = ActionIntent.delete,
            effect = ActionEffect.empty,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
        )
        assertEquals(
            NativeCollectionActionCapabilities(emptyList(), null, emptyList()),
            nativeCollectionActions(
                schema(resource, malformedRead, malformedCommand),
                malformedRead,
                resource,
                emptyList(),
                mapOf("groupId" to "7"),
                collectionComplete = true,
            ),
        )

        val read = readAction(
            resourceId = resource.id,
            path = "/groups/{groupId}/items",
            pathFields = listOf("groupId"),
        )
        val batch = action(
            id = "batch-items",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "${read.binding.path}/batch",
            pathFields = listOf("groupId"),
            bodyFields = listOf("itemIds"),
            bodySchema = batchBody("itemIds" to integerArraySchema()),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val conflictingRecord = NativeRecord(
            id = "1",
            values = mapOf("id" to "1"),
            bindingContext = mapOf("groupId" to "8"),
        )
        assertEquals(
            NativeCollectionActionCapabilities(emptyList(), null, emptyList()),
            nativeCollectionActions(
                schema(resource, read, batch),
                read,
                resource,
                listOf(conflictingRecord),
                mapOf("groupId" to "7"),
                collectionComplete = true,
            ),
        )
    }

    @Test
    fun `exact reorder schema produces a complete ordered identity request`() {
        val resource = resource("items")
        val read = readAction(
            resourceId = resource.id,
            path = "/groups/{groupId}/items",
            pathFields = listOf("groupId"),
        )
        val reorder = action(
            id = "reorder-items",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "${read.binding.path}/reorder",
            pathFields = listOf("groupId"),
            bodyFields = listOf("entries"),
            bodySchema = reorderBody("entries", "id", "sortOrder"),
            intent = ActionIntent.execute,
            effect = ActionEffect.reorder,
            risk = ActionRisk.mutating,
        )
        val records = records("11", "12", "13")

        val plan = assertNotNull(
            nativeCollectionActions(
                schema(resource, read, reorder),
                read,
                resource,
                records,
                mapOf("groupId" to "7"),
                collectionComplete = true,
            ).reorder,
        )
        assertEquals("id", plan.identityFieldId)
        assertEquals("sortOrder", plan.orderFieldId)

        val request = plan.request(
            listOf(
                NativeCollectionOrderedIdentity("13", 10),
                NativeCollectionOrderedIdentity("11", 20),
                NativeCollectionOrderedIdentity("12", 30),
            ),
        )
        assertEquals("7", request.values["groupId"])
        val entries = Json.parseToJsonElement(assertNotNull(request.values["entries"])).jsonArray
        assertEquals(listOf(13L, 11L, 12L), entries.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() })
        assertEquals(listOf(10L, 20L, 30L), entries.map {
            it.jsonObject["sortOrder"]!!.jsonPrimitive.content.toLong()
        })
        val derivedEntries = Json.parseToJsonElement(
            assertNotNull(
                plan.requestInOrder(listOf("13", "11", "12")).values["entries"],
            ),
        ).jsonArray
        assertEquals(listOf(13L, 11L, 12L), derivedEntries.map {
            it.jsonObject["id"]!!.jsonPrimitive.content.toLong()
        })
        assertEquals(listOf(0L, 1L, 2L), derivedEntries.map {
            it.jsonObject["sortOrder"]!!.jsonPrimitive.content.toLong()
        })
        assertFailsWith<IllegalArgumentException> {
            plan.request(
                listOf(
                    NativeCollectionOrderedIdentity("11", 10),
                    NativeCollectionOrderedIdentity("12", 20),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.request(
                listOf(
                    NativeCollectionOrderedIdentity("11", 20),
                    NativeCollectionOrderedIdentity("12", 20),
                    NativeCollectionOrderedIdentity("13", 10),
                ),
            )
        }
    }

    @Test
    fun `reorder requires complete bounded collection and one exact schema`() {
        val resource = resource("records")
        val read = readAction(resource.id, "/spaces/{spaceId}/records", listOf("spaceId"))
        val first = action(
            id = "reorder-a",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "${read.binding.path}/order",
            pathFields = listOf("spaceId"),
            bodyFields = listOf("entries"),
            bodySchema = reorderBody("entries", "recordId", "position"),
            intent = ActionIntent.execute,
            effect = ActionEffect.reorder,
            risk = ActionRisk.mutating,
        )
        val second = first.copy(id = "reorder-b", label = "Second reorder")
        val records = records("1", "2")

        assertNull(
            nativeCollectionActions(
                schema(resource, read, first),
                read,
                resource,
                records,
                mapOf("spaceId" to "4"),
                collectionComplete = false,
            ).reorder,
        )
        assertNull(
            nativeCollectionActions(
                schema(resource, read, first, second),
                read,
                resource,
                records,
                mapOf("spaceId" to "4"),
                collectionComplete = true,
            ).reorder,
        )

        val ambiguousShape = first.copy(
            id = "ambiguous-shape",
            binding = first.binding.copy(
                bodySchema = reorderBody(
                    bodyField = "entries",
                    identityField = "recordId",
                    orderField = "position",
                    extraItemField = "label",
                ),
            ),
        )
        assertNull(
            nativeCollectionActions(
                schema(resource, read, ambiguousShape),
                read,
                resource,
                records,
                mapOf("spaceId" to "4"),
                collectionComplete = true,
            ).reorder,
        )
    }

    @Test
    fun `derived reorder positions obey exact integer bounds or withhold the plan`() {
        val resource = resource("records")
        val read = readAction(resource.id, "/spaces/{spaceId}/records", listOf("spaceId"))
        val bounded = action(
            id = "reorder-records",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "${read.binding.path}/order",
            pathFields = listOf("spaceId"),
            bodyFields = listOf("entries"),
            bodySchema = reorderBody(
                bodyField = "entries",
                identityField = "recordId",
                orderField = "position",
                orderMinimum = 10,
                orderMaximum = 11,
            ),
            intent = ActionIntent.execute,
            effect = ActionEffect.reorder,
            risk = ActionRisk.mutating,
        )
        val records = records("1", "2")
        val plan = assertNotNull(
            nativeCollectionActions(
                schema(resource, read, bounded),
                read,
                resource,
                records,
                mapOf("spaceId" to "4"),
                collectionComplete = true,
            ).reorder,
        )
        val entries = Json.parseToJsonElement(
            assertNotNull(plan.requestInOrder(listOf("2", "1")).values["entries"]),
        ).jsonArray
        assertEquals(listOf(10L, 11L), entries.map {
            it.jsonObject["position"]!!.jsonPrimitive.content.toLong()
        })

        val impossible = bounded.copy(
            id = "reorder-impossible",
            binding = bounded.binding.copy(
                bodySchema = reorderBody(
                    bodyField = "entries",
                    identityField = "recordId",
                    orderField = "position",
                    orderMinimum = 10,
                    orderMaximum = 10,
                ),
            ),
        )
        assertNull(
            nativeCollectionActions(
                schema(resource, read, impossible),
                read,
                resource,
                records,
                mapOf("spaceId" to "4"),
                collectionComplete = true,
            ).reorder,
        )
    }

    @Test
    fun `batch planner selects only the self resource identity array`() {
        val resource = resource("items")
        val relatedResource = resource("lists")
        val read = readAction(resource.id, "/houses/{houseId}/items", listOf("houseId"))
        val batch = action(
            id = "move-selected",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/houses/{houseId}/items/batch/move",
            pathFields = listOf("houseId"),
            bodyFields = listOf("itemIds", "targetListIds"),
            bodySchema = batchBody(
                "itemIds" to integerArraySchema(),
                "targetListIds" to integerArraySchema(),
            ),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val relatedSchema = schema(resource, read, batch).copy(
            resources = listOf(resource, relatedResource),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = relatedResource.id,
                    childResourceId = resource.id,
                    parentFieldId = "id",
                    childFieldId = "targetListIds",
                    confidence = Confidence.verified,
                ),
            ),
        )
        val plan = assertNotNull(
            nativeCollectionActions(
                relatedSchema,
                read,
                resource,
                records("31", "32", "33"),
                mapOf("houseId" to "9"),
                collectionComplete = false,
            ).batches.singleOrNull(),
        )

        assertEquals("itemIds", plan.selectionFieldId)
        assertEquals(
            listOf(
                NativeCollectionBatchInputField(
                    id = "targetListIds",
                    kind = NativeCollectionBatchInputKind.IntegerArray,
                    required = false,
                    nullable = false,
                    enumValues = null,
                    relatedResourceId = relatedResource.id,
                ),
            ),
            plan.fields,
        )
        val request = plan.request(
            selectedRecordIds = listOf("32", "31"),
            values = mapOf("targetListIds" to "[44]"),
        )
        assertEquals("[32,31]", request.values["itemIds"])
        assertEquals("[44]", request.values["targetListIds"])
        assertEquals("9", request.values["houseId"])
    }

    @Test
    fun `batch planner withholds nullable inputs until explicit null is representable`() {
        val resource = resource("items")
        val read = readAction(resource.id, "/groups/{groupId}/items", listOf("groupId"))
        val optionalNullable = action(
            id = "update-selected",
            resourceId = resource.id,
            method = HttpMethod.PATCH,
            path = "/groups/{groupId}/items/batch",
            pathFields = listOf("groupId"),
            bodyFields = listOf("itemIds", "note"),
            bodySchema = batchBody(
                "itemIds" to integerArraySchema(),
                "note" to buildJsonObject {
                    put("type", "string")
                    put("nullable", true)
                },
            ),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val optionalPlan = assertNotNull(
            nativeCollectionActions(
                schema(resource, read, optionalNullable),
                read,
                resource,
                records("1", "2"),
                mapOf("groupId" to "7"),
                collectionComplete = false,
            ).batches.singleOrNull(),
        )

        assertTrue(optionalPlan.fields.isEmpty())
        assertEquals(
            mapOf("groupId" to "7", "itemIds" to "[1]"),
            optionalPlan.request(listOf("1")).values,
        )
        assertFailsWith<IllegalArgumentException> {
            optionalPlan.request(listOf("1"), values = mapOf("note" to "null"))
        }

        val requiredNullableBody = buildJsonObject {
            put("type", "object")
            putJsonArray("required") { add(JsonPrimitive("note")) }
            putJsonObject("properties") {
                put("itemIds", integerArraySchema())
                putJsonObject("note") {
                    put("type", "string")
                    put("nullable", true)
                }
            }
        }
        val requiredNullable = optionalNullable.copy(
            id = "require-nullable-note",
            binding = optionalNullable.binding.copy(
                operationId = "require-nullable-note",
                requiredBodyFieldNames = listOf("note"),
                bodySchema = requiredNullableBody,
            ),
        )

        assertTrue(
            nativeCollectionActions(
                schema(resource, read, requiredNullable),
                read,
                resource,
                records("1", "2"),
                mapOf("groupId" to "7"),
                collectionComplete = false,
            ).batches.isEmpty(),
        )
    }

    @Test
    fun `batch planner rejects foreign or ambiguous selection arrays`() {
        val resource = resource("items")
        val read = readAction(resource.id, "/houses/{houseId}/items", listOf("houseId"))
        val foreign = action(
            id = "foreign-selection",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/houses/{houseId}/items/batch",
            pathFields = listOf("houseId"),
            bodyFields = listOf("listIds"),
            bodySchema = batchBody("listIds" to integerArraySchema()),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val ambiguous = foreign.copy(
            id = "ambiguous-selection",
            binding = foreign.binding.copy(
                bodyFieldNames = listOf("ids", "itemIds"),
                bodySchema = batchBody(
                    "ids" to integerArraySchema(),
                    "itemIds" to integerArraySchema(),
                ),
            ),
        )

        listOf(foreign, ambiguous).forEach { action ->
            assertTrue(
                nativeCollectionActions(
                    schema(resource, read, action),
                    read,
                    resource,
                    records("1", "2"),
                    mapOf("houseId" to "7"),
                    collectionComplete = false,
                ).batches.isEmpty(),
            )
        }
    }

    @Test
    fun `destructive batch requires confirmation and active safe records`() {
        val resource = resource("items")
        val read = readAction(resource.id, "/spaces/{spaceId}/items", listOf("spaceId"))
        val batch = action(
            id = "delete-selected",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/spaces/{spaceId}/items/batch/delete",
            pathFields = listOf("spaceId"),
            bodyFields = listOf("itemIds", "permanent"),
            bodySchema = batchBody(
                "itemIds" to integerArraySchema(),
                "permanent" to buildJsonObject {
                    put("type", "boolean")
                    put("default", false)
                },
            ),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
        )
        val records = records("8", "9")
        val plan = assertNotNull(
            nativeCollectionActions(
                schema(resource, read, batch),
                read,
                resource,
                records,
                mapOf("spaceId" to "3"),
                collectionComplete = false,
            ).batches.singleOrNull(),
        )
        assertFailsWith<IllegalArgumentException> {
            plan.request(listOf("8"), mapOf("permanent" to "false"))
        }
        assertTrue(
            plan.request(
                listOf("8"),
                mapOf("permanent" to "false"),
                confirmed = true,
            ).confirmed,
        )
        assertFailsWith<IllegalArgumentException> {
            plan.request(listOf("99"), confirmed = true)
        }

        val unsafeRecords = listOf(
            NativeRecord(
                id = "8",
                values = mapOf("id" to "8"),
                actionSafeIdentity = false,
            ),
        )
        assertEquals(
            NativeCollectionActionCapabilities(emptyList(), null, emptyList()),
            nativeCollectionActions(
                schema(resource, read, batch),
                read,
                resource,
                unsafeRecords,
                mapOf("spaceId" to "3"),
                collectionComplete = false,
            ),
        )
    }

    @Test
    fun `mixed projected resources cannot authorize self resource collection actions`() {
        val resource = resource("items")
        val read = readAction(resource.id, "/groups/{groupId}/items", listOf("groupId"))
        val batch = action(
            id = "batch-items",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/groups/{groupId}/items/batch",
            pathFields = listOf("groupId"),
            bodyFields = listOf("itemIds"),
            bodySchema = batchBody("itemIds" to integerArraySchema()),
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            risk = ActionRisk.mutating,
        )
        val mixed = listOf(
            NativeRecord(
                id = "1",
                values = mapOf(
                    "id" to "1",
                    NATIVE_SYNTHETIC_RESOURCE_FIELD to "other-records",
                ),
            ),
        )

        assertTrue(
            nativeCollectionActions(
                schema(resource, read, batch),
                read,
                resource,
                mixed,
                mapOf("groupId" to "2"),
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

    private fun resource(id: String) = ResourceSpec(
        id = id,
        name = id.replaceFirstChar(Char::uppercaseChar),
        confidence = Confidence.verified,
    )

    private fun readAction(
        resourceId: String,
        path: String,
        pathFields: List<String> = emptyList(),
    ) = action(
        id = "read-$resourceId-${path.hashCode()}",
        resourceId = resourceId,
        method = HttpMethod.GET,
        path = path,
        pathFields = pathFields,
        intent = ActionIntent.list,
        effect = ActionEffect.list,
        risk = ActionRisk.readOnly,
    )

    private fun action(
        id: String,
        resourceId: String,
        method: HttpMethod,
        path: String,
        pathFields: List<String> = emptyList(),
        bodyFields: List<String> = emptyList(),
        requiredBodyFields: List<String> = emptyList(),
        bodySchema: JsonElement? = null,
        intent: ActionIntent,
        effect: ActionEffect,
        risk: ActionRisk,
        requiresConfirmation: Boolean = false,
    ) = ActionSpec(
        id = id,
        label = id.replace('-', ' '),
        resourceId = resourceId,
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = pathFields,
            requiredPathParameterNames = pathFields,
            bodyFieldNames = bodyFields,
            requiredBodyFieldNames = requiredBodyFields,
            bodyContentType = bodySchema?.let { "application/json" },
            bodySchema = bodySchema,
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = requiresConfirmation,
        confidence = Confidence.verified,
        effect = effect,
    )

    private fun records(vararg ids: String): List<NativeRecord> = ids.map { id ->
        NativeRecord(
            id = id,
            values = mapOf("id" to id),
            bindingContext = mapOf("groupId" to "7"),
        )
    }

    private fun reorderBody(
        bodyField: String,
        identityField: String,
        orderField: String,
        extraItemField: String? = null,
        orderMinimum: Long? = null,
        orderMaximum: Long? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(bodyField) {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonArray("required") {
                        add(JsonPrimitive(identityField))
                        add(JsonPrimitive(orderField))
                        extraItemField?.let { add(JsonPrimitive(it)) }
                    }
                    putJsonObject("properties") {
                        putJsonObject(identityField) {
                            put("type", "integer")
                            put("format", "int64")
                        }
                        putJsonObject(orderField) {
                            put("type", "integer")
                            put("format", "int64")
                            orderMinimum?.let { put("minimum", it) }
                            orderMaximum?.let { put("maximum", it) }
                        }
                        extraItemField?.let { field ->
                            putJsonObject(field) { put("type", "string") }
                        }
                    }
                }
            }
        }
    }

    private fun batchBody(
        vararg fields: Pair<String, JsonElement>,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { (fieldId, schema) -> put(fieldId, schema) }
        }
    }

    private fun integerArraySchema(): JsonObject = buildJsonObject {
        put("type", "array")
        put("default", buildJsonArray {})
        putJsonObject("items") {
            put("type", "integer")
            put("format", "int64")
        }
    }
}
