package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.MAX_NEXTCLOUD_UPLOAD_CHUNKS
import dev.obiente.nextcloudnative.app.buildNextcloudChunkUploadUrl
import dev.obiente.nextcloudnative.app.buildNextcloudFileUrl
import dev.obiente.nextcloudnative.app.readBoundedNextcloudChunkCollection
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal fun NextcloudDocumentWebDav.createChunkUpload(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    destinationPath: String,
    allowExistingSession: Boolean,
    cancellation: DocumentRequestCancellation,
): Boolean = try {
    execute(
        requestBuilder(session, buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
            .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
            .header("If-None-Match", "*")
            .method("MKCOL", EMPTY_CHUNK_BODY)
            .build(),
        "start chunked upload",
        cancellation = cancellation,
    )
    true
} catch (failure: DocumentWebDavException) {
    if (allowExistingSession && failure.status == 405) false else throw failure
}

internal fun NextcloudDocumentWebDav.uploadChunk(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    destinationPath: String,
    source: File,
    offset: Long,
    length: Long,
    totalLength: Long,
    chunkNumber: Int,
    cancellation: DocumentRequestCancellation,
) {
    val end = Math.addExact(offset, length)
    require(chunkNumber in 1..10_000 && offset >= 0 && length > 0 && end <= source.length())
    execute(
        requestBuilder(
            session,
            buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) +
                "/${chunkNumber.toString().padStart(5, '0')}",
        )
            .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
            .header("OC-Total-Length", totalLength.toString())
            .put(fileRangeRequestBody(source, offset, length, cancellation))
            .build(),
        "upload file chunk",
        cancellation = cancellation,
        timeoutMillis = CHUNK_TRANSFER_INACTIVITY_TIMEOUT_MILLIS,
    )
}

internal fun NextcloudDocumentWebDav.listChunkUpload(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    cancellation: DocumentRequestCancellation,
): Map<Int, Long> {
    val bytes = executeDavRead(
        requestBuilder(session, buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
            .header("Depth", "1")
            .method("PROPFIND", CHUNK_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
            .build(),
        "inspect chunked upload",
        cancellation,
    )
    return bytes.inputStream().readBoundedNextcloudChunkCollection()
}

internal fun NextcloudDocumentWebDav.deleteChunk(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    chunkNumber: Int,
    cancellation: DocumentRequestCancellation,
) {
    require(chunkNumber in 1..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
    try {
        execute(
            requestBuilder(
                session,
                buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) +
                    "/${chunkNumber.toString().padStart(5, '0')}",
            ).delete().build(),
            "remove stale upload chunk",
            cancellation = cancellation,
        )
    } catch (failure: DocumentWebDavException) {
        if (failure.status != 404) throw failure
    }
}

internal fun NextcloudDocumentWebDav.deleteChunkUpload(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    cancellation: DocumentRequestCancellation,
) {
    try {
        execute(
            requestBuilder(session, buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId))
                .delete().build(),
            "remove rejected chunked upload",
            cancellation = cancellation,
        )
    } catch (failure: DocumentWebDavException) {
        if (failure.status != 404) throw failure
    }
}

internal fun NextcloudDocumentWebDav.commitChunkUpload(
    session: NextcloudSession,
    userId: String,
    uploadId: String,
    destinationPath: String,
    totalLength: Long,
    cancellation: DocumentRequestCancellation,
    onRequestStarted: () -> Unit,
): DocumentMutationResult = execute(
    requestBuilder(session, buildNextcloudChunkUploadUrl(session.serverUrl, userId, uploadId) + "/.file")
        .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
        .header("OC-Total-Length", totalLength.toString())
        .header("Overwrite", "F")
        .method("MOVE", EMPTY_CHUNK_BODY)
        .build(),
    "assemble chunked upload",
    onRequestStarted,
    cancellation,
    timeoutMillis = CHUNK_TRANSFER_INACTIVITY_TIMEOUT_MILLIS,
    requiredSuccessStatus = 201,
)

internal fun NextcloudDocumentWebDav.publishChunkUploadStage(
    session: NextcloudSession,
    userId: String,
    stagePath: String,
    destinationPath: String,
    stagedEtag: String,
    expectedRemoteEtag: String?,
): DocumentMutationResult {
    require(stagedEtag.isNotBlank() && stagedEtag.none { it == '\r' || it == '\n' })
    require(expectedRemoteEtag == null ||
        expectedRemoteEtag.isNotBlank() && expectedRemoteEtag.none { it == '\r' || it == '\n' })
    val stageUrl = buildNextcloudFileUrl(session.serverUrl, userId, stagePath)
    val destinationUrl = buildNextcloudFileUrl(session.serverUrl, userId, destinationPath)
    val builder = requestBuilder(session, stageUrl)
        .header("Destination", destinationUrl)
        .header("Overwrite", if (expectedRemoteEtag == null) "F" else "T")
        .header("If-Match", stagedEtag)
    expectedRemoteEtag?.let { builder.header("If", "<$destinationUrl> ([$it])") }
    return execute(builder.method("MOVE", EMPTY_CHUNK_BODY).build(), "publish chunked upload")
}

private val EMPTY_CHUNK_BODY = byteArrayOf().toRequestBody(null)
private val XML_CONTENT_TYPE = "application/xml; charset=utf-8".toMediaType()
private const val CHUNK_PROPERTIES =
    "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>"
private const val CHUNK_TRANSFER_INACTIVITY_TIMEOUT_MILLIS = 30L * 60L * 1_000L
