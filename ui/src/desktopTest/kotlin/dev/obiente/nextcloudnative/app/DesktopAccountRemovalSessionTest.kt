package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopAccountRemovalSessionTest {
    private val current = NextcloudSession("https://cloud.example.test", "alice", "current-secret")
    private val accountId = current.accountId

    @Test
    fun localRemovalDoesNotLoadTheRemoteCredential() {
        assertNull(
            loadDesktopRemoteRevocationSession(accountId, expectedSession = null) {
                error("The unavailable secret store must not block local removal.")
            },
        )
    }

    @Test
    fun remoteRevocationFailsClosedWhenTheCredentialCannotBeLoaded() {
        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
            loadDesktopRemoteRevocationSession(accountId, current) {
                throw DesktopSecretStoreUnavailableException("Synthetic unavailable secret store.")
            }
        }

        assertEquals("Synthetic unavailable secret store.", failure.message)
    }

    @Test
    fun remoteRevocationRejectsAStaleCredential() {
        assertFailsWith<IllegalStateException> {
            loadDesktopRemoteRevocationSession(accountId, current.copy(appPassword = "stale-secret")) { current }
        }
    }

    @Test
    fun remoteRevocationUsesTheVerifiedCurrentCredential() {
        assertEquals(current, loadDesktopRemoteRevocationSession(accountId, current) { current })
    }
}
