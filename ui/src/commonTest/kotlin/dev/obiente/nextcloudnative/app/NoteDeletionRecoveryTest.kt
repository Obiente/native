package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteDeletionRecoveryTest {
    @Test
    fun `verified deletion is removed from an already loaded note list`() {
        val retained = NextcloudNote(
            id = 1L,
            title = "Retained",
            modified = 0L,
            category = "",
            favorite = false,
            readOnly = false,
            content = null,
            etag = null,
        )
        val deleted = retained.copy(id = 2L, title = "Deleted")

        assertEquals(listOf(retained), removeVerifiedDeletedNote(listOf(retained, deleted), deleted.id))
        assertNull(removeVerifiedDeletedNote(null, deleted.id))
    }

    @Test
    fun `retryable deletion requires a usable original etag`() {
        val scope = "a".repeat(64)

        assertTrue("\"etag\"".isUsableNoteDeletionEtag())
        assertFalse(null.isUsableNoteDeletionEtag())
        assertFalse("".isUsableNoteDeletionEtag())
        assertFalse("   ".isUsableNoteDeletionEtag())
        assertFalse("etag\nvalue".isUsableNoteDeletionEtag())
        assertFailsWith<IllegalArgumentException> {
            NoteDeletionRecoveryState(
                accountScope = scope,
                noteId = 42L,
                originalEtag = null,
                originalPreconditionRecorded = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NoteDeletionRecoveryState(
                accountScope = scope,
                noteId = 42L,
                originalEtag = "",
                originalPreconditionRecorded = true,
            )
        }
    }

    @Test
    fun `verified deletion releases guards before navigating away`() {
        val events = mutableListOf<String>()

        completeVerifiedNoteDeletion(
            onDeletingChanged = { events += "deleting:$it" },
            onMutationInProgressChanged = { events += "mutation:$it" },
            onBack = { events += "back" },
        )

        assertEquals(listOf("deleting:false", "mutation:false", "back"), events)
    }

    @Test
    fun `mismatched recovery releases the guard before leaving the stale editor`() {
        val events = mutableListOf<String>()

        navigateAfterReleasingMutationGuard(
            onMutationInProgressChanged = { events += "mutation:$it" },
            onNavigate = { events += "navigate" },
        )

        assertEquals(listOf("mutation:false", "navigate"), events)
    }

    @Test
    fun `note deletion recovery is account scoped and rejects malformed state`() {
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "person",
            appPassword = "secret",
        )
        val scope = durableMutationAccountScope(session)
        val recovery = NoteDeletionRecoveryState(
            scope,
            noteId = 42L,
            originalEtag = "\"etag-before-delete\"",
            originalPreconditionRecorded = true,
        )
        val encoded = recovery.encodeForDurableStorage()

        assertEquals(recovery, decodeNoteDeletionRecoveryState(encoded, scope))
        assertNull(decodeNoteDeletionRecoveryState(encoded, "f".repeat(64)))
        assertNull(decodeNoteDeletionRecoveryState("not-json", scope))
        assertFailsWith<IllegalArgumentException> { NoteDeletionRecoveryState(scope, noteId = -1L) }
    }

    @Test
    fun `legacy note deletion recovery remains readable but cannot claim an original precondition`() {
        val scope = "a".repeat(64)
        val legacy = """{"accountScope":"$scope","noteId":42}"""

        val decoded = requireNotNull(decodeNoteDeletionRecoveryState(legacy, scope))

        assertNull(decoded.originalEtag)
        assertEquals(false, decoded.originalPreconditionRecorded)
    }
}
