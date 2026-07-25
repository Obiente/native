package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

/**
 * Read-only document body that callers can place inside their own navigation or dialog chrome.
 *
 * It renders bounded UTF-8 text, bounded Markdown through native Compose components, or a
 * server-generated raster preview. It never instantiates a WebView or receives a WOPI access token.
 */
@Composable
fun NextcloudDocumentPreview(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    modifier: Modifier = Modifier,
    policy: DocumentPreviewPolicy = DocumentPreviewPolicy(),
) {
    val loader = remember(services, session, userId, policy) {
        DocumentPreviewLoader(
            backend = NextcloudDocumentPreviewBackend(services, session, userId),
            policy = policy,
        )
    }
    var attempt by remember(file.path) { mutableIntStateOf(0) }
    var state by remember(file.path, file.etag, attempt) {
        mutableStateOf<DocumentPreviewUiState>(DocumentPreviewUiState.Loading)
    }
    val cachedEditing = remember(session.serverUrl, session.loginName) {
        sharedDocumentEditingCapabilitiesCache.get(session)
    }
    var editingCapabilities by remember(session.serverUrl, session.loginName) {
        mutableStateOf(
            cachedEditing?.capabilities ?: NextcloudDocumentEditingCapabilities.Unavailable,
        )
    }
    var editStatus by remember(file.path, file.etag) {
        mutableStateOf<DocumentEditUiState>(DocumentEditUiState.Idle)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loader, file.path, file.etag, file.fileId, file.hasPreview, attempt) {
        state = DocumentPreviewUiState.Loading
        state = runCatching { loader.load(file) }.fold(
            onSuccess = DocumentPreviewUiState::Ready,
            onFailure = { DocumentPreviewUiState.Error },
        )
    }
    LaunchedEffect(services, session.serverUrl, session.loginName) {
        runCatching {
            services.loadDocumentEditingCapabilities(session, cachedEditing?.etag)
        }.onSuccess { result ->
            when (result) {
                is NextcloudConditionalRead.Modified -> {
                    editingCapabilities = result.value
                    sharedDocumentEditingCapabilitiesCache.store(
                        session,
                        result.value,
                        result.responseEtag,
                    )
                }
                NextcloudConditionalRead.NotModified -> Unit
            }
        }
    }
    val officePlan = remember(file, editingCapabilities) {
        planOfficeEditSession(file, editingCapabilities)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            DocumentPreviewUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is DocumentPreviewUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                DocumentWorkflowBar(
                    file = file,
                    officePlan = officePlan,
                    editStatus = editStatus,
                    onEdit = { request ->
                        if (editStatus != DocumentEditUiState.Starting) {
                            editStatus = DocumentEditUiState.Starting
                            scope.launch {
                                runCatching {
                                    val editSession = services.beginDocumentEditSession(session, request)
                                    services.openExternalUrl(editSession.sameOriginUrl)
                                }.onSuccess {
                                    editStatus = DocumentEditUiState.Idle
                                }.onFailure {
                                    editStatus = DocumentEditUiState.Failed(
                                        it.message ?: "Could not start the Office editor.",
                                    )
                                }
                            }
                        }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    DocumentPreviewBody(
                        preview = current.preview,
                        filename = file.name,
                    )
                }
            }

            DocumentPreviewUiState.Error -> DocumentPreviewMessage(
                title = "Couldn’t load this preview",
                detail = "The server did not return a usable document preview.",
                action = "Retry",
                onAction = { attempt += 1 },
            )
        }
    }
}

@Composable
private fun DocumentWorkflowBar(
    file: NextcloudFile,
    officePlan: OfficeEditSessionPlan,
    editStatus: DocumentEditUiState,
    onEdit: (NextcloudDocumentEditSessionRequest) -> Unit,
) {
    val descriptor = describeDocument(file)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            documentMetadataSummary(file, descriptor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (descriptor.officeEditable) {
            when (officePlan) {
                is OfficeEditSessionPlan.Ready -> Button(
                    enabled = editStatus != DocumentEditUiState.Starting,
                    onClick = { onEdit(officePlan.request) },
                ) {
                    if (editStatus == DocumentEditUiState.Starting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).padding(end = 2.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (editStatus == DocumentEditUiState.Starting) "Starting Office…" else "Edit in Office")
                }
                is OfficeEditSessionPlan.Blocked -> Text(
                    officePlan.reason.userMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editStatus is DocumentEditUiState.Failed) {
                Text(
                    editStatus.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

internal fun documentMetadataSummary(
    file: NextcloudFile,
    descriptor: DocumentDescriptor = describeDocument(file),
): String = buildList {
    add(
        when (descriptor.kind) {
            DocumentKind.PlainText -> "Text"
            DocumentKind.Markdown -> "Markdown"
            DocumentKind.Pdf -> "PDF"
            DocumentKind.WordProcessing -> "Document"
            DocumentKind.Spreadsheet -> "Spreadsheet"
            DocumentKind.Presentation -> "Presentation"
            DocumentKind.Drawing -> "Drawing"
            DocumentKind.Diagram -> "Draw.io diagram"
            DocumentKind.Whiteboard -> "Whiteboard"
            DocumentKind.Other -> "File"
        },
    )
    descriptor.mimeType?.let(::add)
    file.size?.let { add(documentByteLabel(it)) }
    add(if (file.permissions?.contains('W') == true) "Writable" else "Read-only or permission unknown")
    file.etag?.takeIf(String::isNotBlank)?.let { add("Versioned") }
}.joinToString(" · ")

private fun documentByteLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
    bytes >= 1024L -> "${bytes / 1024L} KiB"
    else -> "$bytes B"
}

@Composable
private fun DocumentPreviewBody(preview: DocumentPreview, filename: String) {
    when (preview) {
        is DocumentPreview.Text -> when (
            planNativeTextPresentation(
                descriptor = preview.descriptor,
                utf8Bytes = preview.value.utf8Size(),
            )
        ) {
            NativeTextPresentation.RenderedMarkdown -> RenderedMarkdownDocument(preview, filename)
            NativeTextPresentation.LiteralText,
            NativeTextPresentation.MarkdownSourceOnly,
            -> LiteralTextDocument(preview, filename)
        }
        is DocumentPreview.Raster -> RasterDocument(preview, filename)
        is DocumentPreview.Unavailable -> DocumentPreviewMessage(
            title = "Preview unavailable",
            detail = preview.reason.userMessage(),
        )
    }
}

@Composable
private fun LiteralTextDocument(preview: DocumentPreview.Text, filename: String) {
    val presentation = planNativeTextPresentation(
        descriptor = preview.descriptor,
        utf8Bytes = preview.value.utf8Size(),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewLabel(
            title = filename,
            detail = when (presentation) {
                NativeTextPresentation.MarkdownSourceOnly ->
                    "Markdown source · rendered preview limited to " +
                        "${MAX_RENDERED_MARKDOWN_PREVIEW_BYTES / 1024} KiB"
                NativeTextPresentation.LiteralText -> "Read-only text preview"
                NativeTextPresentation.RenderedMarkdown -> "Rendered Markdown · read-only"
            },
        )
        SelectionContainer {
            Text(
                text = preview.value,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun RenderedMarkdownDocument(preview: DocumentPreview.Text, filename: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewLabel(
            title = filename,
            detail = "Rendered Markdown · read-only",
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            if (preview.value.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "This document is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SelectionContainer {
                    Markdown(
                        content = preview.value,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RasterDocument(preview: DocumentPreview.Raster, filename: String) {
    val image = remember(preview) {
        decodePlatformImage(
            preview.encodedImage,
            EncodedImageOrientationPolicy.PixelsAlreadyUpright,
        )
    }
    if (image == null) {
        DocumentPreviewMessage(
            title = "Preview unavailable",
            detail = DocumentPreviewUnavailableReason.InvalidServerPreview.userMessage(),
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        PreviewLabel(
            title = filename,
            detail = if (preview.firstPageOnly) "Read-only server preview · first page" else "Read-only server preview",
        )
        RasterImage(image = image, filename = filename)
    }
}

@Composable
private fun RasterImage(image: ImageBitmap, filename: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = "Preview of $filename",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun PreviewLabel(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DocumentPreviewMessage(
    title: String,
    detail: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) {
                Text(action)
            }
        }
    }
}

private fun DocumentPreviewUnavailableReason.userMessage(): String = when (this) {
    DocumentPreviewUnavailableReason.Directory -> "Folders do not have a document preview."
    DocumentPreviewUnavailableReason.UnsupportedType -> "This file type does not have a safe native preview yet."
    DocumentPreviewUnavailableReason.FileTooLarge -> "This text file is larger than the configured preview limit."
    DocumentPreviewUnavailableReason.InvalidTextEncoding -> "This file is not valid UTF-8 text."
    DocumentPreviewUnavailableReason.MissingFileId -> "The server did not provide the file ID needed for a preview."
    DocumentPreviewUnavailableReason.ServerPreviewUnavailable ->
        "The server has no raster preview for this document. The original file was not downloaded."
    DocumentPreviewUnavailableReason.InvalidServerPreview -> "The server returned an invalid preview image."
}

private sealed interface DocumentPreviewUiState {
    data object Loading : DocumentPreviewUiState

    data class Ready(val preview: DocumentPreview) : DocumentPreviewUiState

    data object Error : DocumentPreviewUiState
}

private sealed interface DocumentEditUiState {
    data object Idle : DocumentEditUiState
    data object Starting : DocumentEditUiState
    data class Failed(val message: String) : DocumentEditUiState
}
