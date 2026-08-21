package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Duration
import java.time.DateTimeException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.buffer

class JvmSupportIntake(
    private val diagnostics: AsyncJvmSupportDiagnostics,
    private val temporaryRoot: File,
    private val environment: SupportDiagnosticsEnvironment,
    client: OkHttpClient,
    supportBaseUrl: String = DEFAULT_OBIENTE_SUPPORT_URL,
    private val supportMutationsAllowed: () -> Boolean = { true },
    private val directorySync: (File) -> Unit = ::syncPosixDirectoryEntry,
    private val descriptorCleanupRetryMillis: Long = SUPPORT_DESCRIPTOR_DELETE_RETRY_MILLIS,
    private val beforeCallRegistration: () -> Unit = {},
    private val beforeSubmissionPreparation: () -> Unit = {},
    private val beforePendingSubmissionInstall: () -> Unit = {},
    private val beforeBundlePackaging: () -> Unit = {},
    private val afterBundlePackaging: () -> Unit = {},
    private val beforeArchivePromotion: () -> Unit = {},
    private val beforeRetryUploadTransition: () -> Unit = {},
    private val afterRetryUploadTransition: () -> Unit = {},
    private val beforeRecoveryExpiryDisposition: () -> Unit = {},
    private val beforeUploadMarker: () -> Unit = {},
    private val beforeUploadArchiveValidation: () -> Unit = {},
    private val beforeTransportGateFailureDisposition: () -> Unit = {},
    private val beforeReceiptCallRegistration: () -> Unit = {},
    private val afterCancellationIntentPublished: () -> Unit = {},
    private val afterUploadResponse: () -> Unit = {},
    private val beforeUploadResponseDisposition: () -> Unit = {},
    private val afterReceiptLookup: () -> Unit = {},
    private val beforeReceiptResponseDisposition: () -> Unit = {},
    private val privateFileDelete: (File) -> Boolean = File::delete,
    private val pendingDescriptorRead: (File) -> String = { descriptor ->
        descriptor.readText(Charsets.UTF_8)
    },
    private val completedDescriptorRead: (File) -> String = { descriptor ->
        descriptor.readText(Charsets.UTF_8)
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val baseUrl = supportBaseUrl.toHttpUrl()
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val state = MutableStateFlow<SupportDiagnosticsSubmissionState>(
        SupportDiagnosticsSubmissionState.Initializing,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val activeCall = AtomicReference<Call?>()
    private val cancellationRequested = AtomicBoolean(false)
    private val shutdownRequested = AtomicBoolean(false)
    private val operationActive = AtomicBoolean(false)
    private val cancellationContinuationRequested = AtomicBoolean(false)
    private val rejectedPendingDescriptorCleanup = AtomicBoolean(false)
    private val pendingDescriptorRestorePending = AtomicBoolean(false)
    private val completedDescriptorRestorePending = AtomicBoolean(false)
    private val lock = Any()
    private val persistenceLock = Any()
    private var activeAccountIdentity: String? = null
    private var actualState: SupportDiagnosticsSubmissionState = SupportDiagnosticsSubmissionState.Initializing
    private var actualStateAccountIdentity: String? = null
    private var pending: PendingSubmission? = null
    private var completedSubmissions: List<CompletedSubmission> = emptyList()
    private var completedExpiryJob: Job? = null
    private var storageUnavailableMessage: String? = null

    init {
        require(descriptorCleanupRetryMillis > 0L)
        scope.launch {
            try {
                val storageFailure = runCatching { preparePrivateStorage() }.exceptionOrNull()
                val restored = if (storageFailure == null) restorePendingSubmission() else null
                val restoredCompleted = if (storageFailure == null) restoreCompletedSubmissions() else emptyList()
                if (completedDescriptorRestorePending.get()) {
                    scope.launch { retryCompletedDescriptorRestoration() }
                }
                if (storageFailure == null && !pendingDescriptorRestorePending.get()) {
                    pruneTemporaryReports(restored?.archive)
                }
                synchronized(lock) {
                    storageUnavailableMessage = storageFailure?.let { SUPPORT_STORAGE_UNAVAILABLE_MESSAGE }
                        ?: currentRecoveryUnavailableMessage()
                    pending = restored
                    completedSubmissions = restoredCompleted
                    scheduleCompletedExpiryLocked()
                    publishRecoveredStateLocked()
                }
            } finally {
                initialized.complete(Unit)
            }
        }
    }

    fun states(): StateFlow<SupportDiagnosticsSubmissionState> = state.asStateFlow()

    fun setActiveAccountIdentity(accountIdentity: String?) {
        synchronized(lock) {
            activeAccountIdentity = accountIdentity?.takeIf(String::isNotBlank)
            refreshVisibleStateLocked()
        }
    }

    internal suspend fun awaitInitialization() = initialized.await()

    suspend fun submit(
        reproductionSteps: String,
        channel: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ) = withContext(Dispatchers.IO) {
        awaitInitialization()
        synchronized(lock) { storageUnavailableMessage }?.let { message ->
            publishState(SupportDiagnosticsSubmissionState.Unsupported(message))
            return@withContext
        }
        if (!supportMutationsAreAllowed()) {
            publishState(SupportDiagnosticsSubmissionState.Unsupported(READ_ONLY_SUPPORT_MESSAGE))
            return@withContext
        }
        if (!beginOperation()) return@withContext
        try {
            val existing = synchronized(lock) { pending }
            if (existing != null) {
                publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                    "Finish or discard the pending private report before sending another one.",
                    outcomeAmbiguous = existing.outcomeAmbiguous,
                ))
                return@withContext
            }
            val originAccountIdentity = synchronized(lock) { activeAccountIdentity }
            if (originAccountIdentity == null) {
                publishState(SupportDiagnosticsSubmissionState.AccountRequired)
                return@withContext
            }
            cancellationRequested.set(false)
            publishState(SupportDiagnosticsSubmissionState.Packaging, originAccountIdentity)
            val context = try {
                beforeSubmissionPreparation()
                preparePrivateStorage()
                diagnostics.prepareSubmissionContextForAccountIdentity(
                    reproductionSteps,
                    featureState,
                    originAccountIdentity,
                )
            } catch (cancellation: CancellationException) {
                publishState(SupportDiagnosticsSubmissionState.Cancelled, originAccountIdentity)
                throw cancellation
            } catch (failure: Throwable) {
                if (cancellationRequested.get()) {
                    publishState(SupportDiagnosticsSubmissionState.Cancelling, originAccountIdentity)
                    return@withContext
                }
                publishState(SupportDiagnosticsSubmissionState.Rejected(
                    failure.message?.take(MAX_SUPPORT_INTAKE_MESSAGE_LENGTH)
                        ?: "The private diagnostic report could not be prepared.",
                ), originAccountIdentity)
                return@withContext
            }
            if (cancellationRequested.get()) {
                publishState(SupportDiagnosticsSubmissionState.Cancelling, originAccountIdentity)
                return@withContext
            }
            val submission = PendingSubmission(
                archive = null,
                metadata = SupportIntakeMetadata(
                    title = "Nextcloud Native diagnostic report",
                    description = context.sanitizedReproductionSteps.toSupportIntakeDescription(),
                    release = environment.safeForReport().let { safe ->
                        SupportIntakeRelease(
                            version = safe.appVersion.filterSupportMetadata(80),
                            channel = channel.filterSupportMetadata(40),
                            platform = safe.platform.filterSupportMetadata(60),
                            osVersion = safe.operatingSystemVersion.filterSupportMetadata(120),
                            architecture = safe.architecture.filterSupportMetadata(40),
                        )
                    },
                ),
                idempotencyKey = secureIdempotencyKey(),
                createdAtEpochMillis = currentTimeMillis().coerceAtLeast(0L),
                originAccountIdentity = originAccountIdentity,
                context = context,
            )
            beforePendingSubmissionInstall()
            val submissionInstalled = synchronized(lock) {
                if (cancellationRequested.get()) {
                    false
                } else {
                    pending = submission
                    true
                }
            }
            if (!submissionInstalled) {
                publishState(SupportDiagnosticsSubmissionState.Cancelling, originAccountIdentity)
                return@withContext
            }
            if (!persistPendingSafely(submission)) {
                finishRejectedIfCurrent(
                    submission,
                    "The private support submission could not be retained safely on this device.",
                )
                return@withContext
            }
            if (!packageSubmission(submission)) return@withContext
            upload(submission)
        } finally {
            endOperation()
        }
    }

    suspend fun retry() = withContext(Dispatchers.IO) {
        awaitInitialization()
        synchronized(lock) { storageUnavailableMessage }?.let { message ->
            publishState(SupportDiagnosticsSubmissionState.Unsupported(message))
            return@withContext
        }
        if (!supportMutationsAreAllowed()) {
            val existing = synchronized(lock) { pending }
            publishState(
                existing?.let {
                    SupportDiagnosticsSubmissionState.RetryableFailure(
                        READ_ONLY_SUPPORT_MESSAGE,
                        outcomeAmbiguous = it.outcomeAmbiguous,
                    )
                } ?: SupportDiagnosticsSubmissionState.Unsupported(READ_ONLY_SUPPORT_MESSAGE),
                existing?.originAccountIdentity,
            )
            return@withContext
        }
        if (!beginOperation()) return@withContext
        try {
            val submission = synchronized(lock) { pending }
            if (submission == null) {
                publishState(SupportDiagnosticsSubmissionState.Rejected(
                    "There is no private support submission available to retry.",
                ))
                return@withContext
            }
            if (!submission.belongsTo(synchronized(lock) { activeAccountIdentity })) {
                return@withContext
            }
            if (submission.cancellationPending) {
                cancellationRequested.set(true)
                publishState(
                    SupportDiagnosticsSubmissionState.Cancelling,
                    submission.originAccountIdentity,
                )
                finishOrReconcileCancellation(submission)
                return@withContext
            }
            if (submission.recoveryExpired(currentTimeMillis())) {
                beforeRecoveryExpiryDisposition()
                finishRejectedIfCurrent(
                    submission,
                    "The private report recovery capability expired and was removed from this device.",
                )
                return@withContext
            }
            val waitMillis = submission.retryNotBeforeEpochMillis?.minus(currentTimeMillis()) ?: 0L
            if (waitMillis > 0L) {
                publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                    "Obiente Support asked the app to wait before retrying. Try again shortly.",
                    submission.outcomeAmbiguous,
                ))
                return@withContext
            }
            if (submission.outcomeAmbiguous) {
                reconcileAfterAmbiguousResult(
                    submission,
                    IOException("The previous upload result still needs to be reconciled."),
                )
                return@withContext
            }
            beforeRetryUploadTransition()
            val continueRetry = synchronized(lock) {
                when {
                    pending !== submission -> false
                    submission.cancellationPending || cancellationRequested.get() -> false
                    else -> {
                        cancellationRequested.set(false)
                        true
                    }
                }
            }
            if (!continueRetry) {
                if (synchronized(lock) { pending === submission }) {
                    finishOrReconcileCancellation(submission)
                }
                return@withContext
            }
            afterRetryUploadTransition()
            submission.retryNotBeforeEpochMillis = null
            if (!persistPendingSafely(submission)) {
                finishRejectedIfCurrent(
                    submission,
                    "The private support submission could not be retained safely on this device.",
                )
                return@withContext
            }
            if (submission.archive == null && !packageSubmission(submission)) return@withContext
            if (submission.archive?.isFile != true) {
                finishRejectedIfCurrent(submission, "The pending private report archive is unavailable.")
                return@withContext
            }
            upload(submission)
        } finally {
            endOperation()
        }
    }

    private fun beginOperation(): Boolean = synchronized(lock) {
        operationActive.compareAndSet(false, true)
    }

    private fun supportMutationsAreAllowed(): Boolean = runCatching(supportMutationsAllowed).getOrDefault(false)

    private fun endOperation() {
        var cancellationContinuation: PendingSubmission? = null
        synchronized(lock) {
            if (actualState is SupportDiagnosticsSubmissionState.Cancelling && pending == null) {
                publishStateLocked(
                    SupportDiagnosticsSubmissionState.Cancelled,
                    actualStateAccountIdentity,
                )
            }
            operationActive.set(false)
            val submission = pending
            if (
                cancellationContinuationRequested.getAndSet(false) &&
                submission?.cancellationPending == true &&
                !shutdownRequested.get() &&
                operationActive.compareAndSet(false, true)
            ) {
                cancellationContinuation = submission
            } else {
                refreshVisibleStateLocked()
            }
        }
        cancellationContinuation?.let { submission ->
            try {
                publishState(
                    SupportDiagnosticsSubmissionState.Cancelling,
                    submission.originAccountIdentity,
                )
                cancelPendingSubmission(submission)
            } finally {
                endOperation()
            }
        }
    }

    suspend fun cancel(): Boolean {
        awaitInitialization()
        var callAtIntent: Call? = null
        // Serialize the terminal receipt decision with publication of the user's intent. If receipt
        // completion wins and clears pending first, cancellation is correctly reported as too late.
        val pendingCancellation: Boolean? = synchronized(lock) {
            val submission = pending
            when {
                submission?.belongsTo(activeAccountIdentity) == true -> {
                    callAtIntent = activeCall.get()
                    cancellationRequested.set(true)
                    true
                }
                operationActive.get() && actualState is SupportDiagnosticsSubmissionState.Packaging -> {
                    cancellationRequested.set(true)
                    publishStateLocked(SupportDiagnosticsSubmissionState.Cancelling, activeAccountIdentity)
                    false
                }
                else -> null
            }
        }
        return when (pendingCancellation) {
            null -> false
            false -> true
            true -> {
                afterCancellationIntentPublished()
                withContext(Dispatchers.IO) { cancelAfterIntentPublished(callAtIntent) }
            }
        }
    }

    suspend fun deleteCompletedReport(recordId: String): SupportDiagnosticsDeletionResult =
        withContext(Dispatchers.IO) {
            awaitInitialization()
            synchronized(lock) { storageUnavailableMessage }?.let { message ->
                return@withContext SupportDiagnosticsDeletionResult.Unsupported(message)
            }
            if (!supportMutationsAreAllowed()) {
                return@withContext SupportDiagnosticsDeletionResult.Unsupported(READ_ONLY_SUPPORT_MESSAGE)
            }
            if (!beginOperation()) {
                return@withContext SupportDiagnosticsDeletionResult.Failed(
                    "Another private support operation is still in progress.",
                )
            }
            try {
                val completed = synchronized(lock) {
                    completedSubmissions.firstOrNull { submission ->
                        submission.originAccountIdentity == activeAccountIdentity &&
                            submission.recordId == recordId
                    }
                } ?: return@withContext SupportDiagnosticsDeletionResult.Failed(
                    "This submitted support report is no longer available on this device.",
                )
                publishState(
                    SupportDiagnosticsSubmissionState.DeletingSubmittedReport,
                    completed.originAccountIdentity,
                )
                deleteCompletedReportFromServer(completed)
            } finally {
                endOperation()
            }
        }

    suspend fun refreshCompletedReports(): SupportDiagnosticsConversationResult = withContext(Dispatchers.IO) {
        awaitInitialization()
        if (!beginOperation()) {
            return@withContext SupportDiagnosticsConversationResult.Failed(
                "Another private support operation is still in progress.",
            )
        }
        try {
            val accountIdentity = synchronized(lock) { activeAccountIdentity }
                ?: return@withContext SupportDiagnosticsConversationResult.Failed(
                    "Sign in to refresh private support reports.",
                )
            val reports = synchronized(lock) {
                completedSubmissions.filter { completed ->
                    completed.originAccountIdentity == accountIdentity && completed.isRetained(currentTimeMillis())
                }.onEach { completed ->
                    completed.conversationLoading = true
                    completed.conversationError = null
                }
            }
            if (reports.isEmpty()) return@withContext SupportDiagnosticsConversationResult.Failed(
                "No submitted support reports are available on this device.",
            )
            publishState(submittedStateFor(accountIdentity), accountIdentity)
            var failure: String? = null
            reports.forEach { completed ->
                when (val result = refreshCompletedReport(completed)) {
                    SupportDiagnosticsConversationResult.Updated -> Unit
                    is SupportDiagnosticsConversationResult.ReplyDeliveryUnknown -> if (failure == null) failure = result.message
                    is SupportDiagnosticsConversationResult.Failed -> if (failure == null) failure = result.message
                    is SupportDiagnosticsConversationResult.Unsupported -> if (failure == null) failure = result.reason
                }
                publishState(submittedStateFor(accountIdentity), accountIdentity)
            }
            failure?.let { message -> SupportDiagnosticsConversationResult.Failed(message) } ?: SupportDiagnosticsConversationResult.Updated
        } finally {
            endOperation()
        }
    }
    suspend fun sendCompletedReportMessage(
        recordId: String,
        message: String,
    ): SupportDiagnosticsConversationResult = withContext(Dispatchers.IO) {
        awaitInitialization()
        if (!beginOperation()) {
            return@withContext SupportDiagnosticsConversationResult.Failed(
                "Another private support operation is still in progress.",
            )
        }
        try {
        if (!supportMutationsAreAllowed()) {
            return@withContext SupportDiagnosticsConversationResult.Unsupported(READ_ONLY_SUPPORT_MESSAGE)
        }
        val normalizedMessage = message.trim()
        if (
            normalizedMessage.isEmpty() ||
            normalizedMessage.toByteArray(StandardCharsets.UTF_8).size > MAX_SUPPORT_CONVERSATION_MESSAGE_BYTES
        ) {
            return@withContext SupportDiagnosticsConversationResult.Failed(
                "Enter a message no longer than 8 KiB.",
            )
        }
        val completed = synchronized(lock) {
            completedSubmissions.firstOrNull { submission ->
                submission.originAccountIdentity == activeAccountIdentity &&
                    submission.recordId == recordId &&
                    submission.isRetained(currentTimeMillis())
            }?.also { submission ->
                submission.conversationLoading = true
                submission.conversationError = null
            }
        } ?: return@withContext SupportDiagnosticsConversationResult.Failed(
            "This submitted support report is no longer available on this device.",
        )
        publishState(submittedStateFor(completed.originAccountIdentity), completed.originAccountIdentity)
        val capability = runCatching { capabilityFor(completed) }.getOrElse {
            return@withContext failConversation(completed, "The private report capability is invalid.")
        }
        val requestBody = OneShotSupportMessageRequestBody(
            json.encodeToString(
                SupportConversationMessageInput.serializer(),
                SupportConversationMessageInput(normalizedMessage),
            ).encodeToByteArray(),
        )
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability)
                    .addPathSegment("messages").build(),
            )
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
        val result = executeConversationRequest(completed, request, expectedStatusCode = 201)
        publishState(submittedStateFor(completed.originAccountIdentity), completed.originAccountIdentity)
        result
        } finally {
            endOperation()
        }
    }

    suspend fun markCompletedReportRead(recordId: String): Boolean = withContext(Dispatchers.IO) {
        awaitInitialization()
        if (!beginOperation()) return@withContext false
        try {
        val completed = synchronized(lock) {
            completedSubmissions.firstOrNull { submission ->
                submission.originAccountIdentity == activeAccountIdentity &&
                    submission.recordId == recordId
            }
        } ?: return@withContext false
        val conversation = synchronized(lock) { completed.conversation } ?: return@withContext false
        val previousReadState = synchronized(lock) {
            val previous = completed.acknowledgedStatus to completed.lastReadMaintainerMessageId
            completed.acknowledgedStatus = conversation.status
            completed.lastReadMaintainerMessageId = conversation.messages
                .lastOrNull { message -> message.author == "maintainer" }
                ?.id
            previous
        }
        if (!persistCompletedSafely(completed)) {
            synchronized(lock) {
                completed.acknowledgedStatus = previousReadState.first
                completed.lastReadMaintainerMessageId = previousReadState.second
            }
            return@withContext false
        }
        publishState(submittedStateFor(completed.originAccountIdentity), completed.originAccountIdentity)
        true
        } finally {
            endOperation()
        }
    }

    private fun refreshCompletedReport(
        completed: CompletedSubmission,
    ): SupportDiagnosticsConversationResult {
        val capability = runCatching { capabilityFor(completed) }.getOrElse {
            return failConversation(completed, "The private report capability is invalid.")
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability).build())
            .header("Accept", "application/json")
            .get()
            .build()
        return executeConversationRequest(completed, request, expectedStatusCode = 200)
    }

    private fun executeConversationRequest(
        completed: CompletedSubmission,
        request: Request,
        expectedStatusCode: Int,
    ): SupportDiagnosticsConversationResult {
        val call = client.newCall(request)
        if (!registerActiveCall(call)) {
            call.cancel()
            return failConversation(completed, "Another private support operation is still in progress.")
        }
        return try {
            call.execute().use { response ->
                val body = response.readBoundedText()
                if (response.code != expectedStatusCode) {
                    return@use failConversation(
                        completed,
                        if (response.code == 404 || response.code == 410) {
                            "This private support report is no longer available."
                        } else {
                            "The private support conversation could not be refreshed. Try again."
                        },
                    )
                }
                val conversation = decodeConversation(body, completed)
                synchronized(lock) {
                    completed.conversation = conversation
                    completed.conversationLoading = false
                    completed.conversationError = null
                    if (request.method == "POST") {
                        completed.acknowledgedStatus = conversation.status
                        completed.lastReadMaintainerMessageId = conversation.messages
                            .lastOrNull { message -> message.author == "maintainer" }
                            ?.id
                    }
                }
                if (request.method == "POST" && !persistCompletedSafely(completed)) {
                    return@use failConversation(
                        completed,
                        "Your reply was sent, but its read state could not be stored on this device.",
                    )
                }
                SupportDiagnosticsConversationResult.Updated
            }
        } catch (_: IOException) {
            failConversation(
                completed,
                if (request.method == "POST") {
                    "Reply delivery could not be confirmed. Refresh the conversation before sending another reply."
                } else {
                    "Could not reach Obiente Support. Check your connection and try again."
                },
                replyDeliveryUnknown = request.method == "POST",
            )
        } catch (_: SerializationException) {
            failConversation(completed, "Obiente Support returned an invalid private conversation.", request.method == "POST")
        } catch (_: DateTimeException) {
            failConversation(completed, "Obiente Support returned an invalid private conversation.", request.method == "POST")
        } catch (_: IllegalArgumentException) {
            failConversation(completed, "Obiente Support returned an invalid private conversation.", request.method == "POST")
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun decodeConversation(body: String, completed: CompletedSubmission): SupportPrivateStatus {
        val conversation = json.decodeFromString(SupportPrivateStatus.serializer(), body)
        require(conversation.contractVersion == SUPPORT_INTAKE_CONTRACT_VERSION)
        require(conversation.supportCode == completed.receipt.supportCode)
        require(conversation.productId == SUPPORT_INTAKE_PRODUCT_ID)
        require(conversation.status in SUPPORT_REPORT_STATUSES)
        require(conversation.messages.size <= MAX_SUPPORT_CONVERSATION_MESSAGES)
        Instant.parse(conversation.createdAt)
        Instant.parse(conversation.updatedAt)
        Instant.parse(conversation.retentionUntil)
        conversation.messages.forEach { message ->
            require(message.id.matches(SUPPORT_COMPLETED_RECORD_ID_PATTERN))
            require(message.author == "maintainer" || message.author == "reporter")
            require(message.body.isNotBlank())
            require(message.body.toByteArray(StandardCharsets.UTF_8).size <= MAX_SUPPORT_CONVERSATION_MESSAGE_BYTES)
            Instant.parse(message.createdAt)
        }
        return conversation
    }

    private fun capabilityFor(completed: CompletedSubmission): String =
        validateReceipt(completed.receipt).pathSegments.last()

    private fun failConversation(
        completed: CompletedSubmission,
        message: String,
        replyDeliveryUnknown: Boolean = false,
    ): SupportDiagnosticsConversationResult {
        synchronized(lock) {
            completed.conversationLoading = false
            completed.conversationError = message
        }
        return if (replyDeliveryUnknown) SupportDiagnosticsConversationResult.ReplyDeliveryUnknown(message)
        else SupportDiagnosticsConversationResult.Failed(message)
    }

    private fun cancelAfterIntentPublished(callAtIntent: Call?): Boolean {
        var localCancellationCommitted = false
        val submission = synchronized(lock) {
            pending?.also { current ->
                if (!current.cancellationRequiresTombstone && activeCall.get() == null) {
                    // Claim the terminal decision while holding the same lock used by the upload
                    // marker transition. The upload cannot publish a tombstone requirement after
                    // cancellation has already removed this submission from the active state.
                    pending = null
                    localCancellationCommitted = true
                }
            }
        }
        if (submission != null) {
            if (localCancellationCommitted) {
                finishCancelled(submission)
                return true
            }
            submission.cancellationPending = true
            submission.outcomeAmbiguous = true
            val archive = submission.archive
            val cancellationPersisted = persistMinimalCancellationSafely(
                submission,
                deleteArchiveAfterPersist = false,
            )
            val call = callAtIntent?.takeIf { activeCall.compareAndSet(it, null) }
            if (!cancellationPersisted) {
                call?.cancel()
                publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                    "Cancellation could not be stored safely. Keep the app open and retry to reconcile the private report.",
                    outcomeAmbiguous = true,
                ))
                return false
            }
            call?.cancel()
            deletePrivateFileOrRetry(archive)
            if (call != null) {
                publishState(
                    SupportDiagnosticsSubmissionState.Cancelling,
                    submission.originAccountIdentity,
                )
                return true
            }
            val startCancellation = synchronized(lock) {
                if (operationActive.compareAndSet(false, true)) {
                    true
                } else {
                    cancellationContinuationRequested.set(true)
                    false
                }
            }
            if (startCancellation) {
                try {
                    publishState(
                        SupportDiagnosticsSubmissionState.Cancelling,
                        submission.originAccountIdentity,
                    )
                    cancelPendingSubmission(submission)
                } finally {
                    endOperation()
                }
            } else {
                publishState(
                    SupportDiagnosticsSubmissionState.Cancelling,
                    submission.originAccountIdentity,
                )
            }
            return true
        }
        cancellationRequested.compareAndSet(true, false)
        return false
    }

    override fun close() {
        val call = synchronized(lock) {
            shutdownRequested.set(true)
            activeCall.getAndSet(null)
        }
        call?.cancel()
        scope.cancel()
    }

    private fun registerActiveCall(
        submission: PendingSubmission,
        call: Call,
        allowCancellationRequested: Boolean,
    ): Boolean = synchronized(lock) {
        if (
            shutdownRequested.get() ||
            pending !== submission ||
            (!allowCancellationRequested && cancellationRequested.get())
        ) {
            false
        } else {
            activeCall.compareAndSet(null, call)
        }
    }

    private fun registerActiveCall(call: Call): Boolean = synchronized(lock) {
        !shutdownRequested.get() && activeCall.compareAndSet(null, call)
    }

    private fun deleteCompletedReportFromServer(
        completed: CompletedSubmission,
    ): SupportDiagnosticsDeletionResult {
        val capability = try {
            val statusUrl = validateReceipt(completed.receipt)
            val deletionUrl = completed.receipt.deletionUrl.toHttpUrl()
            require(
                deletionUrl.scheme == statusUrl.scheme &&
                    deletionUrl.host == statusUrl.host &&
                    deletionUrl.port == statusUrl.port &&
                    deletionUrl.encodedPath == statusUrl.encodedPath &&
                    deletionUrl.encodedQuery == null &&
                    deletionUrl.fragment == null,
            )
            statusUrl.pathSegments.last()
        } catch (_: IllegalArgumentException) {
            return failCompletedDeletion(completed, "The private deletion capability is invalid.")
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability).build())
            .header("Accept", "application/json")
            .delete()
            .build()
        val call = client.newCall(request)
        if (!registerActiveCall(call)) {
            call.cancel()
            return failCompletedDeletion(completed, "Deletion was interrupted before it could start.")
        }
        return try {
            call.execute().use { response ->
                response.readBoundedText()
                activeCall.compareAndSet(call, null)
                when {
                    response.code in TERMINAL_DELETION_STATUS_CODES || response.code == 404 ->
                        finishCompletedDeletion(completed)
                    response.isSuccessful -> verifyCompletedDeletion(completed, capability)
                    else -> failCompletedDeletion(
                        completed,
                        "The submitted support report could not be deleted. Try again.",
                    )
                }
            }
        } catch (_: IOException) {
            failCompletedDeletion(
                completed,
                "Deletion could not be confirmed. Check your connection, then try again.",
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun verifyCompletedDeletion(
        completed: CompletedSubmission,
        capability: String,
    ): SupportDiagnosticsDeletionResult {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability).build())
            .header("Accept", "application/json")
            .get()
            .build()
        val call = client.newCall(request)
        if (!registerActiveCall(call)) {
            call.cancel()
            return failCompletedDeletion(completed, "Deletion verification was interrupted. Try again.")
        }
        return try {
            call.execute().use { response ->
                response.readBoundedText()
                if (response.code == 404) {
                    finishCompletedDeletion(completed)
                } else {
                    failCompletedDeletion(
                        completed,
                        "Deletion is still being processed. Try again to verify it was removed.",
                    )
                }
            }
        } catch (_: IOException) {
            failCompletedDeletion(
                completed,
                "Deletion was accepted but could not be verified. Check your connection, then try again.",
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun finishCompletedDeletion(completed: CompletedSubmission): SupportDiagnosticsDeletionResult {
        if (!deleteCompletedDescriptorSafely(completedDescriptor(completed.recordId))) {
            return failCompletedDeletion(
                completed,
                "The report was deleted from support, but its private receipt could not be removed from this device. Try again.",
            )
        }
        val next = synchronized(lock) {
            completedSubmissions = completedSubmissions.filterNot { submission ->
                submission.recordId == completed.recordId
            }
            scheduleCompletedExpiryLocked()
            latestCompletedFor(completed.originAccountIdentity)
                ?.let { submittedStateFor(it.originAccountIdentity) }
                ?: SupportDiagnosticsSubmissionState.Idle
        }
        publishState(next, completed.originAccountIdentity)
        return SupportDiagnosticsDeletionResult.Deleted
    }

    private fun failCompletedDeletion(
        completed: CompletedSubmission,
        message: String,
    ): SupportDiagnosticsDeletionResult.Failed {
        val next = synchronized(lock) {
            latestCompletedFor(completed.originAccountIdentity)
                ?.let { submittedStateFor(it.originAccountIdentity) }
                ?: SupportDiagnosticsSubmissionState.Idle
        }
        publishState(next, completed.originAccountIdentity)
        return SupportDiagnosticsDeletionResult.Failed(message)
    }

    private suspend fun packageSubmission(submission: PendingSubmission): Boolean {
        if (cancellationRequested.get()) {
            finishOrReconcileCancellation(submission)
            return false
        }
        publishState(SupportDiagnosticsSubmissionState.Packaging)
        val destination = File(temporaryRoot, "support-${UUID.randomUUID()}.zip")
        val prepared = try {
            beforeBundlePackaging()
            diagnostics.writeBundleForSubmission(destination, submission.context).also {
                afterBundlePackaging()
            }
        } catch (cancellation: CancellationException) {
            deletePrivateFileOrRetry(destination)
            if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
                finishOrReconcileCancellation(submission)
            } else {
                retainForRetry(
                    submission,
                    "Private report preparation was interrupted. You can retry it safely.",
                    ambiguous = false,
                )
            }
            throw cancellation
        } catch (_: Throwable) {
            deletePrivateFileOrRetry(destination)
            if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
                finishOrReconcileCancellation(submission)
            } else {
                retainForRetry(
                    submission,
                    "The private diagnostic report could not be prepared. You can retry safely.",
                    ambiguous = false,
                )
            }
            return false
        }
        try {
            restrictOwnerOnlyFile(prepared.archive)
        } catch (_: Throwable) {
            deletePrivateFileOrRetry(prepared.archive)
            retainForRetry(
                submission,
                "The private report could not be protected on this device. You can retry safely.",
                ambiguous = false,
            )
            return false
        }
        if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
            deletePrivateFileOrRetry(prepared.archive)
            return false
        }
        beforeArchivePromotion()
        val archivePromoted = synchronized(lock) {
            if (pending !== submission || cancellationRequested.get() || submission.cancellationPending) {
                false
            } else {
                submission.archive = prepared.archive
                true
            }
        }
        if (!archivePromoted) {
            deletePrivateFileOrRetry(prepared.archive)
            return false
        }
        if (!persistPendingSafely(submission)) {
            finishRejectedIfCurrent(
                submission,
                "The private support submission could not be retained safely on this device.",
            )
            return false
        }
        if (cancellationRequested.get()) {
            finishOrReconcileCancellation(submission)
            return false
        }
        return true
    }

    private suspend fun upload(submission: PendingSubmission) {
        if (cancellationRequested.get()) {
            finishOrReconcileCancellation(submission)
            return
        }
        val mutationAllowedBeforePreparation = supportMutationsAreAllowed()
        if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
            finishOrReconcileCancellation(submission)
            return
        }
        if (!mutationAllowedBeforePreparation) {
            retainForRetry(submission, READ_ONLY_SUPPORT_MESSAGE, ambiguous = false)
            return
        }
        val archive = requireNotNull(submission.archive) { "The private support archive has not been prepared." }
        beforeUploadArchiveValidation()
        if (!archive.isFile || archive.length() !in 1L..MAX_SUPPORT_ARCHIVE_BYTES) {
            if (synchronized(lock) { pending === submission }) {
                finishRejectedIfCurrent(submission, "The pending private report archive is unavailable.")
            }
            return
        }
        val metadata = json.encodeToString(SupportIntakeMetadata.serializer(), submission.metadata)
        val progressBody = ProgressRequestBody(
            delegate = archive.asRequestBody(SUPPORT_ARCHIVE_MEDIA_TYPE),
            onProgress = { uploaded, total ->
                if (!cancellationRequested.get()) {
                    publishState(SupportDiagnosticsSubmissionState.Uploading(
                        total.takeIf { it > 0L }?.let { uploaded.toFloat() / it.toFloat() }?.coerceIn(0f, 1f),
                    ))
                }
            },
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", metadata.toRequestBody(SUPPORT_METADATA_MEDIA_TYPE))
            .addFormDataPart("diagnostics", "diagnostics.zip", progressBody)
            .build()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").build())
            .header("Accept", "application/json")
            .header("Idempotency-Key", submission.idempotencyKey)
            .post(body)
            .build()
        val mutationAllowedAtTransport = supportMutationsAreAllowed()
        if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
            finishOrReconcileCancellation(submission)
            return
        }
        if (!mutationAllowedAtTransport) {
            beforeTransportGateFailureDisposition()
            retainForRetry(submission, READ_ONLY_SUPPORT_MESSAGE, ambiguous = false)
            return
        }
        beforeUploadMarker()
        var previousLatestUploadAttemptAtEpochMillis: Long? = null
        var previousOutcomeAmbiguous = false
        var previousCancellationRequiresTombstone = false
        val uploadMarked = synchronized(lock) {
            if (pending !== submission || cancellationRequested.get() || submission.cancellationPending) {
                false
            } else {
                previousLatestUploadAttemptAtEpochMillis = submission.latestUploadAttemptAtEpochMillis
                previousOutcomeAmbiguous = submission.outcomeAmbiguous
                previousCancellationRequiresTombstone = submission.cancellationRequiresTombstone
                submission.latestUploadAttemptAtEpochMillis = currentTimeMillis().coerceAtLeast(0L)
                submission.outcomeAmbiguous = true
                submission.cancellationRequiresTombstone = true
                true
            }
        }
        if (!uploadMarked) {
            if (synchronized(lock) { pending === submission }) {
                finishOrReconcileCancellation(submission)
            }
            return
        }
        if (!persistPendingSafely(submission)) {
            val stillPending = synchronized(lock) {
                if (pending !== submission) {
                    false
                } else {
                    submission.latestUploadAttemptAtEpochMillis = previousLatestUploadAttemptAtEpochMillis
                    submission.outcomeAmbiguous = previousOutcomeAmbiguous
                    submission.cancellationRequiresTombstone = previousCancellationRequiresTombstone
                    true
                }
            }
            if (stillPending) {
                if (previousCancellationRequiresTombstone) {
                    publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                        "The retry could not be stored safely. The earlier upload remains recoverable on this device.",
                        outcomeAmbiguous = previousOutcomeAmbiguous,
                    ))
                } else {
                    finishRejectedIfCurrent(
                        submission,
                        "The private support submission could not be retained safely on this device.",
                    )
                }
            }
            return
        }
        publishState(SupportDiagnosticsSubmissionState.Uploading(0f))
        val call = client.newCall(request)
        beforeCallRegistration()
        if (!registerActiveCall(submission, call, allowCancellationRequested = false)) {
            call.cancel()
            synchronized(lock) {
                if (pending === submission) {
                    submission.latestUploadAttemptAtEpochMillis = previousLatestUploadAttemptAtEpochMillis
                    submission.outcomeAmbiguous = previousOutcomeAmbiguous
                    submission.cancellationRequiresTombstone = previousCancellationRequiresTombstone
                }
            }
            when {
                cancellationRequested.get() && previousCancellationRequiresTombstone ->
                    finishOrReconcileCancellation(submission)
                cancellationRequested.get() -> finishCancelled(submission)
                synchronized(lock) { pending === submission } -> retainForRetry(
                    submission,
                    "The private support submission was interrupted before upload. You can retry it safely.",
                    ambiguous = false,
                )
            }
            return
        }
        try {
            call.execute().use { response ->
                val responseText = response.readBoundedText()
                activeCall.compareAndSet(call, null)
                afterUploadResponse()
                if (cancellationRequested.get() || submission.cancellationPending) {
                    cancelPendingSubmission(submission)
                    return
                }
                beforeUploadResponseDisposition()
                when {
                    response.isSuccessful -> finishReceived(submission, decodeReceipt(responseText))
                    response.code == 408 -> reconcileAfterAmbiguousResult(
                        submission,
                        IOException("Obiente Support timed out while accepting the report."),
                    )
                    response.code in RETRYABLE_CLIENT_STATUS_CODES -> retainForRetry(
                        submission,
                        "Obiente Support is temporarily limiting submissions. You can retry safely.",
                        ambiguous = false,
                        retryNotBeforeEpochMillis = response.retryNotBeforeEpochMillis(),
                    )
                    response.code == 410 && isSubmissionCancelledProblem(responseText) -> finishCancelled(submission)
                    response.code == 410 -> retainForRetry(
                        submission,
                        "Obiente Support returned an unverified cancellation result. Retry safely to reconcile the report.",
                        ambiguous = true,
                    )
                    response.code in 400..499 -> finishRejectedIfCurrent(
                        submission,
                        decodeProblem(responseText),
                    )
                    else -> retainForRetry(
                        submission,
                        "Obiente Support is temporarily unavailable.",
                        ambiguous = response.code in 300..399 || response.code in 500..599,
                        retryNotBeforeEpochMillis = response.retryNotBeforeEpochMillis(),
                    )
                }
            }
        } catch (failure: IOException) {
            activeCall.compareAndSet(call, null)
            if (shutdownRequested.get()) {
                retainForRetry(
                    submission,
                    "The private support submission was interrupted while the app closed. You can retry it safely.",
                    true,
                )
            } else {
                reconcileAfterAmbiguousResult(submission, failure)
            }
        } catch (_: IllegalArgumentException) {
            retainForRetry(submission, "Obiente Support returned an invalid receipt.", true)
        } catch (cancellation: CancellationException) {
            finishCancelled(submission)
            throw cancellation
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun reconcileAfterAmbiguousResult(
        submission: PendingSubmission,
        uploadFailure: IOException,
    ) {
        if (cancellationRequested.get() || submission.cancellationPending) {
            cancelPendingSubmission(submission)
            return
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/receipts").build())
            .header("Accept", "application/json")
            .header("Idempotency-Key", submission.idempotencyKey)
            .get()
            .build()
        val call = client.newCall(request)
        beforeReceiptCallRegistration()
        if (!registerActiveCall(submission, call, allowCancellationRequested = false)) {
            call.cancel()
            retainForRetry(
                submission,
                "The upload result still needs to be reconciled. You can retry it safely.",
                ambiguous = true,
            )
            return
        }
        val responseResult = try {
            call.execute().use { response ->
                response.code to response.readBoundedText()
            }
        } catch (_: IOException) {
            if (cancellationRequested.get() || submission.cancellationPending) {
                cancelPendingSubmission(submission)
            } else {
                retainForRetry(
                    submission,
                    uploadFailure.message?.filterSupportMetadata(MAX_SUPPORT_INTAKE_MESSAGE_LENGTH)
                        ?.takeIf(String::isNotBlank)
                        ?: "The upload result is uncertain. Check your connection before retrying.",
                    true,
                )
            }
            return
        } finally {
            activeCall.compareAndSet(call, null)
        }
        afterReceiptLookup()
        if (cancellationRequested.get() || submission.cancellationPending) {
            cancelPendingSubmission(submission)
            return
        }
        beforeReceiptResponseDisposition()
        val (responseCode, responseText) = responseResult
        when {
            responseCode in 200..299 -> {
                try {
                    finishReceived(submission, decodeReceipt(responseText))
                } catch (_: IOException) {
                    retainForRetry(submission, "Obiente Support returned an invalid receipt.", true)
                } catch (_: IllegalArgumentException) {
                    retainForRetry(submission, "Obiente Support returned an invalid receipt.", true)
                }
            }
            responseCode == 404 ->
                retainForRetry(submission, "The upload did not complete. You can retry it safely.", false)
            responseCode == 410 && isSubmissionCancelledProblem(responseText) -> finishCancelled(submission)
            responseCode == 410 -> retainForRetry(
                submission,
                "Obiente Support returned an unverified cancellation result. Retry safely to reconcile the report.",
                ambiguous = true,
            )
            else -> retainForRetry(
                submission,
                "The upload result is uncertain. Check your connection before retrying.",
                true,
            )
        }
    }

    private fun finishReceived(submission: PendingSubmission, receipt: SupportIntakeReceipt) {
        validateReceipt(receipt, enforceCurrentRetentionWindow = true)
        val submitReceivedReport = synchronized(lock) {
            if (pending !== submission) return
            if (cancellationRequested.get()) {
                false
            } else {
                // Clearing pending is the terminal decision. cancel() takes the same lock and will
                // return false if it starts after this point instead of claiming deletion began.
                pending = null
                true
            }
        }
        if (!submitReceivedReport) {
            cancelPendingSubmission(submission, receipt)
        } else {
            finishSubmitted(submission, receipt)
        }
    }

    private fun cancelPendingSubmission(
        submission: PendingSubmission,
        receipt: SupportIntakeReceipt? = submission.receipt,
    ) {
        cancellationContinuationRequested.set(false)
        submission.cancellationPending = true
        submission.outcomeAmbiguous = true
        submission.receipt = receipt
        if (!persistMinimalCancellationSafely(submission)) {
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                "Cancellation was not sent because its recovery state could not be stored safely. Keep the app open and retry.",
                outcomeAmbiguous = true,
            ))
            return
        }
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/receipts").build())
            .header("Accept", "application/json")
            .header("Idempotency-Key", submission.idempotencyKey)
            .delete()
            .build()
        if (!supportMutationsAreAllowed()) {
            retainCancellationForRetry(submission, READ_ONLY_SUPPORT_MESSAGE)
            return
        }
        val call = client.newCall(request)
        if (!registerActiveCall(submission, call, allowCancellationRequested = true)) {
            call.cancel()
            if (synchronized(lock) { pending === submission }) {
                retainCancellationForRetry(submission, "Cancellation still needs to be confirmed. Retry safely.")
            }
            return
        }
        try {
            call.execute().use { response ->
                response.readBoundedText()
                activeCall.compareAndSet(call, null)
                when {
                    response.code == 204 -> finishCancelled(submission)
                    else -> retainCancellationForRetry(
                        submission,
                        "Obiente Support did not confirm cancellation. Retry safely; the private report remains recoverable.",
                    )
                }
            }
        } catch (_: IOException) {
            retainCancellationForRetry(
                submission,
                "Cancellation could not be confirmed. Check your connection, then retry safely.",
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun persistMinimalCancellationSafely(
        submission: PendingSubmission,
        deleteArchiveAfterPersist: Boolean = true,
    ): Boolean = synchronized(persistenceLock) {
        val archive = submission.archive
        val metadata = submission.metadata
        val context = submission.context
        val receipt = submission.receipt
        val retryNotBeforeEpochMillis = submission.retryNotBeforeEpochMillis
        stripPrivateCancellationPayload(submission)
        if (!persistPendingSafely(submission)) {
            submission.archive = archive
            submission.metadata = metadata
            submission.context = context
            submission.receipt = receipt
            submission.retryNotBeforeEpochMillis = retryNotBeforeEpochMillis
            return@synchronized false
        }
        if (deleteArchiveAfterPersist) deletePrivateFileOrRetry(archive)
        true
    }

    private fun stripPrivateCancellationPayload(submission: PendingSubmission): File? {
        val archive = submission.archive
        submission.archive = null
        submission.metadata = SupportIntakeMetadata(
            title = "",
            description = "",
            release = SupportIntakeRelease("", "", "", "", ""),
        )
        submission.context = PreparedSupportSubmissionContext(
            sanitizedReproductionSteps = null,
            featureState = emptyList(),
            confirmedAtEpochMillis = 0L,
            events = emptyList(),
        )
        submission.receipt = null
        submission.retryNotBeforeEpochMillis = null
        return archive
    }

    private fun finishSubmitted(submission: PendingSubmission, receipt: SupportIntakeReceipt) {
        validateReceipt(receipt)
        val existingCompletion = synchronized(lock) {
            completedSubmissions.firstOrNull { completed ->
                completed.originAccountIdentity == submission.originAccountIdentity &&
                    completed.receipt.statusUrl == receipt.statusUrl &&
                    completed.receipt.supportCode == receipt.supportCode
            }
        }
        if (existingCompletion != null) {
            finishTerminal(submission)
            publishState(
                submittedStateFor(submission.originAccountIdentity),
                submission.originAccountIdentity,
            )
            return
        }
        val completedSubmission = CompletedSubmission(
            recordId = UUID.randomUUID().toString(),
            originAccountIdentity = submission.originAccountIdentity,
            receipt = receipt,
        )
        if (!persistCompletedSafely(completedSubmission)) {
            synchronized(lock) { pending = submission }
            publishState(
                SupportDiagnosticsSubmissionState.RetryableFailure(
                    "The report was received, but its private status could not be stored. Retry safely to recover it.",
                    outcomeAmbiguous = true,
                ),
                submission.originAccountIdentity,
            )
            return
        }
        synchronized(lock) {
            completedSubmissions = completedSubmissions + completedSubmission
            scheduleCompletedExpiryLocked()
        }
        finishTerminal(submission)
        publishState(submittedStateFor(submission.originAccountIdentity), submission.originAccountIdentity)
    }

    private fun validateReceipt(
        receipt: SupportIntakeReceipt,
        enforceCurrentRetentionWindow: Boolean = false,
    ): okhttp3.HttpUrl {
        require(receipt.contractVersion == SUPPORT_INTAKE_CONTRACT_VERSION)
        require(receipt.supportCode.matches(SUPPORT_CODE_PATTERN))
        require(receipt.status.matches(SUPPORT_RECEIPT_STATUS_PATTERN))
        val createdAt = runCatching { Instant.parse(receipt.createdAt) }
            .getOrElse { throw IllegalArgumentException("Invalid receipt timestamp.", it) }
        val retentionUntil = runCatching { Instant.parse(receipt.retentionUntil) }
            .getOrElse { throw IllegalArgumentException("Invalid receipt timestamp.", it) }
        require(!retentionUntil.isBefore(createdAt))
        val now = Instant.now()
        require(
            Duration.between(createdAt, retentionUntil) <=
                Duration.ofMillis(SUPPORT_SERVER_RETENTION_MAX_AGE_MILLIS),
        )
        if (enforceCurrentRetentionWindow) {
            require(!createdAt.isAfter(now.plusMillis(SUPPORT_RECEIPT_CLOCK_SKEW_MILLIS)))
            require(retentionUntil.isAfter(now))
            require(
                !retentionUntil.isAfter(
                    now.plusMillis(SUPPORT_SERVER_RETENTION_MAX_AGE_MILLIS + SUPPORT_RECEIPT_CLOCK_SKEW_MILLIS),
                ),
            )
        }
        val statusUrl = receipt.statusUrl.toHttpUrl()
        require(
            statusUrl.scheme == baseUrl.scheme &&
                statusUrl.host == baseUrl.host &&
                statusUrl.port == baseUrl.port &&
                statusUrl.encodedPath.matches(SUPPORT_STATUS_PATH_PATTERN) &&
                statusUrl.encodedQuery == null &&
                statusUrl.fragment == null,
        )
        val deletionUrl = receipt.deletionUrl.toHttpUrl()
        require(
            deletionUrl.scheme == statusUrl.scheme &&
                deletionUrl.host == statusUrl.host &&
                deletionUrl.port == statusUrl.port &&
                deletionUrl.encodedPath == statusUrl.encodedPath &&
                deletionUrl.encodedQuery == null &&
                deletionUrl.fragment == null,
        )
        return statusUrl
    }

    private fun finishTerminal(submission: PendingSubmission) {
        synchronized(lock) {
            if (pending === submission) pending = null
        }
        deletePrivateFileOrRetry(submission.archive)
        if (!cleanupPendingDescriptorSafely(submission)) {
            scope.launch { retryPendingDescriptorCleanup(submission) }
        }
    }

    private fun deletePrivateFileOrRetry(file: File?) {
        if (file == null || deletePrivateFileSafely(file)) return
        scope.launch {
            while (!shutdownRequested.get()) {
                delay(descriptorCleanupRetryMillis)
                if (deletePrivateFileSafely(file)) return@launch
            }
        }
    }

    private fun deletePrivateFileSafely(file: File): Boolean =
        !file.exists() || runCatching { privateFileDelete(file) }.getOrDefault(false) || !file.exists()

    private fun cleanupPendingDescriptorSafely(submission: PendingSubmission): Boolean =
        synchronized(persistenceLock) {
            runCatching {
                val descriptor = pendingDescriptor()
                if (descriptor.isFile) {
                    val persistedIdempotencyKey = try {
                        json.decodeFromString(
                            PersistedPendingSubmission.serializer(),
                            pendingDescriptorRead(descriptor),
                        ).idempotencyKey
                    } catch (_: Throwable) {
                        return@synchronized false
                    }
                    if (persistedIdempotencyKey != submission.idempotencyKey) return@synchronized true
                }
                deletePrivateDescriptorDurably(descriptor)
            }.isSuccess
        }

    private suspend fun retryPendingDescriptorCleanup(submission: PendingSubmission) {
        while (!shutdownRequested.get()) {
            delay(descriptorCleanupRetryMillis)
            if (cleanupPendingDescriptorSafely(submission)) return
        }
    }

    private fun finishRejected(submission: PendingSubmission, message: String) {
        finishTerminal(submission)
        publishState(SupportDiagnosticsSubmissionState.Rejected(message), submission.originAccountIdentity)
    }

    private fun finishRejectedIfCurrent(submission: PendingSubmission, message: String) {
        val rejectionCommitted = synchronized(lock) {
            if (
                pending === submission &&
                !cancellationRequested.get() &&
                !submission.cancellationPending
            ) {
                pending = null
                true
            } else {
                false
            }
        }
        if (rejectionCommitted) finishRejected(submission, message)
    }

    private fun finishCancelled(submission: PendingSubmission) {
        finishTerminal(submission)
        publishState(
            if (operationActive.get()) {
                SupportDiagnosticsSubmissionState.Cancelling
            } else {
                SupportDiagnosticsSubmissionState.Cancelled
            },
            submission.originAccountIdentity,
        )
    }

    private fun finishOrReconcileCancellation(submission: PendingSubmission) {
        if (
            synchronized(lock) {
                pending === submission && submission.cancellationRequiresTombstone
            }
        ) {
            cancelPendingSubmission(submission)
        } else {
            finishCancelled(submission)
        }
    }

    private fun retainForRetry(
        submission: PendingSubmission,
        message: String,
        ambiguous: Boolean,
        retryNotBeforeEpochMillis: Long? = null,
    ) {
        val retentionCommitted = synchronized(lock) {
            if (
                pending !== submission ||
                cancellationRequested.get() ||
                submission.cancellationPending
            ) {
                false
            } else {
                submission.outcomeAmbiguous = ambiguous
                submission.retryNotBeforeEpochMillis = retryNotBeforeEpochMillis
                true
            }
        }
        if (!retentionCommitted) {
            if (synchronized(lock) { pending === submission }) {
                finishOrReconcileCancellation(submission)
            }
            return
        }
        val persisted = persistPendingSafely(submission)
        val retryStatePublished = synchronized(lock) {
            if (
                pending === submission &&
                !cancellationRequested.get() &&
                !submission.cancellationPending
            ) {
                publishStateLocked(
                    if (persisted) {
                        SupportDiagnosticsSubmissionState.RetryableFailure(message, ambiguous)
                    } else {
                        // Atomic replacement keeps the descriptor from immediately before the
                        // request, including its idempotency key and recovery requirement.
                        SupportDiagnosticsSubmissionState.RetryableFailure(
                            "The updated retry state could not be stored. Keep the app open and retry safely to reconcile the report.",
                            outcomeAmbiguous = true,
                        )
                    },
                    submission.originAccountIdentity,
                )
                true
            } else {
                false
            }
        }
        if (!retryStatePublished && synchronized(lock) { pending === submission }) {
            finishOrReconcileCancellation(submission)
        }
    }

    private fun retainCancellationForRetry(
        submission: PendingSubmission,
        message: String,
    ) {
        submission.cancellationPending = true
        submission.outcomeAmbiguous = true
        synchronized(lock) { pending = submission }
        if (persistPendingSafely(submission)) {
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(message, outcomeAmbiguous = true))
        } else {
            // The receipt was persisted before deletion began. Atomic replacement leaves that last
            // valid recovery record in place when this newer retry-state write fails.
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                "Cancellation was not confirmed and its updated retry state could not be stored. Keep the app open and retry.",
                outcomeAmbiguous = true,
            ))
        }
    }

    private fun persistPendingSafely(submission: PendingSubmission): Boolean = synchronized(persistenceLock) {
        if (synchronized(lock) { pending !== submission }) return@synchronized false
        runCatching { persistPending(submission) }.isSuccess
    }

    private fun decodeReceipt(response: String): SupportIntakeReceipt = try {
        json.decodeFromString(SupportIntakeReceipt.serializer(), response)
    } catch (failure: SerializationException) {
        throw IOException("Obiente Support returned an invalid receipt.", failure)
    }

    private fun decodeProblem(response: String): String = runCatching {
        json.parseToJsonElement(response).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull()
        ?.filterSupportMetadata(MAX_SUPPORT_INTAKE_MESSAGE_LENGTH)
        ?.takeIf(String::isNotBlank)
        ?: "Obiente Support rejected this diagnostic report."

    private fun isSubmissionCancelledProblem(response: String): Boolean = runCatching {
        json.decodeFromString(SupportIntakeProblem.serializer(), response).let { problem ->
            problem.contractVersion == SUPPORT_INTAKE_CONTRACT_VERSION &&
                problem.code == SUPPORT_SUBMISSION_CANCELLED_CODE
        }
    }.getOrDefault(false)

    private fun pruneTemporaryReports(retainedArchive: File?) {
        val cutoff = System.currentTimeMillis() - SUPPORT_TEMPORARY_MAX_AGE_MILLIS
        temporaryRoot.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.name.matches(SUPPORT_TEMPORARY_FILE_PATTERN) &&
                    (file != retainedArchive || file.lastModified() < cutoff)
            }
            .forEach(::deletePrivateFileOrRetry)
        temporaryRoot.listFiles().orEmpty()
            .filter { file ->
                file.isFile && (
                    file.name.matches(SUPPORT_PENDING_TEMPORARY_FILE_PATTERN) ||
                        file.name.matches(SUPPORT_ARCHIVE_TEMPORARY_FILE_PATTERN)
                    )
            }
            .forEach(::deletePrivateFileOrRetry)
    }

    private fun preparePrivateStorage() {
        require(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) {
            "Could not prepare private support submission storage."
        }
        restrictOwnerOnlyDirectory(temporaryRoot)
    }

    private fun restrictOwnerOnlyDirectory(directory: File) {
        val path = directory.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    private fun restrictOwnerOnlyFile(file: File) {
        val path = file.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }

    private fun persistPending(submission: PendingSubmission) {
        val descriptor = pendingDescriptor()
        val parent = requireNotNull(descriptor.parentFile)
        preparePrivateStorage()
        val temporary = Files.createTempFile(parent.toPath(), ".pending-", ".tmp").toFile()
        try {
            restrictOwnerOnlyFile(temporary)
            val encoded = json.encodeToString(
                PersistedPendingSubmission.serializer(),
                PersistedPendingSubmission(
                    archiveName = submission.archive?.name,
                    metadata = submission.metadata,
                    idempotencyKey = submission.idempotencyKey,
                    createdAtEpochMillis = submission.createdAtEpochMillis,
                    originAccountIdentity = submission.originAccountIdentity,
                    context = submission.context,
                    cancellationPending = submission.cancellationPending,
                    outcomeAmbiguous = submission.outcomeAmbiguous,
                    cancellationRequiresTombstone = submission.cancellationRequiresTombstone,
                    latestUploadAttemptAtEpochMillis = submission.latestUploadAttemptAtEpochMillis,
                    retryNotBeforeEpochMillis = submission.retryNotBeforeEpochMillis,
                    receipt = submission.receipt,
                ),
            ).encodeToByteArray()
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    descriptor.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(
                    temporary.toPath(),
                    descriptor.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
            restrictOwnerOnlyFile(descriptor)
            syncDirectoryEntry(parent)
        } finally {
            temporary.delete()
        }
    }

    private fun persistCompletedSafely(submission: CompletedSubmission): Boolean = synchronized(persistenceLock) {
        runCatching { persistCompleted(submission) }.isSuccess
    }

    private fun persistCompleted(submission: CompletedSubmission) {
        val descriptor = completedDescriptor(submission.recordId)
        preparePrivateStorage()
        writePrivateDescriptorAtomically(
            descriptor,
            json.encodeToString(
                PersistedCompletedSubmission.serializer(),
                PersistedCompletedSubmission(
                    originAccountIdentity = submission.originAccountIdentity,
                    receipt = submission.receipt,
                    acknowledgedStatus = submission.acknowledgedStatus,
                    lastReadMaintainerMessageId = submission.lastReadMaintainerMessageId,
                ),
            ).encodeToByteArray(),
            ".completed-",
        )
    }

    private fun writePrivateDescriptorAtomically(
        descriptor: File,
        encoded: ByteArray,
        temporaryPrefix: String,
    ) {
        val parent = requireNotNull(descriptor.parentFile)
        val temporary = Files.createTempFile(parent.toPath(), temporaryPrefix, ".tmp").toFile()
        try {
            restrictOwnerOnlyFile(temporary)
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    descriptor.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(
                    temporary.toPath(),
                    descriptor.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
            restrictOwnerOnlyFile(descriptor)
            syncDirectoryEntry(parent)
        } finally {
            temporary.delete()
        }
    }

    private fun syncDirectoryEntry(directory: File) {
        directorySync(directory)
    }

    private fun deletePrivateDescriptorDurably(descriptor: File) {
        val parent = descriptor.parentFile ?: return
        Files.deleteIfExists(descriptor.toPath())
        syncDirectoryEntry(parent)
    }

    private fun restorePendingSubmission(scheduleRetry: Boolean = true): PendingSubmission? = try {
        val descriptor = pendingDescriptor()
        val descriptorAttributes = try {
            Files.readAttributes(descriptor.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            pendingDescriptorRestorePending.set(false)
            return null
        }
        require(descriptorAttributes.isRegularFile)
        require(descriptorAttributes.size() in 1L..MAX_PENDING_DESCRIPTOR_BYTES)
        val persisted = json.decodeFromString(
            PersistedPendingSubmission.serializer(),
            pendingDescriptorRead(descriptor),
        )
        require(persisted.archiveName == null || persisted.archiveName.matches(SUPPORT_TEMPORARY_FILE_PATTERN))
        require(persisted.idempotencyKey.matches(SUPPORT_IDEMPOTENCY_PATTERN))
        require(persisted.originAccountIdentity.matches(SUPPORT_ACCOUNT_IDENTITY_PATTERN))
        require(persisted.createdAtEpochMillis >= 0L)
        require(persisted.latestUploadAttemptAtEpochMillis == null || persisted.latestUploadAttemptAtEpochMillis >= 0L)
        val nowEpochMillis = System.currentTimeMillis()
        val retryNotBeforeEpochMillis = persisted.retryNotBeforeEpochMillis?.takeIf { deadline ->
            deadline <= nowEpochMillis.saturatingAdd(MAX_SUPPORT_RETRY_AFTER_MILLIS)
        }
        persisted.receipt?.let { receipt ->
            require(persisted.cancellationPending)
            validateReceipt(receipt)
        }
        val recoveryDeadlineEpochMillis = persisted.receipt
            ?.let { receipt -> Instant.parse(receipt.retentionUntil).toEpochMilli() }
            ?: if (persisted.outcomeAmbiguous || persisted.cancellationRequiresTombstone == true) {
                (persisted.latestUploadAttemptAtEpochMillis ?: persisted.createdAtEpochMillis)
                    .saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
            } else {
                persisted.createdAtEpochMillis.saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
            }
        require(persisted.cancellationPending || nowEpochMillis <= recoveryDeadlineEpochMillis)
        val archiveAgeMillis = (nowEpochMillis - persisted.createdAtEpochMillis).coerceAtLeast(0L)
        val archiveIsRetained = archiveAgeMillis <= SUPPORT_TEMPORARY_MAX_AGE_MILLIS
        val archive = persisted.archiveName?.let { archiveName ->
            val candidate = File(temporaryRoot, archiveName).absoluteFile.normalize()
            require(candidate.parentFile == temporaryRoot.absoluteFile.normalize())
            if (!archiveIsRetained) {
                deletePrivateFileOrRetry(candidate)
                null
            } else {
                val archiveAttributes = try {
                    Files.readAttributes(candidate.toPath(), BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    null
                }
                archiveAttributes?.let { attributes ->
                    require(attributes.isRegularFile)
                    require(attributes.size() in 1L..MAX_SUPPORT_ARCHIVE_BYTES)
                    restrictOwnerOnlyFile(candidate)
                    candidate
                }
            }
        }
        pendingDescriptorRestorePending.set(false)
        val restored = PendingSubmission(
            archive = archive,
            metadata = persisted.metadata,
            idempotencyKey = persisted.idempotencyKey,
            createdAtEpochMillis = persisted.createdAtEpochMillis,
            originAccountIdentity = persisted.originAccountIdentity,
            context = persisted.context,
            cancellationPending = persisted.cancellationPending,
            outcomeAmbiguous = persisted.outcomeAmbiguous,
            cancellationRequiresTombstone = persisted.cancellationRequiresTombstone
                ?: (
                    persisted.latestUploadAttemptAtEpochMillis != null ||
                        persisted.outcomeAmbiguous ||
                        persisted.receipt != null
                    ),
            latestUploadAttemptAtEpochMillis = persisted.latestUploadAttemptAtEpochMillis,
            retryNotBeforeEpochMillis = retryNotBeforeEpochMillis,
            receipt = persisted.receipt,
        )
        if (restored.cancellationPending) {
            val privateArchive = stripPrivateCancellationPayload(restored)
            persistPending(restored)
            deletePrivateFileOrRetry(privateArchive)
        }
        restored
    } catch (failure: Throwable) {
        if (failure is IOException || failure is SecurityException) {
            val retryWasNotScheduled = pendingDescriptorRestorePending.compareAndSet(false, true)
            if (scheduleRetry && retryWasNotScheduled) {
                scope.launch { retryPendingDescriptorRestoration() }
            }
            return null
        }
        pendingDescriptorRestorePending.set(false)
        if (!quarantineRejectedPendingDescriptorSafely()) {
            rejectedPendingDescriptorCleanup.set(true)
            scope.launch { retryRejectedPendingDescriptorCleanup() }
        }
        null
    }

    private fun quarantineRejectedPendingDescriptorSafely(): Boolean {
        val descriptor = pendingDescriptor()
        if (!descriptor.exists()) return true
        val quarantined = File(temporaryRoot, ".pending-rejected-${UUID.randomUUID()}.tmp")
        synchronized(persistenceLock) {
            runCatching {
                runCatching {
                    Files.move(
                        descriptor.toPath(),
                        quarantined.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.recoverCatching {
                    Files.move(descriptor.toPath(), quarantined.toPath())
                }.getOrThrow()
                restrictOwnerOnlyFile(quarantined)
                syncDirectoryEntry(temporaryRoot)
            }
        }
        if (quarantined.isFile) deletePrivateFileOrRetry(quarantined)
        return !descriptor.exists()
    }

    private suspend fun retryRejectedPendingDescriptorCleanup() {
        while (!shutdownRequested.get()) {
            delay(descriptorCleanupRetryMillis)
            if (!quarantineRejectedPendingDescriptorSafely()) continue
            rejectedPendingDescriptorCleanup.set(false)
            synchronized(lock) {
                if (storageUnavailableMessage == SUPPORT_REJECTED_PENDING_CLEANUP_MESSAGE) {
                    storageUnavailableMessage = currentRecoveryUnavailableMessage()
                    publishRecoveredStateLocked()
                }
            }
            return
        }
    }

    private suspend fun retryPendingDescriptorRestoration() {
        initialized.await()
        while (!shutdownRequested.get()) {
            delay(descriptorCleanupRetryMillis)
            val restored = restorePendingSubmission(scheduleRetry = false)
            if (pendingDescriptorRestorePending.get()) continue
            pruneTemporaryReports(restored?.archive)
            synchronized(lock) {
                pending = restored
                storageUnavailableMessage = currentRecoveryUnavailableMessage()
                publishRecoveredStateLocked()
            }
            return
        }
    }

    private suspend fun retryCompletedDescriptorRestoration() {
        initialized.await()
        while (!shutdownRequested.get()) {
            delay(descriptorCleanupRetryMillis)
            val restored = restoreCompletedSubmissions()
            if (completedDescriptorRestorePending.get()) continue
            synchronized(lock) {
                completedSubmissions = restored
                scheduleCompletedExpiryLocked()
                storageUnavailableMessage = currentRecoveryUnavailableMessage()
                publishRecoveredStateLocked()
            }
            return
        }
    }

    private fun currentRecoveryUnavailableMessage(): String? = when {
        pendingDescriptorRestorePending.get() -> SUPPORT_PENDING_RESTORE_MESSAGE
        completedDescriptorRestorePending.get() -> SUPPORT_COMPLETED_RESTORE_MESSAGE
        rejectedPendingDescriptorCleanup.get() -> SUPPORT_REJECTED_PENDING_CLEANUP_MESSAGE
        else -> null
    }

    private fun publishRecoveredStateLocked() {
        val pendingSubmission = pending
        val visibleCompleted = latestCompletedFor(activeAccountIdentity)
        publishStateLocked(
            storageUnavailableMessage?.let { unavailableMessage ->
                SupportDiagnosticsSubmissionState.Unsupported(unavailableMessage)
            } ?: pendingSubmission?.let { submission ->
                SupportDiagnosticsSubmissionState.RetryableFailure(
                    if (submission.cancellationPending) {
                        "Cancellation was interrupted. Retry safely to obtain terminal confirmation from Obiente Support."
                    } else if (submission.archive == null) {
                        "Private report preparation was interrupted. You can retry it safely."
                    } else {
                        "A private support submission was interrupted. You can retry it safely."
                    },
                    outcomeAmbiguous = submission.outcomeAmbiguous,
                )
            } ?: visibleCompleted?.let { submittedStateFor(it.originAccountIdentity) }
                ?: idleStateForActiveAccountLocked(),
            pendingSubmission?.originAccountIdentity ?: visibleCompleted?.originAccountIdentity,
        )
    }

    private fun pendingDescriptor(): File = File(temporaryRoot, SUPPORT_PENDING_DESCRIPTOR)

    private fun restoreCompletedSubmissions(): List<CompletedSubmission> {
        completedDescriptorRestorePending.set(false)
        val descriptors = try {
            Files.newDirectoryStream(temporaryRoot.toPath()).use { entries ->
                entries.map { path -> path.toFile() }
                    .filter { descriptor -> descriptor.name.matches(SUPPORT_COMPLETED_FILE_PATTERN) }
            }
        } catch (_: IOException) {
            completedDescriptorRestorePending.set(true)
            return emptyList()
        } catch (_: SecurityException) {
            completedDescriptorRestorePending.set(true)
            return emptyList()
        }
        return descriptors.mapNotNull(::restoreCompletedSubmission)
    }

    private fun restoreCompletedSubmission(descriptor: File): CompletedSubmission? = try {
        val descriptorAttributes = Files.readAttributes(descriptor.toPath(), BasicFileAttributes::class.java)
        require(descriptorAttributes.isRegularFile)
        require(descriptorAttributes.size() in 1L..MAX_COMPLETED_DESCRIPTOR_BYTES)
        val recordId = requireNotNull(SUPPORT_COMPLETED_FILE_PATTERN.matchEntire(descriptor.name))
            .groupValues[1]
        val persisted = json.decodeFromString(
            PersistedCompletedSubmission.serializer(),
            completedDescriptorRead(descriptor),
        )
        require(persisted.originAccountIdentity.matches(SUPPORT_ACCOUNT_IDENTITY_PATTERN))
        validateReceipt(persisted.receipt)
        require(System.currentTimeMillis() <= Instant.parse(persisted.receipt.retentionUntil).toEpochMilli())
        require(persisted.acknowledgedStatus in SUPPORT_REPORT_STATUSES)
        require(
            persisted.lastReadMaintainerMessageId == null ||
                persisted.lastReadMaintainerMessageId.matches(SUPPORT_COMPLETED_RECORD_ID_PATTERN),
        )
        CompletedSubmission(
            recordId = recordId,
            originAccountIdentity = persisted.originAccountIdentity,
            receipt = persisted.receipt,
            acknowledgedStatus = persisted.acknowledgedStatus,
            lastReadMaintainerMessageId = persisted.lastReadMaintainerMessageId,
        )
    } catch (_: IOException) {
        completedDescriptorRestorePending.set(true)
        null
    } catch (_: SecurityException) {
        completedDescriptorRestorePending.set(true)
        null
    } catch (_: Throwable) {
        deleteCompletedDescriptorOrRetry(descriptor)
        null
    }

    private fun deleteCompletedDescriptorOrRetry(descriptor: File) {
        if (deleteCompletedDescriptorSafely(descriptor)) return
        scope.launch { deleteCompletedDescriptorsWithRetry(listOf(descriptor)) }
    }

    private fun deleteCompletedDescriptorSafely(descriptor: File): Boolean = synchronized(persistenceLock) {
        runCatching { deletePrivateDescriptorDurably(descriptor) }.isSuccess
    }

    private fun completedDescriptor(recordId: String): File {
        require(recordId.matches(SUPPORT_COMPLETED_RECORD_ID_PATTERN))
        return File(temporaryRoot, "completed-$recordId.json")
    }

    private data class PendingSubmission(
        var archive: File?,
        var metadata: SupportIntakeMetadata,
        val idempotencyKey: String,
        val createdAtEpochMillis: Long,
        val originAccountIdentity: String,
        var context: PreparedSupportSubmissionContext,
        var cancellationPending: Boolean = false,
        var outcomeAmbiguous: Boolean = false,
        var cancellationRequiresTombstone: Boolean = false,
        var latestUploadAttemptAtEpochMillis: Long? = null,
        var retryNotBeforeEpochMillis: Long? = null,
        var receipt: SupportIntakeReceipt? = null,
    ) {
        fun belongsTo(accountIdentity: String?): Boolean = originAccountIdentity == accountIdentity

        fun recoveryExpired(nowEpochMillis: Long): Boolean {
            val deadline = receipt
                ?.let { value -> runCatching { Instant.parse(value.retentionUntil).toEpochMilli() }.getOrNull() }
                ?: if (outcomeAmbiguous || cancellationRequiresTombstone) {
                    (latestUploadAttemptAtEpochMillis ?: createdAtEpochMillis)
                        .saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
                } else {
                    createdAtEpochMillis.saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
                }
            return nowEpochMillis > deadline
        }
    }

    private data class CompletedSubmission(
        val recordId: String,
        val originAccountIdentity: String,
        val receipt: SupportIntakeReceipt,
        var acknowledgedStatus: String = receipt.status,
        var lastReadMaintainerMessageId: String? = null,
        var conversation: SupportPrivateStatus? = null,
        var conversationLoading: Boolean = false,
        var conversationError: String? = null,
    ) {
        val retentionUntilEpochMillis: Long
            get() = Instant.parse(receipt.retentionUntil).toEpochMilli()

        fun isRetained(nowEpochMillis: Long): Boolean = nowEpochMillis <= retentionUntilEpochMillis
    }

    @Serializable
    private data class SupportIntakeProblem(
        val contractVersion: Int,
        val code: String,
        val message: String,
    )

    @Serializable
    private data class PersistedPendingSubmission(
        val archiveName: String?,
        val metadata: SupportIntakeMetadata,
        val idempotencyKey: String,
        val createdAtEpochMillis: Long,
        val originAccountIdentity: String,
        val context: PreparedSupportSubmissionContext,
        val cancellationPending: Boolean = false,
        val outcomeAmbiguous: Boolean = true,
        val cancellationRequiresTombstone: Boolean? = null,
        val latestUploadAttemptAtEpochMillis: Long? = null,
        // Read descriptors written by early PR #386 builds, but never use wall time to confirm
        // cancellation. Only the server's idempotency-key tombstone is terminal.
        val cancellationRequestedAtEpochMillis: Long? = null,
        val retryNotBeforeEpochMillis: Long? = null,
        val receipt: SupportIntakeReceipt? = null,
    )

    @Serializable
    private data class PersistedCompletedSubmission(
        val originAccountIdentity: String,
        val receipt: SupportIntakeReceipt,
        val acknowledgedStatus: String = receipt.status,
        val lastReadMaintainerMessageId: String? = null,
    )

    @Serializable
    private data class SupportConversationMessageInput(val body: String)

    @Serializable
    private data class SupportPrivateStatus(
        val contractVersion: Int,
        val supportCode: String,
        val productId: String,
        val requestType: String,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
        val retentionUntil: String,
        val messages: List<SupportPrivateMessage>,
    )

    @Serializable
    private data class SupportPrivateMessage(
        val id: String,
        val author: String,
        val body: String,
        val createdAt: String,
    )

    private fun publishState(
        next: SupportDiagnosticsSubmissionState,
        accountIdentity: String? = null,
    ) {
        synchronized(lock) {
            publishStateLocked(next, accountIdentity ?: pending?.originAccountIdentity ?: activeAccountIdentity)
        }
    }

    private fun publishStateLocked(
        next: SupportDiagnosticsSubmissionState,
        accountIdentity: String? = pending?.originAccountIdentity,
    ) {
        if (pruneExpiredCompletedLocked()) {
            scheduleCompletedExpiryLocked()
        }
        actualState = next
        actualStateAccountIdentity = accountIdentity
        state.value = if (accountIdentity != null && accountIdentity != activeAccountIdentity) {
            if (pending?.originAccountIdentity == accountIdentity) {
                SupportDiagnosticsSubmissionState.BlockedByAnotherAccount(
                    "A pending private report belongs to another signed-in account. Switch back to finish or discard it.",
                )
            } else if (operationActive.get()) {
                blockedByAnotherAccountOperation()
            } else {
                idleStateForActiveAccountLocked()
            }
        } else {
            next
        }
    }

    private fun refreshVisibleStateLocked() {
        val pendingSubmission = pending
        when {
            actualState is SupportDiagnosticsSubmissionState.Initializing -> state.value = actualState
            storageUnavailableMessage != null -> state.value = SupportDiagnosticsSubmissionState.Unsupported(
                requireNotNull(storageUnavailableMessage),
            )
            pendingSubmission != null -> publishStateLocked(actualState, pendingSubmission.originAccountIdentity)
            operationActive.get() && actualStateAccountIdentity != activeAccountIdentity -> {
                state.value = blockedByAnotherAccountOperation()
            }
            activeAccountIdentity == null -> state.value = SupportDiagnosticsSubmissionState.AccountRequired
            actualStateAccountIdentity == activeAccountIdentity -> state.value = actualState
            else -> state.value = latestCompletedFor(activeAccountIdentity)
                ?.let { submittedStateFor(it.originAccountIdentity) }
                ?: idleStateForActiveAccountLocked()
        }
    }

    private fun blockedByAnotherAccountOperation() =
        SupportDiagnosticsSubmissionState.BlockedByAnotherAccount(
            "A private support report is being prepared for another signed-in account. Wait for it to finish or switch back.",
        )

    private fun idleStateForActiveAccountLocked(): SupportDiagnosticsSubmissionState =
        if (activeAccountIdentity == null) {
            SupportDiagnosticsSubmissionState.AccountRequired
        } else {
            SupportDiagnosticsSubmissionState.Idle
        }

    private fun latestCompletedFor(accountIdentity: String?): CompletedSubmission? =
        completedSubmissions.filter {
            it.originAccountIdentity == accountIdentity && it.isRetained(System.currentTimeMillis())
        }
            .maxByOrNull { Instant.parse(it.receipt.createdAt) }

    private fun submittedStateFor(accountIdentity: String): SupportDiagnosticsSubmissionState.Submitted =
        SupportDiagnosticsSubmissionState.Submitted(
            completedSubmissions
                .filter {
                    it.originAccountIdentity == accountIdentity && it.isRetained(System.currentTimeMillis())
                }
                .sortedWith(
                    compareByDescending<CompletedSubmission> { Instant.parse(it.receipt.createdAt) }
                        .thenByDescending(CompletedSubmission::recordId),
                )
                .map { completed ->
                    val conversation = completed.conversation
                    val maintainerMessages = conversation?.messages.orEmpty()
                        .filter { message -> message.author == "maintainer" }
                    val lastReadIndex = completed.lastReadMaintainerMessageId?.let { readId ->
                        maintainerMessages.indexOfFirst { message -> message.id == readId }
                    } ?: -1
                    SupportDiagnosticsSubmissionState.SubmittedReport(
                        recordId = completed.recordId,
                        supportCode = completed.receipt.supportCode,
                        createdAt = completed.receipt.createdAt,
                        retentionUntil = completed.receipt.retentionUntil,
                        status = conversation?.status ?: completed.receipt.status,
                        updatedAt = conversation?.updatedAt,
                        messages = conversation?.messages.orEmpty().map { message ->
                            SupportDiagnosticsMessage(
                                id = message.id,
                                author = if (message.author == "maintainer") {
                                    SupportDiagnosticsMessageAuthor.Maintainer
                                } else {
                                    SupportDiagnosticsMessageAuthor.Reporter
                                },
                                body = message.body,
                                createdAt = message.createdAt,
                            )
                        },
                        unreadMaintainerMessages = if (maintainerMessages.isEmpty()) {
                            0
                        } else if (lastReadIndex < 0) {
                            maintainerMessages.size
                        } else {
                            maintainerMessages.lastIndex - lastReadIndex
                        },
                        statusChanged = conversation?.status?.let { status ->
                            status != completed.acknowledgedStatus
                        } ?: false,
                        conversationLoading = completed.conversationLoading,
                        conversationError = completed.conversationError,
                    )
                },
        )

    private fun scheduleCompletedExpiryLocked() {
        completedExpiryJob?.cancel()
        val nextExpiry = completedSubmissions.minOfOrNull(CompletedSubmission::retentionUntilEpochMillis)
        if (nextExpiry == null) {
            completedExpiryJob = null
            return
        }
        val now = System.currentTimeMillis()
        val waitMillis = if (nextExpiry <= now) {
            1L
        } else {
            (nextExpiry - now).takeIf { it > 0L } ?: Long.MAX_VALUE
        }
        completedExpiryJob = scope.launch {
            delay(waitMillis)
            synchronized(lock) {
                completedExpiryJob = null
                pruneExpiredCompletedLocked()
                refreshVisibleStateLocked()
                scheduleCompletedExpiryLocked()
            }
        }
    }

    private fun pruneExpiredCompletedLocked(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val expired = completedSubmissions.filterNot { it.isRetained(nowEpochMillis) }
        if (expired.isEmpty()) return false
        completedSubmissions = completedSubmissions.filter { it.isRetained(nowEpochMillis) }
        if (actualState is SupportDiagnosticsSubmissionState.Submitted) {
            actualState = latestCompletedFor(actualStateAccountIdentity)
                ?.let { submittedStateFor(it.originAccountIdentity) }
                ?: SupportDiagnosticsSubmissionState.Idle
        }
        scope.launch {
            deleteCompletedDescriptorsWithRetry(expired.map { submission -> completedDescriptor(submission.recordId) })
        }
        return true
    }

    private suspend fun deleteCompletedDescriptorsWithRetry(descriptors: List<File>) {
        var remaining = descriptors
        while (remaining.isNotEmpty()) {
            remaining = remaining.filterNot(::deleteCompletedDescriptorSafely)
            if (remaining.isNotEmpty()) delay(descriptorCleanupRetryMillis)
        }
    }
}

private fun syncPosixDirectoryEntry(directory: File) {
    if (Files.getFileAttributeView(directory.toPath(), PosixFileAttributeView::class.java) == null) return
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
}

private fun Long.saturatingAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val forwarding = object : okio.ForwardingSink(sink) {
            var uploaded = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                uploaded += byteCount
                onProgress(uploaded, total)
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

internal class OneShotSupportMessageRequestBody(
    private val content: ByteArray,
) : RequestBody() {
    override fun contentType() = SUPPORT_METADATA_MEDIA_TYPE
    override fun contentLength(): Long = content.size.toLong()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) { sink.write(content) }
}

private fun secureIdempotencyKey(): String {
    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun Response.readBoundedText(): String {
    val source = body.source()
    val buffer = Buffer()
    val limit = MAX_SUPPORT_INTAKE_RESPONSE_BYTES.toLong() + 1L
    while (buffer.size < limit) {
        val read = source.read(buffer, minOf(8_192L, limit - buffer.size))
        if (read == -1L) break
    }
    if (buffer.size > MAX_SUPPORT_INTAKE_RESPONSE_BYTES) {
        throw IOException("Obiente Support returned an oversized response.")
    }
    return buffer.readString(Charsets.UTF_8)
}

private fun Response.retryNotBeforeEpochMillis(nowEpochMillis: Long = System.currentTimeMillis()): Long? {
    val value = header("Retry-After")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val requestedDelayMillis = value.toLongOrNull()?.let { seconds ->
        seconds.coerceAtLeast(0L).coerceAtMost(MAX_SUPPORT_RETRY_AFTER_SECONDS) * 1_000L
    } ?: runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowEpochMillis)
            .coerceAtLeast(0L)
            .coerceAtMost(MAX_SUPPORT_RETRY_AFTER_MILLIS)
    }.getOrNull()
    return requestedDelayMillis?.let { nowEpochMillis + it }
}

private fun String.filterSupportMetadata(maximumBytes: Int): String =
    filterNot(Char::isISOControl).trim().takeUtf8Bytes(maximumBytes)

private fun String?.toSupportIntakeDescription(): String {
    val sanitized = orEmpty().takeUtf8Bytes(MAX_SUPPORT_INTAKE_DESCRIPTION_BYTES)
    if (sanitized.isBlank()) return "The diagnostic report was submitted without additional reproduction steps."
    return if (sanitized.toByteArray(StandardCharsets.UTF_8).size < MIN_SUPPORT_INTAKE_DESCRIPTION_BYTES) {
        "User note: $sanitized"
    } else {
        sanitized
    }
}

private fun String.takeUtf8Bytes(maximumBytes: Int): String {
    require(maximumBytes >= 0)
    if (toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) return this
    val bounded = StringBuilder(length)
    var byteCount = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val encoded = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
        if (byteCount + encoded.size > maximumBytes) break
        bounded.appendCodePoint(codePoint)
        byteCount += encoded.size
        index += Character.charCount(codePoint)
    }
    return bounded.toString()
}

private val SUPPORT_METADATA_MEDIA_TYPE = "application/json".toMediaType()
private val SUPPORT_ARCHIVE_MEDIA_TYPE = "application/zip".toMediaType()
private val SUPPORT_CODE_PATTERN = Regex("OBI-[A-HJ-KM-NP-Z2-9]{5}-[A-HJ-KM-NP-Z2-9]{5}")
private val SUPPORT_RECEIPT_STATUS_PATTERN = Regex("[a-z][a-z_]{1,31}")
private val SUPPORT_STATUS_PATH_PATTERN = Regex("/r/[A-Za-z0-9_-]{43}")
private val SUPPORT_TEMPORARY_FILE_PATTERN = Regex("support-[0-9a-f-]{36}\\.zip")
private val SUPPORT_PENDING_TEMPORARY_FILE_PATTERN = Regex("\\.(?:pending|completed)-[A-Za-z0-9._-]+\\.tmp")
private val SUPPORT_ARCHIVE_TEMPORARY_FILE_PATTERN =
    Regex("\\.support-[0-9a-f-]{36}\\.zip\\.[A-Za-z0-9._-]+\\.tmp")
private val SUPPORT_COMPLETED_RECORD_ID_PATTERN =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val SUPPORT_COMPLETED_FILE_PATTERN = Regex("completed-(${SUPPORT_COMPLETED_RECORD_ID_PATTERN.pattern})\\.json")
private val SUPPORT_IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private val SUPPORT_ACCOUNT_IDENTITY_PATTERN = Regex("[0-9a-f]{32}(?:[0-9a-f]{32})?")
private val RETRYABLE_CLIENT_STATUS_CODES = setOf(425, 429)
private val TERMINAL_DELETION_STATUS_CODES = setOf(200, 204)
private const val SUPPORT_SUBMISSION_CANCELLED_CODE = "submission_cancelled"
private const val MAX_SUPPORT_INTAKE_MESSAGE_LENGTH = 240
private const val MAX_SUPPORT_INTAKE_RESPONSE_BYTES = 64 * 1024
private const val MAX_SUPPORT_CONVERSATION_MESSAGE_BYTES = 8 * 1024
private const val MAX_SUPPORT_CONVERSATION_MESSAGES = 1_000
private const val MAX_SUPPORT_INTAKE_DESCRIPTION_BYTES = 8_000
private const val MIN_SUPPORT_INTAKE_DESCRIPTION_BYTES = 10
private const val MAX_PENDING_DESCRIPTOR_BYTES = 4L * 1024L * 1024L
private const val MAX_COMPLETED_DESCRIPTOR_BYTES = 64L * 1024L
private const val MAX_SUPPORT_ARCHIVE_BYTES = 4L * 1024L * 1024L
private const val SUPPORT_PENDING_DESCRIPTOR = "pending.json"
private const val SUPPORT_TEMPORARY_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private const val SUPPORT_RECOVERY_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val SUPPORT_SERVER_RETENTION_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val SUPPORT_RECEIPT_CLOCK_SKEW_MILLIS = 5L * 60L * 1_000L
private const val SUPPORT_DESCRIPTOR_DELETE_RETRY_MILLIS = 60L * 1_000L
private const val SUPPORT_PENDING_RESTORE_MESSAGE =
    "Private support report recovery is temporarily unavailable. The app will retry automatically."
private const val SUPPORT_COMPLETED_RESTORE_MESSAGE =
    "Submitted support report recovery is temporarily unavailable. The app will retry automatically."
private const val MAX_SUPPORT_RETRY_AFTER_SECONDS = 5L * 60L
private const val MAX_SUPPORT_RETRY_AFTER_MILLIS = MAX_SUPPORT_RETRY_AFTER_SECONDS * 1_000L
private const val READ_ONLY_SUPPORT_MESSAGE =
    "Private support uploads are unavailable while the shared read-only audit session is active."
private const val SUPPORT_STORAGE_UNAVAILABLE_MESSAGE =
    "Private support submission storage is unavailable on this device. " +
        "Check available storage and app permissions, then restart the app."
private const val SUPPORT_REJECTED_PENDING_CLEANUP_MESSAGE =
    "An invalid private support recovery record is still being removed. Try sending again shortly."
private val SUPPORT_REPORT_STATUSES = setOf(
    "new",
    "needs_information",
    "accepted",
    "duplicate",
    "resolved",
    "rejected",
)
