package dev.obiente.nextcloudnative

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.MAX_INCOMING_SHARE_FILES
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.canonicalIncomingShareDestinationPath
import dev.obiente.nextcloudnative.app.incomingShareUploadNameCandidates
import dev.obiente.nextcloudnative.app.safeIncomingShareFileName
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

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
) {
    init {
        require(fileIndex >= 0 && safeIncomingShareFileName(targetName, 0) == targetName)
        require(runCatching { UUID.fromString(uploadId) }.isSuccess)
        require(uploadedChunks >= 0)
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
            ).also(::save)
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
    }
}

internal class AndroidIncomingShareUploads(private val context: Context) {
    private val store = AndroidIncomingShareStore(context.applicationContext)

    fun enqueue(
        session: NextcloudSession,
        userId: String,
        requestId: String,
        destinationPath: String,
    ): AndroidIncomingShareRequest {
        val current = requireNotNull(store.load(requestId)) { "The staged share is no longer available." }
        val queued = prepareIncomingShareRequestForQueue(
            current = current,
            accountId = NextcloudDocumentIds.accountKey(session),
            userId = userId,
            destinationPath = destinationPath,
        )
        store.save(queued)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(requestId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AndroidIncomingShareUploadWorker>()
                .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        return queued
    }

    fun cancel(requestId: String) {
        val canceled = store.transition(
            id = requestId,
            expected = setOf(AndroidIncomingShareState.Queued, AndroidIncomingShareState.Uploading),
            target = AndroidIncomingShareState.Canceled,
            message = "Upload canceled. An in-flight file may already have reached Nextcloud.",
        )
        WorkManager.getInstance(context).cancelUniqueWork(workName(requestId))
        canceled?.let { scheduleIncomingShareCleanup(context, it.id) }
    }

    private fun workName(requestId: String) = "incoming-share-$requestId"
}

internal class AndroidIncomingShareUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        var request = when (val loaded = store.loadResult(requestId)) {
            is AndroidIncomingShareLoadResult.Available -> loaded.request
            is AndroidIncomingShareLoadResult.Corrupt -> {
                publishCorruptIncomingShareNotification(applicationContext, loaded.requestId)
                scheduleIncomingShareCleanup(applicationContext, loaded.requestId)
                return@withContext Result.failure()
            }
            AndroidIncomingShareLoadResult.Missing -> return@withContext Result.success()
        }
        if (request.state == AndroidIncomingShareState.Uploading) {
            val resumable = request.chunkSession?.takeIf { !it.commitInFlight }
            if (resumable != null) {
                request = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = AndroidIncomingShareState.Queued,
                    message = "Resuming the large file from its last saved chunk.",
                ) ?: return@withContext Result.success()
            } else {
            val recovered = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = AndroidIncomingShareState.OutcomeUnknown,
                message = "Android restarted during an upload. Check Files before trying again.",
            )
            recovered?.let {
                publishTerminalNotification(it)
                scheduleIncomingShareCleanup(applicationContext, it.id)
            }
            return@withContext Result.success()
            }
        }
        if (request.state != AndroidIncomingShareState.Queued) return@withContext Result.success()
        val session = AndroidNextcloudServices(applicationContext).loadSession()
        if (session == null || NextcloudDocumentIds.accountKey(session) != request.accountId) {
            val failed = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Queued),
                target = AndroidIncomingShareState.Failed,
                message = "The upload account is not active.",
            )
            failed?.let {
                publishTerminalNotification(it)
                scheduleIncomingShareCleanup(applicationContext, it.id)
            }
            return@withContext Result.failure()
        }
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        runCatching { setForeground(foregroundInfo(request)) }
            .onFailure { failure ->
                if (failure !is IllegalStateException || !isForegroundStartUnavailable(failure)) throw failure
            }
        request = store.transition(
            id = requestId,
            expected = setOf(AndroidIncomingShareState.Queued),
            target = AndroidIncomingShareState.Uploading,
        ) ?: return@withContext Result.success()
        val remote = AndroidFileSyncRemoteTree(
            session = session,
            userId = requireNotNull(request.userId),
            remoteRootPath = requireNotNull(request.destinationPath),
            webDav = NextcloudDocumentWebDav(
                client = OkHttpClient.Builder()
                    .useAndroidNextcloudCertificateTrust(applicationContext)
                    .build(),
                cloudMutationsAllowed = applicationContext.cloudMutationGate(),
            ),
        )
        val occupiedNames = remote.rootChildNames().names.toMutableSet().apply {
            addAll(request.uploadedNames)
        }
        val requestCancellation = CoroutineDocumentRequestCancellation(currentCoroutineContext().job)
        val transfer = AndroidIncomingShareFileTransfer(store, remote, requestCancellation)
        var mutationInFlight = false
        try {
            for (index in request.completedFiles until request.files.size) {
                ensureNotCanceled(requestId, store)
                request = transfer.upload(requestId, request, index, occupiedNames) { inFlight ->
                    mutationInFlight = inFlight
                }
                setForeground(foregroundInfo(request))
            }
            request = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = AndroidIncomingShareState.Completed,
            ) ?: throw CancellationException("Incoming share upload canceled")
            store.removeStagedFiles(request)
            publishTerminalNotification(request)
            scheduleIncomingShareCleanup(applicationContext, request.id)
            Result.success()
        } catch (cancelled: CancellationException) {
            if (store.load(requestId)?.state != AndroidIncomingShareState.Canceled) {
                val transitioned = store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = if (mutationInFlight) {
                        AndroidIncomingShareState.OutcomeUnknown
                    } else {
                        AndroidIncomingShareState.Queued
                    },
                    message = if (mutationInFlight) {
                        "Android stopped during an upload. Check Files before trying again."
                    } else {
                        "Upload paused. It will continue when Android allows background work."
                    },
                )
                transitioned?.takeIf { it.state == AndroidIncomingShareState.OutcomeUnknown }?.let {
                    publishTerminalNotification(it)
                    scheduleIncomingShareCleanup(applicationContext, it.id)
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            val resumableChunk = store.load(requestId)?.chunkSession?.takeIf { !it.commitInFlight }
            val retryable = !mutationInFlight &&
                failure.isRetryableIncomingShareTransferFailure() &&
                runAttemptCount + 1 < MAX_INCOMING_SHARE_TRANSFER_ATTEMPTS
            if (retryable || resumableChunk != null && runAttemptCount + 1 < MAX_INCOMING_SHARE_TRANSFER_ATTEMPTS) {
                store.transition(
                    id = requestId,
                    expected = setOf(AndroidIncomingShareState.Uploading),
                    target = AndroidIncomingShareState.Queued,
                    message = "Upload paused and will retry with backoff.",
                )
                return@withContext Result.retry()
            }
            // A transport failure after a conditional PUT starts cannot prove whether the server
            // committed it. Do not replay automatically and risk a duplicate.
            val outcomeUnknown = incomingShareMutationOutcomeUnknown(failure, mutationInFlight)
            val target = if (outcomeUnknown) {
                AndroidIncomingShareState.OutcomeUnknown
            } else {
                AndroidIncomingShareState.Failed
            }
            val transitioned = store.transition(
                id = requestId,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = target,
                message = failure.message?.take(240) ?: if (outcomeUnknown) {
                    "The upload result is unknown."
                } else {
                    "The upload could not continue."
                },
            )
            transitioned?.let {
                publishTerminalNotification(it)
                scheduleIncomingShareCleanup(applicationContext, it.id)
            }
            Result.failure()
        } finally {
            requestCancellation.close()
        }
    }

    private fun ensureNotCanceled(requestId: String, store: AndroidIncomingShareStore) {
        if (store.load(requestId)?.state == AndroidIncomingShareState.Canceled) {
            throw CancellationException("Incoming share upload canceled")
        }
    }

    private fun foregroundInfo(request: AndroidIncomingShareRequest): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentTitle("Uploading shared files")
            .setContentText("${request.completedFiles} of ${request.files.size} uploaded")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(request.files.size, request.completedFiles, false)
            .setContentIntent(incomingShareRecoveryPendingIntent(applicationContext, request.id))
            .build()
        val id = request.id.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun isForegroundStartUnavailable(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= 31 && isForegroundStartUnavailableApi31(error)

    @RequiresApi(31)
    private fun isForegroundStartUnavailableApi31(error: IllegalStateException): Boolean =
        error is ForegroundServiceStartNotAllowedException

    private fun publishTerminalNotification(request: AndroidIncomingShareRequest) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        val completed = request.state == AndroidIncomingShareState.Completed
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentTitle(if (completed) "Shared files uploaded" else "Shared upload needs attention")
            .setContentText(
                if (completed) {
                    "${request.completedFiles} files uploaded to Nextcloud"
                } else {
                    "${request.completedFiles} of ${request.files.size} uploaded. Tap to review."
                },
            )
            .setCategory(if (completed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(incomingShareRecoveryPendingIntent(applicationContext, request.id))
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(incomingShareNotificationId(request.id), notification)
        } catch (_: SecurityException) {
            // The permission can still be revoked between the explicit check and delivery.
        }
    }

    internal companion object {
        const val KEY_REQUEST_ID = "request_id"
    }
}

internal fun DocumentWebDavException.isIncomingShareNameCollision(): Boolean =
    error == DocumentWebDavError.AlreadyExists || error == DocumentWebDavError.Conflict

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
