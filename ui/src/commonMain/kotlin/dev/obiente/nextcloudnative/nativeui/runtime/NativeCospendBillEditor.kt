package dev.obiente.nextcloudnative.nativeui.runtime

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

internal sealed interface NativeCospendBillTarget {
    data object Create : NativeCospendBillTarget

    data class Edit(val bill: NativeCospendBill) : NativeCospendBillTarget

    data class Delete(val bill: NativeCospendBill) : NativeCospendBillTarget
}

/**
 * Screen-local bill editing. The draft is ordinary remembered UI state: losing it on process
 * death loses no server data, and an unconfirmed create is reconciled by the bill list refresh.
 */
@Stable
internal class NativeCospendBillEditing {
    var target by mutableStateOf<NativeCospendBillTarget?>(null)
        private set
    var draft by mutableStateOf(NativeCospendBillDraft())
        private set
    var errors by mutableStateOf<Map<NativeCospendBillField, String>>(emptyMap())
        private set
    val session = NativeReviewedWriteSession()

    fun open(plan: NativeCospendBillPlan, next: NativeCospendBillTarget, today: String) {
        if (session.submitting) return
        target = next
        errors = emptyMap()
        session.reset()
        draft = when (next) {
            NativeCospendBillTarget.Create -> plan.newDraft(today)
            is NativeCospendBillTarget.Edit -> next.bill.draft
            is NativeCospendBillTarget.Delete -> next.bill.draft
        }
    }

    fun edit(change: NativeCospendBillDraft.() -> NativeCospendBillDraft) {
        draft = draft.change()
        errors = emptyMap()
    }

    fun dismiss() {
        if (!session.submitting) {
            target = null
            session.reset()
        }
    }

    suspend fun submit(plan: NativeCospendBillPlan, executor: NativeActionExecutor, onRefreshRequested: () -> Unit) {
        val (result, retry) = when (val current = target ?: return) {
            NativeCospendBillTarget.Create -> plan.createRequest(draft) to NativeReviewedWriteRetry.NonIdempotent
            is NativeCospendBillTarget.Edit -> plan.updateRequest(current.bill, draft) to NativeReviewedWriteRetry.Idempotent
            is NativeCospendBillTarget.Delete -> plan.deleteRequest(current.bill) to NativeReviewedWriteRetry.Idempotent
        }
        when (result) {
            is NativeCospendBillRequestResult.Invalid -> errors = result.errors
            NativeCospendBillRequestResult.Unavailable -> errors = emptyMap()
            is NativeCospendBillRequestResult.Ready ->
                if (session.submit(executor, result.request, retry, onRefreshRequested)) target = null
        }
    }
}

internal fun nativeCospendBillActions(
    plan: NativeCospendBillPlan?,
    editing: NativeCospendBillEditing,
    record: NativeRecord,
    today: String,
): List<NextcloudCardAction> {
    plan ?: return emptyList()
    val bill = plan.bill(record) ?: return emptyList()
    return listOfNotNull(
        plan.update?.let {
            NextcloudCardAction("Edit bill", "cospend.bill.edit", enabled = !editing.session.submitting) {
                editing.open(plan, NativeCospendBillTarget.Edit(bill), today)
            }
        },
        plan.delete?.let {
            NextcloudCardAction("Delete bill", "cospend.bill.delete", destructive = true, enabled = !editing.session.submitting) {
                editing.open(plan, NativeCospendBillTarget.Delete(bill), today)
            }
        },
    )
}

@Composable
internal fun NativeCospendBillEditorDialog(
    plan: NativeCospendBillPlan,
    editing: NativeCospendBillEditing,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val target = editing.target ?: return
    val scope = rememberCoroutineScope()
    val refreshAction = when (target) {
        NativeCospendBillTarget.Create -> plan.create?.action
        is NativeCospendBillTarget.Edit -> plan.update?.action
        is NativeCospendBillTarget.Delete -> plan.delete?.action
    }
    val submit: () -> Unit = {
        scope.launch {
            editing.submit(plan, executor) { refreshAction?.let { action -> onActionSucceeded?.invoke(action) } }
        }
    }
    if (target is NativeCospendBillTarget.Delete) {
        NativeReviewedEditorDialog(
            title = "Delete bill?",
            session = editing.session,
            submitLabel = "Delete",
            destructive = true,
            onDismiss = editing::dismiss,
            onSubmit = submit,
        ) {
            Text("\"${target.bill.draft.what}\" moves to this project's trash and no longer counts toward balances.")
        }
        return
    }
    val enabled = !editing.session.submitting
    val draft = editing.draft
    val errors = editing.errors
    // Inactive members stay selectable as owers of an existing bill so editing never drops them.
    val owerChoices = plan.members
        .filter { member -> member.active || member.id.toString() in draft.owerIds }
        .map { NativeReviewedChoice(it.id.toString(), if (it.active) it.name else "${it.name} (inactive)") }
    val payerChoices = plan.members
        .filter { member -> member.active || member.id.toString() == draft.payerId }
        .map { NativeReviewedChoice(it.id.toString(), it.name) }
    NativeReviewedEditorDialog(
        title = if (target == NativeCospendBillTarget.Create) "New bill" else "Edit bill",
        session = editing.session,
        submitLabel = if (target == NativeCospendBillTarget.Create) "Add bill" else "Save",
        onDismiss = editing::dismiss,
        onSubmit = submit,
    ) {
        NativeReviewedTextField("What", draft.what, { value -> editing.edit { copy(what = value) } }, enabled,
            required = true, error = errors[NativeCospendBillField.What])
        NativeReviewedTextField("Amount", draft.amount, { value -> editing.edit { copy(amount = value) } }, enabled,
            required = true, error = errors[NativeCospendBillField.Amount], keyboardType = KeyboardType.Decimal)
        NativeReviewedTextField("Date", draft.date, { value -> editing.edit { copy(date = value) } }, enabled,
            required = true, error = errors[NativeCospendBillField.Date], placeholder = "YYYY-MM-DD")
        NativeReviewedChoiceField("Paid by", payerChoices, draft.payerId, { id -> editing.edit { copy(payerId = id) } },
            enabled, required = true, error = errors[NativeCospendBillField.Payer])
        NativeReviewedMultiChoiceField("For whom", owerChoices, draft.owerIds,
            { ids -> editing.edit { copy(owerIds = ids) } }, enabled, required = true,
            error = errors[NativeCospendBillField.Owers])
        if (plan.categories.isNotEmpty()) {
            NativeReviewedChoiceField("Category", plan.categories, draft.categoryId,
                { id -> editing.edit { copy(categoryId = id) } }, enabled)
        }
        if (plan.paymentModes.isNotEmpty()) {
            NativeReviewedChoiceField("Payment mode", plan.paymentModes, draft.paymentModeId,
                { id -> editing.edit { copy(paymentModeId = id) } }, enabled)
        }
        NativeReviewedTextField("Comment", draft.comment, { value -> editing.edit { copy(comment = value) } }, enabled,
            multiline = true)
    }
}

/** What a finance ledger needs to offer reviewed record writes; null keeps the ledger read-only. */
internal class NativeFinanceWriteContext(
    val schema: dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema,
    val datasetContext: NativeDatasetContext,
    val actionExecutor: NativeActionExecutor,
    val onActionSucceeded: ((ActionSpec) -> Unit)?,
    /** Changes identity with every authoritative load, so an unconfirmed write can be released. */
    val recordsKey: Any?,
)

/** The device's local calendar date as YYYY-MM-DD, or an empty string when it is unavailable. */
internal fun nativeCospendToday(): String =
    dev.obiente.nextcloudnative.app.deckInstantToLocalDateTime(kotlin.time.Clock.System.now().toString())
        ?.date?.takeIf(String::isCospendDate).orEmpty()
