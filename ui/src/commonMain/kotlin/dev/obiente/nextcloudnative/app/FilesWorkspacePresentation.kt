package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.vector.ImageVector
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

internal fun workspaceFileIcon(file: NextcloudFile): ImageVector = when {
    file.mimeType?.startsWith("image/") == true -> NextcloudIcons.Image
    file.mimeType?.startsWith("video/") == true -> NextcloudIcons.Video
    else -> NextcloudIcons.File
}

internal fun formatWorkspaceBytes(bytes: Long?): String = when {
    bytes == null -> "Unknown size"
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

internal data class FileTableColumns(val modified: Boolean, val size: Boolean)

internal fun fileTableColumns(width: Float, enabled: Boolean) = FileTableColumns(
    modified = enabled && width >= 620f,
    size = enabled && width >= 760f,
)

internal fun NextcloudFile.readableFileType(): String = when {
    isDirectory -> "Folder"
    mimeType?.startsWith("image/") == true -> "Image"
    mimeType?.startsWith("video/") == true -> "Video"
    mimeType?.startsWith("audio/") == true -> "Audio"
    mimeType == "application/pdf" -> "PDF document"
    mimeType == "text/markdown" -> "Markdown document"
    mimeType?.startsWith("text/") == true -> "Text document"
    else -> name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 8 }
        ?.uppercase()?.let { "$it file" } ?: "File"
}

internal fun String?.readableFileDate(): String {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return "-"
    val http = Regex("(?:[A-Za-z][A-Za-z][A-Za-z], )?([0-9][0-9]?) ([A-Za-z][A-Za-z][A-Za-z]) ([0-9][0-9][0-9][0-9])(?: .*)?").matchEntire(value)
    if (http != null) return "${http.groupValues[1]} ${http.groupValues[2]} ${http.groupValues[3]}"
    val iso = Regex("([0-9][0-9][0-9][0-9])-([0-9][0-9])-([0-9][0-9])(?:[T ].*)?").matchEntire(value)
    if (iso != null) {
        val month = iso.groupValues[2].toIntOrNull()
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        if (month != null && month in 1..12) return "${iso.groupValues[3].toInt()} ${months[month - 1]} ${iso.groupValues[1]}"
    }
    return value
}
