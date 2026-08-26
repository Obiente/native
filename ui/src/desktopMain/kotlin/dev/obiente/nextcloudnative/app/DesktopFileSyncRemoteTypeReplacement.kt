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
    val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
    require(current.entry.etag == expectedRemoteEtag && current.isDirectory) {
        "The server item changed after the sync scan."
    }
    val destinationPath = fullPath(relativePath)
    val ownedStagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
    val stagingPath = fullPath(ownedStagePath)
    val backupPath = replacementBackupPath(destinationPath)
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
    var protected = false
    try {
        moveRemoteDocument(current.copy(entry = current.entry.copy(relativePath = destinationPath)), backupPath, relativePath)
        protected = true
        moveRemotePath(
            stagingPath, destinationPath, stagedEtag, sourceIsDirectory = false,
            mutationRelativePaths = arrayOf(relativePath),
        )
        val after = requireNotNull(resolve(relativePath)) { "The uploaded server file disappeared." }
        require(!after.isDirectory) { "The uploaded server item is not a file." }
        deleteRemoteBackup(backupPath)
        return after.entry
    } catch (failure: Throwable) {
        if (protected) restoreRemoteBackup(destinationPath, backupPath, relativePath)
        throw failure
    }
}
