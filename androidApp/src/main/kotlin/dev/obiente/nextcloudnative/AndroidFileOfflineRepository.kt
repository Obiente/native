package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.obiente.nextcloudnative.app.FileOfflineAvailability
import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineCenterActionResult
import dev.obiente.nextcloudnative.app.FileOfflineCenterItem
import dev.obiente.nextcloudnative.app.FileOfflineCenterSnapshot
import dev.obiente.nextcloudnative.app.FileOfflineJob
import dev.obiente.nextcloudnative.app.FileOfflineJobOperation
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflineRequest
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.markFileOfflineJobRunning
import dev.obiente.nextcloudnative.app.jvmStagingStorageKey
import dev.obiente.nextcloudnative.app.planFileOfflineRequest
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult
import dev.obiente.nextcloudnative.app.sharedJvmStagingSpaceReservations
import dev.obiente.nextcloudnative.app.STAGED_FILE_FREE_SPACE_RESERVE_BYTES
import dev.obiente.nextcloudnative.app.fileOfflineCenterSnapshot
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import okhttp3.OkHttpClient
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class AndroidOfflineExecutionOutcome { Complete, Retry }

internal fun DocumentWebDavException.isRetryableOfflineDownloadFailure(): Boolean =
    error == DocumentWebDavError.Locked ||
        error == DocumentWebDavError.Throttled ||
        error == DocumentWebDavError.Server

/**
 * Durable Android coordinator for offline pin intent, content generations, and WorkManager jobs.
 *
 * Every download is conditional on the ETag observed in the Files listing. Bytes are streamed into
 * an app-private temporary generation, fsynced, hashed, and atomically published before queue state
 * can claim availability. The global lock covers the store's read-modify-write transaction across
 * UI and worker repository instances in this process.
 */
internal class AndroidFileOfflineRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidFileOfflineQueueStore(appContext)
    private val contentRoot = File(appContext.filesDir, CONTENT_DIRECTORY)
    private val webDav = NextcloudDocumentWebDav(
        client = OkHttpClient.Builder()
            .useAndroidNextcloudCertificateTrust(appContext)
            .build(),
        cloudMutationsAllowed = appContext.cloudMutationGate(),
    )

    fun loadAvailability(
        session: NextcloudSession,
        userId: String,
        files: List<NextcloudFile>,
    ): Map<String, FileOfflineAvailability> {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val persisted = synchronized(STATE_LOCK) { store.load() }
        persisted.queue.jobs.filter { it.key.accountId == accountId && it.status.isRunnable() }
            .forEach { enqueue(it, accountId, userId) }
        return files.associate { file ->
            val availability = if (file.isDirectory) {
                persisted.folderAvailability(accountId, file.path)
            } else {
                persisted.queue.availability(FileOfflineKey(accountId, file.path))
            }
            file.path to availability
        }
    }

    fun availableContent(session: NextcloudSession, path: String): AndroidOfflineContent? {
        val key = FileOfflineKey(NextcloudDocumentIds.accountKey(session), path)
        return synchronized(STATE_LOCK) {
            val queue = store.load().queue
            if (queue.availability(key) != FileOfflineAvailability.Available) return@synchronized null
            val record = queue.record(key) ?: return@synchronized null
            val revision = record.localRevision ?: return@synchronized null
            val content = contentFile(key, revision).takeIf(File::isFile) ?: return@synchronized null
            AndroidOfflineContent(record.descriptor.toNextcloudFile(), content, revision)
        }
    }

    fun availableEntry(session: NextcloudSession, path: String): NextcloudFile? {
        availableContent(session, path)?.file?.let { return it }
        val accountId = NextcloudDocumentIds.accountKey(session)
        return synchronized(STATE_LOCK) {
            store.load().folders.offlineDirectories(accountId)[path]?.toNextcloudFile()
        }
    }

    fun isStoredDirectory(session: NextcloudSession, path: String): Boolean {
        val accountId = NextcloudDocumentIds.accountKey(session)
        return synchronized(STATE_LOCK) {
            path in store.load().folders.offlineDirectories(accountId)
        }
    }

    fun availableChildren(session: NextcloudSession, parentPath: String): List<NextcloudFile> {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val normalizedParent = parentPath.trim('/')
        return synchronized(STATE_LOCK) {
            val persisted = store.load()
            val directories = persisted.folders.offlineDirectories(accountId).values.asSequence()
                .filter { it.path.substringBeforeLast('/', "") == normalizedParent }
                .map(AndroidOfflineDirectory::toNextcloudFile)
            val files = persisted.queue.records.asSequence()
                .filter { record ->
                    record.descriptor.key.accountId == accountId &&
                        persisted.queue.availability(record.descriptor.key) == FileOfflineAvailability.Available &&
                        record.descriptor.key.relativePath.substringBeforeLast('/', "") == normalizedParent
                }
                .filter { record ->
                    val revision = record.localRevision ?: return@filter false
                    contentFile(record.descriptor.key, revision).isFile
                }
                .map { it.descriptor.toNextcloudFile() }
            (directories + files)
                .distinctBy(NextcloudFile::path)
                .sortedBy { it.name.lowercase() }
                .toList()
        }
    }

    fun setAvailable(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        available: Boolean,
    ): FileOfflineAvailability {
        if (file.isDirectory) {
            return setFolderAvailable(session, userId, file, available)
        }
        val fileSize = file.size
        require(fileSize == null || fileSize >= 0L) { "The file has an invalid size." }
        val accountId = NextcloudDocumentIds.accountKey(session)
        val key = FileOfflineKey(accountId, file.path)
        val update = synchronized(STATE_LOCK) {
            val current = store.load()
            val currentRecord = current.queue.record(key)
            val observedRevision = currentRecord?.localRevision?.takeIf { revision ->
                contentFile(key, revision).isFile
            }
            val nextFolders = if (available) {
                current.folders.copy(directPins = current.folders.directPins + key)
            } else {
                current.folders.copy(directPins = current.folders.directPins - key)
            }
            val shouldRemainPinned = !available && nextFolders.isCovered(key)
            val request = if (available || shouldRemainPinned) {
                val etag = file.etag?.takeIf(String::isNotBlank)
                    ?: currentRecord?.descriptor?.remoteEtag
                    ?: error("Refresh the folder before making this file available offline.")
                FileOfflineRequest.Pin(
                    FileOfflineDescriptor(key, file.name, etag, file.size, file.mimeType),
                    observedLocalRevision = observedRevision,
                )
            } else {
                FileOfflineRequest.Unpin(key, observedLocalRevision = observedRevision)
            }
            val nextQueue = planFileOfflineRequest(current.queue, request, System.currentTimeMillis())
            val next = AndroidFileOfflinePersistedState(nextQueue, nextFolders)
            store.save(next)
            StateUpdate(next, nextQueue.job(key)?.let(::listOf).orEmpty())
        }
        update.jobs.filter { it.status.isRunnable() }.forEach { enqueue(it, accountId, userId) }
        return update.state.queue.availability(key)
    }

    fun loadCenter(session: NextcloudSession): FileOfflineCenterSnapshot {
        val accountId = NextcloudDocumentIds.accountKey(session)
        return synchronized(STATE_LOCK) {
            val persisted = store.load()
            val base = fileOfflineCenterSnapshot(
                state = persisted.queue,
                accountId = accountId,
                allowRetry = true,
                allowRemove = true,
                storageCapacityBytes = contentRoot.totalSpace.takeIf { it > 0L },
                supportsRecursiveFolderAvailability = true,
            )
            val roots = persisted.folders.roots
                .asSequence()
                .filter { it.accountId == accountId }
                .map { root ->
                    FileOfflineCenterItem(
                        key = FileOfflineKey(accountId, root.rootPath),
                        displayName = root.rootDisplayName,
                        sizeBytes = root.filePaths.sumOfKnownSizes(persisted, accountId),
                        availability = persisted.folderAvailability(accountId, root.rootPath),
                        detail = "Pinned folder · ${root.filePaths.size} files",
                        canRetry = false,
                        canRemove = true,
                    )
                }
                .toList()
            val files = base.items.map { item ->
                val owningRoot = persisted.folders.roots.firstOrNull {
                    it.accountId == accountId && item.key.relativePath in it.filePaths
                }
                if (owningRoot == null) {
                    item
                } else {
                    item.copy(
                        detail = item.detail
                            ?: "Included by pinned folder ${owningRoot.rootDisplayName}.",
                        canRemove = false,
                    )
                }
            }
            base.copy(
                items = (roots + files)
                    .distinctBy(FileOfflineCenterItem::key)
                    .take(MAX_OFFLINE_CENTER_VISIBLE_ITEMS),
            )
        }
    }

    fun retryCenterItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult {
        val accountId = NextcloudDocumentIds.accountKey(session)
        if (key.accountId != accountId) {
            return FileOfflineCenterActionResult.Rejected("This offline item belongs to another account.")
        }
        val job = synchronized(STATE_LOCK) {
            val current = store.load()
            val existing = current.queue.job(key)
                ?: return FileOfflineCenterActionResult.Rejected("There is no failed download to retry.")
            if (
                existing.operation != FileOfflineJobOperation.Download ||
                existing.status !in setOf(
                    FileOfflineJobStatus.Failed,
                    FileOfflineJobStatus.WaitingForNetwork,
                )
            ) {
                return FileOfflineCenterActionResult.Rejected("This offline item is not waiting for a retry.")
            }
            val retried = prepareFileOfflineCenterManualRetry(existing)
            val nextQueue = current.queue.copy(
                jobs = current.queue.jobs.map { if (it.id == retried.id) retried else it },
            )
            store.save(current.copy(queue = nextQueue))
            retried
        }
        enqueue(job, accountId, userId)
        return FileOfflineCenterActionResult.Completed("${key.relativePath.substringAfterLast('/')} queued again.")
    }

    fun removeCenterItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult {
        val accountId = NextcloudDocumentIds.accountKey(session)
        if (key.accountId != accountId) {
            return FileOfflineCenterActionResult.Rejected("This offline item belongs to another account.")
        }
        val update = synchronized(STATE_LOCK) {
            val current = store.load()
            val root = current.folders.root(accountId, key.relativePath)
            val next = if (root != null) {
                planAndroidOfflineFolderUnpin(
                    current = current,
                    accountId = accountId,
                    rootPath = root.rootPath,
                    nowEpochMillis = System.currentTimeMillis(),
                    localGenerationExists = { storedKey, revision ->
                        contentFile(storedKey, revision).isFile
                    },
                )
            } else {
                if (current.folders.isCovered(key)) {
                    return FileOfflineCenterActionResult.Rejected(
                        "This file is managed by a pinned folder. Remove that folder instead.",
                    )
                }
                val record = current.queue.record(key)
                    ?: return FileOfflineCenterActionResult.Rejected("This offline item is no longer stored.")
                val observedRevision = record.localRevision?.takeIf { contentFile(key, it).isFile }
                val nextQueue = planFileOfflineRequest(
                    current.queue,
                    FileOfflineRequest.Unpin(key, observedRevision),
                    System.currentTimeMillis(),
                )
                AndroidFileOfflinePersistedState(
                    queue = nextQueue,
                    folders = current.folders.copy(directPins = current.folders.directPins - key),
                )
            }
            store.save(next)
            StateUpdate(
                next,
                next.queue.jobs.filter {
                    it.key.accountId == accountId &&
                        it.operation == FileOfflineJobOperation.RemoveLocal &&
                        it.status.isRunnable()
                },
                wasFolder = root != null,
            )
        }
        update.jobs.forEach { enqueue(it, accountId, userId) }
        notifyOfflineChanged(session, key.relativePath)
        return FileOfflineCenterActionResult.Completed(
            if (update.wasFolder) {
                "Offline folder removal queued."
            } else {
                "Offline copy removal queued."
            },
        )
    }

    private fun setFolderAvailable(
        session: NextcloudSession,
        userId: String,
        folder: NextcloudFile,
        available: Boolean,
    ): FileOfflineAvailability {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val inventory = if (available) {
            planAndroidOfflineFolder(folder) { path ->
                val result = webDav.listDirectory(
                    session = session,
                    userId = userId,
                    path = path,
                    maximumEntries = AndroidOfflineFolderLimits().maximumEntries,
                )
                check(!result.limited) {
                    "A folder contains more items than the recursive offline limit."
                }
                result.files
            }
        } else {
            null
        }
        val update = synchronized(STATE_LOCK) {
            val current = store.load()
            val next = if (inventory != null) {
                planAndroidOfflineFolderPin(
                    current = current,
                    accountId = accountId,
                    inventory = inventory,
                    nowEpochMillis = System.currentTimeMillis(),
                    localGenerationExists = { key, revision -> contentFile(key, revision).isFile },
                )
            } else {
                planAndroidOfflineFolderUnpin(
                    current = current,
                    accountId = accountId,
                    rootPath = folder.path,
                    nowEpochMillis = System.currentTimeMillis(),
                    localGenerationExists = { key, revision -> contentFile(key, revision).isFile },
                )
            }
            store.save(next)
            StateUpdate(
                state = next,
                jobs = next.queue.jobs.filter {
                    it.key.accountId == accountId && it.status.isRunnable()
                },
            )
        }
        update.jobs.forEach { enqueue(it, accountId, userId) }
        notifyOfflineChanged(session, folder.path)
        return update.state.folderAvailability(accountId, folder.path)
    }

    suspend fun execute(
        expectedAccountId: String,
        userId: String,
        jobId: Long,
        cancellation: DocumentRequestCancellation,
    ): AndroidOfflineExecutionOutcome = ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(expectedAccountId) {
        executeWhileAccountRetained(expectedAccountId, userId, jobId, cancellation)
    }

    private fun executeWhileAccountRetained(
        expectedAccountId: String,
        userId: String,
        jobId: Long,
        cancellation: DocumentRequestCancellation,
    ): AndroidOfflineExecutionOutcome {
        val services = AndroidNextcloudServices(appContext)
        val session = resolveStoredAndroidAccountSession(
            expectedAccountId, services::listAccounts, services::loadSession,
        )
        if (session == null) {
            finish(
                jobId,
                FileOfflineJobResult.PermanentFailure("Sign in to this account to finish the offline download."),
            )
            return AndroidOfflineExecutionOutcome.Complete
        }
        val started = synchronized(STATE_LOCK) {
            val current = store.load()
            val job = current.queue.jobs.firstOrNull { it.id == jobId && it.key.accountId == expectedAccountId }
                ?: return AndroidOfflineExecutionOutcome.Complete
            if (!job.status.isRunnable()) return AndroidOfflineExecutionOutcome.Complete
            val nowEpochMillis = System.currentTimeMillis()
            if ((job.retryNotBeforeEpochMillis ?: 0L) > nowEpochMillis) {
                return AndroidOfflineExecutionOutcome.Retry
            }
            val nextQueue = markFileOfflineJobRunning(current.queue, jobId, nowEpochMillis)
            store.save(current.copy(queue = nextQueue))
            StartedJob(nextQueue.jobs.single { it.id == jobId }, requireNotNull(nextQueue.record(job.key)))
        }
        return when (started.job.operation) {
            FileOfflineJobOperation.Download -> executeDownload(session, userId, started, cancellation)
            FileOfflineJobOperation.RemoveLocal -> executeRemoval(session, started)
        }
    }

    private fun executeDownload(
        session: NextcloudSession,
        userId: String,
        started: StartedJob,
        cancellation: DocumentRequestCancellation,
    ): AndroidOfflineExecutionOutcome {
        val job = started.job
        val expectedEtag = requireNotNull(job.expectedRemoteEtag)
        val accountDirectory = File(contentRoot, job.key.accountId).apply { mkdirs() }
        if (!accountDirectory.isDirectory) {
            finish(job.id, FileOfflineJobResult.PermanentFailure("Could not prepare offline storage."))
            return AndroidOfflineExecutionOutcome.Complete
        }
        val reservation = try {
            sharedJvmStagingSpaceReservations.reserve(
                storageKey = jvmStagingStorageKey(accountDirectory),
                usableBytes = accountDirectory.usableSpace.coerceAtLeast(0L),
                declaredByteCount = started.record.descriptor.size,
                reserveBytes = STAGED_FILE_FREE_SPACE_RESERVE_BYTES,
            )
        } catch (_: IllegalStateException) {
            finish(
                job.id,
                FileOfflineJobResult.PermanentFailure(
                    "There is not enough free storage for this offline file.",
                ),
            )
            return AndroidOfflineExecutionOutcome.Complete
        }
        return reservation.use {
            val temporary = File.createTempFile("offline-", ".part", accountDirectory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val response = FileOutputStream(temporary).use { fileOutput ->
                    val destination = DigestOutputStream(BufferedOutputStream(fileOutput), digest)
                    val read = webDav.readFile(
                        session = session,
                        userId = userId,
                        path = job.key.relativePath,
                        destination = destination,
                        maximumBytes = reservation.maximumBytes,
                        expectedEtag = expectedEtag,
                        cancellation = cancellation,
                    )
                    destination.flush()
                    fileOutput.fd.sync()
                    read
                }
                if (response.etag != null && response.etag != expectedEtag) {
                    temporary.delete()
                    finish(
                        job.id,
                        FileOfflineJobResult.NeedsAttention(
                            FileSyncDecisionReason.SimultaneousEdit,
                            "The server file changed while its offline copy was downloading.",
                        ),
                    )
                    return AndroidOfflineExecutionOutcome.Complete
                }
                val revision = "sha256:${digest.digest().toHex()}"
                val destination = contentFile(job.key, revision)
                publishGeneration(temporary, destination)
                val committed = commitDownloadedGeneration(started, revision, expectedEtag)
                if (committed) notifyOfflineChanged(session, job.key.relativePath)
                AndroidOfflineExecutionOutcome.Complete
            } catch (failure: Throwable) {
                temporary.delete()
                when (failure) {
                    is kotlinx.coroutines.CancellationException -> throw failure
                    is IOException -> retry(job.id, "Network interrupted while downloading this file.")
                    is DocumentWebDavException -> when (failure.error) {
                        DocumentWebDavError.Conflict -> {
                            finish(
                                job.id,
                                FileOfflineJobResult.NeedsAttention(
                                    FileSyncDecisionReason.SimultaneousEdit,
                                    "The server file changed before the offline download could start.",
                                ),
                            )
                            AndroidOfflineExecutionOutcome.Complete
                        }
                        DocumentWebDavError.Throttled -> retry(
                            job.id,
                            failure.message ?: "Nextcloud asked this download to wait.",
                            failure.retryAfterSeconds?.let { seconds ->
                                System.currentTimeMillis() + seconds * 1_000L
                            },
                        )
                        else -> {
                            if (failure.isRetryableOfflineDownloadFailure()) {
                                retry(job.id, failure.message ?: "Nextcloud is temporarily unavailable.")
                            } else {
                                finish(
                                    job.id,
                                    FileOfflineJobResult.PermanentFailure(
                                        failure.message ?: "Could not download this file for offline use.",
                                    ),
                                )
                                AndroidOfflineExecutionOutcome.Complete
                            }
                        }
                    }
                    else -> {
                        finish(
                            job.id,
                            FileOfflineJobResult.PermanentFailure(
                                failure.message?.take(512) ?: "Could not store the offline file.",
                            ),
                        )
                        AndroidOfflineExecutionOutcome.Complete
                    }
                }
            }
        }
    }

    private fun executeRemoval(
        session: NextcloudSession,
        started: StartedJob,
    ): AndroidOfflineExecutionOutcome {
        val job = started.job
        val revision = requireNotNull(job.expectedLocalRevision)
        val file = contentFile(job.key, revision)
        val committed = synchronized(STATE_LOCK) {
            val current = store.load()
            commitAndroidFileOfflineRemoval(
                current = current,
                startedJob = job,
                nowEpochMillis = System.currentTimeMillis(),
                removeLocalGeneration = { !file.exists() || file.delete() },
            )?.also { store.save(it.state) }
        }
        if (committed?.completedRemoval == true) {
            notifyOfflineChanged(session, job.key.relativePath)
        }
        return committed?.outcome ?: AndroidOfflineExecutionOutcome.Complete
    }

    private fun notifyOfflineChanged(session: NextcloudSession, path: String) {
        notifyAndroidDocumentChanged(appContext, session, path)
    }

    private fun retry(
        jobId: Long,
        message: String,
        retryNotBeforeEpochMillis: Long? = null,
    ): AndroidOfflineExecutionOutcome {
        finish(
            jobId,
            FileOfflineJobResult.RetryableFailure(message.take(512), retryNotBeforeEpochMillis),
        )
        return AndroidOfflineExecutionOutcome.Retry
    }

    /** Returns false when the user's intent changed while the job was running. */
    private fun finish(jobId: Long, result: FileOfflineJobResult): Boolean = synchronized(STATE_LOCK) {
        val current = store.load()
        if (current.queue.jobs.none { it.id == jobId }) return false
        val nextQueue = recordFileOfflineJobResult(current.queue, jobId, result, System.currentTimeMillis())
        store.save(current.copy(queue = nextQueue))
        true
    }

    private fun commitDownloadedGeneration(
        started: StartedJob,
        revision: String,
        remoteEtag: String,
    ): Boolean = synchronized(STATE_LOCK) {
        val commit = commitAndroidFileOfflineDownload(
            current = store.load(),
            startedJob = started.job,
            startedRecord = started.record,
            downloadedLocalRevision = revision,
            remoteEtag = remoteEtag,
            nowEpochMillis = System.currentTimeMillis(),
        )
        if (commit.committed) store.save(commit.state)
        commit.removableLocalRevisions.forEach { removableRevision ->
            contentFile(started.job.key, removableRevision).delete()
        }
        commit.committed
    }

    private fun enqueue(job: FileOfflineJob, accountId: String, userId: String) {
        val constraints = Constraints.Builder().apply {
            if (job.operation == FileOfflineJobOperation.Download) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val data = Data.Builder()
            .putString(NextcloudOfflineWorker.KEY_ACCOUNT_ID, accountId)
            .putString(NextcloudOfflineWorker.KEY_USER_ID, userId)
            .putLong(NextcloudOfflineWorker.KEY_JOB_ID, job.id)
            .build()
        val request = OneTimeWorkRequestBuilder<NextcloudOfflineWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(accountId, job.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun contentFile(key: FileOfflineKey, revision: String): File {
        val pathHash = MessageDigest.getInstance("SHA-256")
            .digest(key.relativePath.encodeToByteArray()).toHex().take(32)
        val revisionHash = revision.substringAfter("sha256:").also {
            require(it.length == 64 && it.all { character -> character.isLowerCaseHexDigit() }) {
                "Invalid local revision."
            }
        }
        return File(File(contentRoot, key.accountId), "$pathHash-$revisionHash.blob")
    }

    private fun publishGeneration(source: File, destination: File) {
        if (destination.isFile) {
            source.delete()
            return
        }
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun FileOfflineJobStatus.isRunnable(): Boolean = this in setOf(
        FileOfflineJobStatus.Queued,
        FileOfflineJobStatus.Running,
        FileOfflineJobStatus.WaitingForNetwork,
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

    private fun FileOfflineDescriptor.toNextcloudFile(): NextcloudFile = NextcloudFile(
        path = key.relativePath,
        name = displayName,
        isDirectory = false,
        mimeType = mimeType,
        size = size,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = remoteEtag,
    )

    private data class StateUpdate(
        val state: AndroidFileOfflinePersistedState,
        val jobs: List<FileOfflineJob>,
        val wasFolder: Boolean = false,
    )
    private data class StartedJob(
        val job: FileOfflineJob,
        val record: dev.obiente.nextcloudnative.app.FileOfflinePinRecord,
    )

    internal companion object {
        const val CONTENT_DIRECTORY = "offline-content-v1"
        const val WORK_TAG = "nextcloud-native-offline-files"
        const val MAX_OFFLINE_CENTER_VISIBLE_ITEMS = 10_000
        val STATE_LOCK = Any()

        fun workName(accountId: String, jobId: Long) = "nextcloud-native-offline-$accountId-$jobId"
    }
}

internal fun prepareFileOfflineCenterManualRetry(existing: FileOfflineJob): FileOfflineJob {
    require(
        existing.operation == FileOfflineJobOperation.Download &&
            existing.status in setOf(FileOfflineJobStatus.Failed, FileOfflineJobStatus.WaitingForNetwork),
    )
    return existing.copy(
        status = FileOfflineJobStatus.Queued,
        failureMessage = null,
    )
}

private fun List<String>.sumOfKnownSizes(
    persisted: AndroidFileOfflinePersistedState,
    accountId: String,
): Long? {
    var total = 0L
    for (path in this) {
        val size = persisted.queue.records
            .firstOrNull {
                it.descriptor.key.accountId == accountId &&
                    it.descriptor.key.relativePath == path
            }
            ?.descriptor
            ?.size
            ?: return null
        total = if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
    }
    return total
}

private fun AndroidFileOfflinePersistedState.folderAvailability(
    accountId: String,
    path: String,
): FileOfflineAvailability {
    val root = folders.root(accountId, path) ?: return FileOfflineAvailability.OnlineOnly
    if (root.filePaths.isEmpty()) return FileOfflineAvailability.Available
    val states = root.filePaths.map { queue.availability(FileOfflineKey(accountId, it)) }
    return when {
        states.any { it == FileOfflineAvailability.NeedsAttention } -> FileOfflineAvailability.NeedsAttention
        states.any { it == FileOfflineAvailability.Failed } -> FileOfflineAvailability.Failed
        states.any {
            it in setOf(
                FileOfflineAvailability.Queued,
                FileOfflineAvailability.Downloading,
                FileOfflineAvailability.Removing,
                FileOfflineAvailability.WaitingForNetwork,
            )
        } -> FileOfflineAvailability.Queued
        states.all { it == FileOfflineAvailability.Available } -> FileOfflineAvailability.Available
        else -> FileOfflineAvailability.OnlineOnly
    }
}

internal fun AndroidOfflineFolderState.offlineDirectories(
    accountId: String,
): Map<String, AndroidOfflineDirectory> = buildMap {
    roots.asSequence()
        .filter { it.accountId == accountId }
        .forEach { root ->
            root.directories.forEach { directory -> put(directory.path, directory) }
            val segments = root.rootPath.split('/')
            for (length in 1 until segments.size) {
                val ancestorPath = segments.take(length).joinToString("/")
                putIfAbsent(
                    ancestorPath,
                    AndroidOfflineDirectory(
                        path = ancestorPath,
                        displayName = segments[length - 1],
                        remoteEtag = null,
                        lastModified = null,
                        fileId = null,
                    ),
                )
            }
        }
}
