package dev.obiente.nextcloudnative

import android.net.Uri
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal data class AndroidLocalSyncDocument(
    val entry: LocalSyncEntry,
    val uri: Uri,
    val displayName: String,
)

internal data class AndroidFileSyncContentHashRead(
    val contentHash: String?,
    val bytesRead: Long,
) {
    init {
        require(bytesRead >= 0L)
    }
}

internal interface AndroidFileSyncLocalTree {
    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
        shouldContinue: () -> Boolean,
    ): List<AndroidLocalSyncDocument>

    fun authenticateFileForReplacement(
        path: String,
        expectedLocalRevision: String,
        expectedContentHash: String?,
        shouldContinue: () -> Boolean,
    ) {
        requireScanContinuation(shouldContinue)
        val current = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(current.entry.kind == SyncEntryKind.File && current.entry.revision == expectedLocalRevision) {
            "The local file changed after the sync scan."
        }
        expectedContentHash?.let { expected ->
            val size = requireNotNull(current.entry.size)
            require(contentHash(path, expectedLocalRevision, size, size.coerceAtLeast(1L)) == expected) {
                "The local file content changed after the sync scan."
            }
        }
        requireScanContinuation(shouldContinue)
    }

    fun strengthenReplacementEntries(
        documents: List<AndroidLocalSyncDocument>,
        protectedPaths: Set<String>,
        contentReadBudget: AndroidFileSyncContentReadBudget,
        shouldContinue: () -> Boolean,
    ): List<AndroidLocalSyncDocument> = documents

    fun contentHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): String?

    fun contentHashRead(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): AndroidFileSyncContentHashRead = AndroidFileSyncContentHashRead(
        contentHash(path, expectedLocalRevision, expectedBytes, maximumBytes),
        expectedBytes,
    )

    fun contentRangeHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        offset: Long,
        length: Int,
    ): String? = null

    fun stageForUpload(
        path: String,
        destination: File,
        maximumBytes: Long,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): LocalSyncEntry

    fun createDirectory(path: String, expectedLocalRevision: String?)

    fun createDirectoryForDownload(
        path: String,
        expectedLocalRevision: String?,
        expectedContentHash: String?,
        shouldContinue: () -> Boolean,
    ) {
        if (!shouldContinue()) {
            throw kotlinx.coroutines.CancellationException("The local download was cancelled.")
        }
        createDirectory(path, expectedLocalRevision)
    }

    fun writeFile(path: String, source: File, expectedLocalRevision: String?)

    fun writeFileFromStream(
        path: String,
        expectedLocalRevision: String?,
        write: (OutputStream) -> Unit,
    ) {
        val temporary = File.createTempFile("nextcloud-native-local-stream-", ".tmp")
        try {
            FileOutputStream(temporary).use(write)
            writeFile(path, temporary, expectedLocalRevision)
        } finally {
            temporary.delete()
        }
    }

    fun writeFileFromStreamForDownload(
        path: String,
        expectedLocalRevision: String?,
        expectedContentHash: String?,
        shouldContinue: () -> Boolean,
        write: (OutputStream) -> Unit,
    ) {
        if (!shouldContinue()) {
            throw kotlinx.coroutines.CancellationException("The local download was cancelled.")
        }
        writeFileFromStream(path, expectedLocalRevision, write)
    }

    fun delete(path: String, expectedLocalRevision: String)

    fun deleteForSync(
        path: String,
        expectedLocalRevision: String,
        expectedContentHash: String?,
        shouldContinue: () -> Boolean,
    ) {
        if (!shouldContinue()) {
            throw kotlinx.coroutines.CancellationException("The local deletion was cancelled.")
        }
        delete(path, expectedLocalRevision)
    }

    fun resolve(path: String): AndroidLocalSyncDocument?

    fun reconcileOwnedDownloads() = Unit
}
