package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PantryLiveContractCompatibilityTest {
    @Test
    fun `signed Pantry contract opens a selected house into its checklist collection`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("pantry", "34.0.1", "0.23.0"),
            ),
        )
        assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract.sourceKind)
        assertEquals(VerifiedContractKind.OpenApi, contract.contractKind)

        val descriptor = DynamicAppDescriptorCompiler().compile(
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
            assertEquals(
                DYNAMIC_INTEGER_ARRAY_FORMAT,
                descriptor.forms.single { form -> form.actionId == operationId }
                    .fields.single { field -> field.fieldId == fieldId }
                    .format,
                "$operationId:$fieldId",
            )
        }
        listOf(
            "category-reorder" to "items",
            "share-set-shares" to "shares",
        ).forEach { (operationId, fieldId) ->
            assertNull(
                descriptor.forms.single { form -> form.actionId == operationId }
                    .fields.single { field -> field.fieldId == fieldId }
                    .format,
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
                values = mapOf(
                    "id" to "list-9",
                    "listId" to "list-9",
                    "houseId" to "house-7",
                    "name" to "Test list",
                ),
                bindingContext = mapOf(
                    "houseId" to "house-7",
                    "listId" to "list-9",
                ),
            ),
            navigationContext = preferredLists.pathParameterValues,
        )
        assertNotNull(
            selectedListActions.edit,
            "No structural edit plan for ${listResource.id}; actions=$createEvidence",
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
            listChildren.any { destination -> destination.resourceId == "archive" },
            listEvidence,
        )
        assertEquals("items", assertNotNull(listPreferred, listEvidence).resourceId, listEvidence)
    }

    private fun DynamicAppDescriptor.action(operationId: String): DynamicAction =
        actions.single { action -> action.id == operationId }
}
