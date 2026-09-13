package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncRootLifecycleTest {
    @Test
    fun `delivery followed by disposal before recomposition abandons the delivered root`() {
        var pendingRoot: FileSyncLocalRoot? = null
        val abandoned = mutableListOf<FileSyncLocalRoot>()
        val dispose = fileSyncRootDisposal({ pendingRoot }, abandoned::add)
        val deliveredRoot = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes", savedStateId = "opaque-record-id")

        pendingRoot = deliveredRoot
        dispose()

        assertEquals(listOf(deliveredRoot), abandoned)
    }

    @Test
    fun `activity recreation retains the delivered root for restored setup`() {
        val deliveredRoot = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes", savedStateId = "opaque-record-id")
        val abandoned = mutableListOf<FileSyncLocalRoot>()

        fileSyncRootDisposal(
            currentRoot = { deliveredRoot },
            retainRoot = { true },
            abandon = abandoned::add,
        ).invoke()

        assertTrue(abandoned.isEmpty())
    }

    @Test
    fun `setup draft restores the selected root destination and configuration`() {
        val draft = FileSyncSetupDraftState().apply {
            localRoot.value = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes", savedStateId = "opaque-record-id")
            mediaSuggestionJson.value = "{\"kind\":\"notes\"}"
            remotePath.value = "Shared/Notes"
            configurationJson.value = "{\"direction\":\"Bidirectional\"}"
            remoteFolderPickerVisible.value = true
            selectionPickerVisible.value = true
        }

        val restored = assertNotNull(FileSyncSetupDraftState.restore(assertNotNull(draft.savedState())))

        assertEquals("opaque-record-id", restored.localRoot.value?.localRootId)
        assertFalse(assertNotNull(draft.savedState()).any { it.contains("content://") })
        assertEquals(draft.mediaSuggestionJson.value, restored.mediaSuggestionJson.value)
        assertEquals(draft.remotePath.value, restored.remotePath.value)
        assertEquals(draft.configurationJson.value, restored.configurationJson.value)
        assertTrue(restored.remoteFolderPickerVisible.value)
        assertTrue(restored.selectionPickerVisible.value)
    }

    @Test
    fun `oversized optional setup retains the selected root across recreation`() {
        val root = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes", savedStateId = "opaque-record-id")
        val draft = FileSyncSetupDraftState().apply {
            localRoot.value = root
            configurationJson.value = "x".repeat(32 * 1024)
        }

        val restored = assertNotNull(FileSyncSetupDraftState.restore(assertNotNull(draft.savedState())))

        assertEquals("opaque-record-id", restored.localRoot.value?.localRootId)
        assertNull(restored.configurationJson.value)
    }

    @Test
    fun `capability without an opaque saved reference is never serialized`() {
        val draft = FileSyncSetupDraftState().apply {
            localRoot.value = FileSyncLocalRoot("content://example.documents/tree/private", "Folder")
        }
        val saved = assertNotNull(draft.savedState())
        assertFalse(saved.any { it.contains("content://") })
        assertNull(assertNotNull(FileSyncSetupDraftState.restore(saved)).localRoot.value)
    }

    @Test
    fun `failed abandonment keeps the root available for retry`() {
        val root = FileSyncLocalRoot("content://example.documents/tree/notes", "Notes", savedStateId = "opaque-record-id")
        val draft = FileSyncSetupDraftState().apply {
            localRoot.value = root
            remotePath.value = "Shared/Notes"
            configurationJson.value = "configuration"
            remoteFolderPickerVisible.value = true
        }

        assertFalse(draft.abandon { false })
        assertEquals(root, draft.localRoot.value)
        assertNull(draft.remotePath.value)
        assertNull(draft.configurationJson.value)
        assertFalse(draft.remoteFolderPickerVisible.value)

        assertFalse(draft.abandon { error("synthetic grant release failure") })
        assertEquals(root, draft.localRoot.value)

        assertTrue(draft.abandon { true })
        assertNull(draft.localRoot.value)
    }
}
