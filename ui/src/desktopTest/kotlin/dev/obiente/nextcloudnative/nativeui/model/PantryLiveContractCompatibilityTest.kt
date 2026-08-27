package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.app.dynamicCollectionState
import dev.obiente.nextcloudnative.app.dynamicContextualFormTargetsActiveSurface
import dev.obiente.nextcloudnative.app.dynamicFormRelationLoadRequests
import dev.obiente.nextcloudnative.app.dynamicReadBindingContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecordAuthorityContext
import dev.obiente.nextcloudnative.nativeui.runtime.editableNativeFields
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCollectionActions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import dev.obiente.nextcloudnative.nativeui.runtime.uneditableNativeBodyFieldIds
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PantryLiveContractCompatibilityTest {
    @Test
    fun `signed Pantry item category relationship resolves within its selected house`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val schema = descriptor.toNativeAppSchema()
        val itemCreate = descriptor.action("checklist-add-item")
        val categoryRead = descriptor.action("category-index")
        assertTrue(
            setOf("id", "houseId", "name").all(categoryRead.responseFieldIds::contains),
            "Category read identities or labels are missing from the signed response fields: " +
                categoryRead.responseFieldIds,
        )
        val form = assertNotNull(
            schema.views.singleOrNull { view ->
                view.component == NativeComponent.form &&
                    view.sourceActionId == itemCreate.id
            },
            "Missing item create form.",
        )
        val relationship = assertNotNull(
            schema.relationships.singleOrNull { candidate ->
                candidate.parentResourceId == categoryRead.resourceId &&
                    candidate.childResourceId == itemCreate.resourceId &&
                    candidate.childFieldId == "categoryId"
            },
            "Missing accepted categories to items.categoryId relationship; " +
                "relationships=${schema.relationships}",
        )
        assertTrue(
            relationship.confidence in setOf(Confidence.high, Confidence.verified),
            relationship.toString(),
        )
        assertEquals("id", relationship.parentFieldId, relationship.toString())

        val request = assertNotNull(
            dynamicFormRelationLoadRequests(
                schema = schema,
                formView = form,
                availableValues = mapOf(
                    "id" to "list-9",
                    "listId" to "list-9",
                    "houseId" to "house-7",
                ),
            ).singleOrNull { candidate ->
                candidate.plan.resourceId == categoryRead.resourceId
            },
            "Missing category relation load request.",
        )
        assertEquals(categoryRead.id, request.plan.actionId)
        assertEquals(
            mapOf(categoryRead.binding.pathParameters.single().name to "house-7"),
            request.cacheKey.bindingValues,
        )
    }

    @Test
    fun `signed Pantry item create body has a safe native editor or contextual binding`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val schema = descriptor.toNativeAppSchema()
        val action = assertNotNull(schema.action("checklist-add-item"))
        val resource = assertNotNull(schema.resource(action.resourceId))
        val editable = editableNativeFields(resource, action)
        val uneditable = uneditableNativeBodyFieldIds(
            action = action,
            editableFields = editable,
            autoBoundValues = mapOf(
                "houseId" to "house-7",
                "listId" to "list-9",
            ),
        )

        assertTrue(
            uneditable.isEmpty(),
            "Signed Pantry item creation contains body fields without safe native editors: " +
                uneditable.joinToString() +
                "; schemas=" +
                uneditable.associateWith { fieldId ->
                    val property = (
                        (action.binding.bodySchema as? kotlinx.serialization.json.JsonObject)
                            ?.get("properties") as? kotlinx.serialization.json.JsonObject
                        )?.get(fieldId) as? kotlinx.serialization.json.JsonObject
                    mapOf(
                        "keys" to property?.keys.orEmpty(),
                        "itemKeys" to
                            (property?.get("items") as? kotlinx.serialization.json.JsonObject)
                                ?.keys
                                .orEmpty(),
                        "itemFormat" to
                            (
                                (property?.get("items") as? kotlinx.serialization.json.JsonObject)
                                    ?.get("format") as? kotlinx.serialization.json.JsonPrimitive
                                )?.contentOrNull,
                    )
                },
        )
    }

    @Test
    fun `signed Pantry nullable enum fields remain native choices`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val schema = descriptor.toNativeAppSchema()

        val checklistCreate = assertNotNull(schema.action("checklist-create-list"))
        val checklistResource = assertNotNull(schema.resource(checklistCreate.resourceId))
        val checklistFields = editableNativeFields(checklistResource, checklistCreate)
            .associateBy(FieldSpec::id)
        val checklistColor = assertNotNull(checklistFields["color"])
        val checklistIcon = assertNotNull(checklistFields["icon"])
        assertEquals(FieldKind.enumeration, checklistColor.kind)
        assertEquals(16, assertNotNull(checklistColor.enumValues).size)
        assertTrue(checklistColor.enumValues.orEmpty().all { value ->
            value.matches(Regex("^#[0-9a-fA-F]{6}$"))
        })
        assertEquals(FieldKind.enumeration, checklistIcon.kind)
        assertTrue(assertNotNull(checklistIcon.enumValues).isNotEmpty())

        val noteCreate = assertNotNull(schema.action("note-create-note"))
        val noteResource = assertNotNull(schema.resource(noteCreate.resourceId))
        val noteColor = assertNotNull(
            editableNativeFields(noteResource, noteCreate).singleOrNull { field ->
                field.id == "color"
            },
        )
        assertEquals(FieldKind.enumeration, noteColor.kind)
        assertTrue(assertNotNull(noteColor.enumValues).isNotEmpty())
    }

    @Test
    fun `every signed Pantry mutation is reachable or explicitly blocked by missing contract evidence`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val unreachable = descriptor.unreachablePantryMutations()

        assertEquals(
            mapOf(
                "contextual relationship form" to listOf("share-set-shares"),
            ),
            unreachable,
            unreachable.entries.joinToString(
                prefix = "Signed Pantry mutations have an unexpected generic reachability result:\n",
                separator = "\n",
            ) { (capability, operationIds) ->
                "- $capability: ${operationIds.joinToString(", ")}"
            },
        )
    }

    @Test
    fun `signed Pantry nested Items surface exposes exact record and collection actions`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val schema = descriptor.toNativeAppSchema()
        val houses = descriptor.action("house-index")
        val lists = descriptor.action("checklist-index-lists")
        val items = descriptor.action("checklist-index-items")
        val houseResource = assertNotNull(schema.resource(houses.resourceId))
        val listResource = assertNotNull(schema.resource(lists.resourceId))
        val itemResource = assertNotNull(schema.resource(items.resourceId))

        val selectedHouse = pantryRuntimeRecord(
            readAction = houses,
            resource = houseResource,
            recordId = "7",
            overrides = mapOf("isAdmin" to "true"),
        )
        assertTrue(
            selectedHouse.actionSafeIdentity,
            "House response has no contract-declared runtime identity: ${houses.responseFieldIds}",
        )
        val houseContext = DynamicResourceRecordContext(
            resourceId = houseResource.id,
            recordId = selectedHouse.id,
            fieldValues = selectedHouse.values,
            actionSafeIdentity = selectedHouse.actionSafeIdentity,
        )
        val listDestination = assertNotNull(
            descriptor.planDynamicNavigation(houseContext)
                .contextualChildDestinations
                .singleOrNull { destination -> destination.actionId == lists.id },
            "Selected house did not expose the signed checklist collection.",
        )
        assertEquals(mapOf("id" to "7"), listDestination.pathParameterValues)

        val selectedList = pantryRuntimeRecord(
            readAction = lists,
            resource = listResource,
            recordId = "9",
            bindingContext = listDestination.pathParameterValues,
            overrides = mapOf(
                "name" to "Contract checklist",
                "canEdit" to "true",
            ),
        )
        assertTrue(
            selectedList.actionSafeIdentity,
            "Checklist response has no contract-declared runtime identity: ${lists.responseFieldIds}",
        )
        val listContext = DynamicResourceRecordContext(
            resourceId = listResource.id,
            recordId = selectedList.id,
            fieldValues = selectedList.values,
            parameterValues = listDestination.pathParameterValues,
            actionSafeIdentity = selectedList.actionSafeIdentity,
        )
        val itemDestination = assertNotNull(
            descriptor.planDynamicNavigation(listContext)
                .contextualChildDestinations
                .singleOrNull { destination -> destination.actionId == items.id },
            "Selected checklist did not expose the signed nested Items collection.",
        )
        assertEquals(
            mapOf(
                "id" to "7",
                "houseId" to "7",
                "listId" to "9",
            ),
            itemDestination.pathParameterValues,
        )
        val itemReadBindingContext = dynamicReadBindingContext(
            action = items,
            values = itemDestination.pathParameterValues,
            runtimeContext = itemDestination.pathParameterValues,
        )
        assertEquals(
            mapOf(
                "houseId" to "7",
                "listId" to "9",
            ),
            itemReadBindingContext,
        )
        val itemCollectionCreate = assertNotNull(
            nativeRecordActions(
                schema = schema,
                resource = itemResource,
                navigationContext = itemDestination.pathParameterValues,
            ).create,
            "Signed nested Items collection did not expose checklist-add-item.",
        )
        assertEquals("checklist-add-item", itemCollectionCreate.action.id)

        val itemRecords = listOf("11", "12").map { recordId ->
            pantryRuntimeRecord(
                readAction = items,
                resource = itemResource,
                recordId = recordId,
                bindingContext = itemReadBindingContext,
                overrides = mapOf(
                    "name" to "Contract item $recordId",
                    "canEdit" to "true",
                    "done" to "false",
                    "archived" to null,
                    "archivedAt" to null,
                ).filterKeys(items.responseFieldIds::contains),
            )
        }
        assertTrue(
            itemRecords.all(NativeRecord::actionSafeIdentity),
            "Items response has no contract-declared runtime identity: ${items.responseFieldIds}",
        )
        assertTrue(
            itemRecords.all { record ->
                record.values.keys == items.responseFieldIds.toSet() &&
                    record.bindingContext == itemReadBindingContext
            },
            "Synthetic records must have exactly the fields and parent bindings produced by " +
                "the signed Items read; fields=${items.responseFieldIds}",
        )

        val authority = NativeRecordAuthorityContext(
            parentResource = houseResource,
            parentRecord = selectedHouse,
        )
        val recordActions = nativeRecordActions(
            schema = schema,
            resource = itemResource,
            record = itemRecords.first(),
            navigationContext = itemDestination.pathParameterValues,
            authorityContext = authority,
        )
        val expectedRecordBindings = mapOf(
            "houseId" to "7",
            "listId" to "9",
            "itemId" to "11",
        )
        val edit = assertNotNull(
            recordActions.edit,
            "Signed nested item did not expose checklist-update-item.",
        )
        assertEquals("checklist-update-item", edit.action.id)
        assertEquals(expectedRecordBindings, edit.request(emptyMap()).values)

        val delete = assertNotNull(
            recordActions.delete,
            "Signed nested item did not expose checklist-delete-item with house authority.",
        )
        assertEquals("checklist-delete-item", delete.action.id)
        assertEquals(expectedRecordBindings, delete.request(confirmed = true).values)

        val archive = assertNotNull(
            recordActions.commands.singleOrNull { plan ->
                plan.action.id == "checklist-archive-item"
            },
            "Signed nested item did not expose checklist-archive-item.",
        )
        assertEquals(expectedRecordBindings, archive.request().values)

        val copy = assertNotNull(
            recordActions.commandForms.singleOrNull { plan ->
                plan.action.id == "checklist-copy-item"
            },
            "Signed nested item did not expose checklist-copy-item as a command form.",
        )
        assertEquals(listOf("targetListId"), copy.fields.map(FieldSpec::id))
        assertEquals(
            expectedRecordBindings + ("targetListId" to "10"),
            copy.request(mapOf("targetListId" to "10")).values,
        )

        val collectionActions = nativeCollectionActions(
            schema = schema,
            activeReadAction = assertNotNull(schema.action(items.id)),
            resource = itemResource,
            records = itemRecords,
            navigationContext = itemDestination.pathParameterValues,
            collectionComplete = true,
            authorityContext = authority,
        )
        val batchDelete = assertNotNull(
            collectionActions.batches.singleOrNull { plan ->
                plan.action.id == "checklist-batch-delete-items"
            },
            "Signed nested Items collection did not expose checklist-batch-delete-items.",
        )
        assertEquals("itemIds", batchDelete.selectionFieldId)
        assertEquals(listOf("permanent"), batchDelete.fields.map { field -> field.id })
        assertEquals(
            mapOf(
                "id" to "7",
                "permanent" to "false",
                "itemIds" to "[11,12]",
            ),
            batchDelete.request(
                selectedRecordIds = listOf("11", "12"),
                values = mapOf("permanent" to "false"),
                confirmed = true,
            ).values,
        )

        val reorder = assertNotNull(
            collectionActions.reorder,
            "Signed nested Items collection did not expose checklist-reorder-items.",
        )
        assertEquals("checklist-reorder-items", reorder.action.id)
        val reorderRequest = reorder.requestInOrder(listOf("12", "11"))
        assertEquals("7", reorderRequest.values["houseId"])
        assertEquals("9", reorderRequest.values["listId"])
        assertTrue(
            reorderRequest.values.keys ==
                setOf("houseId", "listId", reorder.action.binding.bodyFieldNames.single()),
            "Reorder request contains undeclared values: ${reorderRequest.values.keys}",
        )
    }

    @Test
    fun `signed Pantry house Items surface exposes exact item edit`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val schema = descriptor.toNativeAppSchema()
        val houses = descriptor.action("house-index")
        val houseItems = descriptor.action("checklist-index-house-items")
        val houseResource = assertNotNull(schema.resource(houses.resourceId))
        val itemResource = assertNotNull(schema.resource(houseItems.resourceId))
        val selectedHouse = pantryRuntimeRecord(
            readAction = houses,
            resource = houseResource,
            recordId = "7",
            overrides = mapOf("isAdmin" to "true"),
        )
        val houseContext = DynamicResourceRecordContext(
            resourceId = houseResource.id,
            recordId = selectedHouse.id,
            fieldValues = selectedHouse.values,
            actionSafeIdentity = selectedHouse.actionSafeIdentity,
        )
        val itemDestination = assertNotNull(
            descriptor.planDynamicNavigation(houseContext)
                .contextualChildDestinations
                .singleOrNull { destination -> destination.actionId == houseItems.id },
        )
        val itemBindingContext = dynamicReadBindingContext(
            action = houseItems,
            values = itemDestination.pathParameterValues,
            runtimeContext = itemDestination.pathParameterValues,
        )
        val itemRecord = pantryRuntimeRecord(
            readAction = houseItems,
            resource = itemResource,
            recordId = "11",
            bindingContext = itemBindingContext,
            overrides = mapOf(
                "name" to "Contract house item",
                "done" to "false",
                "archived" to null,
                "archivedAt" to null,
            ).filterKeys(houseItems.responseFieldIds::contains),
        )
        val actions = nativeRecordActions(
            schema = schema,
            resource = itemResource,
            record = itemRecord,
            navigationContext = itemDestination.pathParameterValues,
            authorityContext = NativeRecordAuthorityContext(
                parentResource = houseResource,
                parentRecord = selectedHouse,
            ),
        )

        assertNotNull(
            actions.edit,
            "House-wide item edit is missing; destination=$itemDestination; " +
                "binding=$itemBindingContext; response=${houseItems.responseFieldIds}; " +
                "record=${itemRecord.values.keys}; commands=${actions.commands.map { it.action.id }}; " +
                "forms=${actions.commandForms.map { it.action.id }}",
        )
    }

    @Test
    fun `signed Pantry contract opens a selected house into its checklist collection`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = signedPantryDescriptor()
        val houses = descriptor.action("house-index")
        val lists = descriptor.action("checklist-index-lists")
        val items = descriptor.action("checklist-index-items")
        val categories = descriptor.action("category-index")
        val notes = descriptor.action("note-index-notes")
        val photos = descriptor.action("photo-index-photos")
        listOf(
            "category-reorder" to ActionEffect.reorder,
            "checklist-reorder-lists" to ActionEffect.reorder,
            "checklist-empty-lists-trash" to ActionEffect.empty,
            "checklist-restore-list" to ActionEffect.restore,
            "checklist-permanently-delete-list" to ActionEffect.permanentDelete,
            "checklist-toggle-item" to ActionEffect.toggle,
            "checklist-copy-item" to ActionEffect.copy,
            "checklist-archive-item" to ActionEffect.archive,
            "checklist-unarchive-item" to ActionEffect.unarchive,
            "checklist-reorder-items" to ActionEffect.reorder,
            "checklist-batch-move-items" to ActionEffect.batch,
            "checklist-batch-archive-items" to ActionEffect.batch,
            "checklist-upload-item-image" to ActionEffect.upload,
            "checklist-clear-item-image" to ActionEffect.clear,
            "house-leave" to ActionEffect.leave,
            "note-restore-note" to ActionEffect.restore,
            "photo-upload-photo" to ActionEffect.upload,
        ).forEach { (operationId, expectedEffect) ->
            assertEquals(expectedEffect, descriptor.action(operationId).effect, operationId)
        }
        listOf(
            "checklist-add-item" to "storeIds",
            "checklist-batch-move-items" to "itemIds",
            "role-set-member-roles" to "roleIds",
        ).forEach { (operationId, fieldId) ->
            val form = assertNotNull(
                descriptor.forms.singleOrNull { candidate -> candidate.actionId == operationId },
                "Missing compiled form for $operationId",
            )
            val field = assertNotNull(
                form.fields.singleOrNull { candidate -> candidate.fieldId == fieldId },
                "Missing compiled field $operationId:$fieldId",
            )
            assertEquals(
                DYNAMIC_INTEGER_ARRAY_FORMAT,
                field.format,
                "$operationId:$fieldId",
            )
        }
        listOf(
            "category-reorder" to "items",
            "share-set-shares" to "shares",
        ).forEach { (operationId, fieldId) ->
            val form = assertNotNull(
                descriptor.forms.singleOrNull { candidate -> candidate.actionId == operationId },
                "Missing compiled form for $operationId",
            )
            val field = assertNotNull(
                form.fields.singleOrNull { candidate -> candidate.fieldId == fieldId },
                "Missing compiled field $operationId:$fieldId",
            )
            assertEquals(
                DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT,
                field.format,
                "$operationId:$fieldId",
            )
        }
        listOf(
            "checklist-reorder-lists",
            "checklist-empty-lists-trash",
            "checklist-restore-list",
            "checklist-permanently-delete-list",
        ).forEach { operationId ->
            assertEquals(lists.resourceId, descriptor.action(operationId).resourceId, operationId)
        }
        listOf(
            "checklist-toggle-item",
            "checklist-copy-item",
            "checklist-archive-item",
            "checklist-unarchive-item",
            "checklist-restore-item",
            "checklist-permanently-delete-item",
            "checklist-reorder-items",
            "checklist-upload-item-image",
            "checklist-clear-item-image",
        ).forEach { operationId ->
            assertEquals(items.resourceId, descriptor.action(operationId).resourceId, operationId)
        }
        assertEquals(categories.resourceId, descriptor.action("category-reorder").resourceId)
        assertEquals(notes.resourceId, descriptor.action("note-restore-note").resourceId)
        assertEquals(photos.resourceId, descriptor.action("photo-restore-photo").resourceId)
        assertEquals(houses.resourceId, descriptor.action("house-leave").resourceId)
        val context = DynamicResourceRecordContext(
            resourceId = houses.resourceId,
            recordId = "house-7",
            fieldValues = mapOf("id" to "house-7"),
            actionSafeIdentity = true,
        )
        val plan = descriptor.planDynamicNavigation(context)
        val preferred = descriptor.preferredSemanticContextualChild(context)
        val userPreferences = descriptor.action("prefs-get-user-prefs")
        val housePreferences = descriptor.action("prefs-get-house-prefs")
        assertTrue(
            plan.rootDestinations.any { destination ->
                destination.actionId == userPreferences.id
            },
            "User preferences must remain an app root.",
        )
        assertTrue(
            plan.contextualChildDestinations.any { destination ->
                destination.actionId == housePreferences.id
            },
            "House preferences must remain a selected-house destination.",
        )
        val housePreferencesDestination = plan.contextualChildDestinations.single { destination ->
            destination.actionId == housePreferences.id
        }
        val housePreferencesPlan = descriptor.planDynamicNavigation(
            context.copy(
                parameterValues = housePreferencesDestination.pathParameterValues,
                currentLayoutId = housePreferencesDestination.layoutId,
            ),
        )
        assertTrue(
            housePreferencesPlan.contextualFormActions.any { formAction ->
                formAction.actionId == "prefs-set-house-prefs"
            },
            "House preferences update is not available on its verified detail surface; " +
                "read=$housePreferences; destination=$housePreferencesDestination; " +
                "forms=${descriptor.forms.filter { form -> form.resourceId == housePreferences.resourceId }}; " +
                "planned=${housePreferencesPlan.contextualFormActions}",
        )
        assertEquals(
            2,
            descriptor.layouts
                .filter { layout -> layout.sourceActionId in setOf(userPreferences.id, housePreferences.id) }
                .map(DynamicLayout::id)
                .distinct()
                .size,
        )
        assertTrue(
            plan.rootDestinations.none { destination ->
                destination.actionId == "house-autocomplete-users"
            },
            "User autocomplete must be consumed as relation data instead of an app root.",
        )
        val evidence = buildString {
            append("children=")
            append(
                plan.contextualChildDestinations.map { destination ->
                    "${destination.resourceId}:${destination.actionId}"
                },
            )
            append(" diagnostics=")
            append(
                descriptor.explainDynamicChildNavigation(context).map { diagnostic ->
                    "${diagnostic.resourceId}:${diagnostic.actionId}:${diagnostic.status}"
                },
            )
            append(" links=")
            append(
                descriptor.links.map { link ->
                    "${link.resourceId}:${(link.target as? DynamicLinkTarget.Action)?.actionId}"
                },
            )
            append(" listAction=")
            append(
                "${lists.resourceId}:${lists.intent}:${lists.risk}:${lists.fallbackOnly}:" +
                    "${lists.binding.path}:${lists.binding.pathParameters.map(HttpParameter::name)}",
            )
        }

        assertTrue(
            plan.contextualChildDestinations.any { destination -> destination.actionId == lists.id },
            evidence,
        )
        val preferredLists = assertNotNull(preferred, evidence)
        assertEquals(lists.id, preferredLists.actionId, evidence)

        val schema = descriptor.toNativeAppSchema()
        val houseResource = assertNotNull(schema.resource(houses.resourceId), evidence)
        val listResource = assertNotNull(schema.resource(lists.resourceId), evidence)
        val itemResource = assertNotNull(schema.resource(items.resourceId), evidence)
        val itemActions = nativeRecordActions(
            schema = schema,
            resource = itemResource,
            record = NativeRecord(
                id = "item-11",
                values = mapOf(
                    "id" to "item-11",
                    "itemId" to "item-11",
                    "listId" to "list-9",
                    "houseId" to "house-7",
                    "name" to "Test item",
                    "done" to "false",
                ),
                bindingContext = mapOf(
                    "houseId" to "house-7",
                    "listId" to "list-9",
                    "itemId" to "item-11",
                ),
            ),
            navigationContext = mapOf(
                "houseId" to "house-7",
                "listId" to "list-9",
            ),
            authorityContext = NativeRecordAuthorityContext(
                parentResource = houseResource,
                parentRecord = NativeRecord(
                    id = "house-7",
                    values = mapOf("id" to "house-7", "isAdmin" to "true"),
                ),
            ),
        )
        val completion = assertNotNull(
            itemActions.completion,
            "The signed Pantry toggle did not become a structural completion action.",
        )
        assertEquals(
            mapOf(
                "houseId" to "house-7",
                "listId" to "list-9",
                "itemId" to "item-11",
            ),
            completion.request(completed = true).values,
        )
        val createEvidence = schema.actions
            .filter { action -> action.resourceId == listResource.id }
            .joinToString { action ->
                "${action.id}:${action.intent}:${action.risk}:${action.confidence}:" +
                    "${action.binding.method}:${action.binding.path}:" +
                    "${action.binding.bodyFieldNames}:${action.binding.requiredBodyFieldNames}"
            }
        val collectionActions = nativeRecordActions(
            schema = schema,
            resource = listResource,
            navigationContext = preferredLists.pathParameterValues,
        )
        assertNotNull(
            collectionActions.create,
            "No structural create plan for ${listResource.id}; actions=$createEvidence " +
                "fields=${listResource.fields.map { field -> "${field.id}:${field.kind}:${field.readOnly}" }}",
        )
        val selectedListActions = nativeRecordActions(
            schema = schema,
            resource = listResource,
            record = NativeRecord(
                id = "list-9",
                values = basePantryAuditRecordValues(
                    resource = listResource,
                    responseFieldIds = lists.responseFieldIds.toSet(),
                    recordId = "list-9",
                    navigationValues = preferredLists.pathParameterValues,
                ) + mapOf(
                    "name" to "Test list",
                    "canEdit" to "true",
                    "houseId" to "house-7",
                ),
                bindingContext = mapOf(
                    "houseId" to "house-7",
                    "listId" to "list-9",
                ),
            ),
            navigationContext = preferredLists.pathParameterValues,
            authorityContext = NativeRecordAuthorityContext(
                parentResource = houseResource,
                parentRecord = NativeRecord(
                    id = "house-7",
                    values = mapOf(
                        "id" to "house-7",
                        "isAdmin" to "true",
                    ),
                ),
            ),
        )
        assertNotNull(
            selectedListActions.edit,
            "No structural edit plan for ${listResource.id}; actions=$createEvidence; fields=" +
                listResource.fields.map { field ->
                    "${field.id}:${field.kind}:${field.readOnly}:${field.format}:${field.enumValues}"
                },
        )
        assertNotNull(
            selectedListActions.delete,
            "No structural delete plan for ${listResource.id}; actions=$createEvidence",
        )

        val selectedListContext = DynamicResourceRecordContext(
            resourceId = lists.resourceId,
            recordId = "list-9",
            fieldValues = mapOf(
                "id" to "list-9",
                "listId" to "list-9",
                "houseId" to "house-7",
            ),
            parameterValues = preferredLists.pathParameterValues,
            actionSafeIdentity = true,
        )
        val listChildren = descriptor.planDynamicNavigation(selectedListContext)
            .contextualChildDestinations
        val listPreferred = descriptor.preferredSemanticContextualChild(selectedListContext)
        val listEvidence = buildString {
            append("children=")
            append(
                listChildren.map { destination ->
                    "${destination.resourceId}:${destination.actionId}:${destination.pathParameterValues}"
                },
            )
            append(" preferred=")
            append(listPreferred)
            append(" diagnostics=")
            append(
                descriptor.explainDynamicChildNavigation(selectedListContext).map { diagnostic ->
                    "${diagnostic.resourceId}:${diagnostic.actionId}:${diagnostic.status}:" +
                        "${diagnostic.missingContextParameters}"
                },
            )
        }
        assertTrue(
            listChildren.none { destination ->
                destination.resourceId in setOf(
                    "categories",
                    "folders",
                    "members",
                    "notes",
                    "photos",
                    "stores",
                )
            },
            listEvidence,
        )
        assertTrue(
            listChildren.any { destination ->
                destination.actionId == "checklist-index-archived-items"
            },
            listEvidence,
        )
        assertEquals("items", assertNotNull(listPreferred, listEvidence).resourceId, listEvidence)
    }

    private fun DynamicAppDescriptor.action(operationId: String): DynamicAction =
        actions.single { action -> action.id == operationId }

    private fun signedPantryDescriptor(): DynamicAppDescriptor = signedPantryDescriptorFixture

    companion object {
        private val signedPantryDescriptorFixture: DynamicAppDescriptor by lazy(
            LazyThreadSafetyMode.SYNCHRONIZED,
        ) {
            val cacheRoot = File(
                System.getProperty("java.io.tmpdir"),
                "nc-native-signed-pantry-contract-test-cache",
            )
            val contract = requireNotNull(
                SignedAppStoreContractAcquirer(
                    catalogCache = FileAppStoreCatalogCache(File(cacheRoot, "catalogs")),
                    verifiedContractCache = FileVerifiedContractCache(File(cacheRoot, "contracts")),
                ).acquire(
                    ContractAcquisitionRequest("pantry", "34.0.1", "0.23.0"),
                ),
            )
            check(contract.sourceKind == OpenApiContractSourceKind.SignedAppPackage)
            check(contract.contractKind == VerifiedContractKind.OpenApi)
            DynamicAppDescriptorCompiler().compile(
                DynamicDiscoveryInput(
                    app = AppIdentity("pantry", "Pantry", "0.23.0"),
                    endpointPolicy = EndpointPolicy(
                        serverOrigin = "https://cloud.example.test",
                        approvedApiPrefixes = listOf("/ocs/v2.php/apps/pantry/api"),
                    ),
                    advertisedOpenApi = AdvertisedOpenApi(
                        documentUrl = contract.sourceUrl,
                        document = Json.parseToJsonElement(contract.document),
                        trust = OpenApiTrust.nextcloudSignedAppPackage,
                    ),
                ),
            )
        }
    }
}

private fun pantryRuntimeRecord(
    readAction: DynamicAction,
    resource: ResourceSpec,
    recordId: String,
    bindingContext: Map<String, String> = emptyMap(),
    overrides: Map<String, String?> = emptyMap(),
): NativeRecord {
    val responseFieldIds = readAction.responseFieldIds
    require(responseFieldIds.distinct().size == responseFieldIds.size)
    require(overrides.keys.all(responseFieldIds::contains)) {
        "Runtime fixture overrides must be declared by ${readAction.id}: " +
            overrides.keys.filterNot(responseFieldIds::contains)
    }
    val identityFieldId = listOf("databaseId", "id", "uuid")
        .firstNotNullOfOrNull { candidate ->
            responseFieldIds.singleOrNull { fieldId ->
                fieldId.equals(candidate, ignoreCase = true)
            }
        }
    val values = responseFieldIds.associateWith { fieldId ->
        when {
            fieldId == identityFieldId -> recordId
            fieldId in overrides -> overrides.getValue(fieldId)
            fieldId in bindingContext -> bindingContext.getValue(fieldId)
            else -> samplePantryAuditValue(
                fieldId = fieldId,
                kind = resource.fields.singleOrNull { field -> field.id == fieldId }?.kind,
            )
        }
    }
    return NativeRecord(
        id = recordId,
        values = values,
        bindingContext = bindingContext,
        actionSafeIdentity = identityFieldId != null,
    )
}

/**
 * Audits invocation paths that exist in the generic shell and renderer, not merely descriptor
 * actions or generated forms. Pantry-specific operation names participate only in the failure
 * report; reachability is derived from generic action intent, navigation, binding, and renderer
 * capability planning.
 */
private fun DynamicAppDescriptor.unreachablePantryMutations(): Map<String, List<String>> {
    val schema = toNativeAppSchema()
    val reachableActionIds = linkedSetOf<String>()
    val rootPlan = planDynamicNavigation()
    reachableActionIds += rootPlan.rootFormActions.map(
        DynamicNavigationFormAction::actionId,
    )
    val collectionSurfaces = ArrayDeque<PantryAuditCollectionSurface>()
    rootPlan.rootDestinations.mapNotNullTo(collectionSurfaces) { destination ->
        destination.takeIf { candidate -> isPantryAuditCollectionDestination(candidate) }
            ?.let(PantryAuditCollectionSurface::from)
    }
    val auditedCollectionStates = linkedSetOf<String>()
    while (collectionSurfaces.isNotEmpty()) {
        val surface = collectionSurfaces.removeFirst()
        val stateKey = "${surface.actionId}:${surface.parameterValues.keys.sorted().joinToString(",")}"
        if (!auditedCollectionStates.add(stateKey)) continue
        val resource = resources.singleOrNull { candidate -> candidate.id == surface.resourceId } ?: continue
        val readAction = actions.singleOrNull { action -> action.id == surface.actionId } ?: continue
        val responseFieldIds = readAction.responseFieldIds.toSet()
        val navigationValues = surface.parameterValues
        val recordBindingValues = dynamicReadBindingContext(
            action = readAction,
            values = navigationValues,
            runtimeContext = navigationValues,
        )
        val fieldValues = buildMap<String, String?> {
            responseFieldIds.forEach { fieldId ->
                put(
                    fieldId,
                    recordBindingValues[fieldId]
                        ?: samplePantryAuditValue(
                            fieldId = fieldId,
                            kind = resource.fields.singleOrNull { field -> field.id == fieldId }?.kind,
                        ),
                )
            }
            put("id", "7")
        }
        val selectedContext = DynamicResourceRecordContext(
            resourceId = resource.id,
            recordId = "7",
            fieldValues = fieldValues,
            parameterValues = navigationValues,
            actionSafeIdentity = true,
            currentLayoutId = surface.layoutId,
        )
        val navigationPlan = planDynamicNavigation(selectedContext)
        val contextualSurfaceContexts = buildList {
            add(selectedContext)
            navigationPlan.contextualChildDestinations.forEach { destination ->
                val destinationKind = layouts.singleOrNull { layout ->
                    layout.id == destination.layoutId
                }?.kind
                if (destinationKind == LayoutKind.detail) {
                    add(
                        selectedContext.copy(
                            parameterValues = destination.pathParameterValues,
                            currentLayoutId = destination.layoutId,
                        ),
                    )
                }
            }
        }
        contextualSurfaceContexts.forEach { surfaceContext ->
            val surfacePlan = if (surfaceContext === selectedContext) {
                navigationPlan
            } else {
                planDynamicNavigation(surfaceContext)
            }
            surfacePlan.contextualFormActions.forEach { formAction ->
                val action = actions.single { candidate -> candidate.id == formAction.actionId }
                val actionSpec = schema.actions.singleOrNull { candidate ->
                    candidate.id == action.id
                } ?: return@forEach
                val formView = schema.views.singleOrNull { view ->
                    view.id == formAction.formId
                } ?: return@forEach
                val candidateViewIds = buildSet {
                    surfaceContext.currentLayoutId?.let(::add)
                    addAll(
                        surfacePlan.contextualChildDestinations.map(
                            DynamicNavigationDestination::layoutId,
                        ),
                    )
                }
                val actionResource = schema.resources.singleOrNull { candidate ->
                    candidate.id.sameDynamicResourceAs(actionSpec.resourceId)
                }
                val exposedByShell = candidateViewIds.asSequence()
                    .mapNotNull { viewId ->
                        schema.views.singleOrNull { view -> view.id == viewId }
                    }
                    .any { activeView ->
                        dynamicContextualFormTargetsActiveSurface(
                            action = actionSpec,
                            formView = formView,
                            activeView = activeView,
                            activeReadAction = schema.actions.singleOrNull { candidate ->
                                candidate.id == activeView.sourceActionId
                            },
                            plannedBindingValues = formAction.pathParameterValues,
                            selectedRecordResourceId = resource.id,
                            selectedCollectionState = dynamicCollectionState(
                                schema.actions.singleOrNull { candidate ->
                                    candidate.id == activeView.sourceActionId
                                },
                            ),
                            hasEditableFileField = actionResource
                                ?.let { target -> editableNativeFields(target, actionSpec) }
                                ?.any { field -> field.kind == FieldKind.file }
                                ?: false,
                            uniqueTargetResource = actionResource != null,
                        )
                    }
                if (exposedByShell) reachableActionIds += action.id
            }
        }
        navigationPlan.contextualChildDestinations.mapNotNullTo(collectionSurfaces) { destination ->
            destination.takeIf { candidate -> isPantryAuditCollectionDestination(candidate) }
                ?.let(PantryAuditCollectionSurface::from)
        }

        val nativeResource = schema.resource(resource.id) ?: continue
        val authorityContext = schema.pantryAuditHouseAuthority(navigationValues, recordBindingValues)
        val rendersCollectionToolbar = layouts.singleOrNull { layout ->
            layout.id == surface.layoutId
        }?.kind in setOf(LayoutKind.list, LayoutKind.grid)
        if (rendersCollectionToolbar) {
            val collectionCapabilities = nativeCollectionActions(
                schema = schema,
                activeReadAction = schema.actions.singleOrNull { action ->
                    action.id == readAction.id
                } ?: continue,
                resource = nativeResource,
                records = listOf("7", "8").map { recordId ->
                    NativeRecord(
                        id = recordId,
                        values = basePantryAuditRecordValues(
                            resource = nativeResource,
                            responseFieldIds = responseFieldIds,
                            recordId = recordId,
                            navigationValues = navigationValues + recordBindingValues,
                        ),
                        bindingContext = recordBindingValues,
                        actionSafeIdentity = true,
                    )
                },
                navigationContext = navigationValues,
                collectionComplete = true,
                authorityContext = authorityContext,
            )
            reachableActionIds += collectionCapabilities.commands.map { plan -> plan.action.id }
            collectionCapabilities.reorder?.let { plan -> reachableActionIds += plan.action.id }
            reachableActionIds += collectionCapabilities.batches.map { plan -> plan.action.id }
        }

        nativeRecordActions(
            schema = schema,
            resource = nativeResource,
            navigationContext = navigationValues,
        ).create?.let { plan -> reachableActionIds += plan.action.id }

        val baseRecordValues = basePantryAuditRecordValues(
            resource = nativeResource,
            responseFieldIds = responseFieldIds,
            recordId = "7",
            navigationValues = navigationValues + recordBindingValues,
        )
        val stateVariants = listOf(
            baseRecordValues,
            baseRecordValues.withPantryAuditState("deleted"),
            baseRecordValues.withPantryAuditState("archived"),
        )
        stateVariants.forEach { values ->
            val capabilities = nativeRecordActions(
                schema = schema,
                resource = nativeResource,
                record = NativeRecord(
                    id = "7",
                    values = values,
                    bindingContext = recordBindingValues,
                    actionSafeIdentity = true,
                ),
                navigationContext = navigationValues,
                authorityContext = authorityContext,
            )
            capabilities.edit?.let { plan -> reachableActionIds += plan.action.id }
            capabilities.delete?.let { plan -> reachableActionIds += plan.action.id }
            capabilities.completion?.let { plan -> reachableActionIds += plan.action.id }
            reachableActionIds += capabilities.commands.map { plan -> plan.action.id }
            reachableActionIds += capabilities.commandForms.map { plan -> plan.action.id }
        }
    }

    return actions
        .asSequence()
        .filter { action ->
            if (action.binding.method == HttpMethod.GET) {
                false
            } else {
                action.id !in reachableActionIds
            }
        }
        .groupBy(DynamicAction::missingGenericPantryCapability)
        .mapValues { (_, missing) -> missing.map(DynamicAction::id).sorted() }
        .toSortedMap()
}

private fun NativeAppSchema.pantryAuditHouseAuthority(
    navigationValues: Map<String, String>,
    recordBindingValues: Map<String, String>,
): NativeRecordAuthorityContext? {
    val houseResource = resources.singleOrNull { resource ->
        resource.fields.count { field ->
            field.id.equals("isAdmin", ignoreCase = true) &&
                field.kind == FieldKind.boolean
        } == 1
    } ?: return null
    val houseId = recordBindingValues.entries
        .singleOrNull { (name, _) -> name.equals("houseId", ignoreCase = true) }
        ?.value
        ?: navigationValues.entries
            .singleOrNull { (name, _) ->
                name.equals("houseId", ignoreCase = true) ||
                    name.equals("id", ignoreCase = true)
            }
            ?.value
        ?: return null
    return NativeRecordAuthorityContext(
        parentResource = houseResource,
        parentRecord = NativeRecord(
            id = houseId,
            values = mapOf(
                "id" to houseId,
                "isAdmin" to "true",
            ),
        ),
    )
}

private fun basePantryAuditRecordValues(
    resource: ResourceSpec,
    responseFieldIds: Set<String>,
    recordId: String,
    navigationValues: Map<String, String>,
): Map<String, String?> = resource.fields.associate { field ->
    field.id to when {
        field.id in navigationValues -> navigationValues[field.id]
        field.id in responseFieldIds -> samplePantryAuditValue(field.id, field.kind)
        else -> null
    }
} + navigationValues + mapOf("id" to recordId)

private data class PantryAuditCollectionSurface(
    val layoutId: String,
    val resourceId: String,
    val actionId: String,
    val parameterValues: Map<String, String>,
) {
    companion object {
        fun from(destination: DynamicNavigationDestination): PantryAuditCollectionSurface =
            PantryAuditCollectionSurface(
                layoutId = destination.layoutId,
                resourceId = destination.resourceId,
                actionId = destination.actionId,
                parameterValues = destination.pathParameterValues,
            )
    }
}

private fun DynamicAppDescriptor.isPantryAuditCollectionDestination(
    destination: DynamicNavigationDestination,
): Boolean = layouts.singleOrNull { layout -> layout.id == destination.layoutId }
    ?.kind in setOf(LayoutKind.list, LayoutKind.grid)

private fun samplePantryAuditValue(fieldId: String, kind: FieldKind? = null): String? {
    val semanticId = fieldId.lowercase().filter(Char::isLetterOrDigit)
    return when {
        semanticId in setOf("readonly") -> "false"
        semanticId == "isadmin" -> "true"
        semanticId in setOf("writable", "canwrite", "canedit", "canupdate", "candelete") -> "true"
        semanticId.contains("deleted") ||
            semanticId.contains("trashed") ||
            semanticId.contains("removed") ||
            semanticId.contains("archived") -> null
        kind == FieldKind.boolean -> "false"
        kind == FieldKind.date -> "2026-07-30"
        kind == FieldKind.dateTime -> "2026-07-30T12:00:00Z"
        semanticId.startsWith("is") || semanticId.startsWith("has") -> "false"
        else -> "7"
    }
}

private fun Map<String, String?>.withPantryAuditState(state: String): Map<String, String?> {
    val stateField = keys.firstOrNull { fieldId ->
        fieldId.lowercase().filter(Char::isLetterOrDigit).contains(state)
    } ?: return this
    return this + (stateField to "2026-07-30T12:00:00Z")
}

private fun DynamicAction.missingGenericPantryCapability(): String {
    val semantics = "$id $label $resourceId ${binding.path}"
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .toSet()
    return when {
        effect == ActionEffect.batch -> "batch/selection action"
        effect == ActionEffect.reorder -> "collection reorder action"
        effect == ActionEffect.empty -> "collection-level command"
        effect == ActionEffect.upload -> "file/upload action"
        semantics.any { word ->
            word in setOf("prefs", "preference", "preferences", "setting", "settings")
        } -> "contextual settings form"
        effect == ActionEffect.assign -> "contextual relationship form"
        effect == ActionEffect.copy && binding.body != null -> "record command form with input"
        else -> "generic mutation form or command"
    }
}
