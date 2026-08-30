package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCollectionCreateControl
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativePendingMutationKey
import dev.obiente.nextcloudnative.nativeui.runtime.NativePendingMutationStore
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicHeaderInlineEditorGuardTest {
    @Test
    fun durableHeaderCreateCannotReplaceAnUnsavedInlineEditWithoutDiscardingIt() {
        for (width in listOf(390, 1280)) {
            val control = NativeCollectionCreateControl()
            val navigation = NativeInlineEditorNavigation()
            val store = RejectUnexpectedWritesStore()
            assertNotNull(nativeRecordActions(schema, resource, record).edit,
                "The synthetic record must expose a permitted exact-target edit")
            nativeSceneTest(width, 900, content = {
                CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column {
                            DynamicCollectionHeaderActions(
                                schema, "Example", control, emptyList(), emptyList(), false,
                                {}, { _, _ -> error("Create must use the durable renderer form") }, { _, _ -> },
                            )
                            GenericNativeAppScreen(
                                schema, view, NativeScreenState.Ready(listOf(record)),
                                NativeActionExecutor { error("Opening a form must not write server data") },
                                showCollectionCreateAction = true, collectionCreateControl = control,
                                pendingMutationStore = store,
                            )
                        }
                    }
                }
            }) {
                assertEquals(create.id, assertNotNull(control.action).id)
                click("Actions for Existing item")
                click("Edit")
                assertTrue(navigation.active)
                replaceText("Existing item", "Unsaved item name")
                click("Create item")
                assertTrue(has("Discard unsaved changes?"))
                assertTrue(has("Save changes"), "The inline editor must remain mounted until the user decides")
                click("Keep editing")
                assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved item name" })
                assertTrue(navigation.active)
                click("Create item")
                click("Discard changes")
                assertFalse(navigation.active)
                assertFalse(has("Save changes"))
                assertTrue(has("Create"), "Explicit discard should continue to the renderer's durable create form")
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved item name" })
                assertEquals(0, store.writes)
                capture("dynamic-header-guarded-create-$width")
            }
        }
    }

    private val resource = ResourceSpec("items", "Items", Confidence.verified, fields = listOf(
        FieldSpec("id", "ID", FieldKind.string, required = false, readOnly = true),
        FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
        FieldSpec("canEdit", "Can edit", FieldKind.boolean, required = false, readOnly = true),
    ))
    private val read = ActionSpec(
        "items.list", "Items", "items", ApiBinding(HttpMethod.GET, "/apps/example/items", "items.list"),
        ActionIntent.list, ActionRisk.readOnly, false, Confidence.verified,
        evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Verified synthetic fixture route")),
        effect = ActionEffect.list,
    )
    private val create = read.copy(
        id = "items.create", label = "Create item", intent = ActionIntent.create, risk = ActionRisk.mutating,
        effect = ActionEffect.create, binding = read.binding.copy(
            method = HttpMethod.POST, operationId = "items.create", bodyFieldNames = listOf("name"),
            requiredBodyFieldNames = listOf("name"), bodyContentType = "application/json",
        ),
    )
    private val edit = create.copy(
        id = "items.update", label = "Edit", intent = ActionIntent.update, effect = ActionEffect.update,
        binding = create.binding.copy(
            method = HttpMethod.PATCH, path = "/apps/example/items/{itemId}", operationId = "items.update",
            pathParameterNames = listOf("itemId"), requiredPathParameterNames = listOf("itemId"),
        ),
    )
    private val view = ViewSpec("items", "Items", "items", NativeComponent.collectionList, read.id, Confidence.verified)
    private val schema = NativeAppSchema(
        "1", AppIdentity("example", "Example", "fixture"), Confidence.verified,
        resources = listOf(resource), actions = listOf(read, create, edit), views = listOf(view),
    )
    private val record = NativeRecord("synthetic-item", mapOf("id" to "synthetic-item", "name" to "Existing item", "canEdit" to "true"))

    private class RejectUnexpectedWritesStore : NativePendingMutationStore {
        var writes = 0
        override suspend fun load(key: NativePendingMutationKey): Map<String, String>? = null
        override suspend fun save(key: NativePendingMutationKey, values: Map<String, String>) {
            writes++
            error("Opening the create form must not stage a mutation")
        }
        override suspend fun clear(key: NativePendingMutationKey) = error("No mutation should exist to clear")
        override suspend fun postconditionSatisfied(key: NativePendingMutationKey, values: Map<String, String>) = false
    }
}
