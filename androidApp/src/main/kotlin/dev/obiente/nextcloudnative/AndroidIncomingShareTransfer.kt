package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.incomingShareUploadNameCandidates
import java.io.IOException
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

internal fun Throwable.isRetryableIncomingShareTransferFailure(): Boolean {
    val dav = this as? DocumentWebDavException
    return this is IOException || dav?.error == DocumentWebDavError.Throttled ||
        (dav?.error == DocumentWebDavError.Server && dav.status >= 500)
}

internal fun incomingShareChunkCount(sizeBytes: Long): Int {
    require(sizeBytes > DIRECT_INCOMING_SHARE_UPLOAD_BYTES)
    return ((sizeBytes + INCOMING_SHARE_CHUNK_BYTES - 1L) / INCOMING_SHARE_CHUNK_BYTES).toInt().also {
        require(it in 2..10_000) { "The shared file requires too many upload chunks." }
    }
}

internal const val DIRECT_INCOMING_SHARE_UPLOAD_BYTES = 20L * 1024L * 1024L
internal const val INCOMING_SHARE_CHUNK_BYTES = 10L * 1024L * 1024L
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
        setMutationInFlight: (Boolean) -> Unit,
    ): AndroidIncomingShareRequest {
        val source = request.files[fileIndex]
        val stagedFile = store.stagedFile(requestId, source)
        return if (stagedFile.length() <= DIRECT_INCOMING_SHARE_UPLOAD_BYTES) {
            uploadDirect(requestId, fileIndex, source.displayName, stagedFile, occupiedNames, setMutationInFlight)
        } else {
            uploadChunked(requestId, fileIndex, source.displayName, stagedFile, occupiedNames, setMutationInFlight)
        }
    }

    private fun uploadDirect(
        requestId: String,
        fileIndex: Int,
        displayName: String,
        stagedFile: File,
        occupiedNames: MutableSet<String>,
        setMutationInFlight: (Boolean) -> Unit,
    ): AndroidIncomingShareRequest {
        val targetName = incomingShareCandidates(displayName, occupiedNames).firstNotNullOfOrNull { candidate ->
            try {
                remote.createFileIfAbsent(
                    candidate,
                    stagedFile,
                    onRequestStarted = { setMutationInFlight(true) },
                    cancellation = cancellation,
                )
                candidate
            } catch (failure: DocumentWebDavException) {
                if (!failure.isIncomingShareNameCollision()) throw failure
                setMutationInFlight(false)
                occupiedNames += candidate
                null
            }
        } ?: error("No safe available name remains for $displayName.")
        val updated = store.recordUploadedFile(requestId, fileIndex, targetName)
            ?: throw CancellationException("Incoming share upload canceled")
        setMutationInFlight(false)
        occupiedNames += targetName
        return updated
    }

    private fun uploadChunked(
        requestId: String,
        fileIndex: Int,
        displayName: String,
        stagedFile: File,
        occupiedNames: MutableSet<String>,
        setMutationInFlight: (Boolean) -> Unit,
    ): AndroidIncomingShareRequest {
        var current = store.requireAvailable(requestId)
        while (true) {
            cancellation.throwIfCancelled()
            val existingUpload = current.chunkSession
            if (existingUpload == null) {
                val targetName = incomingShareCandidates(displayName, occupiedNames).firstOrNull()
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
            val chunkCount = incomingShareChunkCount(stagedFile.length())
            for (chunkIndex in upload.uploadedChunks until chunkCount) {
                val offset = chunkIndex * INCOMING_SHARE_CHUNK_BYTES
                val length = minOf(INCOMING_SHARE_CHUNK_BYTES, stagedFile.length() - offset)
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
                    setMutationInFlight(true)
                }
            } catch (failure: DocumentWebDavException) {
                if (!failure.isIncomingShareNameCollision()) throw failure
                setMutationInFlight(false)
                occupiedNames += upload.targetName
                remote.deleteChunkUpload(upload.uploadId, cancellation)
                current = store.clearChunkSession(requestId)
                continue
            }
            val updated = store.recordUploadedFile(requestId, fileIndex, upload.targetName)
                ?: throw CancellationException("Incoming share upload canceled")
            setMutationInFlight(false)
            occupiedNames += upload.targetName
            return updated
        }
    }

    private fun incomingShareCandidates(
        displayName: String,
        occupiedNames: Set<String>,
    ): Sequence<String> = incomingShareUploadNameCandidates(displayName, limit = 1_000)
        .asSequence()
        .filterNot(occupiedNames::contains)
}

internal fun shouldResetIncomingShareChunkProgress(collectionCreated: Boolean, uploadedChunks: Int): Boolean {
    require(uploadedChunks >= 0)
    return collectionCreated && uploadedChunks > 0
}
