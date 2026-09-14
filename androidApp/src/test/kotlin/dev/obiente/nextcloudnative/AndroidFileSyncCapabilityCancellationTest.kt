package dev.obiente.nextcloudnative

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException

class AndroidFileSyncCapabilityCancellationTest {
    @Test
    fun storageAndCipherCancellationAreNotWrappedAsRecoveryFailures() {
        for (stage in listOf("read", "decrypt", "encrypt", "write")) {
            val fixture = Fixture()
            fixture.cancelAt = stage
            val failure = assertFailsWith<CancellationException> {
                if (stage == "read" || stage == "decrypt") fixture.store.list()
                else fixture.store.remove(fixture.record.id, AndroidFileSyncCapabilityPhase.CleanupPending)
            }
            assertSame(fixture.cancellation, failure)
        }
    }

    @Test
    fun cleanupPropagatesCancellationFromEveryGrantAndPersistenceBoundary() {
        for (stage in listOf("query", "release", "verify", "read", "decrypt", "encrypt", "write", "fallback")) {
            val fixture = Fixture()
            fixture.cancelAt = stage
            val failure = assertFailsWith<CancellationException> {
                fixture.lifecycle.finishPairCleanupOrRetry(PAIR_ID, allowDeferredCleanup = true) {
                    AndroidFileSyncPersistedState()
                }
            }
            assertSame(fixture.cancellation, failure)
        }
    }

    private class Fixture {
        var cancelAt: String? = null
        val cancellation = CancellationException("synthetic cancellation")
        private var encrypted: String? = null
        private var released = false
        private var queries = 0
        private var removalWriteFailed = false
        private fun checkpoint(stage: String) { if (cancelAt == stage) throw cancellation }
        val store = AndroidFileSyncCapabilityStore(
            object : AndroidFileSyncCapabilityEncryptedStorage {
                override fun read(): String? {
                    checkpoint("read")
                    if (cancelAt == "fallback" && removalWriteFailed) throw cancellation
                    return encrypted
                }
                override fun write(value: String): Boolean {
                    checkpoint("write")
                    if (cancelAt == "fallback") { removalWriteFailed = true; error("synthetic write failure") }
                    encrypted = value
                    return true
                }
            },
            object : AndroidFileSyncCapabilityCipher {
                override fun encrypt(value: String): String { checkpoint("encrypt"); return value }
                override fun decrypt(value: String): String { checkpoint("decrypt"); return value }
            },
        )
        val record = AndroidFileSyncCapabilityRecord(
            id = UUID.randomUUID().toString(), uri = "content://example.documents/tree/folder",
            displayName = "Folder", phase = AndroidFileSyncCapabilityPhase.CleanupPending,
            processGeneration = UUID.randomUUID().toString(), preExistingReadGrant = false,
            preExistingWriteGrant = false, pairIds = setOf(PAIR_ID),
        ).also(store::add)
        val lifecycle = AndroidFileSyncCapabilityLifecycle(
            store,
            object : AndroidFileSyncGrantAccess {
                override fun exactGrant(uri: String): AndroidFileSyncGrantState {
                    queries += 1
                    checkpoint(if (queries == 1) "query" else "verify")
                    return AndroidFileSyncGrantState(!released, !released)
                }
                override fun takeExactReadWriteGrant(uri: String) = Unit
                override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
                    checkpoint("release")
                    released = true
                }
            },
            record.processGeneration,
        )
    }

    private companion object {
        const val PAIR_ID = "00000000-0000-0000-0000-000000000001"
    }
}
