package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FilesWorkspacePanesTest {
    @Test
    fun selectionReplacesSecondaryNavigationAtIntermediateWidths() {
        assertEquals(FilesWorkspacePanes(true, true, false), resolveFilesWorkspacePanes(1000, true, false, false, false))
        assertEquals(FilesWorkspacePanes(true, false, true), resolveFilesWorkspacePanes(1000, true, false, false, true))
        assertEquals(FilesWorkspacePanes(true, true, true), resolveFilesWorkspacePanes(1400, true, false, false, true))
    }

    @Test
    fun collapsePreferencesAndPlatformAreRespected() {
        assertEquals(FilesWorkspacePanes(true, false, false), resolveFilesWorkspacePanes(1400, true, true, true, true))
        assertEquals(FilesWorkspacePanes(false, false, false), resolveFilesWorkspacePanes(1400, false, false, false, true))
        assertEquals(FilesWorkspacePanes(false, false, false), resolveFilesWorkspacePanes(600, true, false, false, true))
    }
}
