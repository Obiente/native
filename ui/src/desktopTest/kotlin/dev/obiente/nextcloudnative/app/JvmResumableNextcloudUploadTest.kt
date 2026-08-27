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
    fun `replacement backup name is bounded independently of the destination leaf`() {
        val destinationLeaf = "a".repeat(240) + ".bin"

        val backup = jvmOwnedReplacementBackupPath("Archive/$destinationLeaf", UPLOAD_ID)

        assertTrue(backup.substringAfterLast('/').encodeToByteArray().size <= 255)
        assertFalse(destinationLeaf in backup)
        assertEquals("Archive/.nextcloud-native-backup-$UPLOAD_ID", backup)
        val conflict = jvmOwnedReplacementConflictPath("Archive/$destinationLeaf", UPLOAD_ID)
        assertTrue(conflict.substringAfterLast('/').encodeToByteArray().size <= 255)
        assertFalse(destinationLeaf in conflict)
    }

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
            assertEquals(
                listOf("commit", "verify", "publish", "published-verify", "complete-published"),
                remote.finalizationEvents,
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `same scan revision with different staged content restarts every chunk`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val initial = newFileSyncUploadCheckpoint(
            UPLOAD_ID,
            "metadata-revision",
            plan,
            contentRevision = "sha256:old",
        ).copy(uploadedChunks = 2)
        val remote = RecordingUploadRemote(collectionCreated = true)
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            jvmResumableNextcloudUpload(
                source, "large.bin", "metadata-revision", null, initial,
                newUploadId = { "fedcba98-7654-3210-fedc-ba9876543210" },
                persistCheckpoint = persisted::add,
                remote = remote,
                contentRevision = "sha256:new",
            )

            assertEquals(1, remote.discardCount)
            assertEquals(listOf(1, 2, 3), remote.uploadedChunkNumbers)
            assertEquals("metadata-revision", persisted.first().localRevision)
            assertEquals("sha256:new", persisted.first().contentRevision)
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
            assertEquals(
                listOf("commit", "verify", "publish", "published-verify", "complete-published"),
                remote.finalizationEvents,
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `fresh publication verifies the visible generation before completing ownership`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val remote = RecordingUploadRemote(
            collectionCreated = true,
            failDirectVerification = true,
        )
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            assertFailsWith<IllegalStateException> {
                jvmResumableNextcloudUpload(
                    source,
                    "large.bin",
                    "local-1",
                    null,
                    null,
                    newUploadId = { UPLOAD_ID },
                    persistCheckpoint = persisted::add,
                    remote = remote,
                )
            }

            assertTrue(persisted.last().commitInFlight)
            assertEquals(
                listOf("commit", "verify", "publish", "published-verify"),
                remote.finalizationEvents,
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `ambiguous assembly verifies and publishes its durable owned stage`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val checkpoint = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(
            uploadedChunks = plan.chunkCount,
            commitInFlight = true,
        )
        val remote = RecordingUploadRemote(
            collectionCreated = false,
            assembledStageEtag = "recovered-stage",
            ownedStageEtag = "recovered-stage",
        )
        val persisted = mutableListOf<FileSyncUploadCheckpoint>()
        try {
            val uploaded = jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", null, checkpoint,
                newUploadId = { error("Recovery must retain the durable upload ID.") },
                persistCheckpoint = persisted::add,
                remote = remote,
            )

            assertEquals("remote-etag", uploaded.etag)
            assertEquals("verified-stage-etag", persisted.single().assembledStageEtag)
            assertTrue(remote.uploadedChunkNumbers.isEmpty())
            assertEquals(
                listOf("verify", "publish", "published-verify", "complete-published"),
                remote.finalizationEvents,
            )
            assertEquals(0, remote.discardCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `ambiguous publication verifies the destination without retransmitting`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val checkpoint = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(
            uploadedChunks = plan.chunkCount,
            commitInFlight = true,
            assembledStageEtag = "published-stage",
        )
        val remote = RecordingUploadRemote(
            collectionCreated = false,
            directUpload = true,
            publishedFile = RemoteSyncEntry("large.bin", SyncEntryKind.File, "direct-etag", source.length()),
        )
        try {
            val uploaded = jvmResumableNextcloudUpload(
                source, "large.bin", "local-1", null, checkpoint,
                newUploadId = { error("A published generation must not be retransmitted.") },
                persistCheckpoint = {},
                remote = remote,
            )

            assertEquals("direct-etag", uploaded.etag)
            assertTrue(remote.uploadedChunkNumbers.isEmpty())
            assertEquals(listOf("published-verify", "complete-published"), remote.finalizationEvents)
            assertEquals(0, remote.discardCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `published bookkeeping failure does not discard or retransmit the verified destination`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val checkpoint = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(
            uploadedChunks = plan.chunkCount,
            commitInFlight = true,
            assembledStageEtag = "published-stage",
        )
        val remote = RecordingUploadRemote(
            collectionCreated = false,
            directUpload = true,
            publishedFile = RemoteSyncEntry("large.bin", SyncEntryKind.File, "direct-etag", source.length()),
            failPublishedCompletion = true,
        )
        try {
            assertFailsWith<IllegalStateException> {
                jvmResumableNextcloudUpload(
                    source, "large.bin", "local-1", null, checkpoint,
                    newUploadId = { error("A verified destination must not be retransmitted.") },
                    persistCheckpoint = {},
                    remote = remote,
                )
            }

            assertEquals(listOf("published-verify", "complete-published"), remote.finalizationEvents)
            assertTrue(remote.uploadedChunkNumbers.isEmpty())
            assertEquals(0, remote.discardCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `unknown published verification failure retains recovery without retransmitting`() {
        val source = sparseFile(25L * 1024L * 1024L)
        val plan = nextcloudUploadTransferPlan(source.length()) as NextcloudUploadTransferPlan.Chunked
        val checkpoint = newFileSyncUploadCheckpoint(UPLOAD_ID, "local-1", plan).copy(
            uploadedChunks = plan.chunkCount,
            commitInFlight = true,
            assembledStageEtag = "lost-stage",
        )
        val remote = RecordingUploadRemote(
            collectionCreated = true,
            directUpload = true,
            failDirectVerification = true,
            publishedFile = RemoteSyncEntry("large.bin", SyncEntryKind.File, "different-etag", source.length()),
        )
        try {
            assertFailsWith<IllegalStateException> {
                jvmResumableNextcloudUpload(
                    source, "large.bin", "local-1", "different-etag", checkpoint,
                    newUploadId = { error("An unknown outcome must not allocate another upload.") },
                    persistCheckpoint = {},
                    remote = remote,
                )
            }

            assertTrue(remote.uploadedChunkNumbers.isEmpty())
            assertEquals(listOf("published-verify"), remote.finalizationEvents)
            assertEquals(0, remote.discardCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `cleanup preserves a stage whose generation was never recorded`() {
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

        val remote = RecordingUploadRemote(
            collectionCreated = true,
            ownedStageEtag = "discovered-stage",
            cleanupComplete = false,
        )
        val after = cleanupJvmFileSyncOwnedUploads(
            remote = remote,
            state = initial,
            pairId = pair.id,
            uploads = listOf(cleanup),
            onStateChanged = { stateChangeCount += 1 },
        )

        assertEquals(listOf(cleanup), after.pairs.single().pendingUploadCleanups)
        assertEquals(0, stateChangeCount)
        assertEquals(listOf<String?>(null), remote.discardedStageEtags)
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
        private val failDirectVerification: Boolean = false,
        private val cleanupComplete: Boolean = true,
        private val ownedStageEtag: String? = null,
        private val publishedFile: RemoteSyncEntry? = null,
        private val failPublishedCompletion: Boolean = false,
    ) : JvmResumableNextcloudUploadRemote {
        val uploadedChunkNumbers = mutableListOf<Int>()
        val discardedStageEtags = mutableListOf<String?>()
        val finalizationEvents = mutableListOf<String>()
        var discardCount = 0

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
            check(directUpload)
            finalizationEvents += "direct-verify"
            check(!failDirectVerification) { "The visible destination differs." }
            check(uploaded.etag == "direct-etag")
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

        override fun ownedStageEtag(uploadId: String, relativePath: String): String? = ownedStageEtag

        override fun resolvePublishedFile(relativePath: String): RemoteSyncEntry? = publishedFile

        override fun verifyPublishedFile(
            uploadId: String,
            source: File,
            relativePath: String,
            published: RemoteSyncEntry,
        ): RemoteSyncEntry {
            finalizationEvents += "published-verify"
            check(!failDirectVerification) { "The visible destination differs." }
            return published
        }

        override fun completePublishedFile(uploadId: String, relativePath: String) {
            finalizationEvents += "complete-published"
            check(!failPublishedCompletion) { "Published bookkeeping failed." }
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
            expectedStageSizeBytes: Long?,
            expectedStageContentHash: String?,
            publicationInFlight: Boolean,
        ): Boolean {
            discardCount += 1
            discardedStageEtags += assembledStageEtag
            return cleanupComplete
        }
    }

    private companion object {
        const val UPLOAD_ID = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
