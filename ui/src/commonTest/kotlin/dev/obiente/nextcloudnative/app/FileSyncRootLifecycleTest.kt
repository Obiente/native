package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncRootLifecycleTest {
    @Test
    fun `delivery followed by disposal before recomposition abandons the delivered root`() {
        var pendingRoot: FileSyncLocalRoot? = null
        val abandoned = mutableListOf<FileSyncLocalRoot>()
        val dispose = fileSyncRootDisposal({ pendingRoot }, abandoned::add)
        val deliveredRoot = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes")

        pendingRoot = deliveredRoot
        dispose()

        assertEquals(listOf(deliveredRoot), abandoned)
    }
}
