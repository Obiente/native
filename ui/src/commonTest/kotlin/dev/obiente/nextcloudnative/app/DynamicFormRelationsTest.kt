package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicFormRelationsTest {
    @Test
    fun `form relationships preload one fully bound active collection read`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
                action(
                    "category-trash",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories/trash",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )

        assertEquals(
            listOf(DynamicFormRelationLoadPlan("categories", "category-index")),
            dynamicFormRelationLoadPlans(schema, form, mapOf("workspaceId" to "7")),
        )
    }

    @Test
    fun `unbound or unrelated reads do not become form lookup dependencies`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.high,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )

        assertEquals(emptyList(), dynamicFormRelationLoadPlans(schema, form, emptyMap()))
        assertEquals(
            emptyList(),
            dynamicFormRelationLoadPlans(
                schema.copy(
                    actions = schema.actions.map { action ->
                        if (action.id == "entry-create") {
                            action.copy(binding = action.binding.copy(bodyFieldNames = listOf("name")))
                        } else {
                            action
                        }
                    },
                ),
                form,
                mapOf("workspaceId" to "7"),
            ),
        )
    }

    private fun schema(
        form: ViewSpec,
        relationship: ResourceRelationshipSpec,
        reads: List<ActionSpec>,
    ): NativeAppSchema {
        val create = ActionSpec(
            id = "entry-create",
            label = "Create entry",
            resourceId = "entries",
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/api/workspaces/{workspaceId}/entries",
                operationId = "entry-create",
                requiredPathParameterNames = listOf("workspaceId"),
                bodyFieldNames = listOf("name", "categoryId"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        return NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("example", "Example", "1"),
            confidence = Confidence.verified,
            views = listOf(form) + reads.map { action ->
                view("${action.id}.list", action.resourceId, NativeComponent.collectionList, action.id)
            },
            actions = listOf(create) + reads,
            relationships = listOf(relationship),
        )
    }

    private fun action(
        id: String,
        resourceId: String,
        path: String,
        requiredPathNames: List<String>,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = resourceId,
        binding = ApiBinding(
            method = HttpMethod.GET,
            path = path,
            operationId = id,
            requiredPathParameterNames = requiredPathNames,
        ),
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        confidence = Confidence.verified,
    )

    private fun view(
        id: String,
        resourceId: String,
        component: NativeComponent,
        sourceActionId: String,
    ) = ViewSpec(
        id = id,
        title = id,
        resourceId = resourceId,
        component = component,
        sourceActionId = sourceActionId,
        confidence = Confidence.verified,
    )
}
