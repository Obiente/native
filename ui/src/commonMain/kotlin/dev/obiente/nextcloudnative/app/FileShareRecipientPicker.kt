package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

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

    /**
     * The complete email address the person typed, offered as a choice when the server has not
     * already returned the same address. An email share needs only the address itself, so the
     * choice stays available while the search runs, after it finds nothing, or when it fails.
     */
    fun typedRecipient(target: FileShareTarget): FileShareRecipient? {
        if (target != FileShareTarget.Email || selectedRecipient.isNotBlank()) return null
        val typed = typedFileShareEmailRecipient(query) ?: return null
        return typed.takeIf { matchingResult(target, typed) == null }
    }

    /**
     * The rows to render. The typed address, or the server result for that same address, always
     * leads, even when the server listed it after the visible result limit, so the address the
     * person typed has exactly one choice.
     */
    fun visibleChoices(target: FileShareTarget): List<FileShareRecipient> {
        val typed = if (target == FileShareTarget.Email) typedFileShareEmailRecipient(query) else null
        val leading = typedRecipient(target) ?: typed?.let { matchingResult(target, it) }
            ?: return visibleResults
        return (listOf(leading) + results.filterNot { it == leading }).take(MAX_VISIBLE_FILE_SHARE_RECIPIENTS)
    }

    private fun matchingResult(target: FileShareTarget, typed: FileShareRecipient): FileShareRecipient? =
        results.firstOrNull { it.target == target && it.id.equals(typed.id, ignoreCase = true) }

    fun supportingMessage(target: FileShareTarget): String? {
        val normalized = query.trim()
        val email = target == FileShareTarget.Email
        return when {
            selectedRecipient.isNotBlank() -> "Selected: $selectedRecipient"
            email && typedRecipient(target) != null -> "Press Enter or select the address to use it."
            email && '@' in normalized -> when (val validation = validateFileShareEmailAddress(normalized)) {
                is FileShareEmailAddressValidation.Invalid -> validation.reason
                is FileShareEmailAddressValidation.Valid -> if (loading) null else "Select a result to continue."
            }
            normalized.length < MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH ->
                if (email) {
                    "Enter an email address, or search and select a result."
                } else {
                    "Search your Nextcloud server and select a result."
                }
            !loading && error == null && results.isEmpty() -> target.presentation().emptyMessage
            !loading && error == null -> "Select a result to continue."
            else -> null
        }
    }
}

/**
 * Chooses the recipient the person already typed in full, so an email address or federated
 * cloud ID does not need a second confirming tap. Only an exact server result whose identity
 * equals the query qualifies; people and groups always need an explicit choice because a
 * display name can match several accounts.
 */
internal fun automaticFileShareRecipient(
    target: FileShareTarget,
    query: String,
    results: List<FileShareRecipient>,
): FileShareRecipient? {
    if (target != FileShareTarget.Email && target != FileShareTarget.Remote) return null
    val normalized = query.trim()
    return results
        .filter { it.exact && it.target == target && it.id.equals(normalized, ignoreCase = true) }
        .singleOrNull()
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
    var state by remember(session, target, file.path) {
        mutableStateOf(FileShareRecipientPickerUiState(selectedRecipient = selectedRecipient))
    }
    var retryAttempt by remember(session, target, file.path) { mutableStateOf(0) }
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentOnResultsObserved by rememberUpdatedState(onResultsObserved)

    LaunchedEffect(state.query, target, file.path, session, selectedRecipient, retryAttempt) {
        val normalized = state.query.trim()
        if (selectedRecipient.isNotBlank()) {
            state = state.copy(
                results = state.results.filter { it.id == selectedRecipient && it.target == target },
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
            // A superseded search must not publish results or select a recipient for a new query.
            currentCoroutineContext().ensureActive()
            if (state.query.trim() != normalized) return@LaunchedEffect
            state = state.copy(results = results, loading = false)
            currentOnResultsObserved(results)
            automaticFileShareRecipient(target, normalized, results)?.let { recipient ->
                state = state.copy(results = listOf(recipient), selectedRecipient = recipient.id)
                currentOnSelected(recipient)
            }
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
        onRetry = { retryAttempt += 1 },
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
                results = listOf(recipient),
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
    onRetry: (() -> Unit)? = null,
) {
    require(target.requiresRecipient)
    val presentation = target.presentation()
    val typedRecipient = state.typedRecipient(target)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedTextField(
            value = state.query,
            enabled = enabled,
            onValueChange = onQueryChanged,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled) typedRecipient?.let(onSelected) }),
            label = { Text(presentation.searchLabel) },
            placeholder = { Text("Enter at least two characters") },
            trailingIcon = {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            },
            supportingText = {
                state.supportingMessage(target)?.let { Text(it) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.visibleChoices(target).forEach { recipient ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(NextcloudRadii.Medium),
                    )
                    .selectable(selected = state.selectedRecipient == recipient.id, enabled = enabled, role = Role.RadioButton) {
                        onSelected(recipient)
                    }
                    .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.selectedRecipient == recipient.id, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(
                        recipient.displayName,
                        fontWeight = if (recipient.exact) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (recipient == typedRecipient || recipient.id != recipient.displayName) {
                        Text(
                            if (recipient == typedRecipient) "Typed address" else recipient.id,
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
        state.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    error,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                onRetry?.let { retry ->
                    TextButton(onClick = retry, enabled = enabled && !state.loading) { Text("Retry search") }
                }
            }
        }
    }
}

private const val MAX_VISIBLE_FILE_SHARE_RECIPIENTS = 8
