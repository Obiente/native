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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The account's durable store for the one unconfirmed send of a surface, by surface key. Null
 * when the host keeps no account storage; the record then lives only as long as the screen.
 */
internal val LocalNativeSendAttemptStores = staticCompositionLocalOf<((surface: String) -> NativeSendAttemptStore?)?> { null }

/**
 * Answers for one form. They are ordinary remembered UI state: a response is short, nothing is
 * stored on the server until it is sent, and an unconfirmed send is checked through the refreshed
 * form (its `canSubmit` flag) before it can be sent again.
 *
 * A response is a send: the form cannot show whether an earlier one arrived. Each attempt is
 * therefore recorded in [attempts] before its request leaves the device, and a response that may
 * have been sent stays on record, across leaving the screen and restarting the app, until the
 * person decides to send another one or dismisses it.
 */
@Stable
internal class NativeQuestionnaireDraft(
    private val attempts: NativeSendAttemptStore? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    var open by mutableStateOf(false)
        private set
    var answers by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var sent by mutableStateOf(false)
        private set
    val session = NativeEditorWriteSession()

    /** An earlier response that may have been sent. Another one needs an explicit decision. */
    var unconfirmed by mutableStateOf<NativeSendAttempt?>(null)
        private set

    /** True until the stored attempt has been read; nothing is sent meanwhile. */
    var restoring by mutableStateOf(attempts != null)
        private set
    var storageProblem by mutableStateOf<String?>(null)
        private set
    private var resendConfirmed = false

    val canStart: Boolean get() = session.canSubmit && !restoring

    /** Reads the attempt recorded before this screen existed. An unreadable record keeps sending off. */
    suspend fun restore() {
        val store = attempts ?: return
        try {
            unconfirmed = store.load()?.afterRestart()
            storageProblem = null
            restoring = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Persistence fault boundary: an unreadable record may hold a response already sent.
            storageProblem = "An earlier response saved on this device could not be read, so nothing can be sent from here."
        }
    }

    /** [confirmedResend] is the explicit decision to respond again after an unconfirmed response. */
    fun start(confirmedResend: Boolean = false) {
        if (session.submitting || restoring) return
        if (unconfirmed != null && !confirmedResend) return
        resendConfirmed = confirmedResend
        storageProblem = null
        open = true
        sent = false
        errors = emptyMap()
        session.reset()
    }

    fun answer(questionId: String, value: String) {
        answers = answers + (questionId to value)
        errors = errors - questionId
    }

    fun dismiss() {
        if (!session.submitting) open = false
    }

    suspend fun send(plan: NativeQuestionnairePlan, executor: NativeActionExecutor, onRefreshRequested: () -> Unit) {
        when (val result = plan.submitRequest(answers)) {
            is NativeQuestionnaireResult.Invalid -> errors = result.errors
            NativeQuestionnaireResult.Unavailable -> open = false
            is NativeQuestionnaireResult.Ready -> {
                val earlier = unconfirmed
                if (restoring || !session.canSubmit || earlier != null && !resendConfirmed) return
                val attempt = record(result.request) ?: return
                val confirmed = try {
                    session.submit(executor, result.request, NativeEditorWriteRetry.NonIdempotent, onRefreshRequested)
                } catch (cancelled: CancellationException) {
                    // The request may be on its way; the stored record already reads as unconfirmed.
                    unconfirmed = attempt.afterRestart()
                    throw cancelled
                }
                val status = session.status
                when {
                    confirmed -> {
                        settle(null)
                        open = false
                        sent = true
                        answers = emptyMap()
                    }
                    // A refusal answers only this request; an earlier response stays unconfirmed.
                    status is NativeEditorWriteStatus.Rejected || status is NativeEditorWriteStatus.Changed -> settle(earlier)
                    else -> settle(attempt.copy(state = NativeSendAttemptState.MaybeSent, unknownReason = NativeSendUnknownReason.Unconfirmed))
                }
            }
        }
    }

    /** The responses were checked elsewhere, or not knowing is accepted; the warning is dropped. */
    suspend fun dismissUnconfirmed() {
        if (!session.submitting && !restoring) settle(null)
    }

    /** Makes the attempt durable before its request can leave the device, or sends nothing. */
    private suspend fun record(request: NativeActionRequest.Submit): NativeSendAttempt? {
        val attempt = NativeSendAttempt(newNativeSendToken(), clock(), "1 response", nativeSendFingerprint(request))
        val store = attempts ?: return attempt
        return try {
            store.save(attempt)
            attempt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storageProblem = "This device could not keep a record of the response, so it was not sent."
            null
        }
    }

    private suspend fun settle(remaining: NativeSendAttempt?) {
        unconfirmed = remaining
        resendConfirmed = false
        val store = attempts ?: return
        try {
            if (remaining == null) store.clear() else store.save(remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A record left behind only asks for one more decision; it never sends anything.
        }
    }
}

/** Native entry point of one form: what it asks, whether you can respond, and the response sheet. */
@Composable
internal fun NativeQuestionnaireDetail(
    plan: NativeQuestionnairePlan,
    record: NativeRecord,
    executor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val stores = LocalNativeSendAttemptStores.current
    val draft = remember(plan.recordId, stores) { NativeQuestionnaireDraft(stores?.invoke("questionnaire:${plan.recordId}")) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(draft) { draft.restore() }
    // A refreshed form record means the server state was re-read after an unconfirmed send.
    LaunchedEffect(draft, record) { draft.session.acceptAuthoritativeRefresh() }
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
            plan.submit != null && draft.unconfirmed != null -> {
                Text(
                    "An earlier response may already have been sent, and this form cannot show whether it arrived. " +
                        "Sending another one may leave two responses.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { draft.start(confirmedResend = true) }, enabled = draft.canStart) { Text("Send another response") }
                TextButton(onClick = { scope.launch { draft.dismissUnconfirmed() } }, enabled = draft.canStart) { Text("Dismiss this warning") }
            }
            plan.submit != null -> Button(onClick = { draft.start() }, enabled = draft.canStart) { Text("Fill in form") }
            else -> Text(
                plan.unavailableReason.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NativeEditorWriteStatusText(draft.session)
        draft.storageProblem?.let { problem ->
            Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    if (draft.open && plan.submit != null) {
        val enabled = !draft.session.submitting
        NativeEditorDialog(
            title = plan.title,
            session = draft.session,
            submitLabel = "Send response",
            onDismiss = draft::dismiss,
            onSubmit = {
                scope.launch { draft.send(plan, executor) { onActionSucceeded?.invoke(plan.submit.action) } }
            },
        ) {
            draft.storageProblem?.let { problem ->
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            plan.questions.forEach { question ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    if (question.description.isNotBlank()) {
                        Text(question.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    NativeQuestionAnswerInput(question, draft.answers[question.id].orEmpty(), draft.errors[question.id], enabled) {
                        value -> draft.answer(question.id, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeQuestionAnswerInput(
    question: NativeQuestion,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val label = question.text
    when (question.kind) {
        NativeQuestionKind.Short -> NativeEditorTextField(label, value, onValueChange, enabled, question.required, error)
        NativeQuestionKind.Long ->
            NativeEditorTextField(label, value, onValueChange, enabled, question.required, error, multiline = true)
        NativeQuestionKind.Multiple -> NativeEditorMultiChoiceField(
            label, question.options, value.split(',').filter(String::isNotEmpty).toSet(),
            { ids -> onValueChange(question.options.map { it.id }.filter { it in ids }.joinToString(",")) },
            enabled, question.required, error,
        )
        NativeQuestionKind.Single, NativeQuestionKind.Dropdown ->
            NativeEditorChoiceField(label, question.options, value, onValueChange, enabled, question.required, error)
        NativeQuestionKind.Scale -> NativeEditorChoiceField(
            label,
            (question.scaleLowest..question.scaleHighest).map { NativeEditorChoice(it.toString(), it.toString()) },
            value, onValueChange, enabled, question.required, error,
        )
        NativeQuestionKind.Date ->
            NativeEditorTextField(label, value, onValueChange, enabled, question.required, error, placeholder = "YYYY-MM-DD")
        NativeQuestionKind.Time ->
            NativeEditorTextField(label, value, onValueChange, enabled, question.required, error, placeholder = "HH:MM")
        NativeQuestionKind.Unsupported ->
            NativeEditorPreservedValue(label, "This question type cannot be answered here yet and is left blank.")
    }
}
