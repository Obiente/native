package dev.obiente.nextcloudnative

import java.io.IOException
import javax.crypto.BadPaddingException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AndroidDurableUploadRegistryRecoveryTest {
    @Test
    fun malformedRegistryAndDamagedOrMissingAggregateNeedExplicitRecovery() {
        assertTrue(durableUploadRegistryNeedsRecovery({ "not-json" }, { "not-json" }, { it }))
        assertTrue(durableUploadRegistryNeedsRecovery({ "not-json" }, { null }, { it }))
        assertTrue(durableUploadRegistryNeedsRecovery({ "not-json" }, { throw ClassCastException() }, { it }))
        assertTrue(durableUploadRegistryNeedsRecovery({ "not-json" }, { "ciphertext" }, { throw BadPaddingException() }))
        assertTrue(durableUploadRegistryNeedsRecovery({ "not-json" }, { "{\"version\":999}" }, { it }))
    }

    @Test
    fun temporaryAggregateOrRegistryAccessStillRetries() {
        assertFalse(durableUploadRegistryNeedsRecovery({ "not-json" }, { "ciphertext" }, { throw IOException() }))
        assertFalse(durableUploadRegistryNeedsRecovery({ throw IOException() }, { null }, { it }))
        val valid = encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty)
        assertFalse(durableUploadRegistryNeedsRecovery({ "not-json" }, { valid }, { it }))
        assertFalse(durableUploadRegistryNeedsRecovery({ null }, { "not-json" }, { it }))
    }

    @Test
    fun registryAndAggregateCancellationPropagate() {
        assertFailsWith<CancellationException> {
            durableUploadRegistryNeedsRecovery({ throw CancellationException() }, { null }, { it })
        }
        assertFailsWith<CancellationException> {
            durableUploadRegistryNeedsRecovery({ "not-json" }, { "ciphertext" }, { throw CancellationException() })
        }
    }
}
