package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DynamicHeaderCreateRecoveryUiTest {
    private val evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Verified fixture route"))
    private val resource = ResourceSpec("items", "Items", Confidence.verified, fields = listOf(
        FieldSpec("id", "ID", FieldKind.string, false, readOnly = true),
        FieldSpec("name", "Name", FieldKind.string, true, readOnly = false),
    ))
    private val read = ActionSpec("items.list", "Items", "items",
        ApiBinding(HttpMethod.GET, "/apps/example/items", "items.list"), ActionIntent.list,
        ActionRisk.readOnly, false, Confidence.verified, evidence = evidence, effect = ActionEffect.list)
    private val create = read.copy(id = "items.create", label = "Create item", intent = ActionIntent.create,
        risk = ActionRisk.mutating, effect = ActionEffect.create, binding = read.binding.copy(
            method = HttpMethod.POST, operationId = "items.create", bodyFieldNames = listOf("name"),
            requiredBodyFieldNames = listOf("name"), bodyContentType = "application/json"))
    private val view = ViewSpec("items", "Items", "items", NativeComponent.collectionList, read.id, Confidence.verified)
    private val schema = NativeAppSchema("1", AppIdentity("example", "Example", "1"), Confidence.verified,
        resources = listOf(resource), actions = listOf(read, create), views = listOf(view))

    @Test
    fun headerUsesDurableCreateAndRecreationCannotReplayUnknownDelivery() = onSceneThread {
        listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
            val store = MemoryStore()
            var requests = 0
            val executor = NativeActionExecutor {
                requests += 1
                assertEquals(NativeCreateMutationPhase.TransportMayHaveObserved,
                    nativeCreateMutationPostcondition(store.values.keys.single(), store.values.values.single())?.phase)
                NativeActionExecutionResult.Failure("Response lost", NativeActionFailureOutcome.Unknown)
            }
            repeat(2) { recreation ->
                val control = NativeCollectionCreateControl()
                val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme { Surface { Column {
                        DynamicCollectionHeaderActions(schema, "Example", control, emptyList(), emptyList(), false,
                            {}, { _, _ -> error("Header must not navigate to GenericNativeForm") }, { _, _ -> })
                        GenericNativeAppScreen(schema, view, NativeScreenState.Ready(emptyList()), executor,
                            showCollectionCreateAction = true, collectionCreateControl = control,
                            pendingMutationStore = store)
                    } } }
                }
                try {
                    scene.settle()
                    assertEquals(create.id, assertNotNull(control.action).id)
                    scene.clickDescription("Create item")
                    scene.settle()
                    val field = scene.nodes().single { SemanticsActions.SetText in it.config }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Synthetic item")))
                    scene.settle()
                    if (recreation == 0) scene.capture("header-durable-create-$width")
                    scene.clickText("Create")
                    scene.settle()
                    assertEquals(1, requests, "Recreated forms must reconcile the saved marker, not resubmit")
                    assertEquals(1, store.values.size)
                } finally { scene.close() }
                assertNull(control.action)
                control.open(create.id)
                assertEquals(1, requests)
            }
        }
    }

    @Test
    fun headerAndInlineCreatesSharePagingEvidenceAndStoreGates() = onSceneThread {
        var complete by mutableStateOf(false)
        var ready by mutableStateOf(true)
        var storeAvailable by mutableStateOf(true)
        var supported by mutableStateOf(true)
        val control = NativeCollectionCreateControl()
        val store = MemoryStore()
        val scene = ImageComposeScene(390, 844, Density(1f), coroutineContext = coroutineContext) {
            val activeSchema = if (supported) schema else schema.copy(actions = listOf(read, create.copy(
                binding = create.binding.copy(path = "/apps/example/unverifiable"))))
            MaterialTheme { Surface { Column {
                DynamicCollectionHeaderActions(activeSchema, "Example", control, emptyList(), emptyList(), false,
                    {}, { _, _ -> error("No generic create navigation") }, { _, _ -> })
                GenericNativeAppScreen(activeSchema, view,
                    if (ready) NativeScreenState.Ready(emptyList()) else NativeScreenState.Loading,
                    NativeActionExecutor { error("No write expected") }, showCollectionCreateAction = true,
                    collectionCreateControl = control, pendingMutationStore = store.takeIf { storeAvailable },
                    onLoadMore = if (complete) null else ({ }))
            } } }
        }
        try {
            scene.settle()
            assertNull(control.action)
            assertTrue(scene.nodes().none { it.description() == "Create item" })
            complete = true
            scene.settle()
            assertNotNull(control.action)
            ready = false
            scene.settle()
            assertNull(control.action)
            ready = true
            storeAvailable = false
            scene.settle()
            assertNull(control.action)
            storeAvailable = true
            supported = false
            scene.settle()
            assertNull(control.action)
            supported = true
            scene.settle()
            assertNotNull(control.action)
        } finally { scene.close() }
        assertNull(control.action)

        val form = view.copy(id = "create", component = NativeComponent.form, sourceActionId = create.id)
        val actions = listOf(DynamicNavigationFormAction(form.id, "Create item", "items", create.id) to form)
        assertTrue(dynamicHeaderOverflowActions(schema, actions).isEmpty())
    }

    private class MemoryStore : NativePendingMutationStore {
        val values = mutableMapOf<NativePendingMutationKey, Map<String, String>>()
        override suspend fun load(key: NativePendingMutationKey) = values[key]
        override suspend fun save(key: NativePendingMutationKey, values: Map<String, String>) { this.values[key] = values }
        override suspend fun clear(key: NativePendingMutationKey) { values.remove(key) }
        override suspend fun postconditionSatisfied(key: NativePendingMutationKey, values: Map<String, String>) = false
    }

    private fun onSceneThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher, block) }
    }
    private suspend fun ImageComposeScene.settle() { repeat(16) { render(System.nanoTime()).close(); yield() } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
        return semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    }
    private fun SemanticsNode.description() = config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.singleOrNull()
    private fun ImageComposeScene.clickDescription(label: String) {
        val node = nodes().single { it.description() == label && SemanticsActions.OnClick in it.config }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.clickText(label: String) {
        val node = nodes().single { SemanticsActions.OnClick in it.config &&
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label } }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.capture(name: String) {
        render(System.nanoTime()).use { image ->
            val path = Path.of("build/reports/$name.png")
            Files.createDirectories(path.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) }
        }
    }
}
