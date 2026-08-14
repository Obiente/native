package dev.obiente.nextcloudnative

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.AsyncJvmSupportDiagnostics
import dev.obiente.nextcloudnative.app.JvmSupportIntake
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticsEnvironment
import dev.obiente.nextcloudnative.app.SupportDiagnosticsExportResult
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.system.exitProcess

private val UNCAUGHT_DIAGNOSTIC_HANDLER_INSTALLED = AtomicBoolean(false)

internal fun installAndroidUncaughtDiagnosticHandler(context: Context) {
    if (!UNCAUGHT_DIAGNOSTIC_HANDLER_INSTALLED.compareAndSet(false, true)) return
    val appContext = context.applicationContext ?: context
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val mainThread = appContext.mainLooper.thread
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        try {
            AndroidSupportDiagnostics.get(appContext).recordBeforeProcessExit(
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.App,
                    operation = "app.uncaught-exception",
                    outcome = "failed",
                    fields = listOf(
                        SupportDiagnosticFieldDraft("main_thread", (thread === mainThread).toString()),
                    ),
                    exception = failure.toSupportDiagnosticExceptionDraft(),
                ),
            )
        } catch (_: Throwable) {
            // Crash reporting must never prevent the platform handler from terminating the process.
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, failure)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }
}

internal object AndroidSupportDiagnostics {
    @Volatile
    private var instance: AsyncJvmSupportDiagnostics? = null

    fun get(context: Context): AsyncJvmSupportDiagnostics = instance ?: synchronized(this) {
        val appContext = context.applicationContext ?: context
        instance ?: AsyncJvmSupportDiagnostics(
            root = File(appContext.filesDir, "support-diagnostics"),
            environment = androidSupportDiagnosticsEnvironment(),
            workerName = "nextcloud-support-diagnostics",
        ).also { instance = it }
    }
}

/**
 * Owns the one durable support-submission state machine for this Android process.
 *
 * Activities, workers, and providers each create their own service facade, but they all operate on
 * the same no-backup directory. Sharing the coordinator prevents a replacement facade from
 * restoring or mutating that directory while an earlier facade is still packaging or uploading.
 */
internal object AndroidSupportIntakeCoordinator {
    @Volatile
    private var instance: JvmSupportIntake? = null

    fun get(
        context: Context,
        diagnostics: AsyncJvmSupportDiagnostics,
        client: OkHttpClient,
    ): JvmSupportIntake = instance ?: synchronized(this) {
        val appContext = context.applicationContext ?: context
        instance ?: JvmSupportIntake(
            diagnostics = diagnostics,
            temporaryRoot = File(appContext.noBackupFilesDir, "support-submissions"),
            environment = androidSupportDiagnosticsEnvironment(),
            client = client.newBuilder().retryOnConnectionFailure(false).build(),
            supportMutationsAllowed = appContext.cloudMutationGate(),
        ).also { instance = it }
    }
}

internal fun androidSupportDiagnosticsEnvironment(): SupportDiagnosticsEnvironment =
    SupportDiagnosticsEnvironment(
        appVersion = BuildConfig.VERSION_NAME,
        packageVersion = BuildConfig.VERSION_CODE.toString(),
        platform = "Android",
        operatingSystemVersion = android.os.Build.VERSION.RELEASE.orEmpty(),
        architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
    )

internal class AndroidSupportBundleExporter(
    private val context: Context,
    private val activity: Activity?,
    private val diagnostics: AsyncJvmSupportDiagnostics,
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
