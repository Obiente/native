package dev.obiente.nextcloudnative

import java.io.IOException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidDurableUploadQueueRecoveryTest {
    @Test fun temporaryKeystoreFailureRetriesButDamagedCiphertextDoesNot() {
        listOf(IOException("unavailable"), ProviderException("keystore unavailable")).forEach {
            assertEquals(DurableUploadQueueRecoveryDisposition.Retry, durableUploadQueueDecryptionDisposition(it))
        }
        listOf(AEADBadTagException("invalid authentication tag"), IllegalArgumentException("invalid encoding"),
            IOException("wrapped", AEADBadTagException("invalid tag"))).forEach {
            assertEquals(DurableUploadQueueRecoveryDisposition.Quarantine, durableUploadQueueDecryptionDisposition(it))
        }
    }

    @Test fun workerRetriesKeystoreFailureDuringStoreConstruction() = runBlocking {
        val outcome = withDurableUploadQueueRecovery({ "retry" }, { "preserve" }) {
            constructDurableUploadQueueOwner { throw IOException("keystore unavailable during construction") }
        }
        assertEquals("retry", outcome)
    }

    @Test fun workerDefersInitialQueueReadAndPreservesCancellation() = runBlocking {
        for (disposition in DurableUploadQueueRecoveryDisposition.entries) {
            val outcome = withDurableUploadQueueRecovery({ "retry" }, { "preserve" }) {
                throw AndroidDurableMultipartUploadRecoveryException(IOException("unavailable"), disposition)
            }
            assertEquals(if (disposition == DurableUploadQueueRecoveryDisposition.Retry) "retry" else "preserve", outcome)
        }
        assertFailsWith<CancellationException> {
            withDurableUploadQueueRecovery({ "retry" }, { "preserve" }) { throw CancellationException("cancelled") }
        }
        Unit
    }
}
