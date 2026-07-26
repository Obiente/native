package dev.obiente.nextcloudnative.app

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal class DesktopLocalUploadPicker {
    private val selections = ConcurrentHashMap<String, SelectedSource>()

    suspend fun choose(
        acceptedMimeTypes: List<String>,
        maximumBytes: Long,
    ): LocalUploadSelectionResult = withContext(Dispatchers.IO) {
        val accepted = requireSafeUploadPickerRequest(acceptedMimeTypes, maximumBytes)
        if (GraphicsEnvironment.isHeadless()) {
            return@withContext LocalUploadSelectionResult.Unavailable(
                "The native file picker is unavailable in a headless desktop session.",
            )
        }
        val selected = FileDialog(null as Frame?, "Choose a file", FileDialog.LOAD).useDialog()
            ?: return@withContext LocalUploadSelectionResult.Cancelled
        if (!selected.isFile) {
            return@withContext LocalUploadSelectionResult.Rejected(
                "The selected item is not a readable file.",
            )
        }
        val canonical = runCatching { selected.canonicalFile }.getOrElse {
            return@withContext LocalUploadSelectionResult.Rejected(
                "The selected file could not be opened.",
            )
        }
        val size = canonical.length()
        if (size > maximumBytes) {
            return@withContext LocalUploadSelectionResult.Rejected(
                "The selected file is larger than the allowed upload limit.",
            )
        }
        val mimeType = runCatching { Files.probeContentType(canonical.toPath()) }
            .getOrNull()?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        if (!isAcceptedUploadMimeType(mimeType, accepted)) {
            return@withContext LocalUploadSelectionResult.Rejected(
                "The selected file type is not accepted.",
            )
        }
        val token = UUID.randomUUID().toString()
        val file = localUploadFile(
            selectionId = token,
            displayName = canonical.name,
            mimeType = mimeType,
            sizeBytes = size,
        )
        selections[token] = SelectedSource(canonical, file)
        LocalUploadSelectionResult.Selected(file)
    }

    fun open(file: LocalUploadFile): InputStream {
        val source = selections[file.selectionId]
            ?: error("The local file selection has expired.")
        require(source.file == file) { "The local file selection metadata changed." }
        require(source.localFile.isFile && source.localFile.length() == file.sizeBytes) {
            "The selected file changed after it was chosen."
        }
        return FileInputStream(source.localFile)
    }

    fun release(file: LocalUploadFile) {
        selections.remove(file.selectionId)
    }

    private data class SelectedSource(
        val localFile: File,
        val file: LocalUploadFile,
    )
}

private fun FileDialog.useDialog(): File? = try {
    isMultipleMode = false
    isVisible = true
    files.singleOrNull()
} finally {
    dispose()
}

internal class DesktopStreamingMultipartRequestBody(
    private val upload: PreparedMultipartUpload,
    private val openSource: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType = upload.contentType.toMediaType()

    override fun contentLength(): Long = upload.contentLength ?: -1L

    override fun writeTo(sink: BufferedSink) {
        openSource().use { source ->
            writePreparedMultipartUpload(
                upload = upload,
                readFile = source::read,
                write = { bytes, offset, count ->
                    sink.write(bytes, offset, count)
                },
            )
        }
    }
}
