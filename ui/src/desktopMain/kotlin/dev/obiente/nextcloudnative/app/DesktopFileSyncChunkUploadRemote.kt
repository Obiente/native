package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class DesktopFileSyncChunkUploadRemote(
    private val session: NextcloudSession,
    private val userId: String,
    private val rootPath: String,
    private val client: OkHttpClient,
    private val tree: DesktopFileSyncRemoteTree,
    private val onMutationCommitted: (String) -> Unit,
    private val onAmbiguousMutationResult: (String) -> Unit,
    private val shouldContinue: () -> Boolean,
) : JvmResumableNextcloudUploadRemote {
    private val mutationExecutor = DesktopHttpMutationExecutor(client)

    override fun uploadDirect(
        source: File,
        relativePath: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry = tree.writeFile(relativePath, source, expectedRemoteEtag)

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
            accepted = { it in 200..299 || allowExisting && it == 405 },
        )
        return responseCode != 405
    }

    override fun listChunkCollection(uploadId: String): Map<Int, Long> =
        client.newCall(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
                .header("Depth", "1")
                .method("PROPFIND", CHUNK_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
                .build(),
        ).execute().use { response ->
            if (response.code != 207) {
                throw DesktopFileSyncHttpStatusException(response.code, "inspect chunked upload")
            }
            response.body.byteStream().readNextcloudChunkCollection()
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
        )
    }

    override fun commitChunksToOwnedStage(uploadId: String, relativePath: String, sizeBytes: Long): String? =
        client.newCall(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) + "/.file")
                .header("Destination", fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
                .header("OC-Total-Length", sizeBytes.toString())
                .header("Overwrite", "F")
                .method("MOVE", EMPTY_BODY)
                .build(),
        ).execute().use { response ->
            if (response.code != 201) {
                throw DesktopFileSyncHttpStatusException(response.code, "assemble chunked upload")
            }
            response.header("ETag") ?: response.header("OC-Etag")
        }

    override fun verifyOwnedStage(
        uploadId: String,
        relativePath: String,
        source: File,
        expectedStageEtag: String?,
    ): String {
        val stagePath = jvmOwnedUploadStagePath(relativePath, uploadId)
        val stage = requireNotNull(tree.resolveOwnedUploadStage(stagePath)) {
            "The assembled upload stage disappeared."
        }
        require(!stage.isDirectory && stage.entry.size == source.length()) {
            "The assembled upload stage has an unexpected size."
        }
        require(expectedStageEtag == null || safeEtag(stage.entry.etag) == safeEtag(expectedStageEtag)) {
            "The assembled upload stage changed before verification."
        }
        client.newCall(
            requestBuilder(fileUrl(stagePath))
                .header("Accept", "application/octet-stream")
                .header("If-Match", safeEtag(stage.entry.etag))
                .get()
                .build(),
        ).execute().use { response ->
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

    override fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry {
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
        ) { response ->
            if (!response.isSuccessful) throw DesktopFileSyncHttpStatusException(response.code, "publish upload")
        }
        return requireNotNull(tree.resolve(relativePath)) { "The uploaded server file disappeared." }
            .also { require(!it.isDirectory) }.entry
    }

    override fun discardOwnedUpload(uploadId: String, relativePath: String, assembledStageEtag: String?) {
        execute(
            requestBuilder(buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId)).delete().build(),
            "discard chunked upload",
            accepted = { it in 200..299 || it == 404 },
        )
        assembledStageEtag ?: return
        execute(
            requestBuilder(fileUrl(jvmOwnedUploadStagePath(relativePath, uploadId)))
                .header("If-Match", safeEtag(assembledStageEtag))
                .delete().build(),
            "discard assembled upload",
            accepted = { it in 200..299 || it == 404 || it == 412 },
        )
    }

    private fun execute(
        request: Request,
        operation: String,
        accepted: (Int) -> Boolean = { it in 200..299 },
    ): Int = client.newCall(request).execute().use { response ->
        if (!accepted(response.code)) throw DesktopFileSyncHttpStatusException(response.code, operation)
        response.code
    }

    private fun requestBuilder(url: String): Request.Builder {
        val authorization = Base64.getEncoder().encodeToString(
            "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
        )
        return Request.Builder().url(url)
            .header("Authorization", "Basic $authorization")
            .header("User-Agent", USER_AGENT)
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
