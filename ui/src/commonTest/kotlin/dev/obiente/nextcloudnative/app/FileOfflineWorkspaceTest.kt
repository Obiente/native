package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileOfflineWorkspaceTest {
    @Test
    fun `folder sync destination owns the complete sync workspace`() {
        assertEquals(
            listOf("Folder sync", "Offline files", "Virtual files"),
            FileOfflineWorkspaceSection.entries.map(FileOfflineWorkspaceSection::title),
        )
    }
}
