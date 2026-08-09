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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AndroidSupportDiagnostics {
    @Volatile
    private var instance: JvmSupportDiagnostics? = null

    fun get(context: Context): JvmSupportDiagnostics = instance ?: synchronized(this) {
        instance ?: JvmSupportDiagnostics(
            root = File(context.applicationContext.filesDir, "support-diagnostics"),
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
            instance = created
        }
    }
}

internal class AndroidSupportBundleExporter(
    private val context: Context,
    private val activity: Activity?,
    private val diagnostics: JvmSupportDiagnostics,
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
