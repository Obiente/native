package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
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
}
