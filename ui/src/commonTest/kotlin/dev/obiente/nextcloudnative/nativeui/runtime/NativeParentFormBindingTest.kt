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
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

class NativeParentFormBindingTest {
    @Test
    fun `nested form binds relationships from selected parent instead of observed child row`() {
        val selectedHouse = NativeRecord(
            id = "7",
            values = mapOf("id" to "7", "name" to "Shared home"),
        )
        val observedChecklist = NativeRecord(
            id = "draft",
            values = mapOf("name" to "Observed checklist"),
        )

        assertSame(
            selectedHouse,
            nativeFormBindingRecord(
                initialRecord = observedChecklist,
                parentResourceId = "houses",
                parentRecord = selectedHouse,
            ),
        )
    }

    @Test
    fun `pantry checklist create keeps selected house in its hidden path binding`() {
        val resource = ResourceSpec(
            id = "lists",
            name = "Lists",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("id", "ID", FieldKind.integer, required = true, readOnly = true),
                FieldSpec("houseId", "House", FieldKind.integer, required = true, readOnly = false),
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
                FieldSpec("description", "Description", FieldKind.string, required = true, readOnly = false),
                FieldSpec("icon", "Icon", FieldKind.string, required = true, readOnly = false),
                FieldSpec("color", "Color", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "checklist-create-list",
            label = "Create a checklist in a house",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/ocs/v2.php/apps/pantry/api/houses/{houseId}/lists",
                operationId = "checklist-create-list",
                pathParameterNames = listOf("houseId"),
                requiredPathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("name", "description", "icon", "color"),
                requiredBodyFieldNames = listOf("name"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("icon", buildJsonObject { put("type", "string") })
                        put("color", buildJsonObject { put("type", "string") })
                    },
                )
                put("required", buildJsonArray {
                    add(JsonPrimitive("name"))
                })
            },
        )
        val selectedHouse = NativeRecord(
            id = "7",
            values = mapOf("id" to "7", "name" to "Shared home"),
        )

        val schema = dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema(
            schemaVersion = "0.1",
            app = dev.obiente.nextcloudnative.nativeui.model.AppIdentity(
                id = "pantry",
                name = "Pantry",
                version = "test",
            ),
            confidence = Confidence.verified,
            resources = listOf(resource),
            views = listOf(
                dev.obiente.nextcloudnative.nativeui.model.ViewSpec(
                    id = "checklist-create-list.form",
                    title = "Create a checklist in a house",
                    resourceId = resource.id,
                    component = dev.obiente.nextcloudnative.nativeui.model.NativeComponent.form,
                    sourceActionId = action.id,
                    confidence = Confidence.verified,
                ),
            ),
            actions = listOf(action),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = "houses",
                    childResourceId = resource.id,
                    parentFieldId = "id",
                    childFieldId = "houseId",
                    confidence = Confidence.verified,
                ),
            ),
        )
        val autoBound = nativeFormAutoBoundValues(
            schema = schema,
            action = action,
            resource = resource,
            record = selectedHouse,
            parentResourceId = "houses",
        )
        val visibleFields = editableNativeFields(resource, action)
            .filterNot { field -> field.id in autoBound }
        val values = initialNativeFormDraft(resource, action)
            .values + autoBound + ("name" to "Groceries")
        val request = assertIs<NativeActionRequest.Submit>(
            assertIs<NativeRequestBuildResult.Ready>(
                buildNativeSubmitRequest(
                    schema = schema,
                    view = schema.views.single(),
                    values = values + ("undeclared" to "must not reach the request"),
                    confirmed = false,
                ),
            ).request,
        )

        assertEquals(mapOf("houseId" to "7"), autoBound)
        assertEquals(listOf("name", "description", "icon", "color"), visibleFields.map(FieldSpec::id))
        assertEquals(
            mapOf("name" to true, "description" to false, "icon" to false, "color" to false),
            visibleFields.associate { field -> field.id to field.required },
        )
        assertFalse("houseId" in visibleFields.map(FieldSpec::id))
        assertEquals(emptyMap(), validateNativeForm(resource, action, values).errors)
        assertEquals("7", request.values["houseId"])
        assertEquals("Groceries", request.values["name"])
        assertFalse("undeclared" in request.values)
    }

    @Test
    fun `response id cannot replace an unsafe selected parent identity`() {
        val resource = ResourceSpec(
            id = "folders",
            name = "Folders",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("houseId", "House", FieldKind.integer, required = true, readOnly = false),
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "create-photo-folder",
            label = "Create a photo folder",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/houses/{houseId}/photos/folders",
                operationId = "create-photo-folder",
                pathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("houseId", buildJsonObject { put("type", "integer") })
                        put("name", buildJsonObject { put("type", "string") })
                    },
                )
                put("required", buildJsonArray {
                    add(JsonPrimitive("houseId"))
                    add(JsonPrimitive("name"))
                })
            },
        )
        val selectedParent = NativeRecord(
            id = "synthetic collection row",
            values = mapOf("id" to "7", "name" to "Shared collection"),
            actionSafeIdentity = false,
        )

        val resolution = nativeFormAutoBindingResolution(
            schema = bindingSchema(resource, action, "houses", "houseId"),
            action = action,
            resource = resource,
            record = selectedParent,
            parentResourceId = "houses",
        )

        assertEquals(emptyMap(), resolution.values)
        assertEquals(
            "This action cannot be linked because the selected parent identity could not be verified.",
            resolution.error,
        )
    }

    @Test
    fun `verified navigation context can bind an empty child collection`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("collectionId", "Collection", FieldKind.integer, required = true, readOnly = false),
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "create-entry",
            label = "Create entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/collections/{collectionId}/entries",
                operationId = "create-entry",
                pathParameterNames = listOf("collectionId"),
                requiredPathParameterNames = listOf("collectionId"),
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )

        val resolution = nativeFormAutoBindingResolution(
            schema = bindingSchema(resource, action, "collections", "collectionId"),
            action = action,
            resource = resource,
            record = null,
            parentResourceId = "collections",
            navigationValues = mapOf("collectionId" to "7"),
        )

        assertNull(resolution.error)
        assertEquals(mapOf("collectionId" to "7"), resolution.values)

        val withoutDeclaredRelationship = nativeFormAutoBindingResolution(
            schema = bindingSchema(resource, action),
            action = action,
            resource = resource,
            record = NativeRecord(
                id = "selected-child-9",
                values = mapOf("id" to "selected-child-9"),
                bindingContext = mapOf("id" to "unrelated-parent-7"),
            ),
            parentResourceId = "collections",
            navigationValues = mapOf(
                "id" to "unrelated-parent-7",
                "collectionId" to "7",
            ),
        )

        assertNull(withoutDeclaredRelationship.error)
        assertEquals(mapOf("collectionId" to "7"), withoutDeclaredRelationship.values)
    }

    @Test
    fun `conflicting parent provenance disables automatic form binding`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("collectionId", "Collection", FieldKind.integer, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "create-entry",
            label = "Create entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/collections/{collectionId}/entries",
                operationId = "create-entry",
                pathParameterNames = listOf("collectionId"),
                requiredPathParameterNames = listOf("collectionId"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )

        val resolution = nativeFormAutoBindingResolution(
            schema = bindingSchema(resource, action, "collections", "collectionId"),
            action = action,
            resource = resource,
            record = NativeRecord(
                id = "7",
                values = mapOf("id" to "7"),
                bindingContext = mapOf("collectionId" to "7"),
            ),
            parentResourceId = "collections",
            navigationValues = mapOf("collectionId" to "11"),
        )

        assertEquals(emptyMap(), resolution.values)
        assertEquals(
            "This action cannot be linked because the selected item no longer matches the navigation context.",
            resolution.error,
        )
    }

    @Test
    fun `unrelated selected records cannot satisfy a parent relation`() {
        val resource = ResourceSpec(
            id = "checklists",
            name = "Checklists",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("houseId", "House", FieldKind.integer, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "create-checklist",
            label = "Create checklist",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/checklists",
                operationId = "create-checklist",
                bodyFieldNames = listOf("houseId"),
                requiredBodyFieldNames = listOf("houseId"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("houseId", buildJsonObject { put("type", "integer") })
                })
            },
        )

        assertEquals(
            emptyMap(),
            nativeFormAutoBoundValues(
                schema = bindingSchema(resource, action),
                action = action,
                resource = resource,
                record = NativeRecord("message-1", mapOf("id" to "message-1")),
                parentResourceId = "messages",
            ),
        )
    }

    @Test
    fun `selected parent fields do not prefill a child create form`() {
        val resource = ResourceSpec(
            id = "folders",
            name = "Folders",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("houseId", "House", FieldKind.integer, required = true, readOnly = false),
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val action = ActionSpec(
            id = "create-photo-folder",
            label = "Create a photo folder",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/houses/{houseId}/photos/folders",
                operationId = "create-photo-folder",
                pathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val selectedHouse = NativeRecord(
            id = "7",
            values = mapOf("id" to "7", "name" to "Shared home"),
        )

        val prefillRecord = nativeFormPrefillRecord(
            action = action,
            resource = resource,
            record = selectedHouse,
            parentResourceId = "houses",
        )

        assertNull(prefillRecord)
        assertEquals("", initialNativeFormDraft(resource, action, prefillRecord).values["name"])
    }

    @Test
    fun `same resource and update forms retain their record prefill`() {
        val resource = ResourceSpec(
            id = "settings",
            name = "Settings",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val record = NativeRecord("settings", mapOf("name" to "Existing value"))
        val createAction = ActionSpec(
            id = "create-settings",
            label = "Create settings",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/settings",
                operationId = "create-settings",
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val updateAction = createAction.copy(
            id = "update-settings",
            label = "Update settings",
            intent = ActionIntent.update,
            binding = createAction.binding.copy(
                method = HttpMethod.PUT,
                operationId = "update-settings",
            ),
        )

        assertSame(
            record,
            nativeFormPrefillRecord(
                action = createAction,
                resource = resource,
                record = record,
                parentResourceId = "settings",
            ),
        )
        assertSame(
            record,
            nativeFormPrefillRecord(
                action = updateAction,
                resource = resource,
                record = record,
                parentResourceId = "houses",
            ),
        )
    }

    private fun bindingSchema(
        resource: ResourceSpec,
        action: ActionSpec,
        parentResourceId: String? = null,
        childFieldId: String? = null,
    ): NativeAppSchema = NativeAppSchema(
        schemaVersion = "test",
        app = AppIdentity("synthetic", "Synthetic", "test"),
        confidence = Confidence.verified,
        resources = listOf(resource),
        actions = listOf(action),
        relationships = if (parentResourceId != null && childFieldId != null) {
            listOf(
                ResourceRelationshipSpec(
                    parentResourceId = parentResourceId,
                    childResourceId = resource.id,
                    parentFieldId = "id",
                    childFieldId = childFieldId,
                    confidence = Confidence.verified,
                ),
            )
        } else {
            emptyList()
        },
    )
}
