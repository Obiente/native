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

internal sealed interface NativeTablesRowEditorTarget {
    data object Create : NativeTablesRowEditorTarget

    data class Edit(val row: NativeTablesRowCells, val focusColumnId: Long? = null) : NativeTablesRowEditorTarget

    data class Delete(val row: NativeTablesRowCells, val title: String) : NativeTablesRowEditorTarget
}

/**
 * Screen-local Tables row editing. Drafts are ordinary remembered UI state on purpose: a row
 * draft is short and the server keeps no partial row, so losing it on process death loses no
 * server data. Durable state stays with the server and the authoritative refresh.
 */
@Stable
internal class NativeTablesRowEditing {
    var target by mutableStateOf<NativeTablesRowEditorTarget?>(null)
        private set
    var drafts by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    var errors by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val session = NativeReviewedWriteSession()

    fun open(plan: NativeTablesRowWritePlan, next: NativeTablesRowEditorTarget) {
        if (session.submitting) return
        target = next
        errors = emptyMap()
        notice = null
        session.reset()
        drafts = when (next) {
            NativeTablesRowEditorTarget.Create -> plan.editableColumns.associate { column ->
                column.id to if (column.type == NativeTablesColumnType.SelectionCheck) "false" else ""
            }
            is NativeTablesRowEditorTarget.Edit -> plan.editableColumns.associate { column ->
                column.id to column.draftFrom(next.row.cells[column.id])
            }
            is NativeTablesRowEditorTarget.Delete -> emptyMap()
        }
    }

    fun update(columnId: Long, value: String) {
        drafts = drafts + (columnId to value)
        errors = errors - columnId
        notice = null
    }

    fun dismiss() {
        if (!session.submitting) {
            target = null
            session.reset()
        }
    }

    suspend fun submit(
        plan: NativeTablesRowWritePlan,
        executor: NativeActionExecutor,
        onRefreshRequested: () -> Unit,
    ) {
        val current = target ?: return
        val (result, retry) = when (current) {
            NativeTablesRowEditorTarget.Create -> plan.createRequest(drafts) to NativeReviewedWriteRetry.NonIdempotent
            is NativeTablesRowEditorTarget.Edit -> plan.updateRequest(current.row, drafts) to NativeReviewedWriteRetry.Idempotent
            is NativeTablesRowEditorTarget.Delete -> plan.deleteRequest(current.row) to NativeReviewedWriteRetry.Idempotent
        }
        when (result) {
            is NativeTablesRowRequestResult.Invalid -> errors = result.fieldErrors
            NativeTablesRowRequestResult.NoChanges -> notice = "Nothing has changed."
            NativeTablesRowRequestResult.Unavailable -> notice = "This change is no longer available."
            is NativeTablesRowRequestResult.Ready -> {
                if (session.submit(executor, result.request, retry, onRefreshRequested)) target = null
            }
        }
    }
}

/** Secondary row actions for the shared overflow and long-press menu. */
internal fun nativeTablesRowActions(
    plan: NativeTablesRowWritePlan?,
    editing: NativeTablesRowEditing,
    record: NativeRecord,
    title: String,
): List<NextcloudCardAction> {
    plan ?: return emptyList()
    val row = nativeTablesRowCells(record) ?: return emptyList()
    return listOfNotNull(
        plan.update?.let {
            NextcloudCardAction(
                label = "Edit row",
                semanticId = "tables.row.edit",
                enabled = !editing.session.submitting,
                onClick = { editing.open(plan, NativeTablesRowEditorTarget.Edit(row)) },
            )
        },
        plan.delete?.let {
            NextcloudCardAction(
                label = "Delete row",
                semanticId = "tables.row.delete",
                destructive = true,
                enabled = !editing.session.submitting,
                onClick = { editing.open(plan, NativeTablesRowEditorTarget.Delete(row, title)) },
            )
        },
    )
}

@Composable
internal fun NativeTablesRowEditorDialog(
    plan: NativeTablesRowWritePlan,
    editing: NativeTablesRowEditing,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val target = editing.target ?: return
    val scope = rememberCoroutineScope()
    val refreshAction = when (target) {
        NativeTablesRowEditorTarget.Create -> plan.create?.action
        is NativeTablesRowEditorTarget.Edit -> plan.update?.action
        is NativeTablesRowEditorTarget.Delete -> plan.delete?.action
    }
    val submit: () -> Unit = {
        scope.launch {
            editing.submit(plan, executor) { refreshAction?.let { action -> onActionSucceeded?.invoke(action) } }
        }
    }
    if (target is NativeTablesRowEditorTarget.Delete) {
        NativeReviewedEditorDialog(
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
    NativeReviewedEditorDialog(
        title = if (target == NativeTablesRowEditorTarget.Create) "New row" else "Edit row",
        session = editing.session,
        submitLabel = if (target == NativeTablesRowEditorTarget.Create) "Add row" else "Save",
        onDismiss = editing::dismiss,
        onSubmit = submit,
    ) {
        plan.columns.forEach { column ->
            NativeTablesCellInput(
                column = column,
                value = editing.drafts[column.id].orEmpty(),
                error = editing.errors[column.id],
                enabled = enabled,
                onValueChange = { value -> editing.update(column.id, value) },
            )
        }
        editing.notice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NativeTablesCellInput(
    column: NativeTablesColumn,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    when (column.type) {
        NativeTablesColumnType.TextLine -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
        )
        NativeTablesColumnType.TextLong -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, multiline = true,
        )
        NativeTablesColumnType.TextLink -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
            keyboardType = KeyboardType.Uri, placeholder = "https://",
        )
        NativeTablesColumnType.Number -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
            keyboardType = KeyboardType.Decimal,
            supportingText = listOfNotNull(
                column.numberMin?.let { "Minimum ${formatTablesBound(it)}" },
                column.numberMax?.let { "Maximum ${formatTablesBound(it)}" },
            ).joinToString(", ").takeIf(String::isNotEmpty),
        )
        NativeTablesColumnType.NumberStars -> NativeReviewedChoiceField(
            label = column.title,
            options = (0..5).map { stars ->
                NativeReviewedChoice(stars.toString(), if (stars == 1) "1 star" else "$stars stars")
            },
            selectedId = value,
            onSelected = onValueChange,
            enabled = enabled,
            required = column.mandatory,
            error = error,
        )
        NativeTablesColumnType.NumberProgress -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error,
            keyboardType = KeyboardType.Number, supportingText = "Percent, 0 to 100",
        )
        NativeTablesColumnType.Selection -> NativeReviewedChoiceField(
            label = column.title,
            options = column.options.map { NativeReviewedChoice(it.id.toString(), it.label) },
            selectedId = value,
            onSelected = onValueChange,
            enabled = enabled,
            required = column.mandatory,
            error = error,
        )
        NativeTablesColumnType.SelectionMulti -> NativeReviewedMultiChoiceField(
            label = column.title,
            options = column.options.map { NativeReviewedChoice(it.id.toString(), it.label) },
            selectedIds = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
            onSelectionChange = { ids ->
                onValueChange(ids.mapNotNull(String::toLongOrNull).sorted().joinToString(","))
            },
            enabled = enabled,
            required = column.mandatory,
            error = error,
        )
        NativeTablesColumnType.SelectionCheck -> NativeReviewedToggleField(
            column.title, value == "true", { checked -> onValueChange(checked.toString()) }, enabled,
        )
        NativeTablesColumnType.DateTime -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "YYYY-MM-DD HH:MM",
        )
        NativeTablesColumnType.Date -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "YYYY-MM-DD",
        )
        NativeTablesColumnType.Time -> NativeReviewedTextField(
            column.title, value, onValueChange, enabled, column.mandatory, error, placeholder = "HH:MM",
        )
        NativeTablesColumnType.TextRich, NativeTablesColumnType.Unsupported -> NativeReviewedPreservedValue(
            column.title,
            "This column type is kept unchanged here. Its current value is not modified.",
        )
    }
}

private fun formatTablesBound(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
