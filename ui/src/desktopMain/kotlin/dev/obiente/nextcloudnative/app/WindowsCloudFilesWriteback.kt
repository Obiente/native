package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Local content identity used to prove an uploaded file was not edited before it is marked in sync. */
internal data class WindowsCloudLocalContentStamp(
    val size: Long,
    val lastWriteTime: Long,
    val fileId: Long,
)

internal enum class WindowsCloudInSyncCommit {
    Committed,
    LocalChanged,
}

/** The local file kept changing while each of its bounded uploads was in progress. */
internal class WindowsCloudLocalChangedDuringWritebackException : IllegalStateException(
    "The Windows placeholder kept changing while its edits were uploaded.",
)

/** Files-based stamp for non-native test doubles. */
internal fun nioWindowsCloudLocalContentStamp(path: Path): WindowsCloudLocalContentStamp {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return WindowsCloudLocalContentStamp(
        size = attributes.size(),
        lastWriteTime = attributes.lastModifiedTime().to(TimeUnit.MICROSECONDS),
        fileId = attributes.fileKey()?.hashCode()?.toLong() ?: 0L,
    )
}

/** Non-atomic comparison for test doubles; the native API holds an exclusive handle instead. */
internal fun WindowsCloudFilesApi.markInSyncWhenStampMatches(
    path: Path,
    expected: WindowsCloudLocalContentStamp,
): WindowsCloudInSyncCommit {
    if (localContentStamp(path) != expected) return WindowsCloudInSyncCommit.LocalChanged
    markInSync(path)
    return WindowsCloudInSyncCommit.Committed
}

/**
 * Uploads a dirty file placeholder and commits the result to its placeholder.
 *
 * A dirty placeholder is never in sync, so the commit cannot rely on Cloud Files in-sync
 * verification. The content stamp is taken before the upload reads the file. After the server
 * accepts the upload, the placeholder receives the new revision while it stays dirty, which is
 * correct whether or not the local file changed. It is marked in sync only while an exclusive
 * handle proves the stamp is unchanged. A file edited meanwhile stays dirty and uploads again
 * against the new revision. A placeholder update or commit that fails after the server accepted the
 * upload is retried without uploading again, so the retry never reuses the superseded revision.
 */
internal class WindowsCloudFilesWriteback(
    private val api: WindowsCloudFilesApi,
    private val backend: WindowsCloudFilesBackend,
    private val recordIdentity: (WindowsCloudFileIdentity) -> Unit,
    private val recordUploadedRevision: (Path, WindowsCloudFileIdentity) -> Boolean,
) {
    private data class PendingCommit(
        val base: WindowsCloudFileIdentity,
        val uploaded: WindowsCloudFileIdentity,
        val stamp: WindowsCloudLocalContentStamp,
    )

    private val pendingCommits = ConcurrentHashMap<String, PendingCommit>()

    /** Runs inside the path operation for [current]'s path; [current] is the placeholder identity. */
    fun writeBack(local: Path, current: WindowsCloudFileIdentity) {
        require(current.accountId == backend.accountId && !current.directory) {
            "The dirty Windows placeholder identity is not safe to write back."
        }
        var target = current
        repeat(MAX_UPLOADS_PER_WRITEBACK) {
            // A queued writeback may find an earlier one already committed the same content.
            if (api.placeholderState(local) != WindowsCloudPlaceholderState.Dirty) return
            val pending = pendingCommits[target.path]
            val accepted = when (target) {
                pending?.uploaded -> pending
                // The server accepted this upload, but recording its revision failed: retry only that.
                pending?.base -> pending?.takeIf { recordUploadedRevision(local, it.uploaded) }
                else -> upload(local, target)
            } ?: return handOverToRootRecovery(target.path)
            when (api.markInSyncIfUnchanged(local, accepted.stamp)) {
                WindowsCloudInSyncCommit.Committed -> {
                    pendingCommits.remove(target.path, accepted)
                    return
                }
                WindowsCloudInSyncCommit.LocalChanged -> {
                    pendingCommits.remove(target.path, accepted)
                    target = accepted.uploaded
                }
            }
        }
        throw WindowsCloudLocalChangedDuringWritebackException()
    }

    fun clear() = pendingCommits.clear()

    /** A corrupt placeholder update scheduled root recovery, which rebuilds from the server. */
    private fun handOverToRootRecovery(path: String) {
        pendingCommits.remove(path)
    }

    /** Returns null when a corrupt placeholder update handed the commit to root recovery. */
    private fun upload(local: Path, current: WindowsCloudFileIdentity): PendingCommit? {
        pendingCommits.remove(current.path)
        val stamp = api.localContentStamp(local)
        val uploaded = backend.upload(current.path, local.toFile(), current.remoteRevision)
        recordIdentity(uploaded)
        // Recorded before the placeholder update, which can fail after the server accepted the upload.
        val pending = PendingCommit(current, uploaded, stamp).also { pendingCommits[current.path] = it }
        return pending.takeIf { recordUploadedRevision(local, uploaded) }
    }

    private companion object {
        const val MAX_UPLOADS_PER_WRITEBACK = 3
    }
}
