package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncSetupRestorationTest {
    private val reference = FileSyncLocalRoot("opaque-id", "Notes", "opaque-id")
    private val restored = FileSyncLocalRoot("content://test/tree/notes", "Notes", "opaque-id")

    @Test
    fun dismissalDuringRestoreReleasesTheResolvedCapability() = runBlocking {
        val draft = FileSyncSetupDraftState().apply { localRoot.value = reference }
        val abandoned = mutableListOf<FileSyncLocalRoot>()
        assertTrue(restoreAndReconcileFileSyncRootSetup(draft,
            restore = { draft.clear(); restored },
            reconcile = { error("Stale restoration cannot reconcile") },
            abandon = { abandoned += it; true },
        ))
        assertEquals(listOf(restored), abandoned)
        assertNull(draft.localRoot.value)
    }

    @Test
    fun replacementDuringReconciliationRetainsTheNewDraftAndReleasesTheOldRoot() = runBlocking {
        val draft = FileSyncSetupDraftState().apply { localRoot.value = reference }
        val replacement = FileSyncLocalRoot("another-root", "Other")
        val abandoned = mutableListOf<FileSyncLocalRoot>()
        restoreAndReconcileFileSyncRootSetup(draft, restore = { restored },
            reconcile = { draft.localRoot.value = replacement; true },
            abandon = { abandoned += it; true },
        )
        assertEquals(replacement, draft.localRoot.value)
        assertEquals(listOf(restored), abandoned)
    }

    @Test
    fun failedReconciliationKeepsTheResolvedRootAvailableForAbandonment() = runBlocking {
        val draft = FileSyncSetupDraftState().apply { localRoot.value = reference }
        kotlin.test.assertFalse(restoreAndReconcileFileSyncRootSetup(draft,
            restore = { restored }, reconcile = { false }, abandon = { error("Still owned by draft") },
        ))
        assertEquals(restored, draft.localRoot.value)
    }

    @Test
    fun cancelledReconciliationReleasesTheRestoredRoot() = runBlocking {
        val draft = FileSyncSetupDraftState().apply { localRoot.value = reference }
        val abandoned = mutableListOf<FileSyncLocalRoot>()
        assertFailsWith<CancellationException> {
            restoreAndReconcileFileSyncRootSetup(draft, restore = { restored },
                reconcile = { throw CancellationException() },
                abandon = { abandoned += it; true },
            )
        }
        assertEquals(listOf(restored), abandoned)
    }
}
