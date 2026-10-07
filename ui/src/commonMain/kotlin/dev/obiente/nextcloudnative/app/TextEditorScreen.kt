package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TextEditorScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    navigationCommitInProgress: Boolean = false,
) {
    val descriptor = remember(file) { describeDocument(file) }
    val isMarkdown = descriptor.kind == DocumentKind.Markdown
    val editor = remember(services, session, userId, file.path) {
        TextEditorState(
            store = services.textEditorDraftStore(session, file.path),
            download = { services.downloadFile(session, userId, file.path, maxBytes = MAX_EDITABLE_TEXT_BYTES) },
            upload = { text, revision -> services.saveTextFile(session, userId, file.path, text, revision) },
            listingEtag = file.etag,
        )
    }
    val originalText = editor.originalText
    val draft = editor.draft
    val etag = editor.etag
    val loadingError = editor.loadingError
    val saveError = editor.saveError
    val saving = editor.saving || editor.verifying
    val savedMessage = editor.savedMessage
    var confirmSave by remember(editor) { mutableStateOf(false) }
    var confirmDiscard by remember(editor) { mutableStateOf(false) }
    var markdownViewMode by rememberSaveable(file.path) {
        mutableStateOf(
            if (
                isMarkdown && file.size != 0L &&
                (file.size == null || file.size <= MAX_RENDERED_MARKDOWN_PREVIEW_BYTES)
            ) {
                MarkdownFileViewMode.Preview
            } else {
                MarkdownFileViewMode.Edit
            },
        )
    }
    val scope = rememberCoroutineScope()
    val dirty = originalText != null && draft != originalText
    val textPresentation = remember(descriptor, draft) {
        planNativeTextPresentation(descriptor, draft.utf8Size())
    }
    val markdownPreviewAvailable = textPresentation == NativeTextPresentation.RenderedMarkdown

    LaunchedEffect(editor) { editor.open() }
    LaunchedEffect(editor, draft) {
        // Coalesce typing into one durable recovery write; Save flushes the latest draft first.
        delay(TEXT_DRAFT_PERSIST_DEBOUNCE_MILLIS)
        if (editor.originalText != null) editor.persistEdits()
    }
    val windowVisibility = LocalAppWindowVisibility.current
    LaunchedEffect(editor, windowVisibility) {
        // Leaving the screen (Android stop, desktop window hidden) skips the debounce window.
        windowVisibility.collect { visible ->
            if (!visible && editor.originalText != null) editor.persistEdits()
        }
    }

    fun requestBack() {
        if (saving || navigationCommitInProgress) return
        if (dirty) confirmDiscard = true else onBack()
    }
    LaunchedEffect(navigationRequest?.identity, saving, navigationCommitInProgress) {
        navigationRequest?.let { request ->
            if (!saving && !navigationCommitInProgress) {
                if (dirty) confirmDiscard = true else onNavigationConfirmed(request)
            }
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::requestBack)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (dirty) "Unsaved changes" else "Text editor", ::requestBack,
            compact = true, trailingContent = {
                Button(
                    enabled = editor.canSave && !navigationCommitInProgress,
                    onClick = { confirmSave = true },
                ) {
                    Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (saving) "Saving..." else "Save")
                }
            })
        when {
            loadingError != null -> ErrorMessage(requireNotNull(loadingError))
            originalText == null -> LoadingMessage("Opening ${file.name}...")
            else -> {
                val statusMessage = saveError ?: savedMessage ?: if (etag.isNullOrBlank()) {
                    "Saving is disabled until the server version is verified."
                } else null
                statusMessage?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            saveError != null -> MaterialTheme.colorScheme.error
                            etag.isNullOrBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> NextcloudTheme.colors.success
                        },
                    )
                }
                if (etag.isNullOrBlank()) {
                    TextButton(enabled = !saving, onClick = { scope.launch { editor.verifyRevision() } }) {
                        Text(if (editor.verifying) "Verifying..." else "Verify server version")
                    }
                }
                if (isMarkdown) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Medium,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Preview,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Preview },
                            enabled = markdownPreviewAvailable,
                            label = { Text("Preview") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.File,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Edit,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Edit },
                            label = { Text("Edit source") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        if (!markdownPreviewAvailable) {
                            Text(
                                "Rendered preview is limited to " +
                                    "${MAX_RENDERED_MARKDOWN_PREVIEW_BYTES / 1024} KiB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (isMarkdown && markdownViewMode == MarkdownFileViewMode.Preview) {
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        when {
                            !markdownPreviewAvailable -> Box(
                                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Switch to Edit source to continue.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            draft.isBlank() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "This document is empty.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> Markdown(typography = nativeMarkdownTypography(),
                                content = draft,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(NextcloudSpacing.Large),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        label = { Text("Document content") },
                        onValueChange = {
                            editor.edit(it)
                        },
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        enabled = !saving && !navigationCommitInProgress,
                    )
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save changes to Nextcloud?") },
            text = { Text("This updates ${file.name} on the server. A conflict will stop the save instead of overwriting newer changes.") },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    confirmSave = false
                    scope.launch { editor.save() }
                }) { Text("Save") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = {
                confirmDiscard = false
                navigationRequest?.let(onNavigationCancelled)
            },
            title = { Text("Discard unsaved changes?") },
            text = { Text("This discards your local edits to ${file.name}. It does not undo changes already accepted by Nextcloud.") },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        navigationRequest?.let(onNavigationCancelled)
                    },
                ) { Text("Keep editing") }
            },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = false
                    scope.launch {
                        if (editor.discard()) {
                            navigationRequest?.let(onNavigationConfirmed) ?: onBack()
                        } else {
                            navigationRequest?.let(onNavigationCancelled)
                        }
                    }
                }) { Text("Discard") }
            },
        )
    }
}

private enum class MarkdownFileViewMode {
    Preview,
    Edit,
}

private const val TEXT_DRAFT_PERSIST_DEBOUNCE_MILLIS = 300L
