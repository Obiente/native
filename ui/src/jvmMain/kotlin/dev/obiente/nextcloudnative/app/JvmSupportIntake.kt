package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val lock = Any()
    private val persistenceLock = Any()
    private var activeAccountIdentity: String? = null
    private var actualState: SupportDiagnosticsSubmissionState = SupportDiagnosticsSubmissionState.Initializing
    private var actualStateAccountIdentity: String? = null
    private var pending: PendingSubmission? = null

    init {
        scope.launch {
            try {
                val storageReady = runCatching { preparePrivateStorage() }.isSuccess
                val restored = if (storageReady) restorePendingSubmission() else null
                val restoredCompleted = if (storageReady) restoreCompletedSubmission() else null
                if (storageReady) pruneTemporaryReports(restored?.archive)
                synchronized(lock) {
                    pending = restored
                    publishStateLocked(
                        restored?.let {
                            SupportDiagnosticsSubmissionState.RetryableFailure(
                                if (restored.cancellationPending) {
                                    "Cancellation was interrupted. Retry safely to reconcile and delete the private report."
                                } else if (restored.archive == null) {
                                    "Private report preparation was interrupted. You can retry it safely."
                                } else {
                                    "A private support submission was interrupted. You can retry it safely."
                                },
                                outcomeAmbiguous = restored.outcomeAmbiguous,
                            )
                        } ?: restoredCompleted?.toSubmissionState() ?: SupportDiagnosticsSubmissionState.Idle,
                        restored?.originAccountIdentity ?: restoredCompleted?.originAccountIdentity,
                    )
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
            publishStateLocked(actualState, actualStateAccountIdentity)
        }
    }

    internal suspend fun awaitInitialization() = initialized.await()

    suspend fun submit(
        reproductionSteps: String,
        channel: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ) = withContext(Dispatchers.IO) {
        awaitInitialization()
        if (!operationActive.compareAndSet(false, true)) return@withContext
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
                publishState(SupportDiagnosticsSubmissionState.Rejected(
                    "Sign in before sending a private support report.",
                ))
                return@withContext
            }
            cancellationRequested.set(false)
            val context = try {
                preparePrivateStorage()
                diagnostics.prepareSubmissionContextForAccountIdentity(
                    reproductionSteps,
                    featureState,
                    originAccountIdentity,
                )
            } catch (cancellation: CancellationException) {
                publishState(SupportDiagnosticsSubmissionState.Cancelled)
                throw cancellation
            } catch (failure: Throwable) {
                publishState(SupportDiagnosticsSubmissionState.Rejected(
                    failure.message?.take(MAX_SUPPORT_INTAKE_MESSAGE_LENGTH)
                        ?: "The private diagnostic report could not be prepared.",
                ))
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
                createdAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                originAccountIdentity = originAccountIdentity,
                cancellationPending = cancellationRequested.get(),
                context = context,
            )
            synchronized(lock) { pending = submission }
            if (!persistPendingSafely(submission)) {
                finishRejected(submission, "The private support submission could not be retained safely on this device.")
                return@withContext
            }
            if (!packageSubmission(submission)) return@withContext
            upload(submission)
        } finally {
            operationActive.set(false)
        }
    }

    suspend fun retry() = withContext(Dispatchers.IO) {
        awaitInitialization()
        if (!operationActive.compareAndSet(false, true)) return@withContext
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
            if (submission.recoveryExpired(System.currentTimeMillis())) {
                finishRejected(submission, "The private report recovery capability expired and was removed from this device.")
                return@withContext
            }
            if (submission.cancellationPending) {
                cancellationRequested.set(true)
                val receipt = submission.receipt
                if (receipt == null) {
                    reconcileAfterAmbiguousResult(
                        submission,
                        IOException("Cancellation still needs to be reconciled."),
                    )
                } else {
                    deleteCancelledReceipt(submission, receipt)
                }
                return@withContext
            }
            val waitMillis = submission.retryNotBeforeEpochMillis?.minus(System.currentTimeMillis()) ?: 0L
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
            cancellationRequested.set(false)
            submission.retryNotBeforeEpochMillis = null
            if (!persistPendingSafely(submission)) {
                finishRejected(submission, "The private support submission could not be retained safely on this device.")
                return@withContext
            }
            if (submission.archive == null && !packageSubmission(submission)) return@withContext
            if (submission.archive?.isFile != true) {
                finishRejected(submission, "The pending private report archive is unavailable.")
                return@withContext
            }
            upload(submission)
        } finally {
            operationActive.set(false)
        }
    }

    suspend fun cancel(): Boolean {
        awaitInitialization()
        // Serialize the terminal receipt decision with publication of the user's intent. If receipt
        // completion wins and clears pending first, cancellation is correctly reported as too late.
        val accepted = synchronized(lock) {
            val submission = pending
            if (submission == null || !submission.belongsTo(activeAccountIdentity)) {
                false
            } else {
                cancellationRequested.set(true)
                true
            }
        }
        if (!accepted) return false
        return withContext(Dispatchers.IO) { cancelAfterIntentPublished() }
    }

    private fun cancelAfterIntentPublished(): Boolean {
        val submission = synchronized(lock) { pending }
        if (submission != null) {
            if (!submission.outcomeAmbiguous && activeCall.get() == null) {
                finishCancelled(submission)
                return true
            }
            submission.cancellationPending = true
            submission.outcomeAmbiguous = true
            if (!persistPendingSafely(submission)) {
                publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                    "Cancellation could not be stored safely. Keep the app open and retry to reconcile the private report.",
                    outcomeAmbiguous = true,
                ))
                return false
            }
        }
        val call = activeCall.getAndSet(null)
        if (call != null) {
            call.cancel()
            return true
        }
        if (submission != null) {
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                "Cancellation could not be confirmed. Retry safely to reconcile and delete the private report.",
                outcomeAmbiguous = true,
            ))
            return true
        }
        cancellationRequested.compareAndSet(true, false)
        return false
    }

    override fun close() {
        shutdownRequested.set(true)
        activeCall.getAndSet(null)?.cancel()
        scope.cancel()
    }

    private suspend fun packageSubmission(submission: PendingSubmission): Boolean {
        if (cancellationRequested.get()) {
            finishCancelled(submission)
            return false
        }
        publishState(SupportDiagnosticsSubmissionState.Packaging)
        val destination = File(temporaryRoot, "support-${UUID.randomUUID()}.zip")
        val prepared = try {
            diagnostics.writeBundleForSubmission(destination, submission.context)
        } catch (cancellation: CancellationException) {
            retainForRetry(
                submission,
                "Private report preparation was interrupted. You can retry it safely.",
                ambiguous = false,
            )
            throw cancellation
        } catch (_: Throwable) {
            retainForRetry(
                submission,
                "The private diagnostic report could not be prepared. You can retry safely.",
                ambiguous = false,
            )
            return false
        }
        try {
            restrictOwnerOnlyFile(prepared.archive)
        } catch (_: Throwable) {
            prepared.archive.delete()
            retainForRetry(
                submission,
                "The private report could not be protected on this device. You can retry safely.",
                ambiguous = false,
            )
            return false
        }
        if (cancellationRequested.get() || synchronized(lock) { pending !== submission }) {
            prepared.archive.delete()
            return false
        }
        submission.archive = prepared.archive
        if (!persistPendingSafely(submission)) {
            finishRejected(submission, "The private support submission could not be retained safely on this device.")
            return false
        }
        if (cancellationRequested.get()) {
            finishCancelled(submission)
            return false
        }
        return true
    }

    private fun upload(submission: PendingSubmission) {
        if (cancellationRequested.get()) {
            finishCancelled(submission)
            return
        }
        submission.outcomeAmbiguous = true
        if (!persistPendingSafely(submission)) {
            finishRejected(submission, "The private support submission could not be retained safely on this device.")
            return
        }
        val archive = requireNotNull(submission.archive) { "The private support archive has not been prepared." }
        require(archive.isFile && archive.length() in 1L..MAX_SUPPORT_ARCHIVE_BYTES)
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
        publishState(SupportDiagnosticsSubmissionState.Uploading(0f))
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                val responseText = response.readBoundedText()
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
                    response.code in 400..499 -> finishRejected(submission, decodeProblem(responseText))
                    else -> retainForRetry(
                        submission,
                        "Obiente Support is temporarily unavailable.",
                        ambiguous = response.code in 300..399 || response.code in 500..599,
                        retryNotBeforeEpochMillis = response.retryNotBeforeEpochMillis(),
                    )
                }
            }
        } catch (failure: IOException) {
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

    private fun reconcileAfterAmbiguousResult(submission: PendingSubmission, uploadFailure: IOException) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/receipts").build())
            .header("Accept", "application/json")
            .header("Idempotency-Key", submission.idempotencyKey)
            .get()
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                val responseText = response.readBoundedText()
                when {
                    response.isSuccessful -> finishReceived(submission, decodeReceipt(responseText))
                    response.code == 404 && cancellationRequested.get() -> finishCancelled(submission)
                    response.code == 404 -> retainForRetry(submission, "The upload did not complete. You can retry it safely.", false)
                    else -> retainForRetry(
                        submission,
                        "The upload result is uncertain. Check your connection before retrying.",
                        true,
                    )
                }
            }
        } catch (_: IOException) {
            retainForRetry(
                submission,
                if (cancellationRequested.get()) {
                    "Cancellation could not be confirmed. Reconcile the private submission before retrying."
                } else uploadFailure.message?.filterSupportMetadata(MAX_SUPPORT_INTAKE_MESSAGE_LENGTH)
                    ?.takeIf(String::isNotBlank)
                    ?: "The upload result is uncertain. Check your connection before retrying.",
                true,
            )
        } catch (_: IllegalArgumentException) {
            retainForRetry(submission, "Obiente Support returned an invalid receipt.", true)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun finishReceived(submission: PendingSubmission, receipt: SupportIntakeReceipt) {
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
            deleteCancelledReceipt(submission, receipt)
        } else {
            finishSubmitted(submission, receipt)
        }
    }

    private fun deleteCancelledReceipt(submission: PendingSubmission, receipt: SupportIntakeReceipt) {
        val statusUrl = validateReceipt(receipt)
        val deletionUrl = receipt.deletionUrl.toHttpUrl()
        require(
            deletionUrl.scheme == statusUrl.scheme &&
                deletionUrl.host == statusUrl.host &&
                deletionUrl.port == statusUrl.port &&
                deletionUrl.encodedPath == statusUrl.encodedPath &&
                deletionUrl.encodedQuery == null &&
                deletionUrl.fragment == null,
        )
        submission.cancellationPending = true
        submission.outcomeAmbiguous = true
        submission.receipt = receipt
        if (!persistPendingSafely(submission)) {
            finishSubmitted(submission, receipt)
            return
        }
        val capability = statusUrl.pathSegments.last()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability).build())
            .header("Accept", "application/json")
            .delete()
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                response.readBoundedText()
                when {
                    response.code in TERMINAL_DELETION_STATUS_CODES || response.code == 404 ->
                        finishCancelled(submission)
                    response.isSuccessful -> verifyDeletionAfterAccepted(
                        submission,
                        receipt,
                        capability,
                    )
                    else -> retainCancellationForRetry(
                        submission,
                        receipt,
                        "Deletion could not be confirmed. Retry safely to delete the private report.",
                    )
                }
            }
        } catch (_: IOException) {
            retainCancellationForRetry(
                submission,
                receipt,
                "Deletion could not be confirmed. Check your connection, then retry safely.",
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun verifyDeletionAfterAccepted(
        submission: PendingSubmission,
        receipt: SupportIntakeReceipt,
        capability: String,
    ) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/reports").addPathSegment(capability).build())
            .header("Accept", "application/json")
            .get()
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
                response.readBoundedText()
                if (response.code == 404) {
                    finishCancelled(submission)
                } else {
                    retainCancellationForRetry(
                        submission,
                        receipt,
                        "Deletion is still being processed. Retry safely to verify the private report was removed.",
                    )
                }
            }
        } catch (_: IOException) {
            retainCancellationForRetry(
                submission,
                receipt,
                "Deletion was accepted but could not be verified. Check your connection, then retry safely.",
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun finishSubmitted(submission: PendingSubmission, receipt: SupportIntakeReceipt) {
        validateReceipt(receipt)
        val completedSubmission = CompletedSubmission(submission.originAccountIdentity, receipt)
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
        finishTerminal(submission)
        publishState(completedSubmission.toSubmissionState(), submission.originAccountIdentity)
    }

    private fun validateReceipt(receipt: SupportIntakeReceipt): okhttp3.HttpUrl {
        require(receipt.contractVersion == SUPPORT_INTAKE_CONTRACT_VERSION)
        require(receipt.supportCode.matches(SUPPORT_CODE_PATTERN))
        require(receipt.status.matches(SUPPORT_RECEIPT_STATUS_PATTERN))
        val createdAt = runCatching { Instant.parse(receipt.createdAt) }
            .getOrElse { throw IllegalArgumentException("Invalid receipt timestamp.", it) }
        val retentionUntil = runCatching { Instant.parse(receipt.retentionUntil) }
            .getOrElse { throw IllegalArgumentException("Invalid receipt timestamp.", it) }
        require(!retentionUntil.isBefore(createdAt))
        val statusUrl = receipt.statusUrl.toHttpUrl()
        require(
            statusUrl.scheme == baseUrl.scheme &&
                statusUrl.host == baseUrl.host &&
                statusUrl.port == baseUrl.port &&
                statusUrl.encodedPath.matches(SUPPORT_STATUS_PATH_PATTERN) &&
                statusUrl.encodedQuery == null &&
                statusUrl.fragment == null,
        )
        return statusUrl
    }

    private fun finishTerminal(submission: PendingSubmission) {
        synchronized(lock) {
            if (pending === submission) pending = null
        }
        submission.archive?.delete()
        synchronized(persistenceLock) {
            pendingDescriptor().delete()
        }
    }

    private fun finishRejected(submission: PendingSubmission, message: String) {
        finishTerminal(submission)
        publishState(SupportDiagnosticsSubmissionState.Rejected(message), submission.originAccountIdentity)
    }

    private fun finishCancelled(submission: PendingSubmission) {
        finishTerminal(submission)
        publishState(SupportDiagnosticsSubmissionState.Cancelled, submission.originAccountIdentity)
    }

    private fun retainForRetry(
        submission: PendingSubmission,
        message: String,
        ambiguous: Boolean,
        retryNotBeforeEpochMillis: Long? = null,
    ) {
        submission.outcomeAmbiguous = ambiguous
        submission.retryNotBeforeEpochMillis = retryNotBeforeEpochMillis
        synchronized(lock) { pending = submission }
        if (persistPendingSafely(submission)) {
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(message, ambiguous))
        } else {
            // Atomic replacement keeps the descriptor from immediately before the request. That
            // record retains the idempotency key and conservatively requires reconciliation.
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                "The updated retry state could not be stored. Keep the app open and retry safely to reconcile the report.",
                outcomeAmbiguous = true,
            ))
        }
    }

    private fun retainCancellationForRetry(
        submission: PendingSubmission,
        receipt: SupportIntakeReceipt,
        message: String,
    ) {
        submission.cancellationPending = true
        submission.outcomeAmbiguous = true
        submission.receipt = receipt
        synchronized(lock) { pending = submission }
        if (persistPendingSafely(submission)) {
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(message, outcomeAmbiguous = true))
        } else {
            // The receipt was persisted before deletion began. Atomic replacement leaves that last
            // valid recovery record in place when this newer retry-state write fails.
            publishState(SupportDiagnosticsSubmissionState.RetryableFailure(
                "Deletion was not confirmed and its updated retry state could not be stored. Keep the app open and retry.",
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

    private fun pruneTemporaryReports(retainedArchive: File?) {
        val cutoff = System.currentTimeMillis() - SUPPORT_TEMPORARY_MAX_AGE_MILLIS
        temporaryRoot.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.name.matches(SUPPORT_TEMPORARY_FILE_PATTERN) &&
                    (file != retainedArchive || file.lastModified() < cutoff)
            }
            .forEach(File::delete)
        temporaryRoot.listFiles().orEmpty()
            .filter { file -> file.isFile && file.name.matches(SUPPORT_PENDING_TEMPORARY_FILE_PATTERN) }
            .forEach(File::delete)
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
        } finally {
            temporary.delete()
        }
    }

    private fun persistCompletedSafely(submission: CompletedSubmission): Boolean = synchronized(persistenceLock) {
        runCatching { persistCompleted(submission) }.isSuccess
    }

    private fun persistCompleted(submission: CompletedSubmission) {
        val descriptor = completedDescriptor()
        preparePrivateStorage()
        writePrivateDescriptorAtomically(
            descriptor,
            json.encodeToString(
                PersistedCompletedSubmission.serializer(),
                PersistedCompletedSubmission(submission.originAccountIdentity, submission.receipt),
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
        } finally {
            temporary.delete()
        }
    }

    private fun restorePendingSubmission(): PendingSubmission? = runCatching {
        val descriptor = pendingDescriptor()
        if (!descriptor.isFile) return@runCatching null
        require(descriptor.length() in 1L..MAX_PENDING_DESCRIPTOR_BYTES)
        val persisted = json.decodeFromString(
            PersistedPendingSubmission.serializer(),
            descriptor.readText(Charsets.UTF_8),
        )
        require(persisted.archiveName == null || persisted.archiveName.matches(SUPPORT_TEMPORARY_FILE_PATTERN))
        require(persisted.idempotencyKey.matches(SUPPORT_IDEMPOTENCY_PATTERN))
        require(persisted.originAccountIdentity.matches(SUPPORT_ACCOUNT_IDENTITY_PATTERN))
        require(persisted.createdAtEpochMillis >= 0L)
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
            ?: persisted.createdAtEpochMillis.saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
        require(nowEpochMillis <= recoveryDeadlineEpochMillis)
        val archiveAgeMillis = (nowEpochMillis - persisted.createdAtEpochMillis).coerceAtLeast(0L)
        val archiveIsRetained = archiveAgeMillis <= SUPPORT_TEMPORARY_MAX_AGE_MILLIS
        val archive = persisted.archiveName?.let { archiveName ->
            File(temporaryRoot, archiveName).absoluteFile.normalize().also { candidate ->
                require(candidate.parentFile == temporaryRoot.absoluteFile.normalize())
                if (archiveIsRetained) {
                    require(candidate.isFile && candidate.length() in 1L..MAX_SUPPORT_ARCHIVE_BYTES)
                    restrictOwnerOnlyFile(candidate)
                } else {
                    candidate.delete()
                }
            }
        }?.takeIf { archiveIsRetained }
        PendingSubmission(
            archive = archive,
            metadata = persisted.metadata,
            idempotencyKey = persisted.idempotencyKey,
            createdAtEpochMillis = persisted.createdAtEpochMillis,
            originAccountIdentity = persisted.originAccountIdentity,
            context = persisted.context,
            cancellationPending = persisted.cancellationPending,
            outcomeAmbiguous = persisted.outcomeAmbiguous,
            retryNotBeforeEpochMillis = retryNotBeforeEpochMillis,
            receipt = persisted.receipt,
        )
    }.getOrElse {
        pendingDescriptor().delete()
        null
    }

    private fun pendingDescriptor(): File = File(temporaryRoot, SUPPORT_PENDING_DESCRIPTOR)

    private fun restoreCompletedSubmission(): CompletedSubmission? = runCatching {
        val descriptor = completedDescriptor()
        if (!descriptor.isFile) return@runCatching null
        require(descriptor.length() in 1L..MAX_COMPLETED_DESCRIPTOR_BYTES)
        val persisted = json.decodeFromString(
            PersistedCompletedSubmission.serializer(),
            descriptor.readText(Charsets.UTF_8),
        )
        require(persisted.originAccountIdentity.matches(SUPPORT_ACCOUNT_IDENTITY_PATTERN))
        validateReceipt(persisted.receipt)
        require(System.currentTimeMillis() <= Instant.parse(persisted.receipt.retentionUntil).toEpochMilli())
        CompletedSubmission(persisted.originAccountIdentity, persisted.receipt)
    }.getOrElse {
        completedDescriptor().delete()
        null
    }

    private fun completedDescriptor(): File = File(temporaryRoot, SUPPORT_COMPLETED_DESCRIPTOR)

    private data class PendingSubmission(
        var archive: File?,
        val metadata: SupportIntakeMetadata,
        val idempotencyKey: String,
        val createdAtEpochMillis: Long,
        val originAccountIdentity: String,
        val context: PreparedSupportSubmissionContext,
        var cancellationPending: Boolean = false,
        var outcomeAmbiguous: Boolean = false,
        var retryNotBeforeEpochMillis: Long? = null,
        var receipt: SupportIntakeReceipt? = null,
    ) {
        fun belongsTo(accountIdentity: String?): Boolean = originAccountIdentity == accountIdentity

        fun recoveryExpired(nowEpochMillis: Long): Boolean {
            val deadline = receipt
                ?.let { value -> runCatching { Instant.parse(value.retentionUntil).toEpochMilli() }.getOrNull() }
                ?: createdAtEpochMillis.saturatingAdd(SUPPORT_RECOVERY_MAX_AGE_MILLIS)
            return nowEpochMillis > deadline
        }
    }

    private data class CompletedSubmission(
        val originAccountIdentity: String,
        val receipt: SupportIntakeReceipt,
    ) {
        fun toSubmissionState() = SupportDiagnosticsSubmissionState.Submitted(
            supportCode = receipt.supportCode,
            statusUrl = receipt.statusUrl,
            retentionUntil = receipt.retentionUntil,
        )
    }

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
        val retryNotBeforeEpochMillis: Long? = null,
        val receipt: SupportIntakeReceipt? = null,
    )

    @Serializable
    private data class PersistedCompletedSubmission(
        val originAccountIdentity: String,
        val receipt: SupportIntakeReceipt,
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
        actualState = next
        actualStateAccountIdentity = accountIdentity
        state.value = if (accountIdentity != null && accountIdentity != activeAccountIdentity) {
            if (pending?.originAccountIdentity == accountIdentity) {
                SupportDiagnosticsSubmissionState.BlockedByAnotherAccount(
                    "A pending private report belongs to another signed-in account. Switch back to finish or discard it.",
                )
            } else {
                SupportDiagnosticsSubmissionState.Idle
            }
        } else {
            next
        }
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
private val SUPPORT_IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private val SUPPORT_ACCOUNT_IDENTITY_PATTERN = Regex("[0-9a-f]{32}(?:[0-9a-f]{32})?")
private val RETRYABLE_CLIENT_STATUS_CODES = setOf(425, 429)
private val TERMINAL_DELETION_STATUS_CODES = setOf(200, 204)
private const val MAX_SUPPORT_INTAKE_MESSAGE_LENGTH = 240
private const val MAX_SUPPORT_INTAKE_RESPONSE_BYTES = 64 * 1024
private const val MAX_SUPPORT_INTAKE_DESCRIPTION_BYTES = 8_000
private const val MIN_SUPPORT_INTAKE_DESCRIPTION_BYTES = 10
private const val MAX_PENDING_DESCRIPTOR_BYTES = 4L * 1024L * 1024L
private const val MAX_COMPLETED_DESCRIPTOR_BYTES = 64L * 1024L
private const val MAX_SUPPORT_ARCHIVE_BYTES = 4L * 1024L * 1024L
private const val SUPPORT_PENDING_DESCRIPTOR = "pending.json"
private const val SUPPORT_COMPLETED_DESCRIPTOR = "completed.json"
private const val SUPPORT_TEMPORARY_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private const val SUPPORT_RECOVERY_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val MAX_SUPPORT_RETRY_AFTER_SECONDS = 5L * 60L
private const val MAX_SUPPORT_RETRY_AFTER_MILLIS = MAX_SUPPORT_RETRY_AFTER_SECONDS * 1_000L
