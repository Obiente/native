package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSessionStorageUnavailableException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidDurableUploadStartupRecoveryTest {
    @Test
    fun unavailableStartupCredentialsRetainTheWorkerRetryDisposition() {
        val result = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = "a".repeat(32),
            readRegistry = { DurableUploadAccountRegistry.Unavailable },
            recoverRegistry = { throw NextcloudSessionStorageUnavailableException("Synthetic locked storage") },
            loadSession = { error("Unavailable registry must not resolve a session") },
        )
        assertEquals(DurableUploadAccountResolution.RegistryUnavailable, result)
    }

    @Test
    fun startupCancellationStillPropagates() {
        assertFailsWith<CancellationException> {
            resolveDurableUploadSessionWithRegistryRecovery(
                expectedAccountId = "a".repeat(32),
                readRegistry = { DurableUploadAccountRegistry.Unavailable },
                recoverRegistry = { throw CancellationException() },
                loadSession = { null },
            )
        }
    }
}
