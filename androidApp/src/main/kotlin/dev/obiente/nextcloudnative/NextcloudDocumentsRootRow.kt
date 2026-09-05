package dev.obiente.nextcloudnative

import android.database.MatrixCursor
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun MatrixCursor.addNextcloudRootRow(
    session: NextcloudSession,
    incarnation: NextcloudDocumentIncarnation,
    title: String,
    readOnly: Boolean,
) {
    val host = runCatching { URI(session.serverUrl).host }.getOrNull().orEmpty()
    val values = mapOf(
        DocumentsContract.Root.COLUMN_ROOT_ID to NextcloudDocumentIds.providerRootId(session, incarnation),
        DocumentsContract.Root.COLUMN_DOCUMENT_ID to NextcloudDocumentIds.rootId(session, incarnation),
        DocumentsContract.Root.COLUMN_TITLE to title,
        DocumentsContract.Root.COLUMN_SUMMARY to buildString {
            append(session.loginName)
            if (host.isNotBlank()) append(" on ").append(host)
        },
        DocumentsContract.Root.COLUMN_FLAGS to (
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                if (readOnly) 0 else DocumentsContract.Root.FLAG_SUPPORTS_CREATE
            ),
        DocumentsContract.Root.COLUMN_ICON to R.mipmap.ic_launcher,
        DocumentsContract.Root.COLUMN_MIME_TYPES to "*/*",
    )
    val row = newRow()
    columnNames.forEach { column -> row.add(values[column]) }
}

internal fun MatrixCursor.addNextcloudDocumentRow(
    session: NextcloudSession,
    incarnation: NextcloudDocumentIncarnation,
    file: NextcloudFile?,
    rootTitle: String,
) {
    val isDirectory = file?.isDirectory ?: true
    val path = file?.path.orEmpty()
    val values = mapOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID to
            NextcloudDocumentIds.documentId(session, incarnation, path),
        DocumentsContract.Document.COLUMN_DISPLAY_NAME to (file?.name ?: rootTitle),
        DocumentsContract.Document.COLUMN_MIME_TYPE to when {
            isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
            else -> file.mimeType ?: "application/octet-stream"
        },
        DocumentsContract.Document.COLUMN_FLAGS to nextcloudDocumentFlags(file),
        DocumentsContract.Document.COLUMN_SIZE to file?.size,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED to file?.lastModified?.toEpochMilliseconds(),
    )
    val row = newRow()
    columnNames.forEach { column -> row.add(values[column]) }
}

private fun nextcloudDocumentFlags(file: NextcloudFile?): Int {
    if (file == null) {
        return DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
    }
    var flags = if (file.isDirectory) {
        DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
    } else {
        0
    }
    if (!file.etag.isNullOrBlank()) {
        flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        if (!file.isDirectory) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
    }
    return flags
}

internal fun String.toEpochMilliseconds(): Long? = runCatching {
    ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrNull()
