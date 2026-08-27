package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicActionUiTest {
    @Test
    fun `empty declared delete is confirmed directly while update keeps its editor`() {
        val delete = action("delete-recipe", ActionIntent.delete, ActionRisk.destructive, HttpMethod.DELETE)
        val update = action("update-recipe", ActionIntent.update, ActionRisk.mutating, HttpMethod.PUT)

        assertEquals(DynamicActionUiMode.ConfirmDirectly, dynamicActionUiMode(delete, editableFieldCount = 0))
        assertEquals(DynamicActionUiMode.NavigateToForm, dynamicActionUiMode(update, editableFieldCount = 8))
        assertEquals("Delete", dynamicHeaderActionLabel(delete, "Delete a recipe"))
        assertEquals("Edit", dynamicHeaderActionLabel(update, "Update recipe"))
    }

    @Test
    fun `destructive action with declared input still uses its schema form`() {
        val deleteWithReason = action(
            "delete-with-reason",
            ActionIntent.delete,
            ActionRisk.destructive,
            HttpMethod.DELETE,
        )

        assertEquals(
            DynamicActionUiMode.NavigateToForm,
            dynamicActionUiMode(deleteWithReason, editableFieldCount = 1),
        )
    }

    @Test
    fun `semantic effects keep distinct labels and confirmations`() {
        val permanentDelete = action(
            "delete-permanently",
            ActionIntent.delete,
            ActionRisk.destructive,
            HttpMethod.DELETE,
        ).copy(effect = ActionEffect.permanentDelete)
        val clear = action(
            "clear-image",
            ActionIntent.execute,
            ActionRisk.destructive,
            HttpMethod.DELETE,
        ).copy(effect = ActionEffect.clear)

        assertEquals("Delete permanently", dynamicHeaderActionLabel(permanentDelete, "Delete"))
        assertEquals("Clear", dynamicHeaderActionLabel(clear, "Delete"))
        assertEquals(
            DynamicActionUiMode.ConfirmDirectly,
            dynamicActionUiMode(clear, editableFieldCount = 0),
        )
        assertEquals("Clear photo?", dynamicDirectActionTitle(clear, "photo"))
        assertEquals("Clear", dynamicDirectActionConfirmLabel(clear))
    }

    @Test
    fun `unknown direct mutation requires reconciliation and cannot be retried immediately`() {
        val rejected = dynamicDirectActionFailurePolicy(NativeActionFailureOutcome.Rejected)
        val unknown = dynamicDirectActionFailurePolicy(NativeActionFailureOutcome.Unknown)

        assertTrue(rejected.retryAllowed)
        assertFalse(rejected.requiresReconciliation)
        assertFalse(unknown.retryAllowed)
        assertTrue(unknown.requiresReconciliation)
    }

    @Test
    fun `quick actions prioritize create and edit ahead of destructive operations`() {
        assertEquals(
            listOf(ActionIntent.create, ActionIntent.update, ActionIntent.execute, ActionIntent.delete),
            listOf(
                action("delete", ActionIntent.delete, ActionRisk.destructive, HttpMethod.DELETE),
                action("execute", ActionIntent.execute, ActionRisk.mutating, HttpMethod.POST),
                action("edit", ActionIntent.update, ActionRisk.mutating, HttpMethod.PUT),
                action("create", ActionIntent.create, ActionRisk.mutating, HttpMethod.POST),
            ).sortedBy(::dynamicQuickActionPriority).map(ActionSpec::intent),
        )
    }

    @Test
    fun `verified create can target the exact active singleton route across schema resource identities`() {
        val create = action(
            "create-team",
            ActionIntent.create,
            ActionRisk.mutating,
            HttpMethod.POST,
        ).copy(
            resourceId = "create-team-body",
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/apps/chores/api/v1.0/team",
                operationId = "create-team",
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
                bodyContentType = "application/json",
            ),
        )
        val form = view(
            id = "create-team.form",
            resourceId = create.resourceId,
            component = NativeComponent.form,
            sourceActionId = create.id,
        ).copy(confidence = Confidence.verified)
        val read = action(
            "get-team",
            ActionIntent.read,
            ActionRisk.readOnly,
            HttpMethod.GET,
        ).copy(
            resourceId = "team-response",
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/chores/api/v1.0/team",
                operationId = "get-team",
            ),
        )
        val active = view(
            id = "get-team.detail",
            resourceId = read.resourceId,
            component = NativeComponent.detail,
            sourceActionId = read.id,
        )

        assertTrue(dynamicRootFormTargetsActiveSurface(create, form, active, read, null))
        assertFalse(
            dynamicRootFormTargetsActiveSurface(
                create.copy(binding = create.binding.copy(path = "/apps/chores/api/v1.0/teams")),
                form,
                active,
                read,
                null,
            ),
        )
        assertFalse(dynamicRootFormTargetsActiveSurface(create, form, active, read, "trash"))
    }

    @Test
    fun `verified upload targets only its active read surface with complete bindings`() {
        val upload = action(
            "upload-image",
            ActionIntent.execute,
            ActionRisk.mutating,
            HttpMethod.POST,
        ).copy(
            resourceId = "photos",
            effect = ActionEffect.upload,
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/houses/{houseId}/photos",
                operationId = "upload-image",
                pathParameterNames = listOf("houseId"),
                requiredPathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("image"),
                requiredBodyFieldNames = listOf("image"),
                bodyContentType = "multipart/form-data",
            ),
        )
        val form = view(
            id = "upload-image.form",
            resourceId = "photos",
            component = NativeComponent.form,
            sourceActionId = upload.id,
        )
        val active = view(
            id = "photos.grid",
            resourceId = "photos",
            component = NativeComponent.mediaGrid,
            sourceActionId = "list-photos",
        )
        val read = action(
            "list-photos",
            ActionIntent.list,
            ActionRisk.readOnly,
            HttpMethod.GET,
        ).copy(
            resourceId = "photos",
            confidence = Confidence.verified,
        )

        assertTrue(
            dynamicContextualFormTargetsActiveSurface(
                action = upload,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = mapOf("houseId" to "house-7"),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = true,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = upload,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = emptyMap(),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = true,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = upload,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = mapOf("houseId" to "house-7"),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
    }

    @Test
    fun `verified execute with a fully bound body targets its selected collection record`() {
        val accept = action(
            "accept-invitation",
            ActionIntent.execute,
            ActionRisk.mutating,
            HttpMethod.POST,
        ).copy(
            resourceId = "invitations",
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/account/invitations/accept",
                operationId = "accept-invitation",
                bodyFieldNames = listOf("teamId"),
                requiredBodyFieldNames = listOf("teamId"),
                bodyContentType = "application/json",
            ),
        )
        val form = view(
            id = "accept-invitation.form",
            resourceId = "invitations",
            component = NativeComponent.form,
            sourceActionId = accept.id,
        ).copy(confidence = Confidence.verified)
        val active = view(
            id = "invitations.list",
            resourceId = "invitations",
            component = NativeComponent.collectionList,
            sourceActionId = "list-invitations",
        )
        val read = action(
            "list-invitations",
            ActionIntent.list,
            ActionRisk.readOnly,
            HttpMethod.GET,
        ).copy(resourceId = "invitations", confidence = Confidence.verified)

        assertTrue(
            dynamicContextualFormTargetsActiveSurface(
                action = accept,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = mapOf("teamId" to "42"),
                selectedRecordResourceId = "invitations",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = accept,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = emptyMap(),
                selectedRecordResourceId = "invitations",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
    }

    @Test
    fun `context bound singleton update requires its unique active verified detail read`() {
        val update = action(
            "update-preferences",
            ActionIntent.update,
            ActionRisk.mutating,
            HttpMethod.PATCH,
        ).copy(
            resourceId = "preferences",
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.PATCH,
                path = "/houses/{houseId}/preferences",
                operationId = "update-preferences",
                pathParameterNames = listOf("houseId"),
                requiredPathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("enabled"),
                requiredBodyFieldNames = listOf("enabled"),
                bodyContentType = "application/json",
            ),
        )
        val form = view(
            id = "preferences.form",
            resourceId = "preferences",
            component = NativeComponent.form,
            sourceActionId = update.id,
        )
        val active = view(
            id = "preferences.detail",
            resourceId = "preferences",
            component = NativeComponent.detail,
            sourceActionId = "read-preferences",
        )
        val read = action(
            "read-preferences",
            ActionIntent.read,
            ActionRisk.readOnly,
            HttpMethod.GET,
        ).copy(
            resourceId = "preferences",
            confidence = Confidence.verified,
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/houses/{houseId}/preferences",
                operationId = "read-preferences",
                pathParameterNames = listOf("houseId"),
                requiredPathParameterNames = listOf("houseId"),
            ),
        )

        assertTrue(
            dynamicContextualFormTargetsActiveSurface(
                action = update,
                formView = form,
                activeView = active,
                activeReadAction = read,
                plannedBindingValues = mapOf("houseId" to "house-7"),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = update,
                formView = form,
                activeView = active.copy(component = NativeComponent.collectionList),
                activeReadAction = read,
                plannedBindingValues = mapOf("houseId" to "house-7"),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = update,
                formView = form,
                activeView = active,
                activeReadAction = read.copy(resourceId = "other"),
                plannedBindingValues = mapOf("houseId" to "house-7"),
                selectedRecordResourceId = "houses",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
    }

    @Test
    fun `ordinary record update visibility still follows selected record identity`() {
        val update = action(
            "update-recipe",
            ActionIntent.update,
            ActionRisk.mutating,
            HttpMethod.PATCH,
        )
        val detail = view(
            id = "recipe.detail",
            resourceId = "recipes",
            component = NativeComponent.detail,
            sourceActionId = "read-recipe",
            confidence = Confidence.high,
        )

        assertTrue(
            dynamicContextualFormTargetsActiveSurface(
                action = update,
                formView = detail.copy(
                    id = "recipe.form",
                    component = NativeComponent.form,
                    sourceActionId = update.id,
                ),
                activeView = detail,
                activeReadAction = null,
                plannedBindingValues = emptyMap(),
                selectedRecordResourceId = "recipes",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
        assertFalse(
            dynamicContextualFormTargetsActiveSurface(
                action = update,
                formView = detail.copy(
                    id = "recipe.form",
                    component = NativeComponent.form,
                    sourceActionId = update.id,
                ),
                activeView = detail.copy(resourceId = "other"),
                activeReadAction = null,
                plannedBindingValues = emptyMap(),
                selectedRecordResourceId = "other",
                selectedCollectionState = null,
                hasEditableFileField = false,
                uniqueTargetResource = true,
            ),
        )
    }

    @Test
    fun `state collection reads are recognized without app or operation identifiers`() {
        val trash = action(
            "arbitrary-read",
            ActionIntent.list,
            ActionRisk.readOnly,
            HttpMethod.GET,
        ).copy(binding = ApiBinding(HttpMethod.GET, "/api/workspaces/{workspaceId}/records/trash", "read"))
        val active = trash.copy(binding = trash.binding.copy(path = "/api/workspaces/{workspaceId}/records"))
        val mutation = trash.copy(
            intent = ActionIntent.delete,
            binding = trash.binding.copy(method = HttpMethod.DELETE),
        )

        assertEquals("trash", dynamicCollectionState(trash))
        assertEquals(null, dynamicCollectionState(active))
        assertEquals(null, dynamicCollectionState(mutation))
    }

    @Test
    fun `duplicate state destinations include their resource names`() {
        assertEquals(
            "Checklist Trash",
            dynamicSecondaryDestinationLabel("Trash", "Checklist", duplicate = true),
        )
        assertEquals(
            "Trash",
            dynamicSecondaryDestinationLabel("Trash", "Checklist", duplicate = false),
        )
        assertEquals(
            "Trash",
            dynamicSecondaryDestinationLabel("Trash", "Trash", duplicate = true),
        )
    }

    private fun action(
        id: String,
        intent: ActionIntent,
        risk: ActionRisk,
        method: HttpMethod,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "recipes",
        binding = ApiBinding(method, "/apps/example/api/recipes/{id}", id),
        intent = intent,
        risk = risk,
        requiresConfirmation = risk == ActionRisk.destructive,
        confidence = Confidence.high,
    )

    private fun view(
        id: String,
        resourceId: String,
        component: NativeComponent,
        sourceActionId: String,
        confidence: Confidence = Confidence.verified,
    ) = ViewSpec(
        id = id,
        title = id,
        resourceId = resourceId,
        component = component,
        sourceActionId = sourceActionId,
        confidence = confidence,
    )
}
