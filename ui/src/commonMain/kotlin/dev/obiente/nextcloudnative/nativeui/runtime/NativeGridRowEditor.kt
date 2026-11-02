package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import kotlinx.coroutines.launch

internal sealed interface NativeGridRowEditorTarget {
    data object Create : NativeGridRowEditorTarget

    data class Edit(val row: NativeGridRow) : NativeGridRowEditorTarget

    data class Delete(val row: NativeGridRow, val title: String) : NativeGridRowEditorTarget
}

/**
 * Screen-local grid row editing. Drafts are ordinary remembered UI state on purpose: a row
 * draft is short and the server keeps no partial row, so losing it on process death loses no
 * server data. Durable state stays with the server and the authoritative refresh.
 */
@Stable
internal class NativeGridRowEditing {
    var target by mutableStateOf<NativeGridRowEditorTarget?>(null)
        private set
    var drafts by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val session = NativeEditorWriteSession()

    fun open(plan: NativeGridRowWritePlan, next: NativeGridRowEditorTarget) {
        if (session.submitting) return
        target = next
        errors = emptyMap()
        notice = null
        session.reset()
        drafts = when (next) {
            NativeGridRowEditorTarget.Create -> plan.editableColumns.associate { column ->
                column.key to if (column.kind == NativeGridColumnKind.Toggle) "false" else ""
            }
            is NativeGridRowEditorTarget.Edit -> plan.editableColumns.associate { column ->
                column.key to column.draftFrom(next.row.cells[column.key])
            }
            is NativeGridRowEditorTarget.Delete -> emptyMap()
        }
    }

    fun update(columnKey: String, value: String) {
        drafts = drafts + (columnKey to value)
        errors = errors - columnKey
        notice = null
    }

    fun dismiss() {
        if (!session.submitting) {
            target = null
            session.reset()
        }
    }

    suspend fun submit(
        plan: NativeGridRowWritePlan,
        executor: NativeActionExecutor,
        onRefreshRequested: () -> Unit,
    ) {
        val current = target ?: return
        val (result, retry) = when (current) {
            NativeGridRowEditorTarget.Create -> plan.createRequest(drafts) to NativeEditorWriteRetry.NonIdempotent
            is NativeGridRowEditorTarget.Edit -> plan.updateRequest(current.row, drafts) to plan.updateRetry
            is NativeGridRowEditorTarget.Delete -> plan.deleteRequest(current.row) to NativeEditorWriteRetry.Idempotent
        }
        when (result) {
            is NativeGridRowRequestResult.Invalid -> errors = result.fieldErrors
            NativeGridRowRequestResult.NoChanges -> notice = "Nothing has changed."
            is NativeGridRowRequestResult.Unavailable -> notice = result.reason
            is NativeGridRowRequestResult.Ready -> {
                if (session.submit(executor, result.request, retry, onRefreshRequested)) target = null
            }
        }
    }
}

/** Secondary row actions for the shared overflow and long-press menu. */
internal fun nativeGridRowActions(
    plan: NativeGridRowWritePlan?,
    editing: NativeGridRowEditing,
    record: NativeRecord,
    title: String,
): List<NextcloudCardAction> {
    plan ?: return emptyList()
    val row = plan.row(record) ?: return emptyList()
    return listOfNotNull(
        plan.update?.let {
            NextcloudCardAction(
                label = "Edit row",
                semanticId = "grid.row.edit",
                enabled = !editing.session.submitting,
                onClick = { editing.open(plan, NativeGridRowEditorTarget.Edit(row)) },
            )
        },
        plan.delete?.let {
            NextcloudCardAction(
                label = "Delete row",
                semanticId = "grid.row.delete",
                destructive = true,
                enabled = !editing.session.submitting,
                onClick = { editing.open(plan, NativeGridRowEditorTarget.Delete(row, title)) },
            )
        },
    )
}

@Composable
internal fun NativeGridRowEditorDialog(
    plan: NativeGridRowWritePlan,
    editing: NativeGridRowEditing,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val target = editing.target ?: return
    val scope = rememberCoroutineScope()
    val refreshAction = when (target) {
        NativeGridRowEditorTarget.Create -> plan.create?.action
        is NativeGridRowEditorTarget.Edit -> plan.update?.write?.action
        is NativeGridRowEditorTarget.Delete -> plan.delete?.write?.action
    }
    val refresh: () -> Unit = { refreshAction?.let { action -> onActionSucceeded?.invoke(action) } }
    val submit: () -> Unit = { scope.launch { editing.submit(plan, executor, refresh) } }
    if (target is NativeGridRowEditorTarget.Delete) {
        NativeEditorDialog(
            title = "Delete row?",
            session = editing.session,
            submitLabel = "Delete",
            destructive = true,
            onDismiss = editing::dismiss,
            onSubmit = submit,
        ) {
            Text("\"${target.title}\" will be deleted from this table. This cannot be undone here.")
        }
        return
    }
    val enabled = !editing.session.submitting
    NativeEditorDialog(
        title = if (target == NativeGridRowEditorTarget.Create) "New row" else "Edit row",
        session = editing.session,
        submitLabel = if (target == NativeGridRowEditorTarget.Create) "Add row" else "Save",
        onDismiss = editing::dismiss,
        onSubmit = submit,
        onRefresh = { refresh(); editing.dismiss() },
    ) {
        plan.columns.forEach { column ->
            NativeGridCellInput(
                column = column,
                value = editing.drafts[column.key].orEmpty(),
                error = editing.errors[column.key],
                enabled = enabled,
                onValueChange = { value -> editing.update(column.key, value) },
            )
        }
        editing.notice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NativeGridCellInput(
    column: NativeGridColumn,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    if (!column.editable) {
        NativeEditorPreservedValue(column.title, "This column type is kept unchanged here. Its current value is not modified.")
        return
    }
    val options = column.options.map { NativeEditorChoice(it.key, it.label) }
    when (column.kind) {
        NativeGridColumnKind.Text -> NativeEditorTextField(column.title, value, onValueChange, enabled, column.mandatory, error)
        NativeGridColumnKind.LongText -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, multiline = true,
        )
        NativeGridColumnKind.Number -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
            keyboardType = KeyboardType.Decimal,
            supportingText = listOfNotNull(
                column.numberMin?.let { "Minimum ${formatGridNumber(it)}" },
                column.numberMax?.let { "Maximum ${formatGridNumber(it)}" },
            ).joinToString(", ").takeIf(String::isNotEmpty),
        )
        NativeGridColumnKind.Rating -> NativeEditorChoiceField(
            label = column.title,
            options = (0..5).map { stars -> NativeEditorChoice(stars.toString(), if (stars == 1) "1 star" else "$stars stars") },
            selectedId = value,
            onSelected = onValueChange,
            enabled = enabled,
            required = column.mandatory,
            error = error,
        )
        NativeGridColumnKind.Percent -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
            keyboardType = KeyboardType.Number, supportingText = "Percent, 0 to 100",
        )
        NativeGridColumnKind.Choice -> NativeEditorChoiceField(
            column.title, options, value, onValueChange, enabled, column.mandatory, error,
        )
        NativeGridColumnKind.MultiChoice -> NativeEditorMultiChoiceField(
            label = column.title,
            options = options,
            selectedIds = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
            onSelectionChange = { ids -> onValueChange(ids.sorted().joinToString(",")) },
            enabled = enabled,
            required = column.mandatory,
            error = error,
        )
        NativeGridColumnKind.Toggle -> NativeEditorToggleField(
            column.title, value == "true", { checked -> onValueChange(checked.toString()) }, enabled,
        )
        NativeGridColumnKind.DateTime -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "YYYY-MM-DD HH:MM",
        )
        NativeGridColumnKind.Date -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "YYYY-MM-DD",
        )
        NativeGridColumnKind.Time -> NativeEditorTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "HH:MM",
        )
        NativeGridColumnKind.Unsupported -> Unit
    }
}
