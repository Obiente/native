package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AsyncJvmSupportDiagnostics private constructor(
    root: File,
    private val environment: SupportDiagnosticsEnvironment,
    workerName: String,
    private val beforeInitialization: () -> Unit,
) : AutoCloseable {
    constructor(root: File, environment: SupportDiagnosticsEnvironment, workerName: String) :
        this(root, environment, workerName, {})

    internal constructor(
        root: File,
        environment: SupportDiagnosticsEnvironment,
        workerName: String,
        initializationGate: CountDownLatch,
    ) : this(root, environment, workerName, initializationGate::await)

    private val root = root.absoluteFile.normalize()
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, workerName.take(80)).apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ready = CompletableDeferred<JvmSupportDiagnostics>()
    private val initializationComplete = CountDownLatch(1)
    private val revision = MutableStateFlow(0L)
    private val pending = ArrayDeque<PendingOperation>()
    private val coldCrashMarker = File(this.root, SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_FILE)

    @Volatile
    private var delegate: JvmSupportDiagnostics? = null

    @Volatile
    private var initializationFailed = false

    private var activeAccountIdentity: String? = null
    private var drainScheduled = false
    private var closing = false
    private var pendingCapacityTruncationObserved = false

    init {
        scope.launch {
            runCatching {
                beforeInitialization()
                JvmSupportDiagnostics(root = this@AsyncJvmSupportDiagnostics.root, environment = environment)
                    .also(::finishInitialization)
            }.onSuccess(ready::complete)
                .onFailure { failure ->
                    synchronized(lock) {
                        initializationFailed = true
                        pending.clear()
                        drainScheduled = false
                    }
                    ready.completeExceptionally(failure)
                }
            initializationComplete.countDown()
            publishRevision()
        }
    }

    fun record(event: SupportDiagnosticEventDraft) {
        val drain = synchronized(lock) {
            submitLocked(PendingOperation.Record(activeAccountIdentity, event))
        }
        startDrain(drain)
    }

    fun recordForAccountIdentity(accountIdentity: String, event: SupportDiagnosticEventDraft) {
        submit(PendingOperation.Record(accountIdentity, event))
    }

    fun recordBeforeProcessExit(event: SupportDiagnosticEventDraft) {
        val operation = synchronized(lock) {
            PendingOperation.Record(activeAccountIdentity, event)
        }
        val current = synchronized(lock) {
            delegate ?: run {
                enqueue(operation)
                null
            }
        }
        if (current != null) {
            operation.apply(current)
            if (!current.isStorageAvailable()) persistColdCrashMarker()
            publishRevision()
            return
        }
        val initialized = runCatching {
            initializationComplete.await(COLD_CRASH_INITIALIZATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val initializedDelegate = delegate
        if (!initialized || initializedDelegate == null || !initializedDelegate.isStorageAvailable()) {
            persistColdCrashMarker()
        }
    }

    fun registerPrivateValue(value: String?) {
        submit(PendingOperation.RegisterPrivateValue(value))
    }

    fun setActiveAccount(serverUrl: String?, loginName: String?) {
        val identity = if (serverUrl.isNullOrBlank() || loginName.isNullOrBlank()) {
            null
        } else {
            "${serverUrl.length}:$serverUrl${loginName.length}:$loginName"
        }
        val drain = synchronized(lock) {
            activeAccountIdentity = identity
            submitLocked(PendingOperation.SetActiveAccount(identity))
        }
        startDrain(drain)
        registerPrivateValue(serverUrl)
        registerPrivateValue(loginName)
    }

    fun setActiveAccountIdentity(accountIdentity: String?) {
        val normalized = accountIdentity?.takeIf(String::isNotBlank)
        val drain = synchronized(lock) {
            activeAccountIdentity = normalized
            submitLocked(PendingOperation.SetActiveAccount(normalized))
        }
        startDrain(drain)
    }

    fun summary(): SupportDiagnosticsSummary = delegate?.summary() ?: unavailableSummary()

    private fun unavailableSummary(): SupportDiagnosticsSummary = SupportDiagnosticsSummary(
        available = false,
        eventCount = 0,
        warningCount = 0,
        errorCount = 0,
        oldestEventAtEpochMillis = null,
        newestEventAtEpochMillis = null,
        components = emptySet(),
        storedBytes = 0L,
        includedFiles = SUPPORT_BUNDLE_INCLUDED_FILES,
        explanation = if (initializationFailed) {
            "Private diagnostic storage could not be prepared on this device."
        } else {
            "Preparing private diagnostic storage..."
        },
    )

    fun revisions(): StateFlow<Long> = revision.asStateFlow()

    suspend fun loadSummary(): SupportDiagnosticsSummary = try {
        withContext(dispatcher) {
            ready.await().also(::drainPendingSnapshot).summary()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        unavailableSummary()
    }

    suspend fun clear(): Boolean = runCatchingPreservingCancellation {
        withContext(dispatcher) {
            ready.await().also(::drainPendingSnapshot).clear()
        }
    }.getOrDefault(false).also { publishRevision() }

    suspend fun writeBundle(
        destination: File,
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): File = withContext(dispatcher) {
        ready.await().also(::drainPendingSnapshot).writeBundle(destination, reproductionSteps, featureState)
    }

    internal suspend fun writeBundleForSubmission(
        destination: File,
        context: PreparedSupportSubmissionContext,
    ): PreparedSupportDiagnosticsBundle = withContext(dispatcher) {
        ready.await().also(::drainPendingSnapshot)
            .writeBundleForSubmission(destination, context)
    }

    internal suspend fun prepareSubmissionContext(
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): PreparedSupportSubmissionContext = withContext(dispatcher) {
        ready.await().also(::drainPendingSnapshot)
            .prepareSubmissionContext(reproductionSteps, featureState)
    }

    internal suspend fun prepareSubmissionContextForAccountIdentity(
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
        accountIdentity: String,
    ): PreparedSupportSubmissionContext = withContext(dispatcher) {
        ready.await().also(::drainPendingSnapshot)
            .prepareSubmissionContextForAccountIdentity(reproductionSteps, featureState, accountIdentity)
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closing) {
                false
            } else {
                closing = true
                true
            }
        }
        if (!shouldClose) return
        try {
            executor.submit {
                delegate?.let(::drainPendingSnapshot)
            }.get()
        } finally {
            executor.shutdown()
            runCatching { executor.awaitTermination(CLOSE_TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS) }
            dispatcher.close()
        }
    }

    private fun finishInitialization(created: JvmSupportDiagnostics) {
        created.record(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Info,
                component = SupportDiagnosticComponent.App,
                operation = "app.process",
                outcome = "started",
            ),
        )
        val (queued, capacityTruncationObserved) = synchronized(lock) {
            val operations = pending.toList()
            pending.clear()
            delegate = created
            drainScheduled = true
            operations to takePendingCapacityTruncationObserved()
        }
        val pendingHadCrash = queued.any(PendingOperation::isUncaughtException)
        queued.applyTo(created, capacityTruncationObserved)
        when {
            pendingHadCrash -> {
                if (created.isStorageAvailable()) coldCrashMarker.delete()
            }
            coldCrashMarker.isFile -> {
                created.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.App,
                        operation = "app.previous-cold-start-crash",
                        outcome = "recovered",
                    ),
                )
                if (created.isStorageAvailable()) coldCrashMarker.delete()
            }
        }
        scheduleQueuedDrain(created)
    }

    private fun submit(operation: PendingOperation) {
        val drain = synchronized(lock) { submitLocked(operation) }
        startDrain(drain)
    }

    private fun submitLocked(operation: PendingOperation): JvmSupportDiagnostics? {
        if (initializationFailed || closing) return null
        enqueue(operation)
        return delegate?.takeIf {
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
    }

    private fun startDrain(current: JvmSupportDiagnostics?) {
        if (current != null) scope.launch { drainPendingBatch(current) }
    }

    private fun drainPendingBatch(current: JvmSupportDiagnostics) {
        val (batch, capacityTruncationObserved) = synchronized(lock) {
            val operations = buildList {
                repeat(minOf(MAX_DRAIN_BATCH_SIZE, pending.size)) {
                    add(pending.removeFirst())
                }
            }
            operations to takePendingCapacityTruncationObserved()
        }
        if (batch.isNotEmpty() || capacityTruncationObserved) {
            batch.applyTo(current, capacityTruncationObserved)
            publishRevision()
        }
        scheduleQueuedDrain(current)
    }

    private fun drainPendingSnapshot(current: JvmSupportDiagnostics) {
        val (snapshot, capacityTruncationObserved) = synchronized(lock) {
            pending.toList().also { pending.clear() } to takePendingCapacityTruncationObserved()
        }
        if (snapshot.isNotEmpty() || capacityTruncationObserved) {
            snapshot.applyTo(current, capacityTruncationObserved)
            publishRevision()
        }
    }

    private fun List<PendingOperation>.applyTo(
        current: JvmSupportDiagnostics,
        capacityTruncationObserved: Boolean = false,
    ) {
        current.applyBatch {
            if (capacityTruncationObserved) markCapacityTruncationObserved()
            this@applyTo.forEach { operation -> operation.apply(this) }
        }
    }

    private fun scheduleQueuedDrain(current: JvmSupportDiagnostics) {
        val shouldContinue = synchronized(lock) {
            if (pending.isEmpty()) {
                drainScheduled = false
                false
            } else {
                true
            }
        }
        if (shouldContinue) {
            scope.launch { drainPendingBatch(current) }
        }
    }

    private fun enqueue(operation: PendingOperation) {
        if (operation is PendingOperation.SetActiveAccount) {
            pending.removeAll { it is PendingOperation.SetActiveAccount }
        }
        while (pending.size >= MAX_PENDING_OPERATIONS) {
            val oldestRecord = pending.indexOfFirst { it is PendingOperation.Record }
            val removed = if (oldestRecord >= 0) pending.removeAt(oldestRecord) else pending.removeFirst()
            if (removed is PendingOperation.Record) pendingCapacityTruncationObserved = true
        }
        pending.addLast(operation)
    }

    private fun takePendingCapacityTruncationObserved(): Boolean =
        pendingCapacityTruncationObserved.also { pendingCapacityTruncationObserved = false }

    private fun persistColdCrashMarker() {
        persistJvmSupportDiagnosticsColdCrashMarker(root)
    }

    private fun publishRevision() {
        revision.update { value -> if (value == Long.MAX_VALUE) 0L else value + 1L }
    }

    private sealed interface PendingOperation {
        fun apply(diagnostics: JvmSupportDiagnostics)

        fun isUncaughtException(): Boolean =
            this is Record && event.operation == "app.uncaught-exception"

        data class Record(
            val accountIdentity: String?,
            val event: SupportDiagnosticEventDraft,
        ) : PendingOperation {
            override fun apply(diagnostics: JvmSupportDiagnostics) {
                diagnostics.recordForAccountIdentity(accountIdentity, event)
            }
        }

        data class RegisterPrivateValue(val value: String?) : PendingOperation {
            override fun apply(diagnostics: JvmSupportDiagnostics) {
                diagnostics.registerPrivateValue(value)
            }
        }

        data class SetActiveAccount(val accountIdentity: String?) : PendingOperation {
            override fun apply(diagnostics: JvmSupportDiagnostics) {
                diagnostics.setActiveAccountIdentity(accountIdentity)
            }
        }
    }

    private companion object {
        const val MAX_PENDING_OPERATIONS = 512
        const val MAX_DRAIN_BATCH_SIZE = 32
        const val COLD_CRASH_INITIALIZATION_WAIT_MILLIS = 2_000L
        const val CLOSE_TERMINATION_WAIT_SECONDS = 2L
    }
}

internal fun persistJvmSupportDiagnosticsColdCrashMarker(root: File) {
    runCatching {
        val normalizedRoot = root.absoluteFile.normalize()
        require(normalizedRoot.isDirectory || normalizedRoot.mkdirs())
        FileOutputStream(File(normalizedRoot, SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_FILE)).use { output ->
            output.write(SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_CONTENT)
            output.fd.sync()
        }
    }
}

internal const val SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_FILE = "pending-cold-start-crash-v1"
private val SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_CONTENT = "pending\n".encodeToByteArray()
