package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FilesWorkspacePresentationTest {
    @Test
    fun metadataYieldsSpaceToNamesInNarrowPanes() {
        assertEquals(FileTableColumns(false, false), fileTableColumns(496f, true))
        assertEquals(FileTableColumns(true, false), fileTableColumns(700f, true))
        assertEquals(FileTableColumns(true, true), fileTableColumns(900f, true))
        assertEquals(FileTableColumns(false, false), fileTableColumns(900f, false))
    }

    @Test
    fun davAndIsoDatesAreReadableAndUnknownValuesSurvive() {
        assertEquals("31 Jul 2026", "Fri, 31 Jul 2026 14:00:00 GMT".readableFileDate())
        assertEquals("5 Sep 2026", "2026-09-05T12:30:00Z".readableFileDate())
        assertEquals("Yesterday", "Yesterday".readableFileDate())
        assertEquals("-", (null as String?).readableFileDate())
    }
}
