package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudUploadTransferPlan
import dev.obiente.nextcloudnative.app.incomingShareUploadNameCandidates
import dev.obiente.nextcloudnative.app.nextcloudUploadTransferPlan
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

internal fun Throwable.isRetryableIncomingShareTransferFailure(): Boolean {
    val dav = this as? DocumentWebDavException
    return this is IOException || dav?.error == DocumentWebDavError.Throttled ||
        (dav?.error == DocumentWebDavError.Server && dav.status >= 500)
}

internal const val MAX_INCOMING_SHARE_TRANSFER_ATTEMPTS = 5

internal class AndroidIncomingShareFileTransfer(
    private val store: AndroidIncomingShareStore,
    private val remote: AndroidFileSyncRemoteTree,
    private val cancellation: DocumentRequestCancellation,
) {
    fun upload(
        requestId: String,
        request: AndroidIncomingShareRequest,
        fileIndex: Int,
        occupiedNames: MutableSet<String>,
        destinationSnapshotComplete: Boolean,
        setMutationInFlight: (Boolean, String?) -> Unit,
    ): AndroidIncomingShareRequest {
        val source = request.files[fileIndex]
        val stagedFile = store.stagedFile(requestId, source)
        requireValidIncomingShareStagedFile(stagedFile, source, cancellation)
        return when (val plan = nextcloudUploadTransferPlan(stagedFile.length())) {
            NextcloudUploadTransferPlan.Direct -> uploadDirect(
                requestId,
                fileIndex,
                source.displayName,
                stagedFile,
                occupiedNames,
                destinationSnapshotComplete,
                setMutationInFlight,
            )
            is NextcloudUploadTransferPlan.Chunked -> uploadChunked(
                requestId,
                fileIndex,
                source.displayName,
                stagedFile,
                occupiedNames,
                destinationSnapshotComplete,
                setMutationInFlight,
                plan,
            )
        }
    }

    private fun uploadDirect(
        requestId: String,
        fileIndex: Int,
        displayName: String,
        stagedFile: File,
        occupiedNames: MutableSet<String>,
        destinationSnapshotComplete: Boolean,
        setMutationInFlight: (Boolean, String?) -> Unit,
    ): AndroidIncomingShareRequest {
        var targetName: String
        while (true) {
            val candidate = selectIncomingShareTransferTarget(
                displayName,
                occupiedNames,
                destinationSnapshotComplete,
            ) { name -> remote.resourceExists(name, cancellation) }
                ?: error("No safe available name remains for $displayName.")
            try {
                remote.createFileIfAbsent(
                    candidate,
                    stagedFile,
                    onRequestStarted = { setMutationInFlight(true, candidate) },
                    cancellation = cancellation,
                )
                targetName = candidate
                break
            } catch (failure: DocumentWebDavException) {
                if (!failure.isIncomingShareNameCollision()) throw failure
                setMutationInFlight(false, null)
                occupiedNames += candidate
            }
        }
        val updated = store.recordUploadedFile(requestId, fileIndex, targetName)
            ?: throw CancellationException("Incoming share upload canceled")
        setMutationInFlight(false, null)
        occupiedNames += targetName
        return updated
    }

    private fun uploadChunked(
        requestId: String,
        fileIndex: Int,
        displayName: String,
        stagedFile: File,
        occupiedNames: MutableSet<String>,
        destinationSnapshotComplete: Boolean,
        setMutationInFlight: (Boolean, String?) -> Unit,
        plan: NextcloudUploadTransferPlan.Chunked,
    ): AndroidIncomingShareRequest {
        var current = store.requireAvailable(requestId)
        while (true) {
            cancellation.throwIfCancelled()
            val existingUpload = current.chunkSession
            if (existingUpload?.cleanupPending == true) {
                remote.deleteChunkUpload(existingUpload.uploadId, cancellation)
                current = store.clearChunkSession(requestId)
                continue
            }
            if (existingUpload == null) {
                val targetName = selectIncomingShareTransferTarget(
                    displayName,
                    occupiedNames,
                    destinationSnapshotComplete,
                ) { candidate -> remote.resourceExists(candidate, cancellation) }
                    ?: error("No safe available name remains for $displayName.")
                val uploadId = UUID.randomUUID().toString()
                current = store.beginChunkSession(requestId, fileIndex, targetName, uploadId)
                remote.createChunkUpload(
                    uploadId,
                    targetName,
                    allowExistingSession = false,
                    cancellation = cancellation,
                )
            } else {
                require(existingUpload.fileIndex == fileIndex)
                if (shouldAbandonResumedIncomingShareTarget(
                        existingUpload.targetName,
                        occupiedNames,
                        destinationSnapshotComplete,
                    ) { remote.resourceExists(existingUpload.targetName, cancellation) }
                ) {
                    occupiedNames += existingUpload.targetName
                    current = store.markChunkCleanupPending(requestId)
                    val cleanup = requireNotNull(current.chunkSession)
                    remote.deleteChunkUpload(cleanup.uploadId, cancellation)
                    current = store.clearChunkSession(requestId)
                    continue
                }
                val recreated = remote.createChunkUpload(
                    existingUpload.uploadId,
                    existingUpload.targetName,
                    allowExistingSession = true,
                    cancellation = cancellation,
                )
                if (shouldResetIncomingShareChunkProgress(recreated, existingUpload.uploadedChunks)) {
                    current = store.clearChunkSession(requestId)
                    current = store.beginChunkSession(
                        requestId,
                        fileIndex,
                        existingUpload.targetName,
                        existingUpload.uploadId,
                    )
                }
            }
            var upload = requireNotNull(current.chunkSession)
            for (chunkIndex in upload.uploadedChunks until plan.chunkCount) {
                val offset = Math.multiplyExact(chunkIndex.toLong(), plan.chunkBytes)
                val length = minOf(plan.chunkBytes, stagedFile.length() - offset)
                remote.uploadChunk(
                    upload.uploadId,
                    upload.targetName,
                    stagedFile,
                    offset,
                    length,
                    chunkIndex + 1,
                    cancellation,
                )
                current = store.recordUploadedChunk(requestId, chunkIndex)
                upload = requireNotNull(current.chunkSession)
            }
            try {
                remote.commitChunkUpload(
                    upload.uploadId,
                    upload.targetName,
                    stagedFile.length(),
                    cancellation,
                ) {
                    current = store.markChunkCommitInFlight(requestId)
                    upload = requireNotNull(current.chunkSession)
                    setMutationInFlight(true, upload.targetName)
                }
            } catch (failure: DocumentWebDavException) {
                if (!failure.isIncomingShareNameCollision()) throw failure
                setMutationInFlight(false, null)
                occupiedNames += upload.targetName
                current = store.markChunkCleanupPending(requestId)
                upload = requireNotNull(current.chunkSession)
                remote.deleteChunkUpload(upload.uploadId, cancellation)
                current = store.clearChunkSession(requestId)
                continue
            }
            val updated = store.recordUploadedFile(requestId, fileIndex, upload.targetName)
                ?: throw CancellationException("Incoming share upload canceled")
            setMutationInFlight(false, null)
            occupiedNames += upload.targetName
            return updated
        }
    }
}

internal fun requireValidIncomingShareStagedFile(
    stagedFile: File,
    source: AndroidIncomingShareFile,
    cancellation: DocumentRequestCancellation,
) {
    require(stagedFile.length() == source.sizeBytes) {
        "The protected staged copy of ${source.displayName} changed before upload."
    }
    val expectedHash = source.contentHash ?: return
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(stagedFile).use { input ->
        val buffer = ByteArray(64 * 1024)
        var totalBytes = 0L
        while (true) {
            cancellation.throwIfCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            require(totalBytes <= source.sizeBytes) {
                "The protected staged copy of ${source.displayName} changed before upload."
            }
            digest.update(buffer, 0, count)
        }
        require(totalBytes == source.sizeBytes) {
            "The protected staged copy of ${source.displayName} changed before upload."
        }
    }
    val actualHash = "sha256:" + digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
    require(actualHash == expectedHash) {
        "The protected staged copy of ${source.displayName} failed its integrity check."
    }
}

internal fun shouldAbandonResumedIncomingShareTarget(
    targetName: String,
    occupiedNames: Set<String>,
    destinationSnapshotComplete: Boolean,
    resourceExists: () -> Boolean,
): Boolean = targetName in occupiedNames || !destinationSnapshotComplete && resourceExists()

internal fun selectIncomingShareTransferTarget(
    displayName: String,
    occupiedNames: MutableSet<String>,
    destinationSnapshotComplete: Boolean,
    resourceExists: (String) -> Boolean,
): String? = incomingShareUploadNameCandidates(displayName, limit = 1_000).firstOrNull { candidate ->
    when {
        candidate in occupiedNames -> false
        destinationSnapshotComplete -> true
        resourceExists(candidate) -> {
            occupiedNames += candidate
            false
        }
        else -> true
    }
}

internal fun shouldResetIncomingShareChunkProgress(collectionCreated: Boolean, uploadedChunks: Int): Boolean {
    require(uploadedChunks >= 0)
    return collectionCreated && uploadedChunks > 0
}
