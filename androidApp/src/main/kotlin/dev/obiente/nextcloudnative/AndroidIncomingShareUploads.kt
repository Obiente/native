package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import dev.obiente.nextcloudnative.app.MAX_INCOMING_SHARE_FILES
import dev.obiente.nextcloudnative.app.canonicalIncomingShareDestinationPath
import dev.obiente.nextcloudnative.app.incomingShareUploadNameCandidates
import dev.obiente.nextcloudnative.app.safeIncomingShareFileName
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val ABANDONED_INCOMING_SHARE_STAGING_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

internal enum class AndroidIncomingShareState {
    Staged,
    Queued,
    Uploading,
    Completed,
    Failed,
    OutcomeUnknown,
    Canceled,
}

internal data class AndroidIncomingShareFile(
    val id: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val stagedName: String,
)

internal data class AndroidIncomingShareChunkSession(
    val fileIndex: Int,
    val targetName: String,
    val uploadId: String,
    val uploadedChunks: Int = 0,
    val commitInFlight: Boolean = false,
    val cleanupPending: Boolean = false,
) {
    init {
        require(fileIndex >= 0 && safeIncomingShareFileName(targetName, 0) == targetName)
        require(runCatching { UUID.fromString(uploadId) }.isSuccess)
        require(uploadedChunks >= 0)
        require(!commitInFlight || !cleanupPending)
    }
}

internal data class AndroidIncomingShareRequest(
    val id: String,
    val files: List<AndroidIncomingShareFile>,
    val state: AndroidIncomingShareState,
    val accountId: String? = null,
    val userId: String? = null,
    val destinationPath: String? = null,
    val completedFiles: Int = 0,
    val uploadedNames: List<String> = emptyList(),
    val chunkSession: AndroidIncomingShareChunkSession? = null,
    val message: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        require(files.isNotEmpty() && files.size <= MAX_INCOMING_SHARE_FILES)
        require(files.map(AndroidIncomingShareFile::id).distinct().size == files.size)
        require(completedFiles in 0..files.size)
        require(uploadedNames.size == completedFiles)
        require(chunkSession == null || chunkSession.fileIndex == completedFiles)
    }
}

internal sealed interface AndroidIncomingShareLoadResult {
    data object Missing : AndroidIncomingShareLoadResult
    data class Available(val request: AndroidIncomingShareRequest) : AndroidIncomingShareLoadResult
    data class Corrupt(val requestId: String) : AndroidIncomingShareLoadResult
}

internal class CorruptIncomingShareManifestException(val requestId: String) :
    Exception("This shared upload needs attention because its recovery record is damaged.")

internal class AndroidIncomingShareStore(private val context: Context) {
    private val root = File(context.filesDir, "incoming-share")

    suspend fun stage(intent: Intent): AndroidIncomingShareRequest = withContext(Dispatchers.IO) {
        val sources = incomingShareUris(intent)
        require(sources.isNotEmpty()) { "The share did not contain a readable file." }
        require(sources.size <= MAX_INCOMING_SHARE_FILES) {
            "Share at most $MAX_INCOMING_SHARE_FILES files at once."
        }
        val requestId = UUID.randomUUID().toString()
        val requestDirectory = directory(requestId)
        check(requestDirectory.mkdirs()) { "The private upload staging folder could not be created." }
        scheduleIncomingShareAbandonedStagingCleanup(context, requestId)
        val stagingMarker = createIncomingShareStagingMarker(requestDirectory, STAGING_MARKER_NAME)
        try {
            var totalBytes = 0L
            val files = sources.mapIndexed { index, uri ->
                val metadata = context.contentResolver.queryIncomingShareMetadata(uri)
                val displayName = safeIncomingShareFileName(metadata.first ?: uri.lastPathSegment.orEmpty(), index)
                val stagedName = "${index.toString().padStart(3, '0')}-${UUID.randomUUID()}"
                val destination = File(requestDirectory, stagedName)
                val declaredBytes = metadata.second
                require(declaredBytes == null || declaredBytes in 0L..MAX_SHARE_FILE_BYTES) {
                    "$displayName is too large to stage safely."
                }
                requireIncomingShareStagingSpace(requestDirectory, declaredBytes, displayName, MIN_STAGING_FREE_BYTES)
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            currentCoroutineContext().ensureActive()
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= MAX_SHARE_FILE_BYTES && totalBytes <= MAX_SHARE_TOTAL_BYTES) {
                                "The shared files are too large to stage safely."
                            }
                            requireIncomingShareStreamingSpace(requestDirectory, count, MIN_STAGING_FREE_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        fileBytes
                    }
                } ?: error("$displayName is no longer readable.")
                require(declaredBytes == null || declaredBytes == copied) {
                    "$displayName changed while it was being staged."
                }
                AndroidIncomingShareFile(
                    id = UUID.randomUUID().toString(),
                    displayName = displayName,
                    mimeType = context.contentResolver.getType(uri)?.take(160),
                    sizeBytes = copied,
                    stagedName = stagedName,
                )
            }
            AndroidIncomingShareRequest(
                id = requestId,
                files = files,
                state = AndroidIncomingShareState.Staged,
            ).also(::save).also { stagingMarker.delete() }
        } catch (failure: Throwable) {
            requestDirectory.deleteRecursively()
            throw failure
        }
    }

    fun loadResult(id: String): AndroidIncomingShareLoadResult = synchronized(LOCK) {
        val manifest = manifest(id).takeIf(File::isFile) ?: return@synchronized AndroidIncomingShareLoadResult.Missing
        runCatching { JSONObject(AtomicFile(manifest).readFully().decodeToString()).toIncomingShareRequest() }
            .getOrNull()
            ?.takeIf { it.id == id }
            ?.let(AndroidIncomingShareLoadResult::Available)
            ?: AndroidIncomingShareLoadResult.Corrupt(id)
    }

    fun load(id: String): AndroidIncomingShareRequest? =
        (loadResult(id) as? AndroidIncomingShareLoadResult.Available)?.request

    fun requireAvailable(id: String): AndroidIncomingShareRequest = when (val loaded = loadResult(id)) {
        is AndroidIncomingShareLoadResult.Available -> loaded.request
        is AndroidIncomingShareLoadResult.Corrupt -> throw CorruptIncomingShareManifestException(id)
        AndroidIncomingShareLoadResult.Missing -> error("This shared upload is no longer available.")
    }

    fun listRecoverable(accountId: String): List<AndroidIncomingShareRequest> = synchronized(LOCK) {
        removeExpiredAbandonedIncomingShareStaging(
            root, STAGING_MARKER_NAME, ABANDONED_INCOMING_SHARE_STAGING_RETENTION_MILLIS,
        )
        root.listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .sortedByDescending(File::lastModified)
            .take(MAX_INCOMING_SHARE_RECOVERY_SCAN)
            .mapNotNull { directory ->
                val id = directory.name.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                    ?: return@mapNotNull null
                (loadResult(id) as? AndroidIncomingShareLoadResult.Available)?.request
            }
            .filter { request -> request.requiresIncomingShareRecovery(accountId) }
            .take(MAX_INCOMING_SHARE_FILES)
            .toList()
    }

    fun save(request: AndroidIncomingShareRequest) = synchronized(LOCK) {
        val directory = directory(request.id)
        require(directory.isDirectory)
        val atomic = AtomicFile(manifest(request.id))
        val stream = atomic.startWrite()
        try {
            stream.write(request.toJson().toString().encodeToByteArray())
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun queue(
        id: String,
        accountId: String,
        userId: String,
        destinationPath: String,
    ): AndroidIncomingShareRequest = synchronized(LOCK) {
        val queued = prepareIncomingShareRequestForQueue(
            current = requireAvailable(id),
            accountId = accountId,
            userId = userId,
            destinationPath = destinationPath,
        )
        save(queued)
        queued
    }

    fun transition(
        id: String,
        expected: Set<AndroidIncomingShareState>,
        target: AndroidIncomingShareState,
        message: String? = null,
    ): AndroidIncomingShareRequest? = synchronized(LOCK) {
        val current = load(id) ?: return@synchronized null
        val updated = transitionIncomingShareRequest(current, expected, target, message)
            ?: return@synchronized null
        save(updated)
        updated
    }

    fun recordUploadedFile(
        id: String,
        expectedCompletedFiles: Int,
        uploadedName: String,
    ): AndroidIncomingShareRequest? = synchronized(LOCK) {
        val current = load(id) ?: return@synchronized null
        if (
            current.state != AndroidIncomingShareState.Uploading ||
            current.completedFiles != expectedCompletedFiles
        ) {
            return@synchronized null
        }
        val updated = current.copy(
            completedFiles = expectedCompletedFiles + 1,
            uploadedNames = current.uploadedNames + uploadedName,
            chunkSession = null,
        )
        save(updated)
        updated
    }

    fun beginChunkSession(
        id: String,
        fileIndex: Int,
        targetName: String,
        uploadId: String,
    ): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        require(current.state == AndroidIncomingShareState.Uploading && current.completedFiles == fileIndex)
        val updated = current.copy(
            chunkSession = AndroidIncomingShareChunkSession(fileIndex, targetName, uploadId),
        )
        save(updated)
        updated
    }

    fun recordUploadedChunk(id: String, expectedChunks: Int): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        val session = requireNotNull(current.chunkSession)
        require(current.state == AndroidIncomingShareState.Uploading && session.uploadedChunks == expectedChunks)
        val updated = current.copy(chunkSession = session.copy(uploadedChunks = expectedChunks + 1))
        save(updated)
        updated
    }

    fun markChunkCommitInFlight(id: String): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        val session = requireNotNull(current.chunkSession)
        require(current.state == AndroidIncomingShareState.Uploading && !session.commitInFlight)
        val updated = current.copy(chunkSession = session.copy(commitInFlight = true))
        save(updated)
        updated
    }

    fun clearChunkCommitInFlight(id: String): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        val session = requireNotNull(current.chunkSession)
        require(current.state == AndroidIncomingShareState.Uploading && session.commitInFlight)
        val updated = current.copy(chunkSession = session.copy(commitInFlight = false))
        save(updated)
        updated
    }

    fun markChunkCleanupPending(id: String): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        val session = requireNotNull(current.chunkSession)
        require(current.state == AndroidIncomingShareState.Uploading && session.commitInFlight)
        val updated = current.copy(
            chunkSession = session.copy(commitInFlight = false, cleanupPending = true),
        )
        save(updated)
        updated
    }

    fun clearChunkSessionForCleanup(id: String, uploadId: String): AndroidIncomingShareRequest? = synchronized(LOCK) {
        val current = load(id) ?: return@synchronized null
        val session = current.chunkSession ?: return@synchronized current
        if (session.uploadId != uploadId) return@synchronized current
        if (
            current.state in setOf(AndroidIncomingShareState.Queued, AndroidIncomingShareState.Uploading) &&
            !session.cleanupPending
        ) {
            return@synchronized current
        }
        val updated = current.copy(chunkSession = null)
        save(updated)
        updated
    }

    fun claimChunkSessionForCleanup(
        id: String,
        uploadId: String,
        includeRetryableFailure: Boolean,
    ): AndroidIncomingShareChunkSession? = synchronized(LOCK) {
        val current = load(id) ?: return@synchronized null
        val session = current.chunkSession?.takeIf { it.uploadId == uploadId }
            ?: return@synchronized null
        if (current.state !in TERMINAL_INCOMING_SHARE_STATES) return@synchronized null
        val eligible = session.cleanupPending ||
            current.state != AndroidIncomingShareState.Failed ||
            includeRetryableFailure
        if (!eligible) return@synchronized null
        val claimed = session.copy(commitInFlight = false, cleanupPending = true)
        if (claimed != session) save(current.copy(chunkSession = claimed))
        claimed
    }

    fun clearChunkSession(id: String): AndroidIncomingShareRequest = synchronized(LOCK) {
        val current = requireAvailable(id)
        require(current.state == AndroidIncomingShareState.Uploading)
        val updated = current.copy(chunkSession = null)
        save(updated)
        updated
    }

    fun stagedFile(requestId: String, file: AndroidIncomingShareFile): File {
        val candidate = File(directory(requestId), file.stagedName)
        require(candidate.parentFile == directory(requestId) && candidate.isFile) {
            "The staged share file is missing."
        }
        return candidate
    }

    fun remove(id: String): Boolean = synchronized(LOCK) {
        val target = directory(id)
        target.isDirectory && target.deleteRecursively()
    }

    fun removeExpiredAbandonedStaging(id: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(LOCK) {
            val target = directory(id)
            !target.exists() || removeExpiredAbandonedIncomingShareStagingDirectory(
                directory = target,
                markerName = STAGING_MARKER_NAME,
                retentionMillis = ABANDONED_INCOMING_SHARE_STAGING_RETENTION_MILLIS,
                nowMillis = nowMillis,
            )
        }

    fun removeStagedFiles(request: AndroidIncomingShareRequest) = synchronized(LOCK) {
        request.files.forEach { file ->
            val staged = File(directory(request.id), file.stagedName)
            if (staged.exists()) check(staged.delete()) { "A completed staged share file could not be removed." }
        }
    }

    private fun directory(id: String): File {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        return File(root, id)
    }

    private fun manifest(id: String) = File(directory(id), "request.json")

    private companion object {
        val LOCK = Any()
        const val MAX_SHARE_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_SHARE_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L
        const val MIN_STAGING_FREE_BYTES = 64L * 1024L * 1024L
        const val STAGING_MARKER_NAME = ".staging"
        const val MAX_INCOMING_SHARE_RECOVERY_SCAN = 1_000
    }
}

internal fun DocumentWebDavException.isIncomingShareNameCollision(): Boolean =
    status == 405 || status == 412

internal fun transitionIncomingShareRequest(
    current: AndroidIncomingShareRequest,
    expected: Set<AndroidIncomingShareState>,
    target: AndroidIncomingShareState,
    message: String? = null,
): AndroidIncomingShareRequest? = current
    .takeIf { it.state in expected }
    ?.copy(state = target, message = message)

internal fun prepareIncomingShareRequestForQueue(
    current: AndroidIncomingShareRequest,
    accountId: String,
    userId: String,
    destinationPath: String,
): AndroidIncomingShareRequest {
    require(current.state == AndroidIncomingShareState.Staged || current.state == AndroidIncomingShareState.Failed) {
        "This upload is no longer waiting to be queued."
    }
    require(current.chunkSession?.cleanupPending != true) {
        "Nextcloud is still cleaning up the previous upload attempt. Try again shortly."
    }
    require(accountId.isNotBlank() && userId.isNotBlank())
    val destination = canonicalIncomingShareDestinationPath(destinationPath)
    require((current.completedFiles == 0 && current.chunkSession == null) || current.destinationPath == destination) {
        "A partially completed upload must resume in its original Nextcloud folder."
    }
    return current.copy(
        state = AndroidIncomingShareState.Queued,
        accountId = accountId,
        userId = userId,
        destinationPath = destination,
        message = null,
    )
}

internal fun AndroidIncomingShareRequest.isFullyJournaledIncomingShareUpload(): Boolean =
    state == AndroidIncomingShareState.Uploading &&
        completedFiles == files.size &&
        chunkSession == null

@Suppress("DEPRECATION")
internal fun incomingShareUris(intent: Intent): List<Uri> {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val fromExtras = if (action == Intent.ACTION_SEND_MULTIPLE) {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else {
        listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
    }
    val fromClip = buildList {
        val clip = intent.clipData ?: return@buildList
        repeat(clip.itemCount.coerceAtMost(MAX_INCOMING_SHARE_FILES)) { index ->
            clip.getItemAt(index).uri?.let(::add)
        }
    }
    return (fromExtras + fromClip)
        .filter { uri -> isSupportedIncomingShareUriScheme(uri.scheme) }
        .distinctBy(Uri::toString)
        .take(MAX_INCOMING_SHARE_FILES + 1)
}

internal fun isSupportedIncomingShareUriScheme(scheme: String?): Boolean =
    scheme == ContentResolver.SCHEME_CONTENT

internal fun incomingShareMutationOutcomeUnknown(failure: Throwable, mutationInFlight: Boolean): Boolean {
    if (!mutationInFlight) return false
    val webDavFailure = failure as? DocumentWebDavException ?: return true
    return webDavFailure.error !in setOf(
        DocumentWebDavError.Authentication,
        DocumentWebDavError.Permission,
        DocumentWebDavError.NotFound,
        DocumentWebDavError.AlreadyExists,
        DocumentWebDavError.Conflict,
        DocumentWebDavError.Locked,
        DocumentWebDavError.InsufficientStorage,
        DocumentWebDavError.TooLarge,
        DocumentWebDavError.Throttled,
    )
}

private fun android.content.ContentResolver.queryIncomingShareMetadata(uri: Uri): Pair<String?, Long?> {
    var cursor: Cursor? = null
    return try {
        cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        if (cursor?.moveToFirst() != true) return null to null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it >= 0L }
        name to size
    } finally {
        cursor?.close()
    }
}

private fun AndroidIncomingShareRequest.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("state", state.name)
    .put("accountId", accountId)
    .put("userId", userId)
    .put("destinationPath", destinationPath)
    .put("completedFiles", completedFiles)
    .put("uploadedNames", JSONArray(uploadedNames))
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
    .put("files", JSONArray().also { array ->
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("id", file.id)
                    .put("displayName", file.displayName)
                    .put("mimeType", file.mimeType)
                    .put("sizeBytes", file.sizeBytes)
                    .put("stagedName", file.stagedName),
            )
        }
    })

private fun JSONObject.toIncomingShareRequest(): AndroidIncomingShareRequest = AndroidIncomingShareRequest(
    id = getString("id"),
    state = AndroidIncomingShareState.valueOf(getString("state")),
    accountId = optString("accountId").takeIf(String::isNotBlank),
    userId = optString("userId").takeIf(String::isNotBlank),
    destinationPath = optString("destinationPath").takeIf(String::isNotBlank) ?: if (has("destinationPath")) "" else null,
    completedFiles = optInt("completedFiles"),
    uploadedNames = getJSONArray("uploadedNames").let { array ->
        List(array.length()) { index -> array.getString(index) }
    },
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
    files = getJSONArray("files").let { array ->
        List(array.length()) { index ->
            array.getJSONObject(index).let { file ->
                AndroidIncomingShareFile(
                    id = file.getString("id"),
                    displayName = file.getString("displayName"),
                    mimeType = file.optString("mimeType").takeIf(String::isNotBlank),
                    sizeBytes = file.getLong("sizeBytes"),
                    stagedName = file.getString("stagedName"),
                )
            }
        }
    },
)
