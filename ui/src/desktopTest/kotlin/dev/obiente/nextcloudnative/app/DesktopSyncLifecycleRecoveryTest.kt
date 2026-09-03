package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSyncLifecycleRecoveryTest {
    @Test
    fun secureStorageFailureLeavesTheNextBackgroundReconciliationRetryable() = runBlocking {
        val expected = NextcloudSession("https://cloud.invalid", "alice", "synthetic-secret")
        var attempts = 0
        val reconciled = mutableListOf<NextcloudSession?>()
        val load = {
            attempts += 1
            if (attempts == 1) {
                throw NextcloudSessionStorageUnavailableException("synthetic locked keychain")
            }
            expected
        }

        assertFalse(reconcileDesktopBackgroundSession(load, reconcile = { reconciled += it }))
        assertTrue(reconcileDesktopBackgroundSession(load, reconcile = { reconciled += it }))

        assertEquals(listOf<NextcloudSession?>(expected), reconciled)
    }

    @Test
    fun missingLegacyMigrationProviderDefersBackgroundReconciliation() = runBlocking {
        var reconciled = false

        assertFalse(
            reconcileDesktopBackgroundSession(
                loadSession = {
                    throw NextcloudSessionLegacyMigrationUnavailableException(
                        NextcloudSessionStorageUnavailableException("synthetic missing provider"),
                    )
                },
                reconcile = { reconciled = true },
            ),
        )
        assertFalse(reconciled)
    }
}
