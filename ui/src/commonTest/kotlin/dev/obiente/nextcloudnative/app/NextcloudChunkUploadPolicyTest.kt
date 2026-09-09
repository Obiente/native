package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NextcloudChunkUploadPolicyTest {
    @Test
    fun `small uploads use a direct streamed request`() {
        assertEquals(
            NextcloudUploadTransferPlan.Direct,
            nextcloudUploadTransferPlan(DIRECT_NEXTCLOUD_UPLOAD_BYTES),
        )
    }

    @Test
    fun `large uploads scale chunks inside the official protocol envelope`() {
        val ordinary = assertIs<NextcloudUploadTransferPlan.Chunked>(
            nextcloudUploadTransferPlan(21L * 1024L * 1024L),
        )
        val large = assertIs<NextcloudUploadTransferPlan.Chunked>(
            nextcloudUploadTransferPlan(120L * 1024L * 1024L * 1024L),
        )

        assertEquals(3, ordinary.chunkCount)
        assertTrue(large.chunkCount <= MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        assertTrue(large.chunkBytes <= MAX_NEXTCLOUD_CHUNK_BYTES)
    }

    @Test
    fun `multi-terabyte files remain inside the official chunk envelope`() {
        val plan = assertIs<NextcloudUploadTransferPlan.Chunked>(
            nextcloudUploadTransferPlan(2L * 1024L * 1024L * 1024L * 1024L),
        )

        assertTrue(plan.chunkCount <= MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        assertTrue(plan.chunkBytes <= MAX_NEXTCLOUD_CHUNK_BYTES)
    }

    @Test
    fun `files outside the official chunk envelope remain directly streamable`() {
        assertEquals(
            NextcloudUploadTransferPlan.Direct,
            nextcloudUploadTransferPlan(MAX_NEXTCLOUD_CHUNK_BYTES * MAX_NEXTCLOUD_UPLOAD_CHUNKS + 1L),
        )
    }

    @Test
    fun `resume positions resolve to stable protocol names and byte ranges`() {
        val size = 25L * 1024L * 1024L + 17L
        val plan = assertIs<NextcloudUploadTransferPlan.Chunked>(nextcloudUploadTransferPlan(size))

        assertEquals(
            NextcloudUploadChunk(number = 1, offsetBytes = 0L, sizeBytes = 10L * 1024L * 1024L),
            nextcloudUploadChunk(plan, size, uploadedChunks = 0),
        )
        val resumed = nextcloudUploadChunk(plan, size, uploadedChunks = 2)
        assertEquals("00003", resumed.remoteName)
        assertEquals(20L * 1024L * 1024L, resumed.offsetBytes)
        assertEquals(5L * 1024L * 1024L + 17L, resumed.sizeBytes)
    }

    @Test
    fun `stale chunk progress cannot be reused for a different file generation`() {
        val originalSize = 30L * 1024L * 1024L
        val plan = assertIs<NextcloudUploadTransferPlan.Chunked>(nextcloudUploadTransferPlan(originalSize))

        assertFailsWith<IllegalArgumentException> {
            nextcloudUploadChunk(plan, 21L * 1024L * 1024L, uploadedChunks = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            nextcloudUploadChunk(plan, originalSize, uploadedChunks = plan.chunkCount)
        }
    }

    @Test
    fun `file sync checkpoint preserves the exact chunk plan`() {
        val size = 25L * 1024L * 1024L
        val plan = assertIs<NextcloudUploadTransferPlan.Chunked>(nextcloudUploadTransferPlan(size))
        val checkpoint = newFileSyncUploadCheckpoint(
            "01234567-89ab-cdef-0123-456789abcdef",
            "local-revision",
            plan,
        ).copy(uploadedChunks = plan.chunkCount, commitInFlight = true)

        assertEquals(plan, checkpoint.transferPlan)
        assertFailsWith<IllegalArgumentException> {
            checkpoint.copy(uploadedChunks = plan.chunkCount - 1, commitInFlight = true)
        }
    }

    @Test
    fun `resume keeps only the exact contiguous server prefix`() {
        val plan = assertIs<NextcloudUploadTransferPlan.Chunked>(
            nextcloudUploadTransferPlan(35L * 1024L * 1024L),
        )

        assertEquals(
            NextcloudChunkCollectionReconciliation(2, listOf(3, 4, 9)),
            reconcileNextcloudChunkCollection(
                plan,
                mapOf(
                    1 to 10L * 1024L * 1024L,
                    2 to 10L * 1024L * 1024L,
                    3 to 9L * 1024L * 1024L,
                    4 to 5L * 1024L * 1024L,
                    9 to 1L,
                ),
            ),
        )
    }
}
