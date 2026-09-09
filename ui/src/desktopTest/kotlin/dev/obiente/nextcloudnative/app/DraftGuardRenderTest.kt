package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericRepeatableObjectField
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRepeatableObjectDraftState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftGuardRenderTest {
    @Test
    fun rendersReadOnlyTaskAndRejectedStructuredInputAtPhoneAndDesktopSizes() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                val spec = RepeatableObjectInputSpec(0, 32, listOf(
                    RepeatableObjectInputFieldSpec("label", "Label", RepeatableObjectInputScalarKind.String, true),
                ))
                val original = listOf(RepeatableObjectInputRow(mapOf("label" to "Keep this accepted draft")))
                val state = NativeRepeatableObjectDraftState(mapOf("items" to original), mapOf("items" to spec))
                state.update("items", List(5) { RepeatableObjectInputRow(mapOf("label" to "x".repeat(4_096))) })
                listOf(390 to 844, 1280 to 800).forEach { (width, height) ->
                    listOf("task", "structured").forEach { scenario ->
                        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                            MaterialTheme {
                                Surface {
                                    if (scenario == "task") {
                                        TaskEditorDialog(
                                            task = GroupwareTask("/tasks/one.ics", "\"one\"", "/tasks/", "one",
                                                title = "Large task", description = "x".repeat(40_000), rawCalendar = ""),
                                            calendars = listOf(GroupwareCalendar("/tasks/", "Tasks")),
                                            mutationInProgress = false, error = null, onDismiss = {},
                                            onSave = { _, _, _ -> error("Read-only task must not submit") },
                                        )
                                    } else Column(Modifier.padding(16.dp)) {
                                        Text("Structured form", style = MaterialTheme.typography.titleLarge)
                                        GenericRepeatableObjectField(
                                            FieldSpec("items", "Items", FieldKind.objectValue, required = false,
                                                readOnly = false, repeatableObjectInput = spec),
                                            spec, state.values.getValue("items"), state.error, true,
                                            onRowsChange = { state.update("items", it) },
                                        )
                                    }
                                }
                            }
                        }
                        try {
                            repeat(3) { scene.render().close() }
                            scene.render().use { rendered ->
                                assertEquals(width, rendered.width)
                                val output = Path.of("build/reports/draft-guard-$scenario-$width.png")
                                Files.createDirectories(output.parent)
                                rendered.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
                            }
                        } finally { scene.close() }
                    }
                }
            }
        }
    }
}
