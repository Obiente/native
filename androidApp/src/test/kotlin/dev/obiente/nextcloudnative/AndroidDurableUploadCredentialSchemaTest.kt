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
            assertTrue(durableUploadCredentialNeedsUpgrade(listOf("aggregate", "slot"),
                { if (it == future) "{\"version\":999}" else null }, { it }))
        }
        assertFalse(durableUploadCredentialNeedsUpgrade(listOf("slot"), { "encrypted" }, { error("synthetic locked keystore") }))
        assertFalse(durableUploadCredentialNeedsUpgrade(listOf("slot"), { null }, { it }))
        assertFailsWith<CancellationException> {
            durableUploadCredentialNeedsUpgrade(listOf("slot"), { throw CancellationException() }, { it })
        }
    }
}
