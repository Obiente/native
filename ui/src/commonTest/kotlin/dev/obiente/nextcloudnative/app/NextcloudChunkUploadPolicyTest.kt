package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
