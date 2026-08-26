package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmResumableNextcloudUploadTest {
    @Test
    fun `resume continues after the last durable chunk`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val initial = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(uploadedChunks = 1)
        val remote = RecordingUploadRemote(
            collectionCreated = false,
            serverChunks = mapOf(1 to 10L * 1024L * 1024L),
        )
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            val uploaded = jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", null, initial,
                newUploadId = { error("A resumed upload must retain its owned collection.") },
                persistCheckpoint = persisted::add,
                remote = remote,
            )

            assertEquals(listOf(2, 3), remote.uploadedChunkNumbers)
            assertEquals(listOf(2, 3, 3), persisted.map(FileSyncUploadCheckpoint::uploadedChunks))
            assertFalse(persisted[0].commitInFlight)
            assertTrue(persisted.last().commitInFlight)
            assertEquals("remote-etag", uploaded.etag)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `expired collection resets progress before any bytes are skipped`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val initial = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(uploadedChunks = 2)
        val remote = RecordingUploadRemote(collectionCreated = true)
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", "old-etag", initial,
                newUploadId = { error("The collection ID remains owned when the server recreates it.") },
                persistCheckpoint = persisted::add,
                remote = remote,
            )

            assertEquals(0, persisted.first().uploadedChunks)
            assertEquals(listOf(1, 2, 3), remote.uploadedChunkNumbers)
        } finally {
            source.delete()
        }
    }

    private fun sparseFile(sizeBytes: Long): File =
        File.createTempFile("nextcloud-native-resume-", ".bin").also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
        }

    private class RecordingUploadRemote(
        private val collectionCreated: Boolean,
        private val serverChunks: Map<Int, Long> = emptyMap(),
    ) : JvmResumableNextcloudUploadRemote {
        val uploadedChunkNumbers = mutableListOf<Int>()

        override fun uploadDirect(
            source: File,
            relativePath: String,
            expectedRemoteEtag: String?,
        ): RemoteSyncEntry = error("The test file must use chunking.")

        override fun createChunkCollection(
            uploadId: String,
            relativePath: String,
            allowExisting: Boolean,
        ): Boolean = collectionCreated

        override fun listChunkCollection(uploadId: String): Map<Int, Long> = serverChunks

        override fun deleteChunk(uploadId: String, chunkNumber: Int) = Unit

        override fun uploadChunk(
            uploadId: String,
            relativePath: String,
            source: File,
            chunk: NextcloudUploadChunk,
        ) {
            uploadedChunkNumbers += chunk.number
        }

        override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long) = Unit

        override fun publishOwnedStage(
            uploadId: String,
            relativePath: String,
            expectedRemoteEtag: String?,
        ) = RemoteSyncEntry(relativePath, SyncEntryKind.File, "remote-etag", 25L * 1024L * 1024L)

        override fun discardOwnedUpload(uploadId: String, relativePath: String) = Unit
    }

    private companion object {
        const val UPLOAD_ID = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
