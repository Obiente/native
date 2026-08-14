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
        val recovery = NoteDeletionRecoveryState(scope, noteId = 42L)
        val encoded = recovery.encodeForDurableStorage()

        assertEquals(recovery, decodeNoteDeletionRecoveryState(encoded, scope))
        assertNull(decodeNoteDeletionRecoveryState(encoded, "f".repeat(64)))
        assertNull(decodeNoteDeletionRecoveryState("not-json", scope))
        assertFailsWith<IllegalArgumentException> { NoteDeletionRecoveryState(scope, noteId = -1L) }
    }
}
