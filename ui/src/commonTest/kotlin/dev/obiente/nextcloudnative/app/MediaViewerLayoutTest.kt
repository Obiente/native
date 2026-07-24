package dev.obiente.nextcloudnative.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaViewerLayoutTest {
    @Test
    fun mediaAlwaysUsesTheFullCanvasBehindOverlayChrome() {
        listOf(0, 1, 2, 6).forEach { sourceCount ->
            assertEquals(
                MediaViewerContentLayout.FullCanvasBehindChrome,
                resolveMediaViewerLayout(sourceCount).contentLayout,
            )
        }
    }

    @Test
    fun multipleSourcesUseOneBoundedScrollableRow() {
        val layout = resolveMediaViewerLayout(sourceChoiceCount = 2)

        assertEquals(
            MediaViewerSourceChoiceLayout.SeparateScrollableRow,
            layout.sourceChoiceLayout,
        )
        assertEquals(104.dp, layout.chromeContentHeight)
        assertEquals(48.dp, layout.sourceChoiceRowHeight)
    }

    @Test
    fun singleSourceKeepsTheHeaderCompact() {
        val layout = resolveMediaViewerLayout(sourceChoiceCount = 1)

        assertEquals(MediaViewerSourceChoiceLayout.Hidden, layout.sourceChoiceLayout)
        assertEquals(56.dp, layout.chromeContentHeight)
        assertEquals(null, layout.sourceChoiceRowHeight)
    }

    @Test
    fun invalidSourceCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            resolveMediaViewerLayout(sourceChoiceCount = -1)
        }
    }
}
