package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import kotlinx.coroutines.launch

/**
 * Answers for one form. They are ordinary remembered UI state: a response is short, nothing is
 * stored on the server until it is sent, and an unconfirmed send is checked through the refreshed
 * form (its `canSubmit` flag) before it can be sent again.
 */
@Stable
internal class NativeFormsResponseDraft {
    var open by mutableStateOf(false)
        private set
    var answers by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    var errors by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    var sent by mutableStateOf(false)
        private set
    val session = NativeReviewedWriteSession()

    fun start() {
        if (session.submitting) return
        open = true
        sent = false
        errors = emptyMap()
        session.reset()
    }

    fun answer(questionId: Long, value: String) {
        answers = answers + (questionId to value)
        errors = errors - questionId
    }

    fun dismiss() {
        if (!session.submitting) open = false
    }

    suspend fun send(plan: NativeFormsSubmissionPlan, executor: NativeActionExecutor, onRefreshRequested: () -> Unit) {
        when (val result = plan.submitRequest(answers)) {
            is NativeFormsSubmissionResult.Invalid -> errors = result.errors
            NativeFormsSubmissionResult.Unavailable -> open = false
            is NativeFormsSubmissionResult.Ready ->
                if (session.submit(executor, result.request, NativeReviewedWriteRetry.NonIdempotent, onRefreshRequested)) {
                    open = false
                    sent = true
                    answers = emptyMap()
                }
        }
    }
}

/** Native entry point of one form: what it asks, whether you can respond, and the response sheet. */
@Composable
internal fun NativeFormsSubmissionDetail(
    plan: NativeFormsSubmissionPlan,
    record: NativeRecord,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val draft = remember(plan.formId) { NativeFormsResponseDraft() }
    // A refreshed form record means the server state was re-read after an unconfirmed send.
    androidx.compose.runtime.LaunchedEffect(draft, record) { draft.session.acceptAuthoritativeRefresh() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text(
            plan.title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (plan.description.isNotBlank()) Text(plan.description, style = MaterialTheme.typography.bodyLarge)
        val count = plan.questions.size
        Text(
            if (count == 1) "1 question" else "$count questions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            draft.sent -> Text("Your response was sent.", style = MaterialTheme.typography.bodyLarge)
            plan.submit != null -> Button(onClick = draft::start, enabled = draft.session.canSubmit) { Text("Fill in form") }
            else -> Text(
                plan.unavailableReason.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NativeReviewedWriteStatusText(draft.session)
    }
    if (draft.open && plan.submit != null) {
        val scope = rememberCoroutineScope()
        val enabled = !draft.session.submitting
        NativeReviewedEditorDialog(
            title = plan.title,
            session = draft.session,
            submitLabel = "Send response",
            onDismiss = draft::dismiss,
            onSubmit = {
                scope.launch { draft.send(plan, executor) { onActionSucceeded?.invoke(plan.submit.action) } }
            },
        ) {
            plan.questions.forEach { question ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    if (question.description.isNotBlank()) {
                        Text(question.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    NativeFormsAnswerInput(question, draft.answers[question.id].orEmpty(), draft.errors[question.id], enabled) {
                        value -> draft.answer(question.id, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeFormsAnswerInput(
    question: NativeFormsQuestion,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val label = question.text
    when (question.type) {
        NativeFormsQuestionType.Short -> NativeReviewedTextField(label, value, onValueChange, enabled, question.required, error)
        NativeFormsQuestionType.Long ->
            NativeReviewedTextField(label, value, onValueChange, enabled, question.required, error, multiline = true)
        NativeFormsQuestionType.Multiple -> NativeReviewedMultiChoiceField(
            label, question.options, value.split(',').filter(String::isNotEmpty).toSet(),
            { ids -> onValueChange(question.options.map { it.id }.filter { it in ids }.joinToString(",")) },
            enabled, question.required, error,
        )
        NativeFormsQuestionType.MultipleUnique, NativeFormsQuestionType.Dropdown ->
            NativeReviewedChoiceField(label, question.options, value, onValueChange, enabled, question.required, error)
        NativeFormsQuestionType.LinearScale -> NativeReviewedChoiceField(
            label,
            (question.scaleLowest..question.scaleHighest).map { NativeReviewedChoice(it.toString(), it.toString()) },
            value, onValueChange, enabled, question.required, error,
        )
        NativeFormsQuestionType.Date ->
            NativeReviewedTextField(label, value, onValueChange, enabled, question.required, error, placeholder = "YYYY-MM-DD")
        NativeFormsQuestionType.DateTime -> NativeReviewedTextField(
            label, value, onValueChange, enabled, question.required, error, placeholder = "YYYY-MM-DD HH:MM",
        )
        NativeFormsQuestionType.Time ->
            NativeReviewedTextField(label, value, onValueChange, enabled, question.required, error, placeholder = "HH:MM")
        NativeFormsQuestionType.Unsupported ->
            NativeReviewedPreservedValue(label, "This question type cannot be answered here yet and is left blank.")
    }
}
