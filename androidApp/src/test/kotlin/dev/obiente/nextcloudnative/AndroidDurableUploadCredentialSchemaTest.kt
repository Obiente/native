package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AndroidDurableUploadCredentialSchemaTest {
    @Test
    fun futureAggregateOrSlotStopsRetriesWhileUnreadableSecretsCanRetry() {
        listOf("aggregate", "slot").forEach { future ->
            assertTrue(durableUploadCredentialNeedsRecovery(listOf("aggregate", "slot"),
                { if (it == future) "{\"version\":999}" else null }, { it }))
        }
        assertFalse(durableUploadCredentialNeedsRecovery(listOf("slot"), { "encrypted" }, { error("synthetic locked keystore") }))
        assertFalse(durableUploadCredentialNeedsRecovery(listOf("slot"), { null }, { it }))
        assertFailsWith<CancellationException> {
            durableUploadCredentialNeedsRecovery(listOf("slot"), { throw CancellationException() }, { it })
        }
    }
    @Test
    fun malformedCredentialsPauseWithoutDestroyingTheQueue() {
        assertTrue(durableUploadCredentialNeedsRecovery(listOf("slot"), { throw ClassCastException() }, { it }))
        assertTrue(durableUploadCredentialNeedsRecovery(listOf("slot"), { "invalid" }, { throw javax.crypto.BadPaddingException() }))
        assertTrue(durableUploadCredentialNeedsRecovery(listOf("slot"), { "not json" }, { it }))
    }

    @Test
    fun transientFallbackRemainsRetryableDespiteAnotherDamagedSlot() {
        assertFalse(durableUploadCredentialNeedsRecovery(listOf("aggregate", "slot"),
            { it }, { if (it == "aggregate") throw java.io.IOException() else "not json" }))
        assertTrue(durableUploadCredentialNeedsRecovery(listOf("aggregate", "slot"),
            { it }, { if (it == "aggregate") "{\"version\":999}" else throw java.io.IOException() }))
    }
}
