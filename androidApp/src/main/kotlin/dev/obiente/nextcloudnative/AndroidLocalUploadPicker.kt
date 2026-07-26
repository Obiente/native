package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import dev.obiente.nextcloudnative.app.isAcceptedUploadMimeType
import dev.obiente.nextcloudnative.app.localUploadFile
import dev.obiente.nextcloudnative.app.requireSafeUploadPickerRequest
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single-flight bridge to Android's OpenDocument picker.
 *
 * Selected content URIs remain private to this platform class. Common code receives only an
 * opaque token and validated metadata. Grants are intentionally not persisted beyond the picker
 * result and app task.
 */
internal class AndroidLocalUploadPicker(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val selections = ConcurrentHashMap<String, SelectedSource>()
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: PendingSelection? = null

    fun attach(launcher: ActivityResultLauncher<Array<String>>) {
        check(this.launcher == null) { "The local file picker is already attached." }
        this.launcher = launcher
    }

    suspend fun choose(
        acceptedMimeTypes: List<String>,
        maximumBytes: Long,
    ): LocalUploadSelectionResult = suspendCancellableCoroutine { continuation ->
        val accepted = requireSafeUploadPickerRequest(acceptedMimeTypes, maximumBytes)
        check(pending == null) { "A local file picker is already open." }
        val activeLauncher = checkNotNull(launcher) { "The local file picker is not attached." }
        val selection = PendingSelection(continuation, accepted, maximumBytes)
        pending = selection
        continuation.invokeOnCancellation {
            if (pending === selection) pending = null
        }
        activeLauncher.launch(accepted.toTypedArray())
    }

    fun complete(uri: Uri?) {
        val selection = pending ?: return
        pending = null
        if (!selection.continuation.isActive) return
        if (uri == null) {
            selection.continuation.resume(LocalUploadSelectionResult.Cancelled)
            return
        }
        val result = runCatching {
            val metadata = resolver.queryUploadMetadata(uri)
            val mimeType = resolver.getType(uri)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            if (!isAcceptedUploadMimeType(mimeType, selection.acceptedMimeTypes)) {
                return@runCatching LocalUploadSelectionResult.Rejected(
                    "The selected file type is not accepted.",
                )
            }
            if (metadata.sizeBytes != null && metadata.sizeBytes > selection.maximumBytes) {
                return@runCatching LocalUploadSelectionResult.Rejected(
                    "The selected file is larger than the allowed upload limit.",
                )
            }
            val token = UUID.randomUUID().toString()
            val file = localUploadFile(
                selectionId = token,
                displayName = metadata.displayName,
                mimeType = mimeType,
                sizeBytes = metadata.sizeBytes,
            )
            selections[token] = SelectedSource(uri, file)
            LocalUploadSelectionResult.Selected(file)
        }.getOrElse {
            LocalUploadSelectionResult.Rejected(
                "The selected file could not be opened.",
            )
        }
        selection.continuation.resume(result)
    }

    fun open(file: LocalUploadFile): InputStream {
        val source = selections[file.selectionId]
            ?: error("The local file selection has expired.")
        require(source.file == file) { "The local file selection metadata changed." }
        return checkNotNull(resolver.openInputStream(source.uri)) {
            "The selected file could not be opened."
        }
    }

    fun release(file: LocalUploadFile) {
        selections.remove(file.selectionId)
    }

    private data class PendingSelection(
        val continuation: CancellableContinuation<LocalUploadSelectionResult>,
        val acceptedMimeTypes: List<String>,
        val maximumBytes: Long,
    )

    private data class SelectedSource(
        val uri: Uri,
        val file: LocalUploadFile,
    )
}

private data class AndroidUploadMetadata(
    val displayName: String,
    val sizeBytes: Long?,
)

private fun ContentResolver.queryUploadMetadata(uri: Uri): AndroidUploadMetadata =
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        check(cursor.moveToFirst()) { "The selected file has no metadata." }
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
            cursor.getString(nameIndex)
        } else {
            ""
        }
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            cursor.getLong(sizeIndex).takeIf { it >= 0L }
        } else {
            null
        }
        AndroidUploadMetadata(
            displayName = displayName?.trim().orEmpty().ifBlank { "upload.bin" },
            sizeBytes = size,
        )
    } ?: error("The selected file provider returned no metadata.")
