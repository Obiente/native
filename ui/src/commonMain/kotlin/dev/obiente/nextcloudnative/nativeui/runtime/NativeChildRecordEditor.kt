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
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import kotlinx.coroutines.launch

internal sealed interface NativeChildRecordTarget {
    data object Create : NativeChildRecordTarget

    data class Edit(val record: NativeChildRecord) : NativeChildRecordTarget

    data class Delete(val record: NativeChildRecord, val title: String) : NativeChildRecordTarget
}

/**
 * Screen-local child record editing. The draft is ordinary remembered UI state: losing it on
 * process death loses no server data, and an unconfirmed create is reconciled by the refresh of
 * the collection it was added to.
 */
@Stable
internal class NativeChildRecordEditing {
    var target by mutableStateOf<NativeChildRecordTarget?>(null)
        private set
    var draft by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val session = NativeEditorWriteSession()

    fun open(plan: NativeChildRecordWritePlan, next: NativeChildRecordTarget, today: String) {
        if (session.submitting) return
        target = next
        errors = emptyMap()
        notice = null
        session.reset()
        draft = when (next) {
            NativeChildRecordTarget.Create -> plan.newDraft(today)
            is NativeChildRecordTarget.Edit -> next.record.draft
            is NativeChildRecordTarget.Delete -> next.record.draft
        }
    }

    fun edit(field: String, value: String) {
        draft = draft + (field to value)
        errors = errors - field
        notice = null
    }

    fun dismiss() {
        if (!session.submitting) {
            target = null
            session.reset()
        }
    }

    suspend fun submit(plan: NativeChildRecordWritePlan, executor: NativeActionExecutor, onRefreshRequested: () -> Unit) {
        val (result, retry) = when (val current = target ?: return) {
            NativeChildRecordTarget.Create -> plan.createRequest(draft) to NativeEditorWriteRetry.NonIdempotent
            is NativeChildRecordTarget.Edit -> plan.updateRequest(current.record, draft) to plan.updateRetry
            is NativeChildRecordTarget.Delete -> plan.deleteRequest(current.record) to NativeEditorWriteRetry.Idempotent
        }
        when (result) {
            is NativeChildRecordRequestResult.Invalid -> errors = result.errors
            NativeChildRecordRequestResult.NoChanges -> notice = "Nothing has changed."
            is NativeChildRecordRequestResult.Unavailable -> notice = result.reason
            is NativeChildRecordRequestResult.Ready ->
                if (session.submit(executor, result.request, retry, onRefreshRequested)) target = null
        }
    }
}

internal fun nativeChildRecordActions(
    plan: NativeChildRecordWritePlan?,
    editing: NativeChildRecordEditing,
    record: NativeRecord,
    title: String,
    today: String,
): List<NextcloudCardAction> {
    plan ?: return emptyList()
    val child = plan.record(record) ?: return emptyList()
    return listOfNotNull(
        plan.update?.takeIf { child.editUnavailableReason == null }?.let {
            NextcloudCardAction("Edit", "record.child.edit", enabled = !editing.session.submitting) {
                editing.open(plan, NativeChildRecordTarget.Edit(child), today)
            }
        },
        plan.delete?.let {
            NextcloudCardAction("Delete", "record.child.delete", destructive = true, enabled = !editing.session.submitting) {
                editing.open(plan, NativeChildRecordTarget.Delete(child, title), today)
            }
        },
    )
}

@Composable
internal fun NativeChildRecordEditorDialog(
    plan: NativeChildRecordWritePlan,
    editing: NativeChildRecordEditing,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    noun: String,
) {
    val target = editing.target ?: return
    val scope = rememberCoroutineScope()
    val refreshAction = when (target) {
        NativeChildRecordTarget.Create -> plan.create?.write?.action
        is NativeChildRecordTarget.Edit -> plan.update?.write?.action
        is NativeChildRecordTarget.Delete -> plan.delete?.write?.action
    }
    val refresh: () -> Unit = { refreshAction?.let { action -> onActionSucceeded?.invoke(action) } }
    val submit: () -> Unit = { scope.launch { editing.submit(plan, executor, refresh) } }
    if (target is NativeChildRecordTarget.Delete) {
        NativeEditorDialog(
            title = "Delete $noun?",
            session = editing.session,
            submitLabel = "Delete",
            destructive = true,
            onDismiss = editing::dismiss,
            onSubmit = submit,
        ) {
            Text("\"${target.title}\" will be deleted. The app decides whether it can be restored.")
        }
        return
    }
    val creating = target == NativeChildRecordTarget.Create
    val operation = (if (creating) plan.create else plan.update) ?: return
    val unreadable = (target as? NativeChildRecordTarget.Edit)?.record?.unreadable.orEmpty()
    val enabled = !editing.session.submitting
    NativeEditorDialog(
        title = if (creating) "New $noun" else "Edit $noun",
        session = editing.session,
        submitLabel = if (creating) "Add" else "Save",
        submitEnabled = operation.unavailableReason == null,
        onDismiss = editing::dismiss,
        onSubmit = submit,
        onRefresh = { refresh(); editing.dismiss() },
    ) {
        operation.unavailableReason?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        operation.shownFields.forEach { field ->
            if (field.input == null || field.name in unreadable) {
                NativeEditorPreservedValue(field.label, "This value is kept unchanged here.")
            } else {
                NativeRecordFieldEditor(field, editing.draft[field.name].orEmpty(), editing.errors[field.name], enabled, plan.expense) {
                    value -> editing.edit(field.name, value)
                }
            }
        }
        editing.notice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NativeRecordFieldEditor(
    field: NativeRecordField,
    value: String,
    error: String?,
    enabled: Boolean,
    expense: Boolean,
    onValueChange: (String) -> Unit,
) {
    val label = when {
        expense && field.role == NativeRecordFieldRole.Person -> "Paid by"
        expense && field.role == NativeRecordFieldRole.People -> "For whom"
        else -> field.label
    }
    when (val input = field.input) {
        is NativeRecordFieldInput.Text -> NativeEditorTextField(label, value, onValueChange, enabled, field.required, error,
            multiline = input.multiline)
        is NativeRecordFieldInput.Number -> NativeEditorTextField(label, value, onValueChange, enabled, field.required, error,
            keyboardType = if (input.integer) KeyboardType.Number else KeyboardType.Decimal)
        NativeRecordFieldInput.Date -> NativeEditorTextField(label, value, onValueChange, enabled, field.required, error,
            placeholder = "YYYY-MM-DD")
        NativeRecordFieldInput.Toggle -> NativeEditorToggleField(label, value == "true", { onValueChange(it.toString()) }, enabled)
        // Inactive choices stay visible only while the record already uses them.
        is NativeRecordFieldInput.Choice -> NativeEditorChoiceField(label,
            input.options.filter { it.active || it.id == value }.map { it.toEditorChoice() },
            value, onValueChange, enabled, field.required, error)
        is NativeRecordFieldInput.MultiChoice -> {
            val selected = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            NativeEditorMultiChoiceField(label,
                input.options.filter { it.active || it.id in selected }.map { it.toEditorChoice() },
                selected, { ids -> onValueChange(input.options.map { it.id }.filter { it in ids }.joinToString(",")) },
                enabled, field.required, error)
        }
        null -> Unit
    }
}

private fun NativeFieldOption.toEditorChoice() = NativeEditorChoice(id, if (active) label else "$label (inactive)")

/** What a finance ledger needs to offer contract record writes; null keeps the ledger read-only. */
internal class NativeFinanceWriteContext(
    val schema: NativeAppSchema,
    val listActionId: String,
    val datasetContext: NativeDatasetContext,
    val actionExecutor: NativeActionExecutor,
    val onActionSucceeded: ((ActionSpec) -> Unit)?,
    /** Changes identity with every authoritative load, so an unconfirmed write can be released. */
    val recordsKey: Any?,
)

/** The device's local calendar date as YYYY-MM-DD, or an empty string when it is unavailable. */
internal fun nativeLocalToday(): String =
    dev.obiente.nextcloudnative.app.deckInstantToLocalDateTime(kotlin.time.Clock.System.now().toString())
        ?.date?.takeIf(String::isIsoCalendarDate).orEmpty()
