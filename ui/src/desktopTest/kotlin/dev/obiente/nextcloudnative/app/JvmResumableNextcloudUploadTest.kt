package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class JvmResumableNextcloudUploadTest {
    @Test
    fun `direct upload is byte verified before its result is returned`() {
        val source = sparseFile(1024L)
        val remote = RecordingUploadRemote(collectionCreated = true, directUpload = true)
        try {
            val uploaded = jvmResumableNextcloudUpload(
                source, "small.bin", "local-1", null, null,
                newUploadId = { error("A direct upload must not allocate a chunk collection.") },
                persistCheckpoint = {},
                remote = remote,
            )

            assertEquals("direct-etag", uploaded.etag)
            assertEquals(listOf("direct-upload", "direct-verify"), remote.finalizationEvents)
        } finally {
            source.delete()
        }
    }

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
            assertEquals(listOf(2, 3, 3, 3), persisted.map(FileSyncUploadCheckpoint::uploadedChunks))
            assertFalse(persisted[0].commitInFlight)
            assertTrue(persisted.last().commitInFlight)
            assertEquals("verified-stage-etag", persisted.last().assembledStageEtag)
            assertEquals("remote-etag", uploaded.etag)
            assertEquals(listOf("commit", "verify", "publish"), remote.finalizationEvents)
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

    @Test
    fun `failed stage verification never publishes the visible destination`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val remote = RecordingUploadRemote(collectionCreated = true, failVerification = true)
        try {
            assertFailsWith<IllegalStateException> {
                jvmResumableNextcloudUpload(
                    source, "large.bin", "local-1", null, null,
                    newUploadId = { UPLOAD_ID },
                    persistCheckpoint = {},
                    remote = remote,
                )
            }

            assertEquals(listOf("commit", "verify"), remote.finalizationEvents)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `pause between chunks retains durable progress without assembling or publishing`() {
        val source = sparseFile(25L * 1024L * 1024L)
        var active = true
        val remote = RecordingUploadRemote(
            collectionCreated = true,
            afterChunkUploaded = { active = false },
        )
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            assertFailsWith<CancellationException> {
                jvmResumableNextcloudUpload(
                    source, "large.bin", "local-1", null, null,
                    newUploadId = { UPLOAD_ID },
                    persistCheckpoint = persisted::add,
                    remote = remote,
                    shouldContinue = { active },
                )
            }

            assertEquals(listOf(1), remote.uploadedChunkNumbers)
            assertEquals(1, persisted.last().uploadedChunks)
            assertTrue(remote.finalizationEvents.isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `verified stage generation is durable before publish when assembly omits an etag`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val remote = RecordingUploadRemote(collectionCreated = true, assembledStageEtag = null)
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", null, null,
                newUploadId = { UPLOAD_ID },
                persistCheckpoint = persisted::add,
                remote = remote,
            )

            assertEquals("verified-stage-etag", persisted.last().assembledStageEtag)
            assertEquals(listOf("commit", "verify", "publish"), remote.finalizationEvents)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `cleanup retains ownership while an unverified stage still exists`() {
        val cleanup = FileSyncPendingUploadCleanup(UPLOAD_ID, "large.bin")
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Vault",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
            pendingUploadCleanups = listOf(cleanup),
        )
        val initial = FileSyncCoordinatorState(listOf(pair))
        var stateChangeCount = 0

        val after = cleanupJvmFileSyncOwnedUploads(
            remote = RecordingUploadRemote(collectionCreated = true, cleanupComplete = false),
            state = initial,
            pairId = pair.id,
            uploads = listOf(cleanup),
            onStateChanged = { stateChangeCount += 1 },
        )

        assertEquals(initial, after)
        assertEquals(0, stateChangeCount)
    }

    private fun sparseFile(sizeBytes: Long): File =
        File.createTempFile("nextcloud-native-resume-", ".bin").also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
        }

    private class RecordingUploadRemote(
        private val collectionCreated: Boolean,
        private val serverChunks: Map<Int, Long> = emptyMap(),
        private val failVerification: Boolean = false,
        private val afterChunkUploaded: () -> Unit = {},
        private val assembledStageEtag: String? = "verified-stage-etag",
        private val directUpload: Boolean = false,
        private val cleanupComplete: Boolean = true,
    ) : JvmResumableNextcloudUploadRemote {
        val uploadedChunkNumbers = mutableListOf<Int>()
        val finalizationEvents = mutableListOf<String>()

        override fun uploadDirect(
            source: File,
            relativePath: String,
            expectedRemoteEtag: String?,
        ): RemoteSyncEntry {
            check(directUpload)
            finalizationEvents += "direct-upload"
            return RemoteSyncEntry(relativePath, SyncEntryKind.File, "direct-etag", source.length())
        }

        override fun verifyDirectUpload(
            source: File,
            relativePath: String,
            uploaded: RemoteSyncEntry,
        ): RemoteSyncEntry {
            check(directUpload && uploaded.etag == "direct-etag")
            finalizationEvents += "direct-verify"
            return uploaded
        }

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
            afterChunkUploaded()
        }

        override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long): String? {
            finalizationEvents += "commit"
            return assembledStageEtag
        }

        override fun verifyOwnedStage(
            uploadId: String,
            relativePath: String,
            source: File,
            expectedStageEtag: String?,
        ): String {
            finalizationEvents += "verify"
            check(expectedStageEtag == assembledStageEtag)
            check(!failVerification) { "The assembled stage differs." }
            return "verified-stage-etag"
        }

        override fun publishOwnedStage(
            uploadId: String,
            relativePath: String,
            verifiedStageEtag: String,
            expectedRemoteEtag: String?,
        ): RemoteSyncEntry {
            check(verifiedStageEtag == "verified-stage-etag")
            finalizationEvents += "publish"
            return RemoteSyncEntry(relativePath, SyncEntryKind.File, "remote-etag", 25L * 1024L * 1024L)
        }

        override fun discardOwnedUpload(
            uploadId: String,
            relativePath: String,
            assembledStageEtag: String?,
        ): Boolean = cleanupComplete
    }

    private companion object {
        const val UPLOAD_ID = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
