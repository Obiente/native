package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DynamicDescriptorMapperTest {
    @Test
    fun retainsValidatedBodySchemaForSemanticNativeWrites() {
        val bodySchema = Json.parseToJsonElement(
            """{"type":"object","properties":{"data":{"oneOf":[{"type":"string"},{"type":"object"}]}}}""",
        )
        val action = DynamicAction(
            id = "rows.update",
            label = "Update row",
            resourceId = "rows",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            binding = DynamicHttpBinding(
                method = HttpMethod.PUT,
                path = "/api/rows/{rowId}",
                pathParameters = listOf(
                    HttpParameter(
                        name = "rowId",
                        required = true,
                        schema = Json.parseToJsonElement("""{"type":"integer"}"""),
                        source = ParameterSource.resourceField,
                    ),
                ),
                body = HttpBody("application/json", true, bodySchema),
            ),
            confidence = Confidence.verified,
            provenance = listOf(
                Provenance(
                    kind = ProvenanceKind.advertisedOpenApi,
                    source = "https://cloud.example.test/openapi.json",
                    detail = "PUT /api/rows/{rowId}",
                ),
            ),
        )
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("tables", "Tables", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(resource("rows", fields("id") + field("data", FieldKind.objectValue))),
            actions = listOf(action),
        )

        assertEquals(bodySchema, descriptor.toNativeAppSchema().actions.single().binding.bodySchema)
    }

    @Test
    fun synthesizesCompositeGridFromRelatedDefinitionAndEntryShapes() {
        val container = resource("catalogs", fields("id", "title"))
        val definitions = resource(
            "attributes",
            fields("id", "alias", "title", "type", "position"),
        )
        val entries = resource(
            "entries",
            fields("id", "title") + field("values", FieldKind.objectValue),
        )
        val definitionAction = readAction("list-attributes", definitions.id)
        val entryAction = readAction("list-entries", entries.id)
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("catalog", "Catalog", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(container, definitions, entries),
            links = listOf(
                actionLink("catalogs.attributes", container.id, definitionAction.id),
                actionLink("catalogs.entries", container.id, entryAction.id),
            ),
            actions = listOf(definitionAction, entryAction),
        )

        val view = descriptor.toNativeAppSchema().views.single { it.compositeDataGrid != null }
        val composite = assertNotNull(view.compositeDataGrid)

        assertEquals("catalogs.table", view.id)
        assertEquals("Table", view.title)
        assertEquals(NativeComponent.dataTable, view.component)
        assertEquals("entries", view.resourceId)
        assertEquals("attributes", composite.columnResourceId)
        assertEquals("entries", composite.rowResourceId)
        assertEquals("list-attributes", composite.columnSourceActionId)
        assertEquals("list-entries", composite.rowSourceActionId)
        assertEquals("id", composite.columnIdentityFieldId)
        assertEquals("alias", composite.columnAliasFieldId)
        assertEquals("title", composite.columnTitleFieldId)
        assertEquals("type", composite.columnTypeFieldId)
        assertEquals("position", composite.columnOrderFieldId)
        assertEquals("values", composite.rowCellMapFieldId)
    }

    @Test
    fun mapsHierarchyLinksToTypedForeignKeyRelationships() {
        val parent = resource("stacks", fields("id", "title"))
        val child = resource("cards", fields("id", "title", "stackId"))
        val action = DynamicAction(
            id = "list-cards",
            label = "List cards",
            resourceId = "cards",
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(HttpMethod.GET, "/api/cards"),
            confidence = Confidence.high,
        )
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("workflow", "Workflow", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(parent, child),
            links = listOf(
                DynamicLink(
                    id = "stacks.cards",
                    label = "Cards",
                    resourceId = "stacks",
                    sourceFieldId = "id",
                    target = DynamicLinkTarget.Action("list-cards"),
                    confidence = Confidence.high,
                ),
            ),
            actions = listOf(action),
        )

        assertEquals(
            ResourceRelationshipSpec("stacks", "cards", "id", "stackId", Confidence.high),
            descriptor.toNativeAppSchema().relationships.single(),
        )
    }

    @Test
    fun infersUnambiguousSiblingForeignKeysWithoutAppSpecificRelationshipAdapters() {
        val categories = resource("categories", fields("id", "name"))
        val stores = resource("stores", fields("id", "name"))
        val entries = resource(
            "entries",
            fields("id", "name", "categoryId") +
                field("storeIds", FieldKind.integer, format = DYNAMIC_INTEGER_ARRAY_FORMAT),
        )
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("example", "Example", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(categories, stores, entries),
        )

        assertEquals(
            setOf(
                ResourceRelationshipSpec("categories", "entries", "id", "categoryId", Confidence.high),
                ResourceRelationshipSpec("stores", "entries", "id", "storeIds", Confidence.high),
            ),
            descriptor.toNativeAppSchema().relationships.toSet(),
        )
    }

    @Test
    fun inferredForeignKeysNeverBecomeVerifiedWriteEvidence() {
        val parents = resource("workspaces", fields("id", "name")).copy(
            confidence = Confidence.verified,
            fields = fields("id", "name").map { field -> field.copy(confidence = Confidence.verified) },
        )
        val children = resource("entries", fields("id", "workspaceId", "title")).copy(
            confidence = Confidence.verified,
            fields = fields("id", "workspaceId", "title").map { field ->
                field.copy(confidence = Confidence.verified)
            },
        )
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("example", "Example", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(parents, children),
        )

        assertEquals(
            ResourceRelationshipSpec("workspaces", "entries", "id", "workspaceId", Confidence.high),
            descriptor.toNativeAppSchema().relationships.single(),
        )
    }

    @Test
    fun ambiguousForeignKeyResourceNamesDoNotCreateRelationshipEvidence() {
        val category = resource("category", fields("id", "name"))
        val categories = resource("categories", fields("id", "name"))
        val entries = resource("entries", fields("id", "name", "categoryId"))
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("example", "Example", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/api")),
            resources = listOf(category, categories, entries),
        )

        assertEquals(emptyList(), descriptor.toNativeAppSchema().relationships)
    }

    @Test
    fun selectsSpecializedNativeComponentsFromSemanticAndFieldEvidence() {
        assertEquals(NativeComponent.dataTable, component("rows", fields("id", "columnId", "value")))
        assertEquals(NativeComponent.board, component("cards", fields("id", "title", "stackId")))
        assertEquals(
            NativeComponent.board,
            component("work-items", fields("id", "title", "stage", "position")),
        )
        assertEquals(
            NativeComponent.taskList,
            component(
                "items",
                fields("id", "name", "listId") +
                    field("sortOrder", FieldKind.integer) +
                    field("done", FieldKind.boolean),
            ),
        )
        assertEquals(
            NativeComponent.dashboard,
            component(
                "transactions",
                fields("id", "description", "category") + field("amount", FieldKind.decimal),
            ),
        )
        assertEquals(
            NativeComponent.dashboard,
            component("accounts", fields("id", "name") + field("balance", FieldKind.decimal)),
        )
        assertEquals(
            NativeComponent.dashboard,
            component("projects", fields("id", "name") + field("total_spent", FieldKind.decimal)),
        )
        assertEquals(
            NativeComponent.dashboard,
            component("categories", fields("id", "name") + field("budgetAmount", FieldKind.decimal)),
        )
        assertEquals(
            NativeComponent.dataTable,
            component(
                "transactions",
                fields("id") +
                    field("amount", FieldKind.decimal) +
                    field("dataByAlias", FieldKind.objectValue),
            ),
        )
        assertEquals(NativeComponent.taskList, component("tasks", fields("id", "title", "completed", "dueDate")))
        assertEquals(NativeComponent.calendar, component("events", fields("id", "summary", "start", "end")))
        assertEquals(
            NativeComponent.contactList,
            component("contacts", fields("id", "fn", "email", "tel", "org")),
        )
        assertEquals(
            NativeComponent.conversationList,
            component("conversations", fields("token", "displayName", "lastMessage")),
        )
        assertEquals(NativeComponent.chatThread, component("messages", fields("id", "actorId", "message")))
        assertEquals(
            NativeComponent.mediaGrid,
            component("photos", fields("id") + field("preview", FieldKind.image)),
        )
        assertEquals(NativeComponent.recipeList, component("recipes", fields("id", "title", "ingredients")))
    }

    @Test
    fun preservesOrdinaryListAndDetailFallbacks() {
        assertEquals(NativeComponent.collectionList, component("bills", fields("id", "amount", "what")))
        assertEquals(
            NativeComponent.detail,
            component("rows", fields("id", "columnId", "value"), LayoutKind.detail),
        )
    }

    private fun component(
        resourceId: String,
        fields: List<DynamicField>,
        layoutKind: LayoutKind = LayoutKind.list,
    ): NativeComponent {
        val actionId = "get-$resourceId"
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("example", "Example", "1.0"),
            endpointPolicy = EndpointPolicy(
                "https://cloud.example.test",
                listOf("/index.php/apps/example/api"),
            ),
            resources = listOf(
                DynamicResource(
                    id = resourceId,
                    label = resourceId,
                    collection = layoutKind == LayoutKind.list,
                    fields = fields,
                    confidence = Confidence.high,
                ),
            ),
            layouts = listOf(
                DynamicLayout(
                    id = "$resourceId.${layoutKind.name}",
                    title = resourceId,
                    resourceId = resourceId,
                    kind = layoutKind,
                    sourceActionId = actionId,
                    confidence = Confidence.high,
                ),
            ),
            actions = listOf(
                DynamicAction(
                    id = actionId,
                    label = "Get $resourceId",
                    resourceId = resourceId,
                    intent = if (layoutKind == LayoutKind.list) ActionIntent.list else ActionIntent.read,
                    risk = ActionRisk.readOnly,
                    requiresConfirmation = false,
                    binding = DynamicHttpBinding(
                        method = HttpMethod.GET,
                        path = "/index.php/apps/example/api/$resourceId",
                    ),
                    confidence = Confidence.high,
                ),
            ),
        )
        return descriptor.toNativeAppSchema().views.single().component
    }

    private fun fields(vararg ids: String): List<DynamicField> = ids.map { field(it) }

    private fun resource(id: String, fields: List<DynamicField>) = DynamicResource(
        id = id,
        label = id,
        collection = true,
        fields = fields,
        confidence = Confidence.high,
    )

    private fun readAction(id: String, resourceId: String) = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(HttpMethod.GET, "/api/$resourceId"),
        confidence = Confidence.high,
    )

    private fun actionLink(id: String, resourceId: String, actionId: String) = DynamicLink(
        id = id,
        label = id,
        resourceId = resourceId,
        sourceFieldId = "id",
        target = DynamicLinkTarget.Action(actionId),
        confidence = Confidence.high,
    )

    private fun field(
        id: String,
        kind: FieldKind = FieldKind.string,
        format: String? = null,
    ) = DynamicField(
        id = id,
        label = id,
        kind = kind,
        format = format,
        required = false,
        readOnly = true,
        nullable = true,
        multiple = false,
        confidence = Confidence.high,
    )
}
