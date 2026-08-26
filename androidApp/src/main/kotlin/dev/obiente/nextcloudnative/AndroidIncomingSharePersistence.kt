package dev.obiente.nextcloudnative

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal const val INCOMING_SHARE_RECOVERY_DIRECTORY_PAGE_SIZE = 32

internal data class AndroidIncomingShareRecoveryPage(
    val requests: List<AndroidIncomingShareRequest>,
    val corruptRequestIds: List<String>,
    val nextCursor: String?,
)

internal data class IncomingShareRecoveryDirectory(
    val id: String,
    val lastModifiedMillis: Long,
) {
    fun isAfter(cursor: IncomingShareRecoveryDirectory): Boolean =
        lastModifiedMillis < cursor.lastModifiedMillis ||
            (lastModifiedMillis == cursor.lastModifiedMillis && id < cursor.id)

    fun encodeCursor(): String = "$lastModifiedMillis:$id"
}

internal data class IncomingShareRecoveryDirectoryPage(
    val directories: List<IncomingShareRecoveryDirectory>,
    val nextCursor: String?,
)

internal fun AndroidIncomingShareStore.listRecoverablePage(
    accountId: String,
    cursor: String?,
): AndroidIncomingShareRecoveryPage {
    require(accountId.isNotBlank())
    val directories = synchronized(AndroidIncomingShareStore.LOCK) {
        removeExpiredAbandonedIncomingShareStaging(
            root,
            INCOMING_SHARE_STAGING_MARKER_NAME,
            ABANDONED_INCOMING_SHARE_STAGING_RETENTION_MILLIS,
        )
        root.listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                directory.name.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                    ?.let { id -> IncomingShareRecoveryDirectory(id, directory.lastModified()) }
            }
            .toList()
    }
    val page = selectIncomingShareRecoveryDirectoryPage(directories, cursor)
    val loaded = page.directories.map { directory -> directory to loadResult(directory.id) }
    val requests = loaded.mapNotNull { (_, result) ->
        (result as? AndroidIncomingShareLoadResult.Available)?.request
            ?.takeIf { request -> request.requiresIncomingShareRecovery(accountId) }
    }
    val corruptRequestIds = loaded.mapNotNull { (directory, result) ->
        directory.id.takeIf {
            result is AndroidIncomingShareLoadResult.Corrupt &&
                corruptRecoveryAccountId(directory.id) == accountId
        }
    }
    return AndroidIncomingShareRecoveryPage(requests, corruptRequestIds, page.nextCursor)
}

internal fun selectIncomingShareRecoveryDirectoryPage(
    directories: List<IncomingShareRecoveryDirectory>,
    cursor: String?,
    limit: Int = INCOMING_SHARE_RECOVERY_DIRECTORY_PAGE_SIZE,
): IncomingShareRecoveryDirectoryPage {
    require(limit > 0)
    val decodedCursor = cursor?.let(::decodeIncomingShareRecoveryCursor)
    val candidates = directories.asSequence()
        .filter { directory -> decodedCursor == null || directory.isAfter(decodedCursor) }
        .sortedWith(
            compareByDescending<IncomingShareRecoveryDirectory> { it.lastModifiedMillis }
                .thenByDescending(IncomingShareRecoveryDirectory::id),
        )
        .take(limit + 1)
        .toList()
    val selected = candidates.take(limit)
    return IncomingShareRecoveryDirectoryPage(
        directories = selected,
        nextCursor = selected.lastOrNull()?.takeIf { candidates.size > selected.size }?.encodeCursor(),
    )
}

internal fun decodeIncomingShareRecoveryCursor(value: String): IncomingShareRecoveryDirectory {
    val separator = value.indexOf(':')
    require(separator > 0) { "The shared-upload recovery page is no longer valid." }
    val lastModifiedMillis = value.substring(0, separator).toLong()
    val id = value.substring(separator + 1)
    require(lastModifiedMillis >= 0L && runCatching { UUID.fromString(id) }.isSuccess) {
        "The shared-upload recovery page is no longer valid."
    }
    return IncomingShareRecoveryDirectory(id, lastModifiedMillis)
}

internal fun AndroidIncomingShareRequest.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("state", state.name)
    .put("accountId", accountId)
    .put("userId", userId)
    .put("destinationPath", destinationPath)
    .put("completedFiles", completedFiles)
    .put("uploadedNames", JSONArray(uploadedNames))
    .put("automaticTransferAttempts", automaticTransferAttempts)
    .put("retryNotBeforeEpochMillis", retryNotBeforeEpochMillis)
    .put("visibleMutationInFlight", visibleMutationInFlight)
    .put("visibleMutationTargetName", visibleMutationTargetName)
    .put("chunkSession", chunkSession?.let { session ->
        JSONObject()
            .put("fileIndex", session.fileIndex)
            .put("targetName", session.targetName)
            .put("uploadId", session.uploadId)
            .put("uploadedChunks", session.uploadedChunks)
            .put("commitInFlight", session.commitInFlight)
            .put("cleanupPending", session.cleanupPending)
    })
    .put("message", message)
    .put("discardRequested", discardRequested)
    .put("files", JSONArray().also { array ->
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("id", file.id)
                    .put("displayName", file.displayName)
                    .put("mimeType", file.mimeType)
                    .put("sizeBytes", file.sizeBytes)
                    .put("stagedName", file.stagedName)
                    .put("contentHash", file.contentHash),
            )
        }
    })

internal fun JSONObject.toIncomingShareRequest(): AndroidIncomingShareRequest = AndroidIncomingShareRequest(
    id = getString("id"),
    state = AndroidIncomingShareState.valueOf(getString("state")),
    accountId = optString("accountId").takeIf(String::isNotBlank),
    userId = optString("userId").takeIf(String::isNotBlank),
    destinationPath = optString("destinationPath").takeIf(String::isNotBlank)
        ?: if (has("destinationPath")) "" else null,
    completedFiles = optInt("completedFiles"),
    uploadedNames = getJSONArray("uploadedNames").let { array ->
        List(array.length()) { index -> array.getString(index) }
    },
    automaticTransferAttempts = optInt("automaticTransferAttempts"),
    retryNotBeforeEpochMillis = optLong("retryNotBeforeEpochMillis")
        .takeIf { has("retryNotBeforeEpochMillis") && !isNull("retryNotBeforeEpochMillis") && it >= 0L },
    visibleMutationInFlight = optBoolean("visibleMutationInFlight"),
    visibleMutationTargetName = optString("visibleMutationTargetName").takeIf(String::isNotBlank),
    chunkSession = optJSONObject("chunkSession")?.let { session ->
        AndroidIncomingShareChunkSession(
            fileIndex = session.getInt("fileIndex"),
            targetName = session.getString("targetName"),
            uploadId = session.getString("uploadId"),
            uploadedChunks = session.optInt("uploadedChunks"),
            commitInFlight = session.optBoolean("commitInFlight"),
            cleanupPending = session.optBoolean("cleanupPending"),
        )
    },
    message = optString("message").takeIf(String::isNotBlank),
    discardRequested = optBoolean("discardRequested"),
    files = getJSONArray("files").let { array ->
        List(array.length()) { index ->
            array.getJSONObject(index).let { file ->
                AndroidIncomingShareFile(
                    id = file.getString("id"),
                    displayName = file.getString("displayName"),
                    mimeType = file.optString("mimeType").takeIf(String::isNotBlank),
                    sizeBytes = file.getLong("sizeBytes"),
                    stagedName = file.getString("stagedName"),
                    contentHash = file.optString("contentHash").takeIf(String::isNotBlank),
                )
            }
        }
    },
)

internal fun saveIncomingShareAccountBinding(directory: File, accountId: String) {
    require(directory.isDirectory && accountId.isNotBlank() && accountId.length <= 256)
    val binding = File(directory, INCOMING_SHARE_ACCOUNT_BINDING_NAME)
    if (loadIncomingShareAccountBinding(directory) == accountId) return
    FileOutputStream(binding).use { output ->
        output.write(accountId.encodeToByteArray())
        output.fd.sync()
    }
}

internal fun loadIncomingShareAccountBinding(directory: File): String? {
    val binding = File(directory, INCOMING_SHARE_ACCOUNT_BINDING_NAME)
    if (!binding.isFile || binding.length() !in 1L..256L) return null
    return runCatching { binding.readText() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it.length <= 256 }
}

internal const val INCOMING_SHARE_ACCOUNT_BINDING_NAME = ".account"
