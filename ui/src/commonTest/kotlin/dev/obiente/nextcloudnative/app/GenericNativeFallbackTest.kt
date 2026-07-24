package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericNativeFallbackTest {
    @Test
    fun discoveredAppBuildsReadOnlyMetadataSchemaWithoutActions() {
        val fallback = buildGenericNativeFallback(
            app = NextcloudAppEntry(
                id = "chores",
                name = "Chores",
                href = "/index.php/apps/chores",
            ),
            nativeFamily = "task list",
        )

        assertEquals(Confidence.low, fallback.schema.confidence)
        assertEquals(NativeComponent.detail, fallback.view.component)
        assertTrue(fallback.schema.actions.isEmpty())
        assertTrue(fallback.schema.resources.single().fields.all { it.readOnly })
        assertEquals("chores", fallback.state.records.single().values["id"])
        assertEquals("/index.php/apps/chores", fallback.state.records.single().values["route"])
        assertEquals("metadata-only", fallback.schema.warnings.single().code)
    }
}
