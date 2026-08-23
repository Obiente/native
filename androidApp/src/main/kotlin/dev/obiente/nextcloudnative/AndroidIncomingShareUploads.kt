package dev.obiente.nextcloudnative

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
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

internal data class AndroidIncomingShareRequest(
    val id: String,
    val files: List<AndroidIncomingShareFile>,
    val state: AndroidIncomingShareState,
    val accountId: String? = null,
    val userId: String? = null,
    val destinationPath: String? = null,
    val completedFiles: Int = 0,
    val uploadedNames: List<String> = emptyList(),
    val message: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        require(files.isNotEmpty() && files.size <= MAX_INCOMING_SHARE_FILES)
        require(files.map(AndroidIncomingShareFile::id).distinct().size == files.size)
        require(completedFiles in 0..files.size)
        require(uploadedNames.size == completedFiles)
    }
}

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

    fun load(id: String): AndroidIncomingShareRequest? = synchronized(LOCK) {
        val manifest = manifest(id).takeIf(File::isFile) ?: return@synchronized null
        runCatching { JSONObject(AtomicFile(manifest).readFully().decodeToString()).toIncomingShareRequest() }
            .getOrNull()
            ?.takeIf { it.id == id }
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
        )
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
        var request = store.load(requestId) ?: return@withContext Result.success()
        if (request.state == AndroidIncomingShareState.Uploading) {
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
        var mutationInFlight = false
        try {
            for (index in request.completedFiles until request.files.size) {
                ensureNotCanceled(requestId, store)
                val source = request.files[index]
                val stagedFile = store.stagedFile(requestId, source)
                val targetName = incomingShareUploadNameCandidates(source.displayName, limit = 1_000)
                    .firstNotNullOfOrNull { candidate ->
                        if (candidate in occupiedNames) return@firstNotNullOfOrNull null
                        mutationInFlight = false
                        try {
                            remote.createFileIfAbsent(candidate, stagedFile) {
                                mutationInFlight = true
                            }
                            candidate
                        } catch (failure: DocumentWebDavException) {
                            if (failure.isIncomingShareNameCollision()) {
                                mutationInFlight = false
                                occupiedNames += candidate
                                null
                            } else {
                                throw failure
                            }
                        }
                    }
                    ?: error("No safe available name remains for ${source.displayName}.")
                mutationInFlight = false
                occupiedNames += targetName
                request = store.recordUploadedFile(requestId, index, targetName)
                    ?: throw CancellationException("Incoming share upload canceled")
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
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(incomingShareNotificationId(request.id), notification)
        }
    }

    internal companion object {
        const val KEY_REQUEST_ID = "request_id"
    }
}

internal class AndroidIncomingShareCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID)
            ?: return@withContext Result.failure()
        val store = AndroidIncomingShareStore(applicationContext)
        val request = store.load(requestId) ?: return@withContext Result.success()
        if (request.state in TERMINAL_INCOMING_SHARE_STATES) store.remove(requestId)
        Result.success()
    }
}

internal fun scheduleIncomingShareCleanup(context: Context, requestId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "incoming-share-cleanup-$requestId",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AndroidIncomingShareCleanupWorker>()
            .setInitialDelay(7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString(AndroidIncomingShareUploadWorker.KEY_REQUEST_ID, requestId).build())
            .build(),
    )
}

internal fun incomingShareRecoveryPendingIntent(context: Context, requestId: String): PendingIntent =
    PendingIntent.getActivity(
        context,
        incomingShareNotificationId(requestId),
        Intent(context, AndroidShareUploadActivity::class.java)
            .putExtra(AndroidShareUploadActivity.KEY_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun incomingShareNotificationId(requestId: String): Int =
    requestId.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }.coerceAtLeast(1)

internal fun DocumentWebDavException.isIncomingShareNameCollision(): Boolean =
    error == DocumentWebDavError.AlreadyExists || error == DocumentWebDavError.Conflict

internal val TERMINAL_INCOMING_SHARE_STATES = setOf(
    AndroidIncomingShareState.Completed,
    AndroidIncomingShareState.Failed,
    AndroidIncomingShareState.OutcomeUnknown,
    AndroidIncomingShareState.Canceled,
)

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
    require(current.completedFiles == 0 || current.destinationPath == destination) {
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
