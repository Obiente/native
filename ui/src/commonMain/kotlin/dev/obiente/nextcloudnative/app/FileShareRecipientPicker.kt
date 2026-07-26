package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Immutable
internal data class FileShareRecipientPickerUiState(
    val query: String = "",
    val results: List<FileShareRecipient> = emptyList(),
    val loading: Boolean = false,
    val selectedRecipient: String = "",
    val error: String? = null,
) {
    val visibleResults: List<FileShareRecipient>
        get() = results.take(MAX_VISIBLE_FILE_SHARE_RECIPIENTS)

    fun supportingMessage(target: FileShareTarget): String? = when {
        selectedRecipient.isNotBlank() -> "Selected: $selectedRecipient"
        query.trim().length < MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH ->
            "Search your Nextcloud server and select a result."
        !loading && error == null && results.isEmpty() -> target.presentation().emptyMessage
        else -> null
    }
}

@Composable
internal fun FileShareRecipientPicker(
    session: NextcloudSession,
    services: NextcloudPlatformServices,
    target: FileShareTarget,
    file: NextcloudFile,
    selectedRecipient: String,
    enabled: Boolean,
    onSelected: (FileShareRecipient?) -> Unit,
    onResultsObserved: (List<FileShareRecipient>) -> Unit = {},
) {
    require(target.requiresRecipient)
    var state by remember(target, file.path) {
        mutableStateOf(FileShareRecipientPickerUiState(selectedRecipient = selectedRecipient))
    }

    LaunchedEffect(state.query, target, file.path, session, selectedRecipient) {
        val normalized = state.query.trim()
        if (selectedRecipient.isNotBlank()) {
            state = state.copy(
                results = emptyList(),
                loading = false,
                selectedRecipient = selectedRecipient,
                error = null,
            )
            return@LaunchedEffect
        }
        if (normalized.length < MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH) {
            state = state.copy(
                results = emptyList(),
                loading = false,
                selectedRecipient = "",
                error = null,
            )
            return@LaunchedEffect
        }
        delay(300)
        state = state.copy(loading = true, selectedRecipient = "", error = null)
        try {
            val results = services.searchFileShareRecipients(session, normalized, target, file)
            state = state.copy(results = results, loading = false)
            onResultsObserved(results)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            state = state.copy(
                results = emptyList(),
                loading = false,
                error = failure.message ?: "Could not search Nextcloud recipients.",
            )
        }
    }

    FileShareRecipientPickerContent(
        target = target,
        state = state.copy(selectedRecipient = selectedRecipient),
        enabled = enabled,
        onQueryChanged = { query ->
            state = state.copy(
                query = query,
                results = emptyList(),
                selectedRecipient = "",
                error = null,
            )
            onSelected(null)
        },
        onSelected = { recipient ->
            state = state.copy(
                query = recipient.displayName,
                results = emptyList(),
                selectedRecipient = recipient.id,
                error = null,
            )
            onSelected(recipient)
        },
    )
}

@Composable
internal fun FileShareRecipientPickerContent(
    target: FileShareTarget,
    state: FileShareRecipientPickerUiState,
    enabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onSelected: (FileShareRecipient) -> Unit,
) {
    require(target.requiresRecipient)
    val presentation = target.presentation()
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedTextField(
            value = state.query,
            enabled = enabled,
            onValueChange = onQueryChanged,
            label = { Text(presentation.searchLabel) },
            placeholder = { Text("Enter at least two characters") },
            trailingIcon = {
                if (state.loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            },
            supportingText = {
                state.supportingMessage(target)?.let { Text(it) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.visibleResults.forEach { recipient ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(NextcloudRadii.Medium),
                    )
                    .clickable(enabled = enabled) {
                        onSelected(recipient)
                    }
                    .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        recipient.displayName,
                        fontWeight = if (recipient.exact) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (recipient.id != recipient.displayName) {
                        Text(
                            recipient.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    presentation.resultLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_VISIBLE_FILE_SHARE_RECIPIENTS = 8
