package dev.obiente.nextcloudnative

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.localUploadFile
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class AndroidDurableMultipartUploads(context: Context) {
    private val appContext = context.applicationContext
    private val store = AndroidDurableMultipartUploadStore(appContext)

    suspend fun enqueue(
        session: NextcloudSession,
        scope: DurableUploadScope,
        request: NextcloudMultipartUploadRequest,
    ): DurableUploadEnqueueResult {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val picker = AndroidLocalUploadPicker(appContext)
        var storedJob: AndroidDurableMultipartUploadJob? = null
        return runCatching {
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
            store.add(job)
            storedJob = job
            schedule(job).await()
            DurableUploadEnqueueResult.Queued(job.status())
        }.getOrElse { error ->
            storedJob?.let { job ->
                runCatching { store.remove(job.id) }
            }
            if (!store.hasActiveSelection(request.file.selectionId)) {
                picker.release(request.file)
            }
            DurableUploadEnqueueResult.Rejected(
                error.message?.take(MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS)
                    ?: "The background upload could not be scheduled.",
            )
        }
    }

    fun statuses(session: NextcloudSession, scope: DurableUploadScope): List<DurableUploadStatus> =
        store.list(NextcloudDocumentIds.accountKey(session), scope)
            .asSequence()
            .onEach { job ->
                if (job.state == DurableUploadState.Queued) {
                    runCatching { schedule(job) }
                }
            }
            .sortedByDescending(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
            .take(MAX_VISIBLE_UPLOADS_PER_RESOURCE)
            .map(AndroidDurableMultipartUploadJob::status)
            .toList()

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

    private fun schedule(job: AndroidDurableMultipartUploadJob): Operation =
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "deck-attachment-${job.id}",
            ExistingWorkPolicy.KEEP,
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

internal class DeckAttachmentUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val store = AndroidDurableMultipartUploadStore(applicationContext)
        val initial = store.find(jobId) ?: return@withContext Result.success()
        val picker = AndroidLocalUploadPicker(applicationContext)
        if (initial.state.afterProcessRecovery() != initial.state) {
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = DurableUploadState.OutcomeUnknown,
                message = "The app restarted while this upload was in progress. Check the card before uploading again.",
            )
            picker.release(initial.request.file)
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "process-recovery",
                jobId = jobId,
            )
            return@withContext Result.success()
        }
        if (initial.state != DurableUploadState.Queued) return@withContext Result.success()

        val session = AndroidNextcloudServices(applicationContext).loadSession()
        if (session == null || NextcloudDocumentIds.accountKey(session) != initial.accountId) {
            store.transition(
                jobId,
                expected = DurableUploadState.Queued,
                target = DurableUploadState.Failed,
                message = "The account used for this upload is no longer active.",
            )
            picker.release(initial.request.file)
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "account-unavailable",
                jobId = jobId,
            )
            return@withContext Result.failure()
        }
        val capabilityReady = runCatching {
            picker.requirePersisted(initial.request.file)
            picker.open(initial.request.file).use { }
        }.isSuccess
        if (!capabilityReady) {
            store.transition(
                jobId,
                expected = DurableUploadState.Queued,
                target = DurableUploadState.Failed,
                message = "The selected file is no longer available. Select it again to retry.",
            )
            picker.release(initial.request.file)
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Warning,
                outcome = "source-unavailable",
                jobId = jobId,
            )
            return@withContext Result.failure()
        }
        val started = store.transition(
            jobId,
            expected = DurableUploadState.Queued,
            target = DurableUploadState.Uploading,
            message = null,
        ) ?: return@withContext Result.success()
        val services = AndroidNextcloudServices(applicationContext, localUploadPicker = picker)
        val outcome = runCatching {
            services.executeNextcloudMultipartUpload(session, started.request)
        }
        outcome.onSuccess { response ->
            val state = durableUploadStateForHttpResponse(response.status)
            val message = when (state) {
                DurableUploadState.Completed -> null
                DurableUploadState.Failed ->
                    "The server rejected this upload (HTTP ${response.status})."
                DurableUploadState.OutcomeUnknown ->
                    "The server returned HTTP ${response.status}, but the upload result is unknown. " +
                        "Check the card before uploading again."
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
                -> error("The upload response state is invalid.")
            }
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = state,
                message = message,
            )
            if (state != DurableUploadState.Completed) {
                recordUploadDiagnostic(
                    severity = SupportDiagnosticSeverity.Warning,
                    outcome = when (state) {
                        DurableUploadState.Failed -> "rejected"
                        DurableUploadState.OutcomeUnknown -> "outcome-unknown"
                        DurableUploadState.Completed,
                        DurableUploadState.Queued,
                        DurableUploadState.Uploading,
                        -> error("Only failed upload states are diagnosed here.")
                    },
                    jobId = jobId,
                    code = "HTTP:${response.status}",
                )
            }
            picker.release(started.request.file)
        }.onFailure { failure ->
            // Once the request body starts, a transport exception cannot prove whether the server
            // created the attachment. Never replay it automatically and risk a duplicate.
            store.transition(
                jobId,
                expected = DurableUploadState.Uploading,
                target = DurableUploadState.OutcomeUnknown,
                message = "The upload result is unknown. Check the card before uploading again.",
            )
            recordUploadDiagnostic(
                severity = SupportDiagnosticSeverity.Error,
                outcome = "outcome-unknown",
                jobId = jobId,
                failure = failure,
            )
            picker.release(started.request.file)
        }
        Result.success()
    }

    private fun recordUploadDiagnostic(
        severity: SupportDiagnosticSeverity,
        outcome: String,
        jobId: String,
        code: String? = null,
        failure: Throwable? = null,
    ) {
        AndroidSupportDiagnostics.get(applicationContext).record(
            SupportDiagnosticEventDraft(
                severity = severity,
                component = SupportDiagnosticComponent.Media,
                operation = "media.durable-upload",
                outcome = outcome,
                code = code,
                fields = listOf(
                    SupportDiagnosticFieldDraft("job", jobId, SupportDiagnosticValuePrivacy.Identifier),
                ),
                exception = failure?.toSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    internal companion object {
        const val KEY_JOB_ID = "job_id"
    }
}

internal data class AndroidDurableMultipartUploadJob(
    val id: String,
    val accountId: String,
    val scope: DurableUploadScope,
    val resource: AndroidDurableUploadResource,
    val request: NextcloudMultipartUploadRequest,
    val state: DurableUploadState,
    val message: String?,
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
    context: Context,
    preferenceName: String = PREFERENCES,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val cipher = SessionCipher()

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
                !it.state.isTerminal()
        }
    }

    fun remove(id: String) = synchronized(LOCK) {
        writeAll(readAll().filterNot { it.id == id })
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
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        current[index] = updated
        writeAll(pruneDurableUploadJobs(current))
        updated
    }

    private fun readAll(): List<AndroidDurableMultipartUploadJob> {
        val encrypted = preferences.getString(KEY_JOBS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(cipher.decrypt(encrypted))
            buildList {
                repeat(array.length().coerceAtMost(MAX_STORED_UPLOADS)) { index ->
                    runCatching { array.getJSONObject(index).toJob() }
                        .getOrNull()
                        ?.let(::add)
                }
            }.distinctBy(AndroidDurableMultipartUploadJob::id)
        }.getOrElse { emptyList() }
    }

    private fun writeAll(jobs: List<AndroidDurableMultipartUploadJob>) {
        val array = JSONArray()
        jobs.forEach { array.put(it.toJson()) }
        check(
            preferences.edit()
                .putString(KEY_JOBS, cipher.encrypt(array.toString()))
                .commit(),
        ) { "The durable upload queue could not be saved." }
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

internal fun requireCanAddDurableUpload(
    current: List<AndroidDurableMultipartUploadJob>,
    job: AndroidDurableMultipartUploadJob,
) {
    val active = current.filterNot { it.state.isTerminal() }
    require(current.none { it.id == job.id }) {
        "The attachment upload id is already in use."
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
    val active = jobs.filterNot { it.state.isTerminal() }
    val terminal = jobs.filter { it.state.isTerminal() }
        .sortedByDescending(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
        .take((AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS - active.size).coerceAtLeast(0))
    return (active + terminal).sortedBy(AndroidDurableMultipartUploadJob::updatedAtEpochMillis)
}

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
