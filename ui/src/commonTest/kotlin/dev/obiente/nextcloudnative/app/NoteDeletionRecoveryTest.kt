package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NoteDeletionRecoveryTest {
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
