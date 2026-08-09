package dev.obiente.nextcloudnative

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.JvmSupportDiagnostics
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticsExportResult
import dev.obiente.nextcloudnative.app.SupportDiagnosticsEnvironment
import dev.obiente.nextcloudnative.app.SupportDiagnosticsSummary
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidSupportDiagnostics private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nextcloud-support-diagnostics").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ready = CompletableDeferred<JvmSupportDiagnostics>()
    private val revision = MutableStateFlow(0L)
    private val pending = ArrayDeque<PendingOperation>()

    @Volatile
    private var delegate: JvmSupportDiagnostics? = null

    @Volatile
    private var initializationFailed = false

    private var activeAccountIdentity: String? = null

    init {
        scope.launch {
            runCatching {
                JvmSupportDiagnostics(
                    root = File(appContext.filesDir, "support-diagnostics"),
                    environment = SupportDiagnosticsEnvironment(
                        appVersion = BuildConfig.VERSION_NAME,
                        packageVersion = BuildConfig.VERSION_CODE.toString(),
                        platform = "Android",
                        operatingSystemVersion = android.os.Build.VERSION.RELEASE.orEmpty(),
                        architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    ),
                ).also { created ->
                    created.record(
                        SupportDiagnosticEventDraft(
                            severity = SupportDiagnosticSeverity.Info,
                            component = SupportDiagnosticComponent.App,
                            operation = "app.process",
                            outcome = "started",
                        ),
                    )
                    synchronized(lock) {
                        pending.forEach { operation -> operation.apply(created) }
                        pending.clear()
                        delegate = created
                    }
                }
            }.onSuccess { created ->
                ready.complete(created)
                publishRevision()
            }.onFailure { failure ->
                synchronized(lock) {
                    initializationFailed = true
                    pending.clear()
                }
                ready.completeExceptionally(failure)
                publishRevision()
            }
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
            publishRevision()
        }
    }

    fun registerPrivateValue(value: String?) {
        submit(PendingOperation.RegisterPrivateValue(value))
    }

    fun setActiveAccountIdentity(accountIdentity: String?) {
        val normalized = accountIdentity?.takeIf(String::isNotBlank)
        synchronized(lock) { activeAccountIdentity = normalized }
        submit(PendingOperation.SetActiveAccount(normalized))
    }

    fun summary(): SupportDiagnosticsSummary = delegate?.summary()
        ?: SupportDiagnosticsSummary(
            available = false,
            eventCount = 0,
            warningCount = 0,
            errorCount = 0,
            oldestEventAtEpochMillis = null,
            newestEventAtEpochMillis = null,
            components = emptySet(),
            storedBytes = 0L,
            includedFiles = listOf("README.txt", "report.json", "events.jsonl", "manifest.json"),
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

    private fun publishRevision() {
        revision.update { value -> if (value == Long.MAX_VALUE) 0L else value + 1L }
    }

    private sealed interface PendingOperation {
        fun apply(diagnostics: JvmSupportDiagnostics)

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

    companion object {
        private const val MAX_PENDING_OPERATIONS = 512

        @Volatile
        private var instance: AndroidSupportDiagnostics? = null

        fun get(context: Context): AndroidSupportDiagnostics = instance ?: synchronized(this) {
            instance ?: AndroidSupportDiagnostics(context).also { instance = it }
        }
    }
}

internal class AndroidSupportBundleExporter(
    private val context: Context,
    private val activity: Activity?,
    private val diagnostics: AndroidSupportDiagnostics,
) {
    suspend fun export(
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): SupportDiagnosticsExportResult {
        val host = activity ?: return SupportDiagnosticsExportResult.Unsupported(
            "Open Nextcloud Native before exporting a diagnostic report.",
        )
        val archive = runCatching {
            withContext(Dispatchers.IO) {
                val exportDirectory = File(context.cacheDir, SUPPORT_BUNDLE_CACHE_DIRECTORY)
                require(exportDirectory.isDirectory || exportDirectory.mkdirs()) {
                    "Could not prepare the private report cache."
                }
                pruneCachedBundles(exportDirectory)
                diagnostics.writeBundle(
                    destination = File(exportDirectory, "nextcloud-native-support-${UUID.randomUUID()}.zip"),
                    reproductionSteps = reproductionSteps,
                    featureState = featureState,
                )
            }
        }.getOrElse { failure ->
            return SupportDiagnosticsExportResult.Failed(
                failure.message?.take(240) ?: "Could not create the diagnostic report.",
            )
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.sharedfiles",
                archive,
            )
            withContext(Dispatchers.Main.immediate) {
                host.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = SUPPORT_BUNDLE_MIME_TYPE
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("Anonymized support report", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Save or share diagnostic report",
                    ),
                )
            }
            SupportDiagnosticsExportResult.Exported("Android share sheet")
        }.getOrElse { failure ->
            archive.delete()
            SupportDiagnosticsExportResult.Failed(
                failure.message?.take(240) ?: "Could not open the Android share sheet.",
            )
        }
    }

    private fun pruneCachedBundles(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHED_SUPPORT_BUNDLES - 1)
            .forEach(File::delete)
    }

    private companion object {
        const val SUPPORT_BUNDLE_CACHE_DIRECTORY = "support-bundles"
        const val SUPPORT_BUNDLE_MIME_TYPE = "application/zip"
        const val MAX_CACHED_SUPPORT_BUNDLES = 3
    }
}
