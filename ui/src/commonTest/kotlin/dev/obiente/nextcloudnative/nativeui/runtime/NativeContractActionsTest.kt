package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Contract write selection for a synthetic "planner" 1.0.0 contract. */
class NativeContractActionsTest {
    @Test
    fun aSingleUpdateAtTheItemRouteIsChosen() {
        val schema = schema(write(HttpMethod.PATCH))
        val route = assertNotNull(schema.action("tasks.list")?.nativeCollectionRoute())
        val update = assertNotNull(schema.itemWrite(NativeContractWriteKind.Update, route))
        assertEquals("tasks.update.PATCH", update.write.action.id)
        assertEquals(listOf("boardId", "taskId"), update.write.placeholders)
    }

    @Test
    fun twoCandidatesForOnePositionMeanNoneIsChosen() {
        val schema = schema(write(HttpMethod.PUT), write(HttpMethod.PATCH))
        val route = assertNotNull(schema.action("tasks.list")?.nativeCollectionRoute())
        assertNull(schema.contractWrite(NativeContractWriteKind.Update, route.scopedItemShape),
            "A PUT and a PATCH on the same route are ambiguous, so neither is guessed.")
        assertNull(schema.itemWrite(NativeContractWriteKind.Update, route))
        assertNotNull(schema.contractWrite(NativeContractWriteKind.Update, route.scopedItemShape, setOf(HttpMethod.PATCH)),
            "Narrowing the method to one the caller requires leaves a single candidate.")
    }

    @Test
    fun aBareIdPlaceholderNamesTheSingularContainer() {
        assertEquals("categoryid", parentReferenceKey("categories", "id"), "`ies` becomes `y`.")
        assertEquals("boardid", parentReferenceKey("boards", "id"))
        assertEquals("addressid", parentReferenceKey("address", "id"), "A word ending in `ss` is already singular.")
        assertEquals("classid", parentReferenceKey("class", "id"))
        assertEquals("businessid", parentReferenceKey("business", "id"))
        assertEquals("pantryid", parentReferenceKey("Pantries", "id"), "Case and separators do not matter.")
        assertEquals("boardid", parentReferenceKey("boards", "boardId"), "A named placeholder is its own reference.")
        assertEquals("ownerref", parentReferenceKey("boards", "owner_ref"))
    }

    private fun schema(vararg writes: ActionSpec) = NativeAppSchema("0.1", AppIdentity("planner", "Planner", "1.0.0"),
        Confidence.high, actions = listOf(action("tasks.list", HttpMethod.GET, LIST, ActionIntent.list)) + writes)

    private fun write(method: HttpMethod) = action("tasks.update.$method", method, "$LIST/{taskId}", ActionIntent.update)

    private fun action(id: String, method: HttpMethod, path: String, intent: ActionIntent): ActionSpec {
        val placeholders = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.toList()
        return ActionSpec(id, id, "tasks", ApiBinding(method, path, id, placeholders, placeholders), intent,
            if (intent == ActionIntent.list) ActionRisk.readOnly else ActionRisk.mutating, true, Confidence.high,
            evidence = listOf(Evidence(EvidenceSource.openApi, "synthetic")))
    }

    private companion object {
        const val LIST = "/apps/planner/api/boards/{boardId}/tasks"
    }
}
