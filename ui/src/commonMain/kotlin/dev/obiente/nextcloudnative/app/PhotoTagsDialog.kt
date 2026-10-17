package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PhotoTagsDialog(
    filename: String,
    state: ResolvedMemoriesPhotoTags?,
    selectedTagIds: Set<Long>,
    error: String?,
    saving: Boolean,
    onToggle: (NextcloudSystemTag, Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var search by remember(filename) { mutableStateOf("") }
    val displayedTags = state?.availableTags.orEmpty().filter { tag ->
        tag.userVisible && (search.isBlank() || tag.name.contains(search, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    state == null && error == null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    state == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error ?: "Could not load photo tags.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    else -> {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Find a tag") },
                            singleLine = true,
                        )
                        if (state.unresolvedNames.isNotEmpty()) {
                            Text(
                                "${state.unresolvedNames.size} existing tag assignment" +
                                    if (state.unresolvedNames.size == 1) {
                                        " is preserved but cannot be identified safely."
                                    } else {
                                        "s are preserved but cannot be identified safely."
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (displayedTags.isEmpty()) {
                            Text(
                                if (search.isBlank()) "No visible system tags are available." else "No tags match your search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(displayedTags, key = NextcloudSystemTag::id) { tag ->
                                    val ambiguous = tag.name in state.ambiguousNames
                                    val enabled = !saving && tag.canAssign && !ambiguous
                                    val checked = tag.id in selectedTagIds
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = if (enabled) {
                                                { value -> onToggle(tag, value) }
                                            } else {
                                                null
                                            },
                                            enabled = enabled,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                                            if (!tag.canAssign || ambiguous) {
                                                Text(
                                                    if (ambiguous) "Duplicate server name · preserved" else "Read only",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = state != null && !saving && selectedTagIds != state.currentTagIds,
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
    )
}
