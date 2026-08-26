package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

/**
 * Native version history embedded in Files details.
 *
 * History is lazy by default. Detached preview/download and server rollback remain separate
 * actions, and every action that leaves the app or replaces the current file is confirmed first.
 */
@Composable
internal fun FileVersionHistorySection(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    initiallyExpanded: Boolean = false,
    onVersionRestored: () -> Unit = {},
) {
    var expanded by remember(file.fileId, userId) { mutableStateOf(initiallyExpanded) }
    var history by remember(file.fileId, userId) { mutableStateOf<FileVersionHistory?>(null) }
    var loading by remember(file.fileId, userId) { mutableStateOf(false) }
    var error by remember(file.fileId, userId) { mutableStateOf<String?>(null) }
    var visibleCount by remember(file.fileId, userId) { mutableStateOf(FILE_VERSION_INITIAL_ROWS) }
    var preview by remember(file.fileId, userId) { mutableStateOf<HistoricalPreview?>(null) }
    var previewLoadingId by remember(file.fileId, userId) { mutableStateOf<String?>(null) }
    var actionMessage by remember(file.fileId, userId) { mutableStateOf<String?>(null) }
    var confirmation by remember(file.fileId, userId) { mutableStateOf<FileVersionConfirmation?>(null) }
    val scope = rememberCoroutineScope()

    fun loadHistory() {
        if (loading || previewLoadingId != null || userId.isBlank() || file.fileId == null) return
        loading = true
        error = null
        scope.launch {
            runCatching { services.listFileVersions(session, userId, file) }
                .onSuccess {
                    history = it
                    visibleCount = FILE_VERSION_INITIAL_ROWS
                }
                .onFailure { failure ->
                    error = failure.message ?: "Could not load version history."
                }
            loading = false
        }
    }

    LaunchedEffect(file.fileId, userId, initiallyExpanded) {
        if (initiallyExpanded && history == null) loadHistory()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Version history", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Preview or download an older copy, or explicitly restore it as the current file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = !loading && userId.isNotBlank() && file.fileId != null,
                    onClick = {
                        expanded = !expanded
                        if (expanded && history == null) loadHistory()
                    },
                ) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (expanded) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            if (history == null) "Loading older versions..." else "Refreshing version history...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (history == null && !loading) {
                        OutlinedButton(onClick = ::loadHistory) { Text("Retry") }
                    }
                }
                when {
                    history == null -> Unit
                    history?.versions?.isEmpty() == true -> Text(
                        "No older versions are available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    history != null -> {
                        val versions = requireNotNull(history).versions
                        versions.take(visibleCount).forEachIndexed { index, version ->
                            if (index > 0) HorizontalDivider()
                            FileVersionRow(
                                file = file,
                                version = version,
                                previewLoading = previewLoadingId == version.id,
                                canExport = canExportFileVersion(services.externalFileHandoffSupport, version),
                                canRestore = canRestoreFileVersion(file, version),
                                onPreview = {
                                    if (previewLoadingId != null) return@FileVersionRow
                                    previewLoadingId = version.id
                                    actionMessage = null
                                    scope.launch {
                                        runCatching {
                                            if (version.sizeBytes == 0L) {
                                                NextcloudFileContent(byteArrayOf(), file.mimeType, version.etag)
                                            } else {
                                                services.downloadFileVersion(
                                                    session = session,
                                                    userId = userId,
                                                    file = file,
                                                    version = version,
                                                    maximumBytes = versionPreviewByteLimit(version),
                                                )
                                            }
                                        }.onSuccess { content ->
                                            preview = HistoricalPreview(version, content)
                                        }.onFailure { failure ->
                                            actionMessage = failure.message ?: "Could not preview this version."
                                        }
                                        previewLoadingId = null
                                    }
                                },
                                onDownload = {
                                    confirmation = FileVersionConfirmation.Download(version)
                                },
                                onRestore = {
                                    confirmation = FileVersionConfirmation.Restore(version)
                                },
                            )
                        }
                        if (visibleCount < versions.size) {
                            TextButton(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                onClick = {
                                    visibleCount = (visibleCount + FILE_VERSION_ROW_PAGE).coerceAtMost(versions.size)
                                },
                            ) {
                                Text("Show ${minOf(FILE_VERSION_ROW_PAGE, versions.size - visibleCount)} more")
                            }
                        }
                        TextButton(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            enabled = previewLoadingId == null && !loading,
                            onClick = ::loadHistory,
                        ) {
                            Text("Refresh history")
                        }
                    }
                }
                actionMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    confirmation?.let { pending ->
        FileVersionConfirmationDialog(
            file = file,
            confirmation = pending,
            busy = previewLoadingId != null,
            onDismiss = { if (previewLoadingId == null) confirmation = null },
            onConfirm = {
                val version = pending.version
                if (previewLoadingId != null) return@FileVersionConfirmationDialog
                confirmation = null
                previewLoadingId = version.id
                actionMessage = null
                error = null
                scope.launch {
                    when (pending) {
                        is FileVersionConfirmation.Download -> runCatching {
                            services.handoffFileVersionToExternalApp(
                                session,
                                userId,
                                file,
                                version,
                                ExternalFileHandoffAction.Share,
                            )
                        }.onSuccess { result ->
                            actionMessage = result.fileVersionActionMessage()
                        }.onFailure { failure ->
                            error = failure.message ?: "Could not download this historical copy."
                        }
                        is FileVersionConfirmation.Restore -> runCatching {
                            services.restoreFileVersion(session, userId, file, version)
                        }.onSuccess {
                            actionMessage = "Version restored. Refreshing the current file..."
                            runCatching { services.listFileVersions(session, userId, file) }
                                .onSuccess { refreshed ->
                                    history = refreshed
                                    visibleCount = FILE_VERSION_INITIAL_ROWS
                                }
                                .onFailure { refreshFailure ->
                                    error = "The version was restored, but history could not be refreshed: " +
                                        (refreshFailure.message ?: "unknown error")
                                }
                            onVersionRestored()
                        }.onFailure { failure ->
                            error = failure.message ?: "Could not restore this file version."
                        }
                    }
                    previewLoadingId = null
                }
            },
        )
    }

    preview?.let { loaded ->
        FileVersionPreviewDialog(
            file = file,
            preview = loaded,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun FileVersionRow(
    file: NextcloudFile,
    version: NextcloudFileVersion,
    previewLoading: Boolean,
    canExport: Boolean,
    canRestore: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            version.label ?: version.lastModified ?: "Version ${version.id}",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
        val metadata = buildList {
            version.lastModified?.takeUnless { it == version.label }?.let(::add)
            version.sizeBytes?.let { add(formatFileVersionBytes(it)) }
            version.author?.let { add("Edited by $it") }
        }
        if (metadata.isNotEmpty()) {
            Text(
                metadata.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            OutlinedButton(enabled = !previewLoading, onClick = onPreview) {
                if (previewLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (previewLoading) "Working..." else if (file.isEditableText()) "Read copy" else "Preview copy")
            }
            if (canExport) {
                Button(enabled = !previewLoading, onClick = onDownload) {
                    Text("Download copy")
                }
            }
        }
        if (canRestore) {
            TextButton(enabled = !previewLoading, onClick = onRestore) {
                Text("Restore as current file")
            }
        }
    }
}

@Composable
private fun FileVersionConfirmationDialog(
    file: NextcloudFile,
    confirmation: FileVersionConfirmation,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val versionName = confirmation.version.label
        ?: confirmation.version.lastModified
        ?: "Version ${confirmation.version.id}"
    val restoring = confirmation is FileVersionConfirmation.Restore
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restoring) "Restore this version?" else "Download this version?") },
        text = {
            Text(
                if (restoring) {
                    "$versionName will replace the current server copy of ${file.name}. " +
                        "Other synced devices will receive this change."
                } else {
                    "A detached copy of $versionName will be downloaded and opened in the platform export sheet. " +
                        "The current server file will not change."
                },
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = onConfirm) {
                Text(if (restoring) "Restore version" else "Download copy")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface FileVersionConfirmation {
    val version: NextcloudFileVersion

    data class Download(override val version: NextcloudFileVersion) : FileVersionConfirmation
    data class Restore(override val version: NextcloudFileVersion) : FileVersionConfirmation
}

@Composable
private fun FileVersionPreviewDialog(
    file: NextcloudFile,
    preview: HistoricalPreview,
    onDismiss: () -> Unit,
) {
    val mimeType = preview.content.mimeType?.substringBefore(';')?.lowercase()
        ?: file.mimeType?.substringBefore(';')?.lowercase()
    val image: ImageBitmap? = remember(preview) {
        if (mimeType?.startsWith("image/") == true) {
            decodePlatformImageSampled(
                preview.content.bytes,
                MAX_FILE_VERSION_IMAGE_PREVIEW_DIMENSION,
            )?.image
        } else {
            null
        }
    }
    val text = remember(preview) {
        if (file.isEditableText() || mimeType?.startsWith("text/") == true || mimeType in TEXTUAL_VERSION_MIME_TYPES) {
            runCatching {
                preview.content.bytes.decodeToString(throwOnInvalidSequence = true)
                    .take(MAX_FILE_VERSION_TEXT_PREVIEW_CHARACTERS)
            }.getOrNull()
        } else {
            null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.version.label ?: "Historical copy") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Text(
                    "${formatFileVersionBytes(preview.content.bytes.size.toLong())} downloaded as a temporary read-only preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = "Historical preview of ${file.name}",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        contentScale = ContentScale.Fit,
                    )
                    text != null -> Text(
                        if (text.isEmpty()) "This version is empty." else text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        "This file type has no native historical renderer yet. The bounded copy was downloaded without changing the current file.",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val MAX_FILE_VERSION_IMAGE_PREVIEW_DIMENSION = 2_048

internal fun versionPreviewByteLimit(version: NextcloudFileVersion): Long =
    version.sizeBytes
        ?.takeIf { it > 0L }
        ?.coerceAtMost(MAX_FILE_VERSION_PREVIEW_BYTES)
        ?: MAX_FILE_VERSION_PREVIEW_BYTES

internal fun canExportFileVersion(
    support: ExternalFileHandoffSupport,
    version: NextcloudFileVersion,
): Boolean {
    val capability = (support as? ExternalFileHandoffSupport.Available)?.capability ?: return false
    return ExternalFileHandoffAction.Share in capability.supportedActions &&
        version.sizeBytes?.let { it >= 0L } != false
}

internal fun canRestoreFileVersion(
    file: NextcloudFile,
    version: NextcloudFileVersion,
): Boolean = runCatching {
    requireRestorableFileVersion(file, version)
}.isSuccess

private fun ExternalFileHandoffResult.fileVersionActionMessage(): String = when (this) {
    is ExternalFileHandoffResult.Launched -> "A detached historical copy is ready in the platform export sheet."
    is ExternalFileHandoffResult.Cancelled -> "Export cancelled."
    is ExternalFileHandoffResult.NoCompatibleApplication -> "No app can receive this historical copy."
    is ExternalFileHandoffResult.Rejected -> message
    is ExternalFileHandoffResult.Unsupported -> reason
}

private fun formatFileVersionBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private data class HistoricalPreview(
    val version: NextcloudFileVersion,
    val content: NextcloudFileContent,
)

private const val FILE_VERSION_INITIAL_ROWS = 12
private const val FILE_VERSION_ROW_PAGE = 20
private const val MAX_FILE_VERSION_TEXT_PREVIEW_CHARACTERS = 200_000
private val TEXTUAL_VERSION_MIME_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
)
