package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import kotlinx.coroutines.launch

internal data class NativeCellAddress(val recordId: String, val fieldId: String)

/**
 * Inline cell editing shared by the table grid and the compact record list.
 *
 * Confirmed values are kept per cell so the collection shows the saved value until the next
 * authoritative records arrive. Hold one session per schema and projection, and call
 * [acceptAuthoritativeRecords] when new records load so the server value replaces the override.
 */
@Stable
internal class NativeCellEditSession {
    private val savedValues = mutableStateMapOf<NativeCellAddress, String>()

    var activePlan by mutableStateOf<NativeCellEditPlan?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    fun savedValue(recordId: String, fieldId: String): String? =
        savedValues[NativeCellAddress(recordId, fieldId)]

    /** Drops optimistic overrides when a new authoritative load arrives. */
    fun acceptAuthoritativeRecords() {
        savedValues.clear()
    }

    fun begin(plan: NativeCellEditPlan) {
        val current = savedValue(plan.recordId, plan.field.id) ?: plan.originalValue
        activePlan = plan.copy(originalValue = current)
        draft = current
        error = null
    }

    fun updateDraft(value: String) {
        draft = value
        error = null
    }

    fun dismiss() {
        if (!saving) activePlan = null
    }

    /** Validates and submits the draft; returns the executed action only when the server accepted it. */
    suspend fun save(executor: NativeActionExecutor): ActionSpec? {
        val plan = activePlan ?: return null
        if (saving) return null
        validateNativeCellEdit(plan.field, draft)?.let { message ->
            error = message
            return null
        }
        // Text keeps leading and trailing whitespace exactly as typed; scalar values are normalized.
        val value = if (plan.field.kind in VERBATIM_CELL_KINDS) draft else draft.trim()
        saving = true
        try {
            return when (val result = executor.execute(plan.request(value))) {
                is NativeActionExecutionResult.Success -> {
                    savedValues[NativeCellAddress(plan.recordId, plan.field.id)] = value
                    activePlan = null
                    plan.action
                }
                is NativeActionExecutionResult.Failure -> {
                    error = result.message
                    null
                }
            }
        } finally {
            saving = false
        }
    }
}

private val VERBATIM_CELL_KINDS = setOf(FieldKind.string, FieldKind.longText)

@Composable
internal fun NativeCellEditDialog(
    session: NativeCellEditSession,
    actionExecutor: NativeActionExecutor,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val plan = session.activePlan ?: return
    AlertDialog(
        onDismissRequest = session::dismiss,
        title = { Text("Edit ${plan.field.label}") },
        text = {
            OutlinedTextField(
                value = session.draft,
                onValueChange = session::updateDraft,
                enabled = !session.saving,
                label = { Text(plan.field.label) },
                supportingText = session.error?.let { message -> { Text(message) } },
                isError = session.error != null,
                singleLine = plan.field.kind != FieldKind.longText,
                minLines = if (plan.field.kind == FieldKind.longText) 3 else 1,
            )
        },
        dismissButton = {
            TextButton(enabled = !session.saving, onClick = session::dismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = !session.saving,
                onClick = {
                    scope.launch {
                        session.save(actionExecutor)?.let { action -> onInlineActionSucceeded?.invoke(action) }
                    }
                },
            ) {
                if (session.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
    )
}
