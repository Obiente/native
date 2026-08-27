package dev.obiente.nextcloudnative.app

import java.io.File
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

internal fun DesktopFileSyncRemoteTree.replaceWithFile(
    relativePath: String,
    source: File,
    expectedRemoteEtag: String,
    uploadId: String,
    shouldContinue: () -> Boolean,
    onStageGenerationKnown: (String) -> Unit,
): RemoteSyncEntry {
    require(source.isFile)
    require(isValidNextcloudChunkUploadId(uploadId))
    requireDirectoryGeneration(relativePath, expectedRemoteEtag)
    val ownedStagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
    val stagingPath = fullPath(ownedStagePath)
    val stageBody = if (source.length() == 0L) {
        source.asRequestBody("application/octet-stream".toMediaType())
    } else {
        jvmFileRangeRequestBody(source, 0L, source.length()) {
            if (!shouldContinue()) throw CancellationException("Sync upload paused.")
        }
    }
    val responseEtag = executeMutationForEtag(
        requestBuilder(fileUrl(stagingPath)).header("If-None-Match", "*").put(stageBody).build(),
        "create replacement upload stage",
    )
    val staged = requireNotNull(resolveOwnedUploadStage(ownedStagePath)) {
        "The replacement upload stage disappeared."
    }
    require(!staged.isDirectory && staged.entry.size == source.length()) {
        "The replacement upload stage has an unexpected size."
    }
    require(responseEtag == null || safeEtag(staged.entry.etag) == safeEtag(responseEtag)) {
        "The replacement upload stage changed before verification."
    }
    onStageGenerationKnown(staged.entry.etag)
    val stagedEtag = resumableUploadRemote(shouldContinue).verifyOwnedStage(
        uploadId, relativePath, source, staged.entry.etag,
    )
    return publishOwnedStageReplacingDirectory(
        relativePath,
        uploadId,
        stagedEtag,
        expectedRemoteEtag,
        shouldContinue,
    )
}

internal fun DesktopFileSyncRemoteTree.requireDirectoryGeneration(
    relativePath: String,
    expectedRemoteEtag: String,
) {
    val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
    require(current.entry.etag == expectedRemoteEtag && current.isDirectory) {
        "The server item changed after the sync scan."
    }
}

internal fun DesktopFileSyncRemoteTree.publishOwnedStageReplacingDirectory(
    relativePath: String,
    uploadId: String,
    verifiedStageEtag: String,
    expectedRemoteEtag: String,
    shouldContinue: (() -> Boolean)? = null,
): RemoteSyncEntry {
    require(isValidNextcloudChunkUploadId(uploadId))
    val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
    require(current.entry.etag == expectedRemoteEtag && current.isDirectory) {
        "The server item changed before replacement publication."
    }
    val destinationPath = fullPath(relativePath)
    val stagingPath = fullPath(jvmOwnedUploadStagePath(relativePath, uploadId))
    val backupPath = fullPath(jvmOwnedReplacementBackupPath(relativePath, uploadId))
    var protected = false
    try {
        moveRemoteDocument(
            current.copy(entry = current.entry.copy(relativePath = destinationPath)),
            backupPath,
            relativePath,
            shouldContinue = shouldContinue,
        )
        protected = true
        moveRemotePath(
            stagingPath, destinationPath, verifiedStageEtag, sourceIsDirectory = false,
            mutationRelativePaths = arrayOf(relativePath),
            shouldContinue = shouldContinue,
        )
        val after = requireNotNull(resolvePhysical(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory) { "The uploaded server item is not a file." }
        return after.entry
    } catch (failure: Throwable) {
        if (protected) {
            restoreRemoteBackup(
                destinationPath,
                backupPath,
                relativePath,
                expectedBackupEtag = expectedRemoteEtag,
            )
        }
        throw failure
    }
}

internal fun DesktopFileSyncRemoteTree.completeReplacementBackup(
    relativePath: String,
    uploadId: String,
    expectedBackupEtag: String,
) {
    deleteOwnedReplacementBackup(
        fullPath(jvmOwnedReplacementBackupPath(relativePath, uploadId)),
        expectedBackupEtag,
    )
}

/**
 * Resolves the direct-replacement crash window after the owned stage may have become visible.
 *
 * A file at the destination is never treated as abandoned staging. Its exact generation is
 * compared with the durably recorded size and content hash before the old directory backup is
 * removed. A mismatch keeps both generations owned for a later, explicit recovery attempt.
 * Returns null while publication definitely has not replaced the destination directory yet.
 */
internal fun DesktopFileSyncRemoteTree.reconcilePublishedReplacement(
    relativePath: String,
    uploadId: String,
    expectedSizeBytes: Long?,
    expectedContentHash: String?,
    shouldContinue: () -> Boolean,
): Boolean? {
    val expectedBackupEtag = ownedReplacementBackupEtags[uploadId] ?: return null
    val destination = resolvePhysical(relativePath) ?: return null
    if (destination.isDirectory) return null
    if (expectedSizeBytes == null || expectedContentHash == null) return false
    if (destination.entry.size != expectedSizeBytes) return false
    if (
        !verifyContentHash(
            relativePath = relativePath,
            expectedRemoteEtag = destination.entry.etag,
            expectedBytes = expectedSizeBytes,
            expectedContentHash = expectedContentHash,
            maximumBytes = expectedSizeBytes.coerceAtLeast(1L),
            shouldContinue = shouldContinue,
            physicalDestination = true,
        )
    ) {
        return false
    }
    completeReplacementBackup(
        relativePath,
        uploadId,
        expectedBackupEtag,
    )
    return true
}

internal fun DesktopFileSyncRemoteTree.discardReplacementBackup(
    relativePath: String,
    uploadId: String,
    assembledStageEtag: String?,
): Boolean {
    val destinationPath = fullPath(relativePath)
    val backupPath = fullPath(jvmOwnedReplacementBackupPath(relativePath, uploadId))
    val documents = rawListDirectory(destinationPath.substringBeforeLast('/', ""))
    val backup = documents.firstOrNull { it.entry.relativePath == backupPath } ?: return true
    val expectedBackupEtag = requireNotNull(ownedReplacementBackupEtags[uploadId])
    require(backup.isDirectory) { "The owned replacement backup changed type." }
    require(backup.entry.etag == expectedBackupEtag) { "The owned replacement backup changed." }
    val destination = documents.firstOrNull { it.entry.relativePath == destinationPath }
    if (destination == null) {
        moveRemoteDocument(backup, destinationPath, relativePath)
        return true
    }
    if (!destination.isDirectory && assembledStageEtag != null && destination.entry.etag == assembledStageEtag) {
        deleteRemoteDocument(destination)
        moveRemoteDocument(backup, destinationPath, relativePath)
        return true
    }
    val conflictPath = fullPath(jvmOwnedReplacementConflictPath(relativePath, uploadId))
    if (documents.any { it.entry.relativePath == conflictPath }) return false
    moveRemoteDocument(backup, conflictPath, relativePath)
    return true
}
