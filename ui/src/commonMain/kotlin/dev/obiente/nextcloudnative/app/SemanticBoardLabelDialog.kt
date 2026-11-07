package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import kotlinx.coroutines.launch

/** Ephemeral dialog state only; durable delivery and recovery belong to the controller. */
@Composable
internal fun SemanticBoardLabelDialog(
    controller: SemanticBoardMembershipController?,
    board: DeckBoard?,
    card: DeckCard,
    busy: Boolean,
    errorMessage: String?,
    onBusyChanged: (Boolean) -> Unit,
    onResult: (NativeActionExecutionResult) -> Unit,
    onCardChanged: (DeckCard) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
    onLegacySubmit: (List<DeckLabel>) -> Unit,
    onManageLabels: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val lifetime = remember(controller, card.boardId, card.stackId, card.id) { MembershipDialogLifetime() }
    val busyChanged by rememberUpdatedState(onBusyChanged)
    val resultChanged by rememberUpdatedState(onResult)
    val cardChanged by rememberUpdatedState(onCardChanged)
    val saved by rememberUpdatedState(onSaved)
    var refreshError by remember(lifetime) { mutableStateOf<String?>(null) }
    var canRepeat by remember(controller, card) { mutableStateOf(false) }
    var revision by remember(controller, card) { mutableStateOf(0) }
    val managed = controller?.takeIf { it.managesMembership }
    DisposableEffect(lifetime) {
        onDispose { lifetime.active = false; busyChanged(false) }
    }
    LaunchedEffect(managed, card, revision) { canRepeat = managed?.canRepeat(card) == true }
    fun run(submitting: Boolean = false, action: suspend () -> NativeActionExecutionResult) {
        if (busy || lifetime.running || !lifetime.active) return
        lifetime.running = true
        busyChanged(true)
        scope.launch {
            try {
                val result = action()
                val current = managed?.refreshCard(card)
                if (lifetime.active) {
                    refreshError = if (current == null) "Labels could not be refreshed. Check again before saving." else null
                    current?.let(cardChanged)
                    revision++
                    resultChanged(result)
                    if (submitting && result is NativeActionExecutionResult.Success) saved()
                }
            } finally { lifetime.running = false; if (lifetime.active) busyChanged(false) }
        }
    }
    key(controller) { DeckUiLabelPickerDialog(card, board?.labels.orEmpty(), busy, refreshError ?: errorMessage, onDismiss,
        onSubmit = { selected ->
            if (managed == null) onLegacySubmit(selected)
            else if (board != null) run(submitting = true) { managed.apply(board, card, selected).dialogResult() }
        },
        onManageLabels = onManageLabels,
        onCheckStatus = managed?.let { { run { it.checkStatus() } } },
        onRepeat = managed?.takeIf { canRepeat && board != null }?.let { { run { it.repeat(checkNotNull(board), card) } } },
        saveEnabled = refreshError == null,
    ) }
}

private class MembershipDialogLifetime { var active = true; var running = false }

private fun BoardMembershipBatchResult.dialogResult(): NativeActionExecutionResult = when (val outcome = result) {
    is NativeActionExecutionResult.Failure -> if (completed == 0) outcome else
        outcome.copy(message = "$completed label changes completed. ${outcome.message}")
    is NativeActionExecutionResult.Success -> outcome
}
