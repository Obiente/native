package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.DynamicSelectedRecordReconciliation
import dev.obiente.nextcloudnative.app.planDynamicMutationRefresh
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicField
import dev.obiente.nextcloudnative.nativeui.model.DynamicForm
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicLayout
import dev.obiente.nextcloudnative.nativeui.model.DynamicLink
import dev.obiente.nextcloudnative.nativeui.model.DynamicLinkTarget
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.FormField
import dev.obiente.nextcloudnative.nativeui.model.HttpBody
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.LayoutKind
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.Provenance
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GenericNestedCollectionWorkflowTest {
    @Test
    fun `verified nested workflow preserves exact bindings and invalidates connected resources`() {
        val descriptor = nestedCollectionDescriptor()
        val navigation = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = COLLECTION_RESOURCE_ID,
                recordId = COLLECTION_ID,
                fieldValues = mapOf("id" to COLLECTION_ID, "title" to "Selected collection"),
                parameterValues = mapOf(COLLECTION_PARAMETER to COLLECTION_ID),
            ),
        )
        val childDestination = navigation.contextualChildDestinations.single()

        assertEquals(ENTRY_RESOURCE_ID, childDestination.resourceId)
        assertEquals(ENTRY_LIST_ACTION_ID, childDestination.actionId)
        assertEquals(mapOf(COLLECTION_PARAMETER to COLLECTION_ID), childDestination.pathParameterValues)

        val schema = descriptor.toNativeAppSchema()
        val collectionResource = requireNotNull(schema.resource(COLLECTION_RESOURCE_ID))
        val entryResource = requireNotNull(schema.resource(ENTRY_RESOURCE_ID))
        val createAction = requireNotNull(schema.action(ENTRY_CREATE_ACTION_ID))
        val createView = schema.views.single { view -> view.sourceActionId == ENTRY_CREATE_ACTION_ID }
        val selectedCollection = NativeRecord(
            id = COLLECTION_ID,
            values = mapOf("id" to COLLECTION_ID, "title" to "Selected collection"),
            bindingContext = childDestination.pathParameterValues,
        )

        assertEquals(
            ResourceRelationshipSpec(
                parentResourceId = collectionResource.id,
                childResourceId = entryResource.id,
                parentFieldId = "id",
                childFieldId = COLLECTION_PARAMETER,
                confidence = Confidence.verified,
            ),
            schema.relationships.single(),
        )

        val parentBinding = nativeFormAutoBindingResolution(
            schema = schema,
            action = createAction,
            resource = entryResource,
            record = selectedCollection,
            parentResourceId = collectionResource.id,
            navigationValues = childDestination.pathParameterValues,
        )
        val visibleCreateFields = editableNativeFields(entryResource, createAction)
            .filterNot { field -> field.id in parentBinding.values }
        val createRequest = assertIs<NativeActionRequest.Submit>(
            assertIs<NativeRequestBuildResult.Ready>(
                buildNativeSubmitRequest(
                    schema = schema,
                    view = createView,
                    values = parentBinding.values + ("title" to "First entry"),
                    confirmed = false,
                ),
            ).request,
        )

        assertNull(parentBinding.error)
        assertEquals(mapOf(COLLECTION_PARAMETER to COLLECTION_ID), parentBinding.values)
        assertEquals(listOf("title"), visibleCreateFields.map(FieldSpec::id))
        assertFalse(COLLECTION_PARAMETER in visibleCreateFields.map(FieldSpec::id))
        assertEquals(
            mapOf(COLLECTION_PARAMETER to COLLECTION_ID, "title" to "First entry"),
            createRequest.values,
        )

        val selectedEntry = NativeRecord(
            id = ENTRY_ID,
            values = mapOf(
                "id" to ENTRY_ID,
                COLLECTION_PARAMETER to COLLECTION_ID,
                "title" to "First entry",
                "complete" to "false",
                "canEdit" to "true",
            ),
            bindingContext = childDestination.pathParameterValues,
        )
        val entryActions = nativeRecordActions(
            schema = schema,
            resource = entryResource,
            record = selectedEntry,
            navigationContext = childDestination.pathParameterValues,
        )
        val completion = requireNotNull(entryActions.completion)
        val completionRequest = completion.request(completed = true)

        assertFalse(completion.currentlyCompleted)
        assertEquals(ENTRY_COMPLETE_ACTION_ID, completion.action.id)
        assertEquals(
            mapOf(ENTRY_PARAMETER to ENTRY_ID, "complete" to "true"),
            completionRequest.values,
        )

        val refresh = requireNotNull(
            schema.planDynamicMutationRefresh(
                action = completion.action,
                selectedRecordResourceId = entryResource.id,
            ),
        )

        assertEquals(
            setOf(COLLECTION_RESOURCE_ID, ENTRY_RESOURCE_ID),
            refresh.affectedResourceIds,
        )
        assertEquals(
            setOf(COLLECTION_VIEW_ID, ENTRY_VIEW_ID, ENTRY_CREATE_VIEW_ID),
            refresh.affectedViewIds,
        )
        assertEquals(
            DynamicSelectedRecordReconciliation.KeepRouteAndReloadWhenVisible,
            refresh.selectedRecordReconciliation,
        )
        assertTrue(refresh.invalidateAllAppScreenSnapshots)
        assertTrue(refresh.reloadVisibleView)
        assertEquals(
            emptyMap(),
            refresh.discardAffectedRelatedRecords(
                mapOf(
                    COLLECTION_RESOURCE_ID to listOf(selectedCollection),
                    ENTRY_RESOURCE_ID to listOf(selectedEntry),
                ),
            ),
        )
    }

    private fun nestedCollectionDescriptor(): DynamicAppDescriptor {
        val collectionResource = dynamicResource(
            id = COLLECTION_RESOURCE_ID,
            label = "Collections",
            fields = listOf(
                dynamicField("id", "ID", FieldKind.string, readOnly = true),
                dynamicField("title", "Title", FieldKind.string),
            ),
        )
        val entryResource = dynamicResource(
            id = ENTRY_RESOURCE_ID,
            label = "Entries",
            fields = listOf(
                dynamicField("id", "ID", FieldKind.string, readOnly = true),
                dynamicField(COLLECTION_PARAMETER, "Collection", FieldKind.string, readOnly = true),
                dynamicField("title", "Title", FieldKind.string),
                dynamicField("complete", "Complete", FieldKind.boolean),
                dynamicField("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
            ),
        )
        val actions = listOf(
            readAction(
                id = COLLECTION_LIST_ACTION_ID,
                resourceId = COLLECTION_RESOURCE_ID,
                path = "/api/collections",
            ),
            readAction(
                id = ENTRY_LIST_ACTION_ID,
                resourceId = ENTRY_RESOURCE_ID,
                path = "/api/collections/{$COLLECTION_PARAMETER}/entries",
                pathParameters = listOf(COLLECTION_PARAMETER),
            ),
            mutationAction(
                id = ENTRY_CREATE_ACTION_ID,
                label = "Create entry",
                resourceId = ENTRY_RESOURCE_ID,
                intent = ActionIntent.create,
                method = HttpMethod.POST,
                path = "/api/collections/{$COLLECTION_PARAMETER}/entries",
                pathParameters = listOf(COLLECTION_PARAMETER),
                bodyFields = listOf("title" to "string"),
            ),
            mutationAction(
                id = ENTRY_COMPLETE_ACTION_ID,
                label = "Set completion",
                resourceId = ENTRY_RESOURCE_ID,
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                path = "/api/entries/{$ENTRY_PARAMETER}",
                pathParameters = listOf(ENTRY_PARAMETER),
                bodyFields = listOf("complete" to "boolean"),
            ),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("nested-collection-fixture", "Nested collection fixture", "test"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/api"),
            ),
            resources = listOf(collectionResource, entryResource),
            layouts = listOf(
                DynamicLayout(
                    id = COLLECTION_VIEW_ID,
                    title = "Collections",
                    resourceId = COLLECTION_RESOURCE_ID,
                    kind = LayoutKind.list,
                    sourceActionId = COLLECTION_LIST_ACTION_ID,
                    confidence = Confidence.verified,
                ),
                DynamicLayout(
                    id = ENTRY_VIEW_ID,
                    title = "Entries",
                    resourceId = ENTRY_RESOURCE_ID,
                    kind = LayoutKind.list,
                    sourceActionId = ENTRY_LIST_ACTION_ID,
                    confidence = Confidence.verified,
                ),
            ),
            links = listOf(
                DynamicLink(
                    id = "collections.entries",
                    label = "Entries",
                    resourceId = COLLECTION_RESOURCE_ID,
                    sourceFieldId = "id",
                    target = DynamicLinkTarget.Action(ENTRY_LIST_ACTION_ID),
                    confidence = Confidence.verified,
                ),
            ),
            forms = listOf(
                DynamicForm(
                    id = ENTRY_CREATE_VIEW_ID,
                    title = "Create entry",
                    resourceId = ENTRY_RESOURCE_ID,
                    actionId = ENTRY_CREATE_ACTION_ID,
                    fields = listOf(
                        FormField(
                            fieldId = "title",
                            label = "Title",
                            kind = FieldKind.string,
                            required = true,
                        ),
                    ),
                    confidence = Confidence.verified,
                ),
            ),
            actions = actions,
        )
    }

    private fun dynamicResource(
        id: String,
        label: String,
        fields: List<DynamicField>,
    ): DynamicResource = DynamicResource(
        id = id,
        label = label,
        collection = true,
        fields = fields,
        confidence = Confidence.verified,
    )

    private fun dynamicField(
        id: String,
        label: String,
        kind: FieldKind,
        readOnly: Boolean = false,
    ): DynamicField = DynamicField(
        id = id,
        label = label,
        kind = kind,
        required = true,
        readOnly = readOnly,
        nullable = false,
        multiple = false,
        confidence = Confidence.verified,
    )

    private fun readAction(
        id: String,
        resourceId: String,
        path: String,
        pathParameters: List<String> = emptyList(),
    ): DynamicAction = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = path,
            pathParameters = pathParameters.map(::pathParameter),
        ),
        confidence = Confidence.verified,
    )

    private fun mutationAction(
        id: String,
        label: String,
        resourceId: String,
        intent: ActionIntent,
        method: HttpMethod,
        path: String,
        pathParameters: List<String>,
        bodyFields: List<Pair<String, String>>,
    ): DynamicAction = DynamicAction(
        id = id,
        label = label,
        resourceId = resourceId,
        intent = intent,
        risk = ActionRisk.mutating,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = method,
            path = path,
            pathParameters = pathParameters.map(::pathParameter),
            body = HttpBody(
                contentType = "application/json",
                required = true,
                schema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            bodyFields.forEach { (field, type) ->
                                put(field, buildJsonObject { put("type", type) })
                            }
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            bodyFields.forEach { (field, _) -> add(JsonPrimitive(field)) }
                        },
                    )
                },
            ),
        ),
        confidence = Confidence.verified,
        provenance = listOf(
            Provenance(
                kind = ProvenanceKind.advertisedOpenApi,
                source = "https://cloud.example.test/api/openapi.json",
                detail = "Synthetic verified mutation contract",
            ),
        ),
    )

    private fun pathParameter(name: String): HttpParameter = HttpParameter(
        name = name,
        required = true,
        schema = buildJsonObject { put("type", "string") },
        source = ParameterSource.resourceField,
    )

    private companion object {
        const val COLLECTION_RESOURCE_ID = "collections"
        const val ENTRY_RESOURCE_ID = "entries"
        const val COLLECTION_PARAMETER = "collectionId"
        const val ENTRY_PARAMETER = "entryId"
        const val COLLECTION_ID = "collection-4"
        const val ENTRY_ID = "entry-9"
        const val COLLECTION_LIST_ACTION_ID = "list-collections"
        const val ENTRY_LIST_ACTION_ID = "list-entries"
        const val ENTRY_CREATE_ACTION_ID = "create-entry"
        const val ENTRY_COMPLETE_ACTION_ID = "complete-entry"
        const val COLLECTION_VIEW_ID = "collections.list"
        const val ENTRY_VIEW_ID = "entries.list"
        const val ENTRY_CREATE_VIEW_ID = "entries.create"
    }
}
