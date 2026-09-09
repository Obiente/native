package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeInlineRecordEditorTest {
    @Test
    fun editsReuseCurrentWorkspaceWhileCreateAndCommandsKeepDialogs() {
        assertEquals(NativeRecordFormPresentation.Inline, nativeRecordFormPresentation(NativeRecordFormActionKind.Edit))
        assertEquals(NativeRecordFormPresentation.Dialog, nativeRecordFormPresentation(NativeRecordFormActionKind.Create))
        assertEquals(NativeRecordFormPresentation.Dialog, nativeRecordFormPresentation(null))
    }

    @Test
    fun pendingMutationBlocksEvenCleanDraftAndDirtyDraftRequiresDiscard() {
        assertEquals(NativeInlineEditorLeaveDecision.Leave, nativeInlineEditorLeaveDecision(false, false))
        assertEquals(NativeInlineEditorLeaveDecision.ConfirmDiscard, nativeInlineEditorLeaveDecision(true, false))
        assertEquals(NativeInlineEditorLeaveDecision.Block, nativeInlineEditorLeaveDecision(false, true))
        assertEquals(NativeInlineEditorLeaveDecision.Block, nativeInlineEditorLeaveDecision(true, true))
    }

    @Test
    fun shellAndSectionNavigationUseRegisteredSessionWithoutAutomaticReplay() {
        val navigation = NativeInlineEditorNavigation()
        val owner = Any()
        var pending: NativeInlineEditorLeaveRequest? = null
        var navigations = 0
        var cancellations = 0
        navigation.register(owner) { pending = it }
        assertTrue(navigation.intercept({ navigations++ }, { cancellations++ }))
        assertEquals(0, navigations)
        requireNotNull(pending).cancel()
        assertEquals(1, cancellations)
        assertEquals(0, navigations)
        navigation.navigate { navigations++ }
        assertEquals(0, navigations)
        navigation.unregister(owner)
        requireNotNull(pending).proceed()
        assertEquals(1, navigations)
        assertFalse(navigation.active)
    }

    @Test
    fun disposingOlderEditorCannotUnregisterNewEditor() {
        val navigation = NativeInlineEditorNavigation()
        val oldOwner = Any()
        val currentOwner = Any()
        var requests = 0
        navigation.register(oldOwner) { error("Stale editor callback") }
        navigation.register(currentOwner) { requests++ }
        navigation.unregister(oldOwner)
        navigation.navigate { error("New editor was bypassed") }
        assertEquals(1, requests)
        navigation.unregister(currentOwner)
        var navigated = false
        navigation.navigate { navigated = true }
        assertTrue(navigated)
    }

    @Test
    fun refreshUsesExplicitIntentAndRunsDirectlyWithoutEditor() {
        val navigation = NativeInlineEditorNavigation()
        var refreshes = 0
        navigation.refresh { refreshes++ }
        assertEquals(1, refreshes)
        val owner = Any()
        var pending: NativeInlineEditorLeaveRequest? = null
        navigation.register(owner) { pending = it }
        navigation.refresh { refreshes++ }
        assertEquals(1, refreshes)
        assertEquals(NativeInlineEditorIntent.Refresh, requireNotNull(pending).intent)
        requireNotNull(pending).proceed()
        assertEquals(2, refreshes)
        assertTrue(navigation.active)
    }

    @Test
    fun inlineInputUsesExistingValidationAndKeepsExactRecordBinding() {
        val title = FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = false)
        val action = ActionSpec(
            id = "edit-record", label = "Edit", resourceId = "records",
            binding = ApiBinding(HttpMethod.PATCH, "/records/{recordId}", "editRecord", bodyFieldNames = listOf("title")),
            intent = ActionIntent.update, risk = ActionRisk.mutating, requiresConfirmation = true,
            confidence = Confidence.high,
        )
        val plan = NativeRecordFormActionPlan(
            kind = NativeRecordFormActionKind.Edit,
            action = action,
            fields = listOf(title),
            initialValues = mapOf("title" to "Existing title"),
            bindingValues = mapOf("recordId" to "synthetic-42"),
        )
        assertFailsWith<IllegalArgumentException> { plan.request(mapOf("title" to ""), confirmed = true) }
        assertFailsWith<IllegalArgumentException> { plan.request(mapOf("title" to "Changed"), confirmed = false) }
        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("title" to "Changed", "recordId" to "different-record"), confirmed = true)
        }
        val draft = requireNotNull(decodeNativeRecordFormDraft(requireNotNull(
            encodeNativeRecordFormDraft(mapOf("title" to " Changed title ")),
        )))
        val request = plan.requestWithStructuredInput(draft, emptyMap(), confirmed = true)
        assertEquals(mapOf("recordId" to "synthetic-42", "title" to "Changed title"), request.values)
        assertEquals(action, request.action)
        assertTrue(request.confirmed)
    }
}
