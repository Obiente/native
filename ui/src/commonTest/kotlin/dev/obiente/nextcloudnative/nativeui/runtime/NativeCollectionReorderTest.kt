package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeCollectionReorderTest {
    @Test
    fun `move to visible target ignores an offscreen target`() {
        val result = moveNativeCollectionRecordToVisibleTarget(
            orderedRecordIds = listOf("one", "two", "three"),
            recordId = "one",
            rowBounds = mapOf(
                "one" to Rect(0f, 0f, 100f, 40f),
                "three" to Rect(0f, 80f, 100f, 120f),
            ),
            pointerPosition = Offset(50f, 100f),
            visibleItemKeys = setOf("one"),
        )

        assertEquals(null, result)
    }

    @Test
    fun `move to visible target uses the target position`() {
        val result = moveNativeCollectionRecordToVisibleTarget(
            orderedRecordIds = listOf("one", "two", "three"),
            recordId = "one",
            rowBounds = mapOf(
                "one" to Rect(0f, 0f, 100f, 40f),
                "three" to Rect(0f, 80f, 100f, 120f),
            ),
            pointerPosition = Offset(50f, 100f),
            visibleItemKeys = setOf("one", "three"),
        )

        assertEquals(listOf("two", "three", "one"), result)
    }
}
