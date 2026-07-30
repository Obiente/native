package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.CompositeDataGridSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicMutationRefreshTest {
    @Test
    fun `child creation refreshes its full declared parent child component`() {
        val schema = schema(
            resources = listOf("houses", "checklists", "items", "accounts"),
            actions = listOf(mutation("create-checklist", "checklists", ActionIntent.create)),
            relationships = listOf(
                relationship("houses", "checklists"),
                relationship("checklists", "items"),
            ),
            views = listOf(
                view("houses-view", "houses"),
                view("checklists-view", "checklists"),
                view("items-view", "items"),
                view("accounts-view", "accounts"),
            ),
        )

        val plan = schema.planDynamicMutationRefresh(
            action = schema.actions.single(),
            selectedRecordResourceId = "houses",
        )

        requireNotNull(plan)
        assertEquals(setOf("houses", "checklists", "items"), plan.affectedResourceIds)
        assertEquals(setOf("houses-view", "checklists-view", "items-view"), plan.affectedViewIds)
        assertEquals(
            DynamicSelectedRecordReconciliation.KeepRouteAndReloadWhenVisible,
            plan.selectedRecordReconciliation,
        )
        assertTrue(plan.invalidateAllAppScreenSnapshots)
        assertTrue(plan.reloadVisibleView)
    }

    @Test
    fun `deleting the selected target clears only that deleted selection`() {
        val schema = schema(
            resources = listOf("collections", "entries"),
            actions = listOf(mutation("delete-entry", "entries", ActionIntent.delete)),
            relationships = listOf(relationship("collections", "entries")),
        )

        assertEquals(
            DynamicSelectedRecordReconciliation.ClearDeletedSelection,
            schema.planDynamicMutationRefresh(schema.actions.single(), "entries")
                ?.selectedRecordReconciliation,
        )
        assertEquals(
            DynamicSelectedRecordReconciliation.KeepRouteAndReloadWhenVisible,
            schema.planDynamicMutationRefresh(schema.actions.single(), "collections")
                ?.selectedRecordReconciliation,
        )
    }

    @Test
    fun `affected related records are discarded while unrelated records stay available`() {
        val schema = schema(
            resources = listOf("projects", "tasks", "accounts"),
            actions = listOf(mutation("update-task", "tasks", ActionIntent.update)),
            relationships = listOf(relationship("projects", "tasks")),
        )
        val plan = requireNotNull(schema.planDynamicMutationRefresh(schema.actions.single(), null))
        val reconciled = plan.discardAffectedRelatedRecords(
            mapOf(
                "projects" to listOf(NativeRecord("project-1", emptyMap())),
                "tasks" to listOf(NativeRecord("task-1", emptyMap())),
                "accounts" to listOf(NativeRecord("account-1", emptyMap())),
            ),
        )

        assertEquals(setOf("accounts"), reconciled.keys)
        assertEquals("account-1", reconciled.getValue("accounts").single().id)
    }

    @Test
    fun `composite grid contracts connect every participating resource`() {
        val composite = CompositeDataGridSpec(
            parentResourceId = "catalogs",
            columnResourceId = "attributes",
            rowResourceId = "entries",
            columnSourceActionId = "list-attributes",
            rowSourceActionId = "list-entries",
            columnIdentityFieldId = "id",
            columnAliasFieldId = null,
            columnTitleFieldId = "name",
            columnTypeFieldId = null,
            columnOrderFieldId = null,
            rowCellMapFieldId = "values",
        )
        val schema = schema(
            resources = listOf("catalogs", "attributes", "entries", "accounts"),
            actions = listOf(mutation("update-entry", "entries", ActionIntent.update)),
            views = listOf(
                view("catalog-table", "entries", composite),
                view("accounts-view", "accounts"),
            ),
        )

        val plan = requireNotNull(schema.planDynamicMutationRefresh(schema.actions.single(), "catalogs"))

        assertEquals(setOf("catalogs", "attributes", "entries"), plan.affectedResourceIds)
        assertEquals(setOf("catalog-table"), plan.affectedViewIds)
        assertEquals(
            DynamicSelectedRecordReconciliation.KeepRouteAndReloadWhenVisible,
            plan.selectedRecordReconciliation,
        )
    }

    @Test
    fun `resource matching remains exact and does not pull semantic lookalikes`() {
        val schema = schema(
            resources = listOf("item", "items", "item-notes"),
            actions = listOf(mutation("update-item", "item", ActionIntent.update)),
            relationships = listOf(relationship("items", "item-notes")),
            views = listOf(
                view("item-view", "item"),
                view("items-view", "items"),
                view("notes-view", "item-notes"),
            ),
        )

        val plan = requireNotNull(schema.planDynamicMutationRefresh(schema.actions.single(), "items"))

        assertEquals(setOf("item"), plan.affectedResourceIds)
        assertEquals(setOf("item-view"), plan.affectedViewIds)
        assertEquals(DynamicSelectedRecordReconciliation.Keep, plan.selectedRecordReconciliation)
    }

    @Test
    fun `dangling relationship evidence cannot fabricate affected resources`() {
        val schema = schema(
            resources = listOf("parents", "children"),
            actions = listOf(mutation("update-child", "children", ActionIntent.update)),
            relationships = listOf(
                relationship("parents", "children"),
                relationship("children", "missing-resource"),
            ),
        )

        val plan = requireNotNull(schema.planDynamicMutationRefresh(schema.actions.single(), null))

        assertEquals(setOf("parents", "children"), plan.affectedResourceIds)
        assertFalse("missing-resource" in plan.affectedResourceIds)
    }

    @Test
    fun `missing and read only actions do not produce mutation refresh plans`() {
        val schema = schema(
            resources = listOf("entries"),
            actions = listOf(
                ActionSpec(
                    id = "list-entries",
                    label = "List entries",
                    resourceId = "entries",
                    binding = ApiBinding(HttpMethod.GET, "/entries", "listEntries"),
                    intent = ActionIntent.list,
                    risk = ActionRisk.readOnly,
                    requiresConfirmation = false,
                    confidence = Confidence.verified,
                ),
            ),
        )

        val readAction = schema.actions.single()
        assertNull(
            schema.planDynamicMutationRefresh(
                readAction.copy(id = "missing"),
                null,
            ),
        )
        assertNull(schema.planDynamicMutationRefresh(readAction, null))
    }

    @Test
    fun `modified action copy cannot impersonate the canonical schema mutation`() {
        val schema = schema(
            resources = listOf("entries"),
            actions = listOf(mutation("update-entry", "entries", ActionIntent.update)),
        )
        val canonical = schema.actions.single()

        assertNull(
            schema.planDynamicMutationRefresh(
                canonical.copy(resourceId = "other"),
                null,
            ),
        )
    }

    private fun schema(
        resources: List<String>,
        actions: List<ActionSpec>,
        relationships: List<ResourceRelationshipSpec> = emptyList(),
        views: List<ViewSpec> = emptyList(),
    ): NativeAppSchema = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity("example", "Example", "1"),
        confidence = Confidence.verified,
        resources = resources.map { id ->
            ResourceSpec(id, id, Confidence.verified)
        },
        actions = actions,
        relationships = relationships,
        views = views,
    )

    private fun mutation(
        id: String,
        resourceId: String,
        intent: ActionIntent,
    ): ActionSpec = ActionSpec(
        id = id,
        label = id,
        resourceId = resourceId,
        binding = ApiBinding(
            method = when (intent) {
                ActionIntent.delete -> HttpMethod.DELETE
                ActionIntent.create -> HttpMethod.POST
                else -> HttpMethod.PATCH
            },
            path = "/$resourceId/{id}",
            operationId = id,
            pathParameterNames = listOf("id"),
            requiredPathParameterNames = listOf("id"),
        ),
        intent = intent,
        risk = if (intent == ActionIntent.delete) ActionRisk.destructive else ActionRisk.mutating,
        requiresConfirmation = intent == ActionIntent.delete,
        confidence = Confidence.verified,
    )

    private fun relationship(
        parentResourceId: String,
        childResourceId: String,
    ): ResourceRelationshipSpec = ResourceRelationshipSpec(
        parentResourceId = parentResourceId,
        childResourceId = childResourceId,
        parentFieldId = "id",
        childFieldId = "${parentResourceId}Id",
        confidence = Confidence.verified,
    )

    private fun view(
        id: String,
        resourceId: String,
        composite: CompositeDataGridSpec? = null,
    ): ViewSpec = ViewSpec(
        id = id,
        title = id,
        resourceId = resourceId,
        component = if (composite == null) NativeComponent.collectionList else NativeComponent.dataTable,
        sourceActionId = "read-$resourceId",
        confidence = Confidence.verified,
        compositeDataGrid = composite,
    )
}
