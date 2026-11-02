package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.launch

/** Display-only option for record editors; the caller keeps the meaning of [id]. */
internal data class NativeEditorChoice(val id: String, val label: String)

private fun requiredLabel(label: String, required: Boolean) = if (required) "$label *" else label

@Composable
internal fun NativeEditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    required: Boolean = false,
    error: String? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        label = { Text(requiredLabel(label, required)) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        supportingText = (error ?: supportingText)?.let { text -> { Text(text) } },
        isError = error != null,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 10 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** A single choice. Optional fields offer an explicit "None" entry with an empty ID. */
@Composable
internal fun NativeEditorChoiceField(
    label: String,
    options: List<NativeEditorChoice>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    required: Boolean = false,
    error: String? = null,
) {
    val choices = buildList {
        if (!required) add(NextcloudChoiceOption(NONE_CHOICE_ID, "None"))
        options.forEach { add(NextcloudChoiceOption(it.id, it.label)) }
    }
    NextcloudChoiceField(
        label = requiredLabel(label, required),
        options = choices,
        selectedId = selectedId?.takeIf(String::isNotEmpty) ?: if (required) null else NONE_CHOICE_ID,
        onSelected = { id -> onSelected(if (id == NONE_CHOICE_ID) "" else id) },
        enabled = enabled,
        error = error,
        fieldKey = "editor:$label",
        contentDescription = label,
    )
}

/** A set of choices shown as labelled check boxes; touch targets stay at least 48 dp. */
@Composable
internal fun NativeEditorMultiChoiceField(
    label: String,
    options: List<NativeEditorChoice>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    enabled: Boolean,
    required: Boolean = false,
    error: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Text(requiredLabel(label, required), style = MaterialTheme.typography.titleSmall)
        options.forEach { option ->
            val checked = option.id in selectedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { now ->
                            onSelectionChange(if (now) selectedIds + option.id else selectedIds - option.id)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Text(option.label, modifier = Modifier.weight(1f))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun NativeEditorToggleField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (checked) "Yes" else "No",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A read-only note for values the editor keeps unchanged because it cannot edit them safely. */
@Composable
internal fun NativeEditorPreservedValue(label: String, explanation: String) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Status text for a record write; it never offers a retry the session would refuse. */
@Composable
internal fun NativeEditorWriteStatusText(session: NativeEditorWriteSession) {
    val message = when (val status = session.status) {
        is NativeEditorWriteStatus.Rejected -> status.message
        is NativeEditorWriteStatus.Changed -> status.message
        is NativeEditorWriteStatus.Unconfirmed -> status.message
        is NativeEditorWriteStatus.Drifted -> status.message
        else -> null
    } ?: return
    Text(
        message,
        modifier = Modifier.semantics { contentDescription = message },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Shared dialog frame for record editors. It blocks dismissal while a request is in flight so
 * an unresolved write cannot be abandoned silently. When the target changed after it was read,
 * the primary action becomes [onRefresh], because the draft is based on stale values.
 */
@Composable
internal fun NativeEditorDialog(
    title: String,
    session: NativeEditorWriteSession,
    submitLabel: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean = true,
    destructive: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val refresh = onRefresh?.takeIf { session.status is NativeEditorWriteStatus.Changed }
    val restorable = (session.status as? NativeEditorWriteStatus.Drifted)?.restore != null
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!session.submitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                content()
                NativeEditorWriteStatusText(session)
            }
        },
        dismissButton = {
            TextButton(enabled = !session.submitting, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            if (refresh != null) {
                Button(onClick = refresh) { Text("Refresh") }
            } else if (restorable) {
                // Event-triggered and screen-local: the session never sends it on its own.
                Button(onClick = { scope.launch { session.restoreBaseline() } }) { Text("Restore previous values") }
            } else {
                Button(
                    enabled = submitEnabled && session.canSubmit,
                    onClick = onSubmit,
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    if (session.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(submitLabel)
                    }
                }
            }
        },
    )
}

private const val NONE_CHOICE_ID = "\u0000none"
