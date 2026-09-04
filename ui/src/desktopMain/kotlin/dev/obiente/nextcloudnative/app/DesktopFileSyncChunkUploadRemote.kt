package dev.obiente.nextcloudnative.app

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class DesktopFileSyncChunkUploadRemote(
    private val session: NextcloudSession,
    private val userId: String,
    private val rootPath: String,
    httpClient: OkHttpClient,
    private val tree: DesktopFileSyncRemoteTree,
    private val onMutationCommitted: (String) -> Unit,
    private val onAmbiguousMutationResult: (String) -> Unit,
    private val shouldContinue: () -> Boolean,
    private val replacingDirectoryEtag: String? = null,
) : JvmResumableNextcloudUploadRemote {
    private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val requestPolicy = NextcloudAuthenticatedRequestPolicy(session, USER_AGENT)
    private val mutationExecutor = DesktopHttpMutationExecutor(this.client)

    override fun uploadDirect(
        source: File,
        relativePath: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry {
        check(replacingDirectoryEtag == null) { "Directory replacement must use an owned upload stage." }
        return tree.writeFileCancellable(relativePath, source, expectedRemoteEtag, shouldContinue)
    }

    override fun ownedStageCreationAllowed(relativePath: String): Boolean? {
        val allowed = tree.ownedStageCreationAllowed(relativePath, shouldContinue)
        if (replacingDirectoryEtag != null) {
            check(allowed != false) {
                "The destination folder does not allow the staging file required to replace a directory."
            }
            return true
        }
        return allowed
    }

    override fun verifyDirectUpload(
        source: File,
        relativePath: String,
        uploaded: RemoteSyncEntry,
    ): RemoteSyncEntry {
        val exact = requireNotNull(tree.resolvePhysical(relativePath, shouldContinue)) {
            "The directly uploaded file disappeared."
        }
        require(!exact.isDirectory && exact.entry.etag == uploaded.etag && exact.entry.size == source.length()) {
            "The directly uploaded file changed before verification."
        }
        val request = requestBuilder(fileUrl(relativePath))
            .header("Accept", "application/octet-stream")
            .header("If-Match", safeEtag(exact.entry.etag))
            .get()
            .build()
        executeRequest(request) { response ->
            if (response.code != 200) {
                throw DesktopFileSyncHttpStatusException(response.code, "verify direct upload")
            }
            val declaredBytes = response.body.contentLength()
            require(declaredBytes == -1L || declaredBytes == source.length()) {
                "The directly uploaded file has an unexpected response size."
            }
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                response.body.byteStream().copyBoundedNetworkResponseTo(
                    output = comparison,
                    maxBytes = source.length().coerceAtLeast(1L),
                    onLimitExceeded = { error("The directly uploaded file is larger than expected.") },
                    onNetworkReadFailure = {},
                    shouldContinue = shouldContinue,
                )
                comparison.requireComplete()
            }
        }
        return exact.entry
    }

    override fun completePublishedFile(uploadId: String, relativePath: String) {
        replacingDirectoryEtag?.let { tree.completeReplacementBackup(relativePath, uploadId, it, shouldContinue) }
    }

    override fun createChunkCollection(
        uploadId: String,
        relativePath: String,
        allowExisting: Boolean,
    ): Boolean {
        val responseCode = execute(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
                .header("Destination", fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
                .header("If-None-Match", "*")
                .method("MKCOL", EMPTY_BODY)
                .build(),
            "start chunked upload",
            mutationRelativePath = relativePath,
            accepted = { it in 200..299 || allowExisting && it == 405 },
        )
        return responseCode != 405
    }

    override fun listChunkCollection(uploadId: String): Map<Int, Long> {
        val request = requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
            .header("Depth", "1")
            .method("PROPFIND", CHUNK_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
            .build()
        return executeRequest(request) { response ->
            if (response.code != 207) {
                throw DesktopFileSyncHttpStatusException(response.code, "inspect chunked upload")
            }
            response.body.byteStream().readNextcloudChunkCollection()
        }
    }

    override fun deleteChunk(uploadId: String, chunkNumber: Int) {
        require(chunkNumber in 1..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        execute(
            requestBuilder(
                buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) +
                    "/${chunkNumber.toString().padStart(5, '0')}",
            ).delete().build(),
            "discard stale upload chunk",
            accepted = { it in 200..299 || it == 404 },
        )
    }

    override fun uploadChunk(
        uploadId: String,
        relativePath: String,
        source: File,
        chunk: NextcloudUploadChunk,
    ) {
        execute(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) + "/${chunk.remoteName}")
                .header("Destination", fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
                .header("OC-Total-Length", source.length().toString())
                .put(jvmFileRangeRequestBody(source, chunk.offsetBytes, chunk.sizeBytes) {
                    if (!shouldContinue()) throw kotlinx.coroutines.CancellationException("Sync upload paused.")
                })
                .build(),
            "upload file chunk",
            mutationRelativePath = relativePath,
        )
    }

    override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long): String? {
        val request = requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) + "/.file")
            .header("Destination", fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
            .header("OC-Total-Length", sizeBytes.toString())
            .header("Overwrite", "F")
            .method("MOVE", EMPTY_BODY)
            .build()
        return mutationExecutor.execute(
            request = request,
            onAmbiguousNetworkResult = { onAmbiguousMutationResult(relativePath) },
            shouldContinue = shouldContinue,
        ) { response ->
            if (response.code != 201) {
                throw DesktopFileSyncHttpStatusException(response.code, "assemble chunked upload")
            }
            response.header("ETag") ?: response.header("OC-Etag")
        }
    }

    override fun verifyOwnedStage(
        uploadId: String,
        relativePath: String,
        source: File,
        expectedStageEtag: String?,
    ): String {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stage = requireNotNull(tree.resolveOwnedUploadStage(stagePath, shouldContinue)) {
            "The assembled upload stage disappeared."
        }
        require(!stage.isDirectory && stage.entry.size == source.length()) {
            "The assembled upload stage has an unexpected size."
        }
        require(expectedStageEtag == null || safeEtag(stage.entry.etag) == safeEtag(expectedStageEtag)) {
            "The assembled upload stage changed before verification."
        }
        val request = requestBuilder(fileUrl(stagePath))
            .header("Accept", "application/octet-stream")
            .header("If-Match", safeEtag(stage.entry.etag))
            .get()
            .build()
        executeRequest(request) { response ->
            if (response.code != 200) {
                throw DesktopFileSyncHttpStatusException(response.code, "verify assembled upload")
            }
            val declaredBytes = response.body.contentLength()
            require(declaredBytes == -1L || declaredBytes == source.length()) {
                "The assembled upload stage has an unexpected response size."
            }
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                response.body.byteStream().copyBoundedNetworkResponseTo(
                    output = comparison,
                    maxBytes = source.length().coerceAtLeast(1L),
                    onLimitExceeded = { error("The assembled upload stage is larger than expected.") },
                    onNetworkReadFailure = {},
                    shouldContinue = shouldContinue,
                )
                comparison.requireComplete()
            }
        }
        return stage.entry.etag
    }

    override fun ownedStageEtag(uploadId: String, relativePath: String): String? =
        tree.resolveOwnedUploadStage(jvmOwnedUploadStagePath(relativePath, uploadId), shouldContinue)?.entry?.etag

    override fun resolvePublishedFile(relativePath: String): RemoteSyncEntry? =
        tree.resolvePhysical(relativePath, shouldContinue)?.takeUnless { it.isDirectory }?.entry

    override fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry {
        if (replacingDirectoryEtag != null) {
            require(expectedRemoteEtag == replacingDirectoryEtag)
            return tree.publishOwnedStageReplacingDirectory(
                relativePath, uploadId, verifiedStageEtag, replacingDirectoryEtag, shouldContinue,
            )
        }
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stageUrl = fileUrl(stagePath)
        val destinationUrl = fileUrl(relativePath)
        val builder = requestBuilder(stageUrl)
            .header("Destination", destinationUrl)
            .header("Overwrite", if (expectedRemoteEtag == null) "F" else "T")
            .header("If-Match", safeEtag(verifiedStageEtag))
        expectedRemoteEtag?.let { builder.header("If", "<$destinationUrl> ([${safeEtag(it)}])") }
        mutationExecutor.execute(
            request = builder.method("MOVE", EMPTY_BODY).build(),
            onAmbiguousNetworkResult = { onAmbiguousMutationResult(relativePath) },
            onAcceptedResponse = { onMutationCommitted(relativePath) },
            shouldContinue = shouldContinue,
        ) { response ->
            if (!response.isSuccessful) throw DesktopFileSyncHttpStatusException(response.code, "publish upload")
        }
        return requireNotNull(tree.resolve(relativePath)) { "The uploaded server file disappeared." }
            .also { require(!it.isDirectory) }.entry
    }

    override fun discardOwnedUpload(
        uploadId: String,
        relativePath: String,
        assembledStageEtag: String?,
        expectedStageSizeBytes: Long?,
        expectedStageContentHash: String?,
        publicationInFlight: Boolean,
    ): Boolean {
        execute(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId)).delete().build(),
            "discard chunked upload",
            mutationRelativePath = relativePath,
            accepted = { it in 200..299 || it == 404 },
        )
        if (publicationInFlight) {
            tree.reconcilePublishedReplacement(
                relativePath,
                uploadId,
                expectedStageSizeBytes,
                expectedStageContentHash,
                shouldContinue,
            )?.let { return it }
        }
        val stageCleaned = if (assembledStageEtag == null) {
            reconcileUnrecordedOwnedStage(
                uploadId,
                relativePath,
                expectedStageSizeBytes,
                expectedStageContentHash,
            )
        } else {
            execute(
                requestBuilder(fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
                    .header("If-Match", safeEtag(assembledStageEtag))
                    .delete().build(),
                "discard assembled upload",
                mutationRelativePath = relativePath,
                accepted = { it in 200..299 || it == 404 || it == 412 },
            ) != 412
        }
        return stageCleaned && tree.discardReplacementBackup(
            relativePath, uploadId, assembledStageEtag, shouldContinue,
        )
    }

    private fun reconcileUnrecordedOwnedStage(
        uploadId: String,
        relativePath: String,
        expectedStageSizeBytes: Long?,
        expectedStageContentHash: String?,
    ): Boolean {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stage = tree.resolveOwnedUploadStage(stagePath) ?: return true
        if (expectedStageSizeBytes == null || expectedStageContentHash == null) return false
        if (stage.isDirectory || stage.entry.size != expectedStageSizeBytes) return false
        if (
            !tree.verifyContentHash(
                stagePath,
                stage.entry.etag,
                expectedStageSizeBytes,
                expectedStageContentHash,
                expectedStageSizeBytes.coerceAtLeast(1L),
                shouldContinue,
                ownedStage = true,
            )
        ) {
            return false
        }
        return execute(
            requestBuilder(fileUrl(stagePath))
                .header("If-Match", safeEtag(stage.entry.etag))
                .delete()
                .build(),
            "discard verified assembled upload",
            mutationRelativePath = relativePath,
            accepted = { it in 200..299 || it == 404 || it == 412 },
        ) != 412
    }

    private fun execute(
        request: Request,
        operation: String,
        mutationRelativePath: String? = null,
        accepted: (Int) -> Boolean = { it in 200..299 },
    ): Int = mutationExecutor.execute(
        request = request,
        onAmbiguousNetworkResult = { mutationRelativePath?.let(onAmbiguousMutationResult) },
        shouldContinue = shouldContinue,
    ) { response ->
        if (!accepted(response.code)) throw DesktopFileSyncHttpStatusException(response.code, operation)
        response.code
    }

    private fun requestBuilder(url: String): Request.Builder = requestPolicy.requestBuilder(url)

    private fun <T> executeRequest(request: Request, consume: (okhttp3.Response) -> T): T = try {
        withDesktopFileSyncCallCancellation(shouldContinue) { executeCall ->
            executeNextcloudAuthenticatedRequest(
                client = client,
                initialRequest = request,
                executeCall = executeCall,
                consume = consume,
            )
        }
    } catch (failure: NextcloudAuthenticatedRedirectException) {
        throw failure.toDesktopFileSyncHttpStatusException("follow authenticated chunk upload redirect")
    }

    private fun fileUrl(relativePath: String): String = buildNextcloudFileUrl(
        session.serverUrl,
        userId,
        listOf(rootPath, relativePath.trim('/')).filter(String::isNotBlank).joinToString("/"),
    )

    private fun safeEtag(value: String): String = value.also {
        require(it.isNotBlank() && '\r' !in it && '\n' !in it) { "The server revision is invalid." }
    }

    private companion object {
        val EMPTY_BODY = byteArrayOf().toRequestBody(null)
        val XML_CONTENT_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val CHUNK_PROPERTIES =
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>"
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Desktop file sync)"
    }
}
