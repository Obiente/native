package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.localUploadFile
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadCleanupPruningTest {
    @Test
    fun `pruning retains terminal rows until capability cleanup commits`() {
        val pending = fixtureJob(
            index = 1,
            cleanupPending = true,
            updatedAt = 0L,
        )
        val history = (2..70).map { index -> fixtureJob(index = index, updatedAt = index.toLong()) }

        val pruned = pruneDurableUploadJobs(history + pending)

        assertEquals(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS, pruned.size)
        assertTrue(pending in pruned)
        assertFalse(pruned.any { job -> job.id == fixtureId(2) })
    }

    @Test
    fun `terminal transition persists cleanup until its commit`() {
        val storage = MemoryStorage()
        val store = AndroidDurableMultipartUploadStore(storage, PlaintextCipher)
        val queued = fixtureJob(index = 1, state = DurableUploadState.Queued)
        store.add(queued)

        store.transition(queued.id, DurableUploadState.Queued, DurableUploadState.Failed, "failed")

        assertTrue(AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single().capabilityCleanupPending)
        store.completeCapabilityCleanup(queued.id)
        assertFalse(AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single().capabilityCleanupPending)
    }

    @Test
    fun `pending cleanup consumes bounded queue capacity`() {
        val pending = (1..AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS).map { index ->
            fixtureJob(index = index, cleanupPending = true)
        }

        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                current = pending,
                job = fixtureJob(index = 100, state = DurableUploadState.Queued),
            )
        }
    }

    @Test
    fun `cleanup commit failure retries and preserves cancellation`() {
        assertEquals(
            "retry",
            resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { true },
                completeCapabilityCleanup = { error("queue unavailable") },
                releasedResult = "finished",
                retainedResult = "retry",
            ),
        )
        assertFailsWith<CancellationException> {
            resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { true },
                completeCapabilityCleanup = { throw CancellationException("worker stopped") },
                releasedResult = "finished",
                retainedResult = "retry",
            )
        }
    }

    private fun fixtureJob(
        index: Int,
        state: DurableUploadState = DurableUploadState.Completed,
        cleanupPending: Boolean = false,
        updatedAt: Long = index.toLong(),
    ): AndroidDurableMultipartUploadJob {
        val cardId = index.toLong()
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$cardId/attachments",
            file = localUploadFile(
                selectionId = "selection-${index.toString().padStart(16, '0')}",
                displayName = "fixture-$index.txt",
                mimeType = "text/plain",
                sizeBytes = 16L,
            ),
            maximumFileBytes = 1024L,
        )
        return AndroidDurableMultipartUploadJob(
            id = fixtureId(index),
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = state,
            message = null,
            capabilityCleanupPending = cleanupPending,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun fixtureId(index: Int) = "upload-${index.toString().padStart(16, '0')}"

    private class MemoryStorage(var value: String? = null) : AndroidDurableMultipartUploadEncryptedStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean = true.also { this.value = value }
    }

    private object PlaintextCipher : AndroidDurableMultipartUploadCipher {
        override fun encrypt(value: String): String = value
        override fun decrypt(value: String): String = value
    }
}
