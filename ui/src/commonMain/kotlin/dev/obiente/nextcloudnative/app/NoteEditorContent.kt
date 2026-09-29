package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import com.mikepenz.markdown.m3.Markdown

internal enum class NoteViewMode { Edit, Preview }

/** Presentation-only note form. Saving and recovery remain owned by the note screen. */
@Composable
internal fun NoteEditorContent(
    title: String,
    category: String,
    content: TextFieldValue,
    folderOptions: List<String>,
    viewMode: NoteViewMode,
    readOnly: Boolean,
    mutationInProgress: Boolean,
    canSave: Boolean,
    saving: Boolean,
    previewAvailable: Boolean,
    contentBytes: Long,
    loadError: String?,
    saveError: String?,
    onTitleChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onContentChange: (TextFieldValue) -> Unit,
    onViewModeChange: (NoteViewMode) -> Unit,
    onSave: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    val editable = !readOnly && !mutationInProgress
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 480.dp
        Column(
            Modifier.fillMaxSize().padding(if (compact) NextcloudSpacing.Small else NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    FilterChip(selected = viewMode == NoteViewMode.Edit,
                        onClick = { onViewModeChange(NoteViewMode.Edit) }, label = { Text("Edit") })
                    FilterChip(selected = viewMode == NoteViewMode.Preview,
                        onClick = { onViewModeChange(NoteViewMode.Preview) }, label = { Text("Preview") },
                        enabled = previewAvailable)
                    if (compact) TextButton(onClick = { showDetails = true }) { Text("Details") }
                }
                Button(enabled = canSave, onClick = onSave) { Text(if (saving) "Saving..." else "Save") }
            }
            if (!compact) NoteEditorMetadata(title, category, folderOptions, editable, onTitleChange, onCategoryChange)
            if (viewMode == NoteViewMode.Edit) {
                MarkdownFormattingToolbar(content, onContentChange, editable)
                OutlinedTextField(value = content, onValueChange = onContentChange,
                    modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Markdown") }, enabled = editable)
            } else {
                Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium) {
                    when {
                        !previewAvailable -> Box(Modifier.fillMaxSize().padding(NextcloudSpacing.Medium), contentAlignment = Alignment.Center) {
                            Text("Switch to Edit to continue. Large Markdown previews are disabled.")
                        }
                        content.text.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("This note is empty.")
                        }
                        else -> Markdown(content = content.text, typography = nativeMarkdownTypography(),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Medium))
                    }
                }
            }
            if (!compact) Text(formatNoteSize(contentBytes), style = MaterialTheme.typography.bodySmall)
            if (!previewAvailable || contentBytes > MAX_NOTE_BYTES || loadError != null || saveError != null) {
                Column(Modifier.heightIn(max = 88.dp).verticalScroll(rememberScrollState())) {
                    if (!previewAvailable) Text("Preview is disabled for notes larger than ${MAX_NOTE_MARKDOWN_PREVIEW_BYTES / 1024} KiB to keep editing responsive.",
                        style = MaterialTheme.typography.bodySmall)
                    if (contentBytes > MAX_NOTE_BYTES) Text("This note is larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB and cannot be saved.", color = MaterialTheme.colorScheme.error)
                    loadError?.let { Text("Could not refresh from the server. $it", color = MaterialTheme.colorScheme.error) }
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (compact && showDetails) AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Note details") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                NoteEditorMetadata(title, category, folderOptions, editable, onTitleChange, onCategoryChange)
            } },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun NoteEditorMetadata(
    title: String, category: String, folderOptions: List<String>, editable: Boolean,
    onTitleChange: (String) -> Unit, onCategoryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedTextField(title, onTitleChange, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, enabled = editable)
        OutlinedTextField(category, onCategoryChange, Modifier.fillMaxWidth(), label = { Text("Folder") }, singleLine = true, enabled = editable)
        if (folderOptions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            FilterChip(category.isBlank(), { onCategoryChange("") }, label = { Text("Uncategorized") }, enabled = editable)
            folderOptions.forEach { path ->
                FilterChip(category == path, { onCategoryChange(path) }, label = { Text(path) }, enabled = editable)
            }
        }
    }
}

private fun formatNoteSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}
