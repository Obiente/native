package dev.obiente.nextcloudnative.app

/** One file write for the account a [DesktopFileWrites] is bound to. */
internal class DesktopFileWriteRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val contentType: String? = null,
    /** The success body bound; null keeps the transport's default. */
    val maximumResponseBytes: Long? = null,
    /** False sends the request through the mutation executor that refuses redirects. */
    val followRedirects: Boolean = true,
)

internal class DesktopFileWriteResponse(val status: Int, val etag: String?, val body: ByteArray)

internal fun interface DesktopFileWriteTransport {
    /**
     * Sends [request] through the desktop mutation executor, which calls
     * [onAcceptedOrUnknownResult] once the server accepted the request or the exchange was
     * interrupted after it may have been sent, and raises
     * [DesktopFileSyncAmbiguousMutationException] for the interrupted exchange.
     */
    suspend fun send(request: DesktopFileWriteRequest, onAcceptedOrUnknownResult: () -> Unit): DesktopFileWriteResponse
}

/**
 * The desktop file writes of one account: folder and text file creation, text saves, favorites,
 * version restores, and rename, move, copy, and delete.
 *
 * The server result is authoritative and reaches the caller promptly. Local cache and
 * virtual-file bookkeeping runs on [followUps], queued once the server accepted a request or its
 * result became unknown, and each confirmed result reports it as its local follow-up.
 * [refreshLocalState] may wait on the virtual-file provider's monitor, which neither cancellation
 * nor interruption can end, so it never runs on the caller's coroutine.
 */
internal class DesktopFileWrites(
    private val accountId: String,
    private val serverUrl: String,
    private val userId: String,
    private val followUps: DesktopFileMutationFollowUps,
    private val transport: DesktopFileWriteTransport,
    private val refreshLocalState: (path: String) -> Throwable?,
    private val loadEtag: suspend (path: String) -> String? = { null },
    private val storeContent: (path: String, content: NextcloudFileContent) -> Unit = { _, _ -> },
    private val favoriteConfirmed: (multistatus: ByteArray) -> Boolean = { false },
) {
    suspend fun createDirectoryIfAbsent(path: String): DirectoryCreation {
        val mutation = followUps.mutation(accountId, listOf(path), refreshLocalState)
        val response = mutation.send(
            DesktopFileWriteRequest("MKCOL", fileUrl(path), CREATE_ONLY_HEADERS, maximumResponseBytes = STATUS_RESPONSE_BYTES),
        )
        if (response.status in setOf(405, 412)) return DirectoryCreation(wasCreated = false)
        if (response.status !in 200..299) throw fileOperationException(response.status)
        check(response.status == 201) { "The server did not confirm that a new folder was created." }
        return DirectoryCreation(wasCreated = true, mutation.afterConfirmedMutation())
    }

    suspend fun createTextFileIfAbsent(path: String, text: String): SavedTextFile {
        val utf8 = text.encodeToByteArray()
        require(utf8.size.toLong() <= MAX_EDITABLE_TEXT_BYTES) {
            "Text files larger than ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB cannot be created in the app."
        }
        val mutation = followUps.mutation(accountId, listOf(path), refreshLocalState)
        val response = mutation.send(DesktopFileWriteRequest("PUT", fileUrl(path), CREATE_ONLY_HEADERS, utf8, TEXT_CONTENT_TYPE))
        if (response.status == 412) return SavedTextFile(etag = null, wasCreated = false)
        check(response.status in 200..299) { "Creating the text file failed (HTTP ${response.status})." }
        check(response.status == 201) { "The server did not confirm that a new text file was created." }
        val followUp = mutation.afterConfirmedMutation()
        response.etag?.let { etag -> prefillReadCache(path, NextcloudFileContent(utf8, TEXT_CONTENT_TYPE, etag)) }
        return SavedTextFile(response.etag, wasCreated = true, followUp)
    }

    suspend fun saveTextFile(path: String, text: String, expectedEtag: String): SavedTextFile {
        val specification = textFileDavSaveRequest(text, expectedEtag)
        val mutation = followUps.mutation(accountId, listOf(path), refreshLocalState)
        val response = mutation.send(
            DesktopFileWriteRequest("PUT", fileUrl(path), specification.headers, specification.body, specification.contentType),
        )
        val confirmation = confirmTextFileDavSave(response.status)
        val etag = response.etag ?: runCatchingPreservingCancellation { loadEtag(path) }.getOrNull()
        val followUp = mutation.afterConfirmedMutation()
        etag?.let { prefillReadCache(path, NextcloudFileContent(specification.body, specification.contentType, it)) }
        return SavedTextFile(etag, confirmation.created, followUp)
    }

    suspend fun setFileFavorite(file: NextcloudFile, favorite: Boolean): FileMutationLocalFollowUp {
        val safePath = requireSafeFilePath(file.path, allowRoot = false)
        val expectedEtag = file.etag?.trim().orEmpty()
        require(expectedEtag.isNotEmpty()) { "Refresh the folder before changing favorites." }
        val condition = FileWebDavMutationSpec(
            method = "PROPPATCH",
            sourcePath = safePath,
            destinationPath = null,
            expectedEtag = expectedEtag,
            sourceIsDirectory = file.isDirectory,
            overwrite = false,
        ).conflictConditionHeaders()
        val mutation = followUps.mutation(accountId, listOf(safePath), refreshLocalState)
        val response = mutation.send(
            DesktopFileWriteRequest(
                "PROPPATCH",
                fileUrl(safePath),
                mapOf("Accept" to "application/xml") + condition,
                buildFileFavoritePropPatch(favorite).encodeToByteArray(),
                "application/xml; charset=utf-8",
                STATUS_RESPONSE_BYTES,
            ),
        )
        if (response.status !in 200..299) throw fileOperationException(response.status)
        check(response.status == 200 || response.status == 207 && favoriteConfirmed(response.body)) {
            "The server did not confirm the favorite change."
        }
        return mutation.afterConfirmedMutation()
    }

    suspend fun restoreFileVersion(file: NextcloudFile, version: NextcloudFileVersion): FileMutationLocalFollowUp {
        val specification = fileVersionRestoreRequest(userId, file, version)
        val mutation = followUps.mutation(accountId, listOf(file.path), refreshLocalState)
        val response = mutation.send(
            DesktopFileWriteRequest(
                specification.method,
                serverUrl + specification.relativePath,
                specification.headers + mapOf(
                    "Accept" to "*/*",
                    "Destination" to serverUrl + specification.destinationRelativePath,
                ),
                maximumResponseBytes = specification.maximumResponseBytes,
                followRedirects = false,
            ),
        )
        return when (val result = classifyFileVersionRestoreHttpResponse(response.status)) {
            FileVersionRestoreHttpResult.Restored -> mutation.afterConfirmedMutation()
            is FileVersionRestoreHttpResult.Rejected -> error(result.message)
        }
    }

    suspend fun executeFileMutation(mutation: NextcloudFileMutation): NextcloudFileMutationResult {
        val spec = mutation.toWebDavMutationSpec()
        val headers = buildMap {
            put("Accept", "*/*")
            putAll(spec.conflictConditionHeaders())
            spec.destinationPath?.let { destinationPath ->
                put("Destination", fileUrl(destinationPath))
                put("Overwrite", if (spec.overwrite) "T" else "F")
            }
        }
        val followUp = followUps.mutation(accountId, listOfNotNull(spec.sourcePath, spec.destinationPath), refreshLocalState)
        val response = followUp.send(
            DesktopFileWriteRequest(spec.method, fileUrl(spec.sourcePath), headers, maximumResponseBytes = STATUS_RESPONSE_BYTES),
        )
        if (response.status !in 200..299) {
            val gone = if (response.status == 404) followUp.afterConfirmedMutation() else null
            throw fileOperationException(response.status, spec.sourceIsDirectory, gone)
        }
        return NextcloudFileMutationResult(spec.destinationPath, response.etag, followUp.afterConfirmedMutation())
    }

    private suspend fun DesktopFileMutationFollowUps.Mutation.send(request: DesktopFileWriteRequest): DesktopFileWriteResponse =
        reconcilingUnknownResult { onAcceptedOrUnknownResult -> transport.send(request, onAcceptedOrUnknownResult) }

    private fun fileUrl(path: String): String = buildNextcloudFileUrl(serverUrl, userId, path)

    /** Best effort: a read-cache miss only costs a later download of what the server confirmed. */
    private fun prefillReadCache(path: String, content: NextcloudFileContent) {
        runCatching { storeContent(path, content) }
    }

    private companion object {
        val CREATE_ONLY_HEADERS = mapOf("Accept" to "*/*", "If-None-Match" to "*")
        const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
        const val STATUS_RESPONSE_BYTES = 64L * 1024L
    }
}
