package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

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
