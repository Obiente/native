package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

class AsyncJvmSupportDiagnostics(
    root: File,
    private val environment: SupportDiagnosticsEnvironment,
    workerName: String,
) : AutoCloseable {
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
    private val coldCrashMarker = File(this.root, COLD_CRASH_MARKER_FILE)

    @Volatile
    private var delegate: JvmSupportDiagnostics? = null

    @Volatile
    private var initializationFailed = false

    private var activeAccountIdentity: String? = null

    init {
        scope.launch {
            runCatching {
                JvmSupportDiagnostics(root = this@AsyncJvmSupportDiagnostics.root, environment = environment)
                    .also(::finishInitialization)
            }.onSuccess(ready::complete)
                .onFailure { failure ->
                    synchronized(lock) {
                        initializationFailed = true
                        pending.clear()
                    }
                    ready.completeExceptionally(failure)
                }
            initializationComplete.countDown()
            publishRevision()
        }
    }

    fun record(event: SupportDiagnosticEventDraft) {
        val operation = synchronized(lock) {
            PendingOperation.Record(activeAccountIdentity, event)
        }
        submit(operation)
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
            if (!current.summary().available) persistColdCrashMarker()
            publishRevision()
            return
        }
        val initialized = runCatching {
            initializationComplete.await(COLD_CRASH_INITIALIZATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val initializedDelegate = delegate
        if (!initialized || initializedDelegate == null || !initializedDelegate.summary().available) {
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
        synchronized(lock) { activeAccountIdentity = identity }
        submit(PendingOperation.SetActiveAccount(identity))
        registerPrivateValue(serverUrl)
        registerPrivateValue(loginName)
    }

    fun setActiveAccountIdentity(accountIdentity: String?) {
        val normalized = accountIdentity?.takeIf(String::isNotBlank)
        synchronized(lock) { activeAccountIdentity = normalized }
        submit(PendingOperation.SetActiveAccount(normalized))
    }

    fun summary(): SupportDiagnosticsSummary = delegate?.summary() ?: SupportDiagnosticsSummary(
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

    suspend fun clear(): Boolean = runCatching {
        withContext(dispatcher) { ready.await().clear() }
    }.getOrDefault(false).also { publishRevision() }

    suspend fun writeBundle(
        destination: File,
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): File = withContext(dispatcher) {
        ready.await().writeBundle(destination, reproductionSteps, featureState)
    }

    override fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS) }
        dispatcher.close()
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
        val queued = synchronized(lock) {
            val operations = pending.toList()
            pending.clear()
            delegate = created
            operations
        }
        val pendingHadCrash = queued.any(PendingOperation::isUncaughtException)
        queued.forEach { operation -> operation.apply(created) }
        when {
            pendingHadCrash -> coldCrashMarker.delete()
            coldCrashMarker.isFile -> {
                created.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.App,
                        operation = "app.previous-cold-start-crash",
                        outcome = "recovered",
                    ),
                )
                coldCrashMarker.delete()
            }
        }
    }

    private fun submit(operation: PendingOperation) {
        val current = synchronized(lock) {
            if (initializationFailed) return
            delegate ?: run {
                enqueue(operation)
                null
            }
        }
        if (current != null) {
            scope.launch {
                operation.apply(current)
                publishRevision()
            }
        }
    }

    private fun enqueue(operation: PendingOperation) {
        if (operation is PendingOperation.SetActiveAccount) {
            pending.removeAll { it is PendingOperation.SetActiveAccount }
        }
        while (pending.size >= MAX_PENDING_OPERATIONS) {
            val oldestRecord = pending.indexOfFirst { it is PendingOperation.Record }
            if (oldestRecord >= 0) pending.removeAt(oldestRecord) else pending.removeFirst()
        }
        pending.addLast(operation)
    }

    private fun persistColdCrashMarker() {
        runCatching {
            require(root.isDirectory || root.mkdirs())
            FileOutputStream(coldCrashMarker).use { output ->
                output.write(COLD_CRASH_MARKER_CONTENT)
                output.fd.sync()
            }
        }
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
        const val COLD_CRASH_INITIALIZATION_WAIT_MILLIS = 2_000L
        const val CLOSE_WAIT_SECONDS = 2L
        const val COLD_CRASH_MARKER_FILE = "pending-cold-start-crash-v1"
        val COLD_CRASH_MARKER_CONTENT = "pending\n".encodeToByteArray()
    }
}
