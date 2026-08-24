package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.buildNextcloudFileUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class DocumentWebDavError {
    Authentication,
    Permission,
    NotFound,
    AlreadyExists,
    Conflict,
    Locked,
    InsufficientStorage,
    TooLarge,
    Throttled,
    Server,
}

internal class DocumentWebDavException(
    val error: DocumentWebDavError,
    val status: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

internal data class DocumentMutationResult(val etag: String?)

internal data class DocumentReadResult(
    val byteCount: Long,
    val contentType: String?,
    val etag: String?,
)

internal data class DocumentSearchResult(
    val files: List<NextcloudFile>,
    val query: String,
    val limited: Boolean,
)

internal data class DocumentDirectoryResult(
    val files: List<NextcloudFile>,
    val limited: Boolean,
)

internal data class DocumentDirectoryAccess(val canCreateChildren: Boolean)

/**
 * Android's [android.os.CancellationSignal] is deliberately kept out of this transport so the
 * WebDAV behavior remains locally unit-testable. The provider adapter installs a callback which
 * cancels the in-flight OkHttp call, while [throwIfCancelled] closes the race before and after IO.
 */
internal interface DocumentRequestCancellation {
    fun throwIfCancelled()
    fun setOnCancelAction(action: (() -> Unit)?)
}

internal object NoDocumentRequestCancellation : DocumentRequestCancellation {
    override fun throwIfCancelled() = Unit
    override fun setOnCancelAction(action: (() -> Unit)?) = Unit
}

/** Bounded read and conflict-aware mutation WebDAV client for the Android DocumentsProvider. */
internal class NextcloudDocumentWebDav(
    private val client: OkHttpClient = OkHttpClient(),
    private val cloudMutationsAllowed: () -> Boolean = { true },
) {
    fun readFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        destination: OutputStream,
        maximumBytes: Long,
        expectedEtag: String? = null,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentReadResult {
        require(maximumBytes > 0L) { "The document read limit must be positive." }
        require(expectedEtag == null || expectedEtag.isNotBlank()) { "An expected ETag cannot be blank." }
        cancellation.throwIfCancelled()
        val request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path)).apply {
            expectedEtag?.let { header("If-Match", it) }
        }.get().build()
        val call = client.newCall(request)
        cancellation.setOnCancelAction(call::cancel)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw response.toDocumentException("read document")
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength > maximumBytes) {
                    throw DocumentWebDavException(
                        DocumentWebDavError.TooLarge,
                        response.code,
                        "The document is larger than the current provider limit.",
                    )
                }
                var copied = 0L
                val buffer = ByteArray(READ_BUFFER_BYTES)
                body.byteStream().use { source ->
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maximumBytes) {
                            throw DocumentWebDavException(
                                DocumentWebDavError.TooLarge,
                                response.code,
                                "The document is larger than the current provider limit.",
                            )
                        }
                        destination.write(buffer, 0, read)
                    }
                }
                cancellation.throwIfCancelled()
                destination.flush()
                return DocumentReadResult(
                    byteCount = copied,
                    contentType = body.contentType()?.toString(),
                    etag = response.header("ETag") ?: response.header("OC-Etag"),
                )
            }
        } catch (failure: IOException) {
            // A CancellationSignal cancels the OkHttp call, which normally surfaces as an
            // IOException. Prefer the platform cancellation exception supplied by the adapter.
            cancellation.throwIfCancelled()
            throw failure
        } finally {
            cancellation.setOnCancelAction(null)
        }
    }

    /**
     * Bounded server-side filename search for Android's system file picker.
     *
     * This uses WebDAV Basic Search and never downloads file content. The result count and XML
     * response size are both capped so a global account search cannot grow without bound.
     */
    fun searchFiles(
        session: NextcloudSession,
        userId: String,
        rawQuery: String,
        maximumResults: Int = DEFAULT_SEARCH_RESULT_LIMIT,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentSearchResult {
        val query = normalizeDocumentSearchQuery(rawQuery)
        require(maximumResults in 1..MAX_SEARCH_RESULT_LIMIT)
        cancellation.throwIfCancelled()
        val body = documentSearchRequestBody(userId, query, maximumResults)
        val request = requestBuilder(session, session.serverUrl.trimEnd('/') + "/remote.php/dav/")
            .header("Accept", "application/xml")
            .method("SEARCH", body.toRequestBody(XML_CONTENT_TYPE))
            .build()
        val call = client.newCall(request)
        cancellation.setOnCancelAction(call::cancel)
        try {
            call.execute().use { response ->
                if (response.code != 207) throw response.toDocumentException("search documents")
                val declaredLength = response.body.contentLength()
                if (declaredLength > MAX_SEARCH_RESPONSE_BYTES) {
                    throw DocumentWebDavException(
                        DocumentWebDavError.TooLarge,
                        response.code,
                        "The document search response is too large.",
                    )
                }
                val bytes = response.body.byteStream().readBoundedSearchResponse(cancellation)
                cancellation.throwIfCancelled()
                val parsed = parseDocumentSearchResponse(bytes, userId, maximumResults)
                return DocumentSearchResult(
                    files = parsed.take(maximumResults),
                    query = query,
                    limited = parsed.size > maximumResults,
                )
            }
        } catch (failure: IOException) {
            cancellation.throwIfCancelled()
            throw failure
        } finally {
            cancellation.setOnCancelAction(null)
        }
    }

    /**
     * Loads one DAV directory level for recursive offline planning.
     *
     * Both the response bytes and parsed entry count are bounded. The transport returns only
     * immediate children even if a non-conforming server includes unrelated responses.
     */
    fun listDirectory(
        session: NextcloudSession,
        userId: String,
        path: String,
        maximumEntries: Int = DEFAULT_DIRECTORY_ENTRY_LIMIT,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentDirectoryResult {
        require(maximumEntries in 1..MAX_DIRECTORY_ENTRY_LIMIT)
        cancellation.throwIfCancelled()
        val request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
            .header("Accept", "application/xml")
            .header("Depth", "1")
            .method("PROPFIND", DIRECTORY_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
            .build()
        val call = client.newCall(request)
        cancellation.setOnCancelAction(call::cancel)
        try {
            call.execute().use { response ->
                if (response.code != 207) throw response.toDocumentException("list folder")
                if (response.body.contentLength() > MAX_DIRECTORY_RESPONSE_BYTES) {
                    throw DocumentWebDavException(
                        DocumentWebDavError.TooLarge,
                        response.code,
                        "The folder listing response is too large.",
                    )
                }
                val bytes = response.body.byteStream().readBoundedResponse(
                    maximumBytes = MAX_DIRECTORY_RESPONSE_BYTES,
                    cancellation = cancellation,
                    tooLargeMessage = "The folder listing response is too large.",
                )
                val normalizedParent = path.trim('/')
                val parsed = parseDocumentDavResponse(bytes, userId, maximumEntries + 2)
                    .filter { it.path.substringBeforeLast('/', "") == normalizedParent }
                return DocumentDirectoryResult(
                    files = parsed.take(maximumEntries),
                    limited = parsed.size > maximumEntries,
                )
            }
        } catch (failure: IOException) {
            cancellation.throwIfCancelled()
            throw failure
        } finally {
            cancellation.setOnCancelAction(null)
        }
    }

    fun createFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        source: File,
        onRequestStarted: () -> Unit = {},
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentMutationResult {
        val checksum = source.sha256ChecksumForDav(cancellation)
        return execute(
            request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
            .header("If-None-Match", "*")
            .apply { checksum?.let { header("OC-Checksum", it) } }
            .put(source.asRequestBody(OCTET_STREAM))
            .build(),
            operation = "create file",
            onRequestStarted = onRequestStarted,
            cancellation = cancellation,
        )
    }

    fun inspectDirectoryAccess(
        session: NextcloudSession,
        userId: String,
        path: String,
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentDirectoryAccess {
        val request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
            .header("Accept", "application/xml")
            .header("Depth", "0")
            .method("PROPFIND", DIRECTORY_PROPERTIES.toRequestBody(XML_CONTENT_TYPE))
            .build()
        val bytes = executeDavRead(request, "inspect folder", cancellation)
        val response = SafeXmlParser.parse(bytes).getElementsByTagNameNS(DOCUMENT_SEARCH_DAV, "response").item(0)
            ?: throw DocumentWebDavException(DocumentWebDavError.NotFound, 404, "The folder no longer exists.")
        val isDirectory = response.searchCount(DOCUMENT_SEARCH_DAV, "collection") > 0
        val permissions = response.searchText(DOCUMENT_SEARCH_OC, "permissions")
        require(isDirectory) { "The selected upload destination is not a folder." }
        return DocumentDirectoryAccess(canCreateChildren = permissions?.contains('C') == true)
    }

    fun createChunkUpload(
        session: NextcloudSession,
        userId: String,
        uploadId: String,
        destinationPath: String,
        cancellation: DocumentRequestCancellation,
    ): Boolean = try {
        execute(
            requestBuilder(session, chunkUploadUrl(session, userId, uploadId))
                .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
                .header("If-None-Match", "*")
                .method("MKCOL", EMPTY_BODY)
                .build(),
            "start chunked upload",
            cancellation = cancellation,
        )
        true
    } catch (failure: DocumentWebDavException) {
        if (failure.status == 405) false else throw failure
    }

    fun uploadChunk(
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
        require(chunkNumber in 1..10_000 && offset >= 0 && length > 0 && offset + length <= source.length())
        val body = fileRangeRequestBody(source, offset, length, cancellation)
        execute(
            requestBuilder(session, chunkUploadUrl(session, userId, uploadId) + "/${chunkNumber.toString().padStart(5, '0')}")
                .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
                .header("OC-Total-Length", totalLength.toString())
                .put(body)
                .build(),
            "upload file chunk",
            cancellation = cancellation,
        )
    }

    fun commitChunkUpload(
        session: NextcloudSession,
        userId: String,
        uploadId: String,
        destinationPath: String,
        totalLength: Long,
        cancellation: DocumentRequestCancellation,
        onRequestStarted: () -> Unit,
    ): DocumentMutationResult = execute(
        requestBuilder(session, chunkUploadUrl(session, userId, uploadId) + "/.file")
            .header("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
            .header("OC-Total-Length", totalLength.toString())
            .header("Overwrite", "F")
            .method("MOVE", EMPTY_BODY)
            .build(),
        "assemble chunked upload",
        onRequestStarted,
        cancellation,
    )

    fun replaceFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        source: File,
        expectedEtag: String,
    ): DocumentMutationResult {
        require(expectedEtag.isNotBlank()) { "An ETag is required for conflict-protected replacement." }
        val checksum = source.sha256ChecksumForDav()
        return execute(
            request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
                .header("If-Match", expectedEtag)
                .apply { checksum?.let { header("OC-Checksum", it) } }
                .put(source.asRequestBody(OCTET_STREAM))
                .build(),
            operation = "replace file",
        )
    }

    fun createFolder(session: NextcloudSession, userId: String, path: String) {
        execute(
            request = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
                .header("If-None-Match", "*")
                .method("MKCOL", EMPTY_BODY)
                .build(),
            operation = "create folder",
        )
    }

    /**
     * Replaces an existing file with a staged sibling and a conditional MOVE.
     * The tagged WebDAV If condition protects the destination ETag while the source If-Match
     * protects the staged upload itself. MOVE is the single visible replacement operation.
     */
    fun replaceFileAtomically(
        session: NextcloudSession,
        userId: String,
        path: String,
        source: File,
        expectedEtag: String,
    ): DocumentMutationResult {
        require(expectedEtag.isNotBlank()) { "An ETag is required for conflict-protected replacement." }
        val parent = NextcloudDocumentIds.parentPath(path)
        val stagingName = ".nextcloud-native-${UUID.randomUUID()}.upload"
        val stagingPath = if (parent.isBlank()) stagingName else "$parent/$stagingName"
        val stagingUrl = buildNextcloudFileUrl(session.serverUrl, userId, stagingPath)
        val destinationUrl = buildNextcloudFileUrl(session.serverUrl, userId, path)
        val staged = createFile(session, userId, stagingPath, source)
        val stagedEtag = staged.etag
        try {
            val builder = requestBuilder(session, stagingUrl)
                .header("Destination", destinationUrl)
                .header("Overwrite", "T")
                .header("If", "<$destinationUrl> ([$expectedEtag])")
            stagedEtag?.let { builder.header("If-Match", it) }
            return execute(
                request = builder.method("MOVE", EMPTY_BODY).build(),
                operation = "replace file",
            )
        } catch (failure: Throwable) {
            runCatching { deleteOwnedStage(session, userId, stagingPath, stagedEtag) }
            throw failure
        }
    }

    fun move(
        session: NextcloudSession,
        userId: String,
        sourcePath: String,
        destinationPath: String,
        expectedEtag: String,
    ): DocumentMutationResult {
        require(expectedEtag.isNotBlank()) { "An ETag is required for conflict-protected move." }
        val sourceUrl = buildNextcloudFileUrl(session.serverUrl, userId, sourcePath)
        val destinationUrl = buildNextcloudFileUrl(session.serverUrl, userId, destinationPath)
        return execute(
            request = requestBuilder(session, sourceUrl)
                .header("Destination", destinationUrl)
                .header("Overwrite", "F")
                .header("If-Match", expectedEtag)
                .method("MOVE", EMPTY_BODY)
                .build(),
            operation = "move document",
        )
    }

    fun delete(
        session: NextcloudSession,
        userId: String,
        path: String,
        expectedEtag: String?,
        isDirectory: Boolean = false,
    ) {
        require(!expectedEtag.isNullOrBlank()) { "An ETag is required for conflict-protected deletion." }
        val resourceUrl = buildNextcloudFileUrl(session.serverUrl, userId, path)
        val builder = requestBuilder(session, resourceUrl)
        if (isDirectory) {
            builder.header("If", "<$resourceUrl> ([$expectedEtag])")
        } else {
            builder.header("If-Match", expectedEtag)
        }
        execute(
            request = builder.delete().build(),
            operation = "delete document",
        )
    }

    private fun deleteOwnedStage(
        session: NextcloudSession,
        userId: String,
        path: String,
        expectedEtag: String?,
    ) {
        val builder = requestBuilder(session, buildNextcloudFileUrl(session.serverUrl, userId, path))
        expectedEtag?.takeIf(String::isNotBlank)?.let { builder.header("If-Match", it) }
        execute(builder.delete().build(), "clean up staged upload")
    }

    private fun execute(
        request: Request,
        operation: String,
        onRequestStarted: () -> Unit = {},
        cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
    ): DocumentMutationResult {
        check(cloudMutationsAllowed()) {
            "This emulator is using a shared read-only test session. Cloud changes are blocked."
        }
        cancellation.throwIfCancelled()
        val call = client.newCall(request)
        cancellation.setOnCancelAction(call::cancel)
        try {
            onRequestStarted()
            return call.execute().use { response ->
                if (!response.isSuccessful) throw response.toDocumentException(operation)
                DocumentMutationResult(response.header("ETag") ?: response.header("OC-Etag"))
            }
        } catch (failure: IOException) {
            cancellation.throwIfCancelled()
            throw failure
        } finally {
            cancellation.setOnCancelAction(null)
        }
    }

    private fun executeDavRead(
        request: Request,
        operation: String,
        cancellation: DocumentRequestCancellation,
    ): ByteArray {
        cancellation.throwIfCancelled()
        val call = client.newCall(request)
        cancellation.setOnCancelAction(call::cancel)
        try {
            return call.execute().use { response ->
                if (response.code != 207) throw response.toDocumentException(operation)
                response.body.byteStream().readBoundedResponse(
                    MAX_DIRECTORY_RESPONSE_BYTES,
                    cancellation,
                    "The folder metadata response is too large.",
                )
            }
        } catch (failure: IOException) {
            cancellation.throwIfCancelled()
            throw failure
        } finally {
            cancellation.setOnCancelAction(null)
        }
    }

    private fun chunkUploadUrl(session: NextcloudSession, userId: String, uploadId: String): String {
        require(runCatching { UUID.fromString(uploadId) }.isSuccess)
        return session.serverUrl.trimEnd('/') + "/remote.php/dav/uploads/" +
            encodeDocumentSearchPathSegment(userId) + "/" + encodeDocumentSearchPathSegment(uploadId)
    }

    private fun requestBuilder(session: NextcloudSession, url: String): Request.Builder {
        val credentials = "${session.loginName}:${session.appPassword}"
        val basic = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .header("User-Agent", USER_AGENT)
    }

    private fun okhttp3.Response.toDocumentException(operation: String): DocumentWebDavException {
        val error = when (code) {
            401 -> DocumentWebDavError.Authentication
            403 -> DocumentWebDavError.Permission
            404 -> DocumentWebDavError.NotFound
            405, 409 -> DocumentWebDavError.AlreadyExists
            412 -> DocumentWebDavError.Conflict
            423 -> DocumentWebDavError.Locked
            429 -> DocumentWebDavError.Throttled
            507 -> DocumentWebDavError.InsufficientStorage
            else -> DocumentWebDavError.Server
        }
        val message = when (error) {
            DocumentWebDavError.Authentication -> "Sign in again before trying to $operation."
            DocumentWebDavError.Permission -> "Nextcloud did not allow this account to $operation."
            DocumentWebDavError.NotFound -> "The document no longer exists."
            DocumentWebDavError.AlreadyExists -> "A document with that name already exists."
            DocumentWebDavError.Conflict -> "The document changed on the server. Refresh before trying again."
            DocumentWebDavError.Locked -> "The document is currently locked by another operation."
            DocumentWebDavError.InsufficientStorage -> "The Nextcloud server does not have enough free storage."
            DocumentWebDavError.TooLarge -> "The document is larger than the current provider limit."
            DocumentWebDavError.Throttled -> "Nextcloud asked this upload to wait before trying again."
            DocumentWebDavError.Server -> "Nextcloud could not $operation (HTTP $code)."
        }
        return DocumentWebDavException(
            error,
            code,
            message,
            retryAfterSeconds = header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 86_400L),
        )
    }

    private companion object {
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Android DocumentsProvider)"
        const val READ_BUFFER_BYTES = 32 * 1024
        const val DEFAULT_SEARCH_RESULT_LIMIT = 50
        const val MAX_SEARCH_RESULT_LIMIT = 100
        const val MAX_SEARCH_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_DIRECTORY_ENTRY_LIMIT = 1_000
        const val MAX_DIRECTORY_ENTRY_LIMIT = 5_000
        const val MAX_DIRECTORY_RESPONSE_BYTES = 4L * 1024L * 1024L
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val XML_CONTENT_TYPE = "application/xml; charset=utf-8".toMediaType()
        val EMPTY_BODY = byteArrayOf().toRequestBody(null)
        val DIRECTORY_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:displayname/><d:resourcetype/><d:getcontenttype/><d:getlastmodified/>
                <d:getcontentlength/><d:getetag/><oc:fileid/><oc:size/><oc:permissions/><oc:checksums/><nc:has-preview/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}

internal fun normalizeDocumentSearchQuery(rawQuery: String): String {
    val query = rawQuery.trim()
    require(query.isNotEmpty()) { "Enter a filename to search for." }
    require(query.length <= 128) { "The document search is too long." }
    require(query.none(Char::isISOControl)) { "The document search contains invalid characters." }
    return query
}

internal fun documentSearchRequestBody(
    userId: String,
    query: String,
    maximumResults: Int,
): String {
    require(userId.isNotBlank() && userId.none(Char::isISOControl))
    require(maximumResults in 1..100)
    val encodedUser = encodeDocumentSearchPathSegment(userId)
    val literal = "%${escapeDocumentSearchXml(query)}%"
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:resourcetype/><d:getcontenttype/><d:getlastmodified/>
              <d:getcontentlength/><d:getetag/><oc:fileid/><oc:size/><oc:checksums/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>/files/$encodedUser</d:href><d:depth>infinity</d:depth></d:scope></d:from>
            <d:where><d:like>
              <d:prop><d:displayname/></d:prop><d:literal>$literal</d:literal>
            </d:like></d:where>
            <d:orderby><d:order><d:prop><d:displayname/></d:prop><d:ascending/></d:order></d:orderby>
            <d:limit><d:nresults>${maximumResults + 1}</d:nresults></d:limit>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
}

internal fun parseDocumentSearchResponse(
    xml: ByteArray,
    userId: String,
    maximumResults: Int,
): List<NextcloudFile> {
    require(maximumResults in 1..100)
    return parseDocumentDavResponse(xml, userId, maximumResults)
}

private fun parseDocumentDavResponse(
    xml: ByteArray,
    userId: String,
    maximumResults: Int,
): List<NextcloudFile> {
    require(maximumResults in 1..5_002)
    val responses = SafeXmlParser.parse(xml).getElementsByTagNameNS(DOCUMENT_SEARCH_DAV, "response")
    val marker = "/files/$userId/"
    return buildList {
        for (index in 0 until responses.length) {
            if (size > maximumResults) break
            val response = responses.item(index)
            val name = response.searchText(DOCUMENT_SEARCH_DAV, "displayname")?.takeIf(String::isNotBlank)
                ?: continue
            val href = decodeDavHref(response.searchText(DOCUMENT_SEARCH_DAV, "href").orEmpty())
            val path = href.substringAfter(marker, missingDelimiterValue = "").trimEnd('/')
            if (path.isBlank() || path.split('/').any { it.isBlank() || it == "." || it == ".." }) continue
            if (any { it.path == path }) continue
            add(
                NextcloudFile(
                    path = path,
                    name = name,
                    isDirectory = response.searchCount(DOCUMENT_SEARCH_DAV, "collection") > 0,
                    mimeType = response.searchText(DOCUMENT_SEARCH_DAV, "getcontenttype"),
                    size = response.searchText(DOCUMENT_SEARCH_OC, "size")?.toLongOrNull()
                        ?: response.searchText(DOCUMENT_SEARCH_DAV, "getcontentlength")?.toLongOrNull(),
                    lastModified = response.searchText(DOCUMENT_SEARCH_DAV, "getlastmodified"),
                    fileId = response.searchText(DOCUMENT_SEARCH_OC, "fileid")?.toLongOrNull(),
                    hasPreview = response.searchText(DOCUMENT_SEARCH_NC, "has-preview") == "true",
                    etag = response.searchText(DOCUMENT_SEARCH_DAV, "getetag"),
                    permissions = response.searchText(DOCUMENT_SEARCH_OC, "permissions"),
                    checksums = response.searchTexts(DOCUMENT_SEARCH_OC, "checksum"),
                ),
            )
        }
    }
}

private fun java.io.InputStream.readBoundedSearchResponse(
    cancellation: DocumentRequestCancellation,
): ByteArray = readBoundedResponse(
    maximumBytes = 2L * 1024L * 1024L,
    cancellation = cancellation,
    tooLargeMessage = "The document search response is too large.",
)

private fun java.io.InputStream.readBoundedResponse(
    maximumBytes: Long,
    cancellation: DocumentRequestCancellation,
    tooLargeMessage: String,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        cancellation.throwIfCancelled()
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) {
            throw DocumentWebDavException(
                DocumentWebDavError.TooLarge,
                207,
                tooLargeMessage,
            )
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun org.w3c.dom.Node.searchText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private fun org.w3c.dom.Node.searchCount(namespace: String, localName: String): Int =
    (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, localName)?.length ?: 0

private fun org.w3c.dom.Node.searchTexts(namespace: String, localName: String): List<String> {
    val nodes = (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, localName)
        ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(nodes.length, MAX_DAV_CHECKSUMS_PER_FILE)) {
            nodes.item(index).textContent
                ?.trim()
                ?.takeIf { it.length in 1..MAX_DAV_CHECKSUM_LENGTH && it.none(Char::isISOControl) }
                ?.let(::add)
        }
    }
}

private fun File.sha256ChecksumForDav(
    cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
): String? {
    if (!isFile || length() !in 0..MAX_DAV_CHECKSUM_FILE_BYTES) return null
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(CHECKSUM_BUFFER_BYTES)
        var total = 0L
        while (true) {
            cancellation.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_DAV_CHECKSUM_FILE_BYTES) return null
            digest.update(buffer, 0, read)
        }
    }
    return "SHA256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun encodeDocumentSearchPathSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        if (
            unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned in listOf('-'.code, '.'.code, '_'.code, '~'.code)
        ) {
            append(unsigned.toChar())
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned ushr 4])
            append("0123456789ABCDEF"[unsigned and 0x0f])
        }
    }
}

private fun escapeDocumentSearchXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private const val DOCUMENT_SEARCH_DAV = "DAV:"
private const val DOCUMENT_SEARCH_OC = "http://owncloud.org/ns"
private const val DOCUMENT_SEARCH_NC = "http://nextcloud.org/ns"
private const val MAX_DAV_CHECKSUMS_PER_FILE = 8
private const val MAX_DAV_CHECKSUM_LENGTH = 256
private const val MAX_DAV_CHECKSUM_FILE_BYTES = 64L * 1024L * 1024L
private const val CHECKSUM_BUFFER_BYTES = 32 * 1024
