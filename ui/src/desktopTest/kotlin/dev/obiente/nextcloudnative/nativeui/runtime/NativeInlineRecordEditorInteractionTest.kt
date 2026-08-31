package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.marketingInlineRecordPending
import dev.obiente.nextcloudnative.app.marketingInlineRecordSchema
import dev.obiente.nextcloudnative.app.nativeSceneTest
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class NativeInlineRecordEditorInteractionTest {
    @Test
    fun recordOverflowEditOpensInlineThroughActualRenderer() {
        val resource = marketingInlineRecordPending.resource.let {
            it.copy(fields = it.fields + listOf(
                FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
                FieldSpec("canEdit", "Can edit", FieldKind.boolean, required = true, readOnly = true),
            ))
        }
        val edit = marketingInlineRecordPending.action.let {
            it.copy(binding = it.binding.copy(pathParameterNames = listOf("recordId"),
                requiredPathParameterNames = listOf("recordId"), requiredBodyFieldNames = listOf("title"),
                bodyContentType = "application/json"))
        }
        val read = edit.copy(id = "list-records", label = "List records", intent = ActionIntent.list,
            risk = ActionRisk.readOnly, binding = ApiBinding(HttpMethod.GET, "/synthetic/records", "listRecords"))
        val view = ViewSpec(id = "record-list", title = "Project records", resourceId = resource.id,
            sourceActionId = read.id, component = NativeComponent.collectionList, confidence = resource.confidence)
        val schema = marketingInlineRecordSchema.copy(resources = listOf(resource), actions = listOf(read, edit),
            views = listOf(view))
        val record = NativeRecord("synthetic-42", marketingInlineRecordPending.initialValues +
            mapOf("id" to "synthetic-42", "canEdit" to "true"))
        assertNotNull(nativeRecordActions(schema, resource, record).edit, "Fixture must have a permitted exact-target edit")
        val requests = mutableListOf<NativeActionRequest>()
        val navigation = NativeInlineEditorNavigation()
        var saves = 0
        nativeSceneTest(1000, 720, content = {
            CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
                GenericNativeAppScreen(schema = schema, view = view, state = NativeScreenState.Ready(listOf(record)),
                    actionExecutor = NativeActionExecutor { requests += it; NativeActionExecutionResult.Success() },
                    onInlineActionSucceeded = { saves++ })
            }
        }) {
            click("Actions for Shared planning notes")
            click("Edit")
            assertTrue(navigation.active, "Renderer Edit must register the inline navigation guard")
            assertTrue(has("Save changes"), "Dialog presentation labels this action Edit instead")
            replaceText("Shared planning notes", "Saved through the renderer")
            click("Save changes")
            assertEquals(1, saves)
            assertEquals("synthetic-42", (requests.single() as NativeActionRequest.Submit).values["recordId"])
            assertFalse(navigation.active)
        }
    }

    @Test
    fun editUsesExistingValuesAndSaveKeepsExactMutationIdentity() {
        val editor = RecordEditorScene()
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            click("Edit")
            assertTrue(has("Edit Shared planning notes"))
            replaceText("Shared planning notes", "Reviewed planning notes")
            click("Save changes")
            assertEquals(1, editor.submissions.size)
            val request = editor.submissions.single() as NativeActionRequest.Submit
            assertEquals("synthetic-42", request.values["recordId"])
            assertEquals("Reviewed planning notes", request.values["title"])
            assertEquals(marketingInlineRecordPending.action, request.action)
            assertEquals(1, editor.successes)
            assertFalse(editor.editing)
            assertFalse(editor.navigation.active)
        }
    }

    @Test
    fun dirtyCancelKeepsDraftAndExplicitDiscardClosesWithoutSaving() {
        val editor = RecordEditorScene()
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            click("Edit")
            replaceText("Shared planning notes", "Unsaved title")
            click("Cancel")
            assertTrue(has("Discard unsaved changes?"))
            click("Keep editing")
            assertTrue(editor.editing)
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved title" })
            click("Cancel")
            click("Discard changes")
            assertFalse(editor.editing)
            assertTrue(editor.submissions.isEmpty())
            assertFalse(editor.navigation.active)
        }
    }

    @Test
    fun rootAndSectionNavigationWaitForExplicitDraftDecision() {
        val editor = RecordEditorScene()
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            click("Edit")
            replaceText("Shared planning notes", "Unsaved title")
            click("Home")
            assertEquals(0, editor.rootNavigations)
            click("Keep editing")
            assertEquals(0, editor.rootNavigations)
            click("Other section")
            assertEquals(0, editor.sectionNavigations)
            click("Discard changes")
            assertEquals(1, editor.sectionNavigations)
            assertEquals(0, editor.rootNavigations)
            assertFalse(editor.editing)
        }
    }

    @Test
    fun pendingSaveBlocksBothNavigationPathsWithoutCancellingOrReplayingRequest() {
        val result = CompletableDeferred<NativeActionExecutionResult>()
        val editor = RecordEditorScene(result = result)
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            click("Edit")
            replaceText("Shared planning notes", "Pending title")
            click("Save changes")
            assertEquals(1, editor.submissions.size)
            for (destination in listOf("Home", "Other section")) {
                click(destination)
                assertTrue(has("Save not finished"))
                assertFalse(has("Discard changes"))
                click("Stay here")
            }
            assertEquals(0, editor.rootNavigations)
            assertEquals(0, editor.sectionNavigations)
            assertTrue(editor.editing)
            assertFalse(result.isCancelled)
            result.complete(NativeActionExecutionResult.Success())
            settle()
            assertEquals(1, editor.successes)
            assertEquals(1, editor.submissions.size)
            assertEquals(0, editor.rootNavigations)
            assertEquals(0, editor.sectionNavigations)
        }
    }

    @Test
    fun ambiguousSaveKeepsDraftAndBlocksLeavingUntilReconciliation() {
        val result = CompletableDeferred<NativeActionExecutionResult>()
        val editor = RecordEditorScene(result = result)
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            click("Edit")
            replaceText("Shared planning notes", "Unknown result title")
            click("Save changes")
            result.complete(NativeActionExecutionResult.Failure("Server result needs verification.",
                NativeActionFailureOutcome.Unknown))
            settle()
            assertTrue(has("Edit result unknown"))
            assertFalse(has("Save changes"))
            click("Home")
            assertTrue(has("Save not finished"))
            click("Stay here")
            assertTrue(editor.editing)
            assertEquals(0, editor.rootNavigations)
            assertEquals(1, editor.submissions.size)
            editor.recovery = null
            settle()
            assertTrue(has("Save changes"))
            click("Cancel")
            assertTrue(has("Discard unsaved changes?"))
        }
    }

    @Test
    fun manualRefreshWaitsForDirtyDraftDecision() {
        val editor = RecordEditorScene(initiallyEditing = true)
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            replaceText("Shared planning notes", "Unsaved refresh title")
            click("Refresh")
            assertEquals(0, editor.refreshes)
            click("Keep editing")
            assertTrue(editor.editing)
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved refresh title"
            })
            click("Refresh")
            click("Discard changes")
            assertEquals(1, editor.refreshes)
            assertFalse(editor.editing)
            assertFalse(editor.navigation.active)
            assertTrue(editor.submissions.isEmpty())
        }
    }

    @Test
    fun refreshDuringSaveDoesNotCancelOrReplayMutation() {
        val result = CompletableDeferred<NativeActionExecutionResult>()
        val editor = RecordEditorScene(initiallyEditing = true, result = result)
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            replaceText("Shared planning notes", "Pending refresh title")
            click("Save changes")
            click("Refresh")
            assertTrue(has("Save not finished"))
            assertFalse(has("Discard changes"))
            click("Stay here")
            assertEquals(0, editor.refreshes)
            assertTrue(editor.editing)
            assertFalse(result.isCancelled)
            result.complete(NativeActionExecutionResult.Success())
            settle()
            assertEquals(1, editor.submissions.size)
            assertEquals(1, editor.successes)
            assertEquals(0, editor.refreshes)
        }
    }

    @Test
    fun reconciliationRefreshKeepsEditorAndDraftWithoutReplayingSave() {
        val result = CompletableDeferred<NativeActionExecutionResult>()
        val editor = RecordEditorScene(initiallyEditing = true, result = result)
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            replaceText("Shared planning notes", "Unverified refresh title")
            click("Save changes")
            result.complete(NativeActionExecutionResult.Failure("Server result needs verification.",
                NativeActionFailureOutcome.Unknown))
            settle()
            click("Refresh")
            assertEquals(1, editor.refreshes)
            assertEquals(1, editor.submissions.size)
            assertTrue(editor.editing)
            assertTrue(editor.navigation.active)
            assertFalse(has("Discard unsaved changes?"))
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unverified refresh title"
            })
            click("Home")
            assertTrue(has("Save not finished"))
            click("Stay here")
            editor.recovery = null
            settle()
            click("Refresh")
            assertTrue(has("Discard unsaved changes?"))
            click("Keep editing")
            assertEquals(1, editor.refreshes)
            assertEquals(1, editor.submissions.size)
        }
    }

    @Test
    fun saveStartingCancelsOlderDiscardRequestWithoutReplayingIt() {
        val editor = RecordEditorScene(initiallyEditing = true)
        var proceeded = 0
        var cancelled = 0
        nativeSceneTest(1000, 720, content = { editor.Content() }) {
            replaceText("Shared planning notes", "Draft before asynchronous save")
            editor.navigation.intercept({ proceeded++ }, { cancelled++ })
            settle()
            assertTrue(has("Discard unsaved changes?"))
            editor.recovery = marketingInlineRecordPending.mutationRecoveryOwner.begin(0)
            settle()
            assertFalse(has("Discard unsaved changes?"))
            assertFalse(has("Save not finished"))
            assertEquals(1, cancelled)
            assertEquals(0, proceeded)
            editor.recovery = null
            settle()
            assertFalse(has("Discard unsaved changes?"))
            assertEquals(0, proceeded)
            assertTrue(editor.editing)
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Draft before asynchronous save"
            })

            editor.recovery = marketingInlineRecordPending.mutationRecoveryOwner.begin(0)
            settle()
            click("Home")
            assertTrue(has("Save not finished"), "A new request made while busy must remain visible")
            click("Stay here")
            assertEquals(0, editor.rootNavigations)
            assertEquals(1, cancelled)
        }
    }

    @Test
    fun smallPhoneLargeTextKeepsFooterVisibleAndEscapeUsesDiscardGuard() {
        val editor = RecordEditorScene(initiallyEditing = true)
        nativeSceneTest(320, 640, fontScale = 1.5f, content = { editor.Content(showNavigation = false) }) {
            for (label in listOf("Cancel", "Save changes")) {
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f, "$label must fit the phone width")
                assertTrue(bounds.top >= 0f && bounds.bottom <= 640f, "$label must remain visible")
            }
            replaceText("Shared planning notes", "Unsaved title")
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            settle()
            assertTrue(has("Discard unsaved changes?"))
            click("Keep editing")
            assertTrue(editor.editing)
            capture("inline-record-small-phone-large-text")
        }
    }
}

/** Only the surrounding navigation is a fixture. Fields, draft guard and submit logic are production UI. */
private class RecordEditorScene(
    initiallyEditing: Boolean = false,
    private val result: CompletableDeferred<NativeActionExecutionResult>? = null,
) {
    val navigation = NativeInlineEditorNavigation()
    val submissions = mutableListOf<NativeActionRequest>()
    var editing by mutableStateOf(initiallyEditing)
    var recovery by mutableStateOf<NativeFormMutationRecoveryState?>(null)
    var successes = 0
    var rootNavigations = 0
    var sectionNavigations = 0
    var refreshes = 0

    @Composable
    fun Content(showNavigation: Boolean = true) {
        CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
            Column(Modifier.fillMaxSize()) {
                if (showNavigation) Row {
                    Button(onClick = { navigation.navigate { rootNavigations++ } }) { Text("Home") }
                    Button(onClick = { navigation.navigate { sectionNavigations++ } }) { Text("Other section") }
                    Button(onClick = { navigation.refresh { refreshes++ } }) { Text("Refresh") }
                }
                Box(Modifier.weight(1f)) {
                    if (!editing) Button(onClick = { editing = true }) { Text("Edit") }
                    else GenericRecordActionForm(
                        pending = marketingInlineRecordPending, schema = marketingInlineRecordSchema,
                        actionExecutor = NativeActionExecutor { request ->
                            submissions += request
                            result?.await() ?: NativeActionExecutionResult.Success()
                        },
                        filePicker = null, pendingMutationStore = null, mutationRecovery = recovery,
                        onMutationStarted = { recovery = it.begin(0) },
                        onMutationFinished = { _, completed -> recovery = recovery?.afterExecutionResult(completed) },
                        onDismiss = { editing = false }, onActionSucceeded = { successes++; editing = false },
                        presentation = NativeRecordFormPresentation.Inline,
                    )
                }
            }
        }
    }
}
