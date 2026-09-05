package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.await
import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.DurableUploadStatus
import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS
import dev.obiente.nextcloudnative.app.MultipartTextField
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.localUploadFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class AndroidDurableMultipartUploads(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidDurableMultipartUploadStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    suspend fun enqueue(
        session: NextcloudSession,
        scope: DurableUploadScope,
        request: NextcloudMultipartUploadRequest,
    ): DurableUploadEnqueueResult {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val picker = AndroidLocalUploadPicker(appContext)
        return try {
            val safeRequest = request.requireSafe()
            picker.requirePersisted(safeRequest.file)
            val job = AndroidDurableMultipartUploadJob(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                scope = scope,
                resource = resolveDurableUploadResource(scope, safeRequest),
                request = safeRequest,
                state = DurableUploadState.Queued,
                message = null,
            )
            persistAndScheduleDurableUpload(
                job = job,
                persist = store::add,
                schedule = { queued -> schedule(queued).await() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            releaseIfUnowned(request.file)
            DurableUploadEnqueueResult.Rejected(
                error.message?.take(MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS)
                    ?: "The background upload could not be scheduled.",
            )
        }
    }

    fun releaseIfUnowned(file: LocalUploadFile): Boolean = releaseUnownedDurableUploadSelection(
        selectionId = file.selectionId,
        hasActiveSelection = store::hasActiveSelection,
        releaseSelection = { AndroidLocalUploadPicker(appContext).release(file) },
    )

    suspend fun <Result> runEnqueueWithCancellationCleanup(
        file: LocalUploadFile,
        enqueue: suspend () -> Result,
    ): Result = runDurableUploadEnqueueWithCancellationCleanup(
        enqueue = { withContext(Dispatchers.IO) { enqueue() } },
        releaseUnownedSelection = { releaseIfUnowned(file) },
    )

    fun statuses(session: NextcloudSession, scope: DurableUploadScope): List<DurableUploadStatus> {
        val jobs = store.list(NextcloudDocumentIds.accountKey(session), scope)
        requestDurableUploadSchedulingRecoveryForQueuedStatuses(jobs)
        return jobs.asSequence()
            .sortedByDescending(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
            .take(MAX_VISIBLE_UPLOADS_PER_RESOURCE)
            .map(AndroidDurableMultipartUploadJob::status)
            .toList()
    }

    suspend fun resumeQueuedForAccount(accountId: String) {
        queuedDurableUploadsForAccount(store.list(), accountId).forEach { job ->
            try {
                replaceDeferredDurableUploadWork(
                    expected = job,
                    load = store::find,
                    replace = { queued ->
                        schedule(queued, DURABLE_UPLOAD_ACCOUNT_RECOVERY_WORK_POLICY).await()
                    },
                )
            } catch (cancelled: CancellationException) {
                runCatching { requestQueuedDurableUploadSchedulingRecovery() }
                throw cancelled
            } catch (_: Exception) {
                requestQueuedDurableUploadSchedulingRecovery()
            }
        }
    }

    suspend fun reconcileQueuedUploads(): Boolean =
        reconcileQueuedDurableUploads(
            jobs = store.list(),
            schedulerOwns = { job ->
                workManager.getWorkInfosForUniqueWorkFlow(durableUploadWorkName(job.id))
                    .first()
                    .any { work -> !work.state.isFinished }
            },
            schedule = { job -> schedule(job).await() },
        )

    fun dismiss(session: NextcloudSession, scope: DurableUploadScope, uploadId: String): Boolean {
        val job = store.find(uploadId) ?: return false
        if (
            job.accountId != NextcloudDocumentIds.accountKey(session) ||
            job.scope != scope ||
            !job.state.isTerminal()
        ) {
            return false
        }
        if (!AndroidLocalUploadPicker(appContext).release(job.request.file)) return false
        store.remove(uploadId)
        return true
    }

    private fun schedule(
        job: AndroidDurableMultipartUploadJob,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ): Operation =
        workManager.enqueueUniqueWork(
            durableUploadWorkName(job.id),
            policy,
            OneTimeWorkRequestBuilder<DeckAttachmentUploadWorker>()
                .setInputData(Data.Builder().putString(DeckAttachmentUploadWorker.KEY_JOB_ID, job.id).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )

    private companion object {
        const val MAX_VISIBLE_UPLOADS_PER_RESOURCE = 12
    }
}

internal fun releaseUnownedDurableUploadSelection(
    selectionId: String,
    hasActiveSelection: (String) -> Boolean,
    releaseSelection: () -> Boolean,
): Boolean = synchronized(AndroidDurableMultipartUploadStore.LOCK) {
    val selectionIsDefinitelyInactive = runCatching {
        !hasActiveSelection(selectionId)
    }.getOrNull() == true
    if (!selectionIsDefinitelyInactive) return@synchronized false
    runCatching(releaseSelection).getOrDefault(false)
}

internal suspend fun <Result> runDurableUploadEnqueueWithCancellationCleanup(
    enqueue: suspend () -> Result,
    releaseUnownedSelection: () -> Unit,
): Result = try {
    enqueue()
} catch (cancelled: CancellationException) {
    runCatching(releaseUnownedSelection)
    throw cancelled
}

internal val DURABLE_UPLOAD_ACCOUNT_RECOVERY_WORK_POLICY = ExistingWorkPolicy.REPLACE

internal fun durableUploadWorkName(jobId: String) = "deck-attachment-$jobId"

internal fun requestDurableUploadSchedulingRecoveryForQueuedStatuses(
    jobs: List<AndroidDurableMultipartUploadJob>,
    requestRecovery: () -> Unit = ::requestQueuedDurableUploadSchedulingRecovery,
) {
    if (jobs.any { job -> job.state == DurableUploadState.Queued }) requestRecovery()
}

internal data class AndroidDurableMultipartUploadJob(
    val id: String,
    val accountId: String,
    val scope: DurableUploadScope,
    val resource: AndroidDurableUploadResource,
    val request: NextcloudMultipartUploadRequest,
    val state: DurableUploadState,
    val message: String?,
    val capabilityCleanupPending: Boolean = false,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(id.length in 16..96 && id.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload id is invalid."
        }
        require(accountId.length == 32 && accountId.all { it in '0'..'9' || it in 'a'..'f' }) {
            "The durable upload account id is invalid."
        }
        require(resource.feature == scope.feature && resource.itemId == scope.resourceId) {
            "The durable upload resource does not match its scope."
        }
        require(message == null || message.length <= MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS) {
            "The durable upload message is too long."
        }
        require(!capabilityCleanupPending || state.isTerminal()) {
            "Only a terminal durable upload can have pending capability cleanup."
        }
    }

    fun status(): DurableUploadStatus = DurableUploadStatus(
        id = id,
        scope = scope,
        displayName = request.file.displayName,
        state = state,
        message = message,
    )
}

internal data class AndroidDurableUploadResource(
    val feature: String,
    val boardId: String?,
    val stackId: String?,
    val itemId: String,
) {
    init {
        require(feature.length in 1..32 && feature.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload resource feature is invalid."
        }
        require(boardId == null || boardId.isPositiveResourceId()) {
            "The durable upload board id is invalid."
        }
        require(stackId == null || stackId.isPositiveResourceId()) {
            "The durable upload stack id is invalid."
        }
        require(itemId.length in 1..96 && itemId.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload item id is invalid."
        }
    }
}

internal class AndroidDurableMultipartUploadStore(
    private val storage: AndroidDurableMultipartUploadEncryptedStorage,
    private val cipher: AndroidDurableMultipartUploadCipher,
) {
    constructor(
        context: Context,
        preferenceName: String = PREFERENCES,
    ) : this(
        storage = SharedPreferencesDurableMultipartUploadStorage(context, preferenceName),
        cipher = SessionDurableMultipartUploadCipher(),
    )

    fun add(job: AndroidDurableMultipartUploadJob) = synchronized(LOCK) {
        val current = readAll().toMutableList()
        requireCanAddDurableUpload(current, job)
        current += job
        writeAll(pruneDurableUploadJobs(current))
    }

    fun find(id: String): AndroidDurableMultipartUploadJob? = synchronized(LOCK) {
        readAll().firstOrNull { it.id == id }
    }

    fun list(): List<AndroidDurableMultipartUploadJob> = synchronized(LOCK) { readAll() }

    fun list(accountId: String, scope: DurableUploadScope): List<AndroidDurableMultipartUploadJob> =
        synchronized(LOCK) {
            readAll().filter { it.accountId == accountId && it.scope == scope }
        }

    fun hasActiveSelection(selectionId: String): Boolean = synchronized(LOCK) {
        readAll().any {
            it.request.file.selectionId == selectionId &&
                it.mustRetain()
        }
    }

    fun remove(id: String) = synchronized(LOCK) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun removeForAccount(accountId: String): List<AndroidDurableMultipartUploadJob> = synchronized(LOCK) {
        val current = readAll()
        val removed = current.filter { job -> job.accountId == accountId }
        if (removed.isNotEmpty()) writeAll(current.filterNot { job -> job.accountId == accountId })
        removed
    }

    fun completeCapabilityCleanup(id: String) = synchronized(LOCK) {
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { job -> job.id == id }
        if (index < 0 || !current[index].capabilityCleanupPending) return@synchronized
        current[index] = current[index].copy(capabilityCleanupPending = false)
        writeAll(pruneDurableUploadJobs(current))
    }

    fun transition(
        id: String,
        expected: DurableUploadState,
        target: DurableUploadState,
        message: String?,
    ): AndroidDurableMultipartUploadJob? = synchronized(LOCK) {
        require(isAllowedDurableUploadTransition(expected, target)) {
            "The durable upload state transition is invalid."
        }
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { it.id == id && it.state == expected }
        if (index < 0) return@synchronized null
        val updated = current[index].copy(
            state = target,
            message = message?.take(MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS),
            capabilityCleanupPending = target.isTerminal(),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        current[index] = updated
        writeAll(pruneDurableUploadJobs(current))
        updated
    }

    private fun readAll(): List<AndroidDurableMultipartUploadJob> {
        val encrypted = try {
            storage.read()
        } catch (failure: Exception) {
            throw AndroidDurableMultipartUploadRecoveryException(failure)
        } ?: return emptyList()
        return try {
            val array = JSONArray(cipher.decrypt(encrypted))
            check(array.length() <= MAX_STORED_UPLOADS) {
                "The durable upload queue contains too many rows."
            }
            val jobs = buildList {
                repeat(array.length()) { index ->
                    add(array.getJSONObject(index).toJob())
                }
            }
            check(jobs.distinctBy(AndroidDurableMultipartUploadJob::id).size == jobs.size) {
                "The durable upload queue contains duplicate rows."
            }
            jobs
        } catch (failure: Exception) {
            throw AndroidDurableMultipartUploadRecoveryException(failure)
        }
    }

    private fun writeAll(jobs: List<AndroidDurableMultipartUploadJob>) {
        val array = JSONArray()
        jobs.forEach { array.put(it.toJson()) }
        val encrypted = try {
            cipher.encrypt(array.toString())
        } catch (failure: Exception) {
            throw IllegalStateException("The durable upload queue could not be saved.", failure)
        }
        val saved = try {
            storage.write(encrypted)
        } catch (failure: Exception) {
            throw IllegalStateException("The durable upload queue could not be saved.", failure)
        }
        check(saved) { "The durable upload queue could not be saved." }
    }

    internal companion object {
        val LOCK = Any()
        const val PREFERENCES = "nextcloud_native_durable_uploads"
        const val KEY_JOBS = "jobs"
        const val MAX_ACTIVE_UPLOADS = 16
        const val MAX_ACTIVE_UPLOADS_PER_ACCOUNT = 12
        const val MAX_ACTIVE_UPLOADS_PER_RESOURCE = 4
        const val MAX_STORED_UPLOADS = 64
    }
}

internal interface AndroidDurableMultipartUploadEncryptedStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

internal interface AndroidDurableMultipartUploadCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

internal class AndroidDurableMultipartUploadRecoveryException(
    cause: Exception,
) : IllegalStateException(
    "The saved background upload queue is unavailable. Its recovery data was left unchanged.",
    cause,
)

private class SharedPreferencesDurableMultipartUploadStorage(
    context: Context,
    preferenceName: String,
) : AndroidDurableMultipartUploadEncryptedStorage {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(AndroidDurableMultipartUploadStore.KEY_JOBS, null)

    override fun write(value: String): Boolean = preferences.edit()
        .putString(AndroidDurableMultipartUploadStore.KEY_JOBS, value)
        .commit()
}

private class SessionDurableMultipartUploadCipher : AndroidDurableMultipartUploadCipher {
    private val cipher = SessionCipher()

    override fun encrypt(value: String): String = cipher.encrypt(value)

    override fun decrypt(value: String): String = cipher.decrypt(value)
}

internal fun requireCanAddDurableUpload(
    current: List<AndroidDurableMultipartUploadJob>,
    job: AndroidDurableMultipartUploadJob,
) {
    val active = current.filterNot { it.state.isTerminal() }
    require(current.none { it.id == job.id }) {
        "The attachment upload id is already in use."
    }
    require(
        current.count(AndroidDurableMultipartUploadJob::mustRetain) <
            AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS,
    ) {
        "Background upload cleanup must finish before another upload can be queued."
    }
    require(active.size < AndroidDurableMultipartUploadStore.MAX_ACTIVE_UPLOADS) {
        "Too many attachment uploads are already pending."
    }
    require(
        active.count { it.accountId == job.accountId } <
            AndroidDurableMultipartUploadStore.MAX_ACTIVE_UPLOADS_PER_ACCOUNT,
    ) {
        "Too many attachment uploads are already pending for this account."
    }
    require(
        active.count { it.accountId == job.accountId && it.resource == job.resource } <
            AndroidDurableMultipartUploadStore.MAX_ACTIVE_UPLOADS_PER_RESOURCE,
    ) {
        "Too many attachment uploads are already pending for this card."
    }
    require(
        active.none {
            it.request.file.selectionId == job.request.file.selectionId
        },
    ) {
        "The selected file is already queued for upload."
    }
}

internal fun pruneDurableUploadJobs(
    jobs: List<AndroidDurableMultipartUploadJob>,
): List<AndroidDurableMultipartUploadJob> {
    val retained = jobs.filter(AndroidDurableMultipartUploadJob::mustRetain)
    val terminal = jobs.filterNot(AndroidDurableMultipartUploadJob::mustRetain)
        .sortedByDescending(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
        .take((AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS - retained.size).coerceAtLeast(0))
    return (retained + terminal).sortedBy(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
}

private fun AndroidDurableMultipartUploadJob.mustRetain(): Boolean =
    !state.isTerminal() || capabilityCleanupPending

private fun DurableUploadState.isTerminal(): Boolean =
    this == DurableUploadState.Completed ||
        this == DurableUploadState.Failed ||
        this == DurableUploadState.OutcomeUnknown

internal fun isAllowedDurableUploadTransition(
    from: DurableUploadState,
    to: DurableUploadState,
): Boolean = when (from) {
    DurableUploadState.Queued ->
        to == DurableUploadState.Uploading || to == DurableUploadState.Failed
    DurableUploadState.Uploading ->
        to == DurableUploadState.Completed ||
            to == DurableUploadState.Failed ||
            to == DurableUploadState.OutcomeUnknown
    DurableUploadState.Completed,
    DurableUploadState.Failed,
    DurableUploadState.OutcomeUnknown,
    -> false
}

/**
 * A definite client rejection can be presented as failed. Redirects, timeouts, throttling, and
 * server errors remain unknown because the non-idempotent request body may already have arrived.
 */
internal fun durableUploadStateForHttpResponse(status: Int): DurableUploadState = when {
    status in 200..299 -> DurableUploadState.Completed
    status in 400..499 && status !in AMBIGUOUS_CLIENT_RESPONSE_STATUSES -> DurableUploadState.Failed
    else -> DurableUploadState.OutcomeUnknown
}

private fun AndroidDurableMultipartUploadJob.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("accountId", accountId)
    .put("feature", scope.feature)
    .put("resourceId", scope.resourceId)
    .put("resourceFeature", resource.feature)
    .put("boardId", resource.boardId)
    .put("stackId", resource.stackId)
    .put("itemId", resource.itemId)
    .put("state", state.name)
    .put("message", message)
    .put("capabilityCleanupPending", capabilityCleanupPending)
    .put("updatedAt", updatedAtEpochMillis)
    .put("method", request.method.name)
    .put("relativePath", request.relativePath)
    .put("selectionId", request.file.selectionId)
    .put("displayName", request.file.displayName)
    .put("mimeType", request.file.mimeType)
    .put("sizeBytes", request.file.sizeBytes)
    .put("query", JSONObject(request.queryParameters))
    .put("fileFieldName", request.fileFieldName)
    .put(
        "textFields",
        JSONArray().also { array ->
            request.textFields.forEach { field ->
                array.put(JSONObject().put("name", field.name).put("value", field.value))
            }
        },
    )
    .put("ocsApiRequest", request.ocsApiRequest)
    .put("maximumFileBytes", request.maximumFileBytes)
    .put("maximumResponseBytes", request.maximumResponseBytes)

private fun JSONObject.toJob(): AndroidDurableMultipartUploadJob {
    val queryObject = getJSONObject("query")
    val query = buildMap {
        queryObject.keys().forEach { key -> put(key, queryObject.getString(key)) }
    }
    val fieldsArray = getJSONArray("textFields")
    val fields = buildList {
        repeat(fieldsArray.length()) { index ->
            val field = fieldsArray.getJSONObject(index)
            add(MultipartTextField(field.getString("name"), field.getString("value")))
        }
    }
    val file = localUploadFile(
        selectionId = getString("selectionId"),
        displayName = getString("displayName"),
        mimeType = if (isNull("mimeType")) null else getString("mimeType"),
        sizeBytes = if (isNull("sizeBytes")) null else getLong("sizeBytes"),
    )
    val scope = DurableUploadScope(getString("feature"), getString("resourceId"))
    val request = NextcloudMultipartUploadRequest(
        method = NextcloudApiMethod.valueOf(getString("method")),
        relativePath = getString("relativePath"),
        file = file,
        queryParameters = query,
        fileFieldName = getString("fileFieldName"),
        textFields = fields,
        ocsApiRequest = getBoolean("ocsApiRequest"),
        maximumFileBytes = getLong("maximumFileBytes"),
        maximumResponseBytes = getLong("maximumResponseBytes"),
    ).requireSafe()
    val resource = resolveDurableUploadResource(scope, request)
    if (has("resourceFeature")) {
        require(getString("resourceFeature") == resource.feature) {
            "The persisted upload feature changed."
        }
        require((if (isNull("boardId")) null else getString("boardId")) == resource.boardId) {
            "The persisted upload board changed."
        }
        require((if (isNull("stackId")) null else getString("stackId")) == resource.stackId) {
            "The persisted upload stack changed."
        }
        require(getString("itemId") == resource.itemId) {
            "The persisted upload item changed."
        }
    }
    return AndroidDurableMultipartUploadJob(
        id = getString("id"),
        accountId = getString("accountId"),
        scope = scope,
        resource = resource,
        request = request,
        state = DurableUploadState.valueOf(getString("state")),
        message = if (isNull("message")) null else getString("message"),
        capabilityCleanupPending = optBoolean("capabilityCleanupPending", false),
        updatedAtEpochMillis = getLong("updatedAt"),
    )
}

internal fun resolveDurableUploadResource(
    scope: DurableUploadScope,
    request: NextcloudMultipartUploadRequest,
): AndroidDurableUploadResource {
    if (scope.feature != DECK_ATTACHMENT_FEATURE) {
        return AndroidDurableUploadResource(
            feature = scope.feature,
            boardId = null,
            stackId = null,
            itemId = scope.resourceId,
        )
    }
    val segments = request.relativePath.split('/').filter(String::isNotEmpty)
    require(segments.size == 12) { "The Deck attachment upload path is invalid." }
    require(segments.take(4) == listOf("index.php", "apps", "deck", "api")) {
        "The Deck attachment upload path is invalid."
    }
    require(segments[4] == "v1.0" || segments[4] == "v1.1") {
        "The Deck attachment API version is invalid."
    }
    require(
        segments[5] == "boards" &&
            segments[7] == "stacks" &&
            segments[9] == "cards" &&
            segments[11] == "attachments",
    ) {
        "The Deck attachment upload path is invalid."
    }
    val boardId = segments[6]
    val stackId = segments[8]
    val cardId = segments[10]
    require(boardId.isPositiveResourceId()) { "The Deck attachment board id is invalid." }
    require(stackId.isPositiveResourceId()) { "The Deck attachment stack id is invalid." }
    require(cardId.isPositiveResourceId()) { "The Deck attachment card id is invalid." }
    require(scope.resourceId == cardId) {
        "The durable upload card does not match the request target."
    }
    require(request.method == NextcloudApiMethod.POST) {
        "Deck attachment uploads require POST."
    }
    return AndroidDurableUploadResource(
        feature = scope.feature,
        boardId = boardId,
        stackId = stackId,
        itemId = cardId,
    )
}

private fun String.isPositiveResourceId(): Boolean =
    length in 1..19 && all(Char::isDigit) && toLongOrNull()?.let { it > 0L } == true

private val AMBIGUOUS_CLIENT_RESPONSE_STATUSES = setOf(408, 425, 429)
private const val DECK_ATTACHMENT_FEATURE = "deck-attachment"
