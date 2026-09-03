package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.delay

@Composable
fun DeckUiBoardEditorDialog(
    board: DeckBoard?,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (DeckUiBoardDraft) -> Unit,
) {
    var draft by remember(board) {
        mutableStateOf(
            DeckUiBoardDraft(
                title = board?.title.orEmpty(),
                color = board?.color ?: DECK_UI_COLOR_OPTIONS.first().value,
            ),
        )
    }
    val validationError = draft.validationError()
    DeckUiAdaptiveDialog(
        title = if (board == null) "New board" else "Edit board",
        supportingText = "Choose a name and color that make this board easy to find.",
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = if (board == null) "Create board" else "Save changes",
        confirmEnabled = validationError == null && !busy,
        busy = busy,
        onConfirm = { onSubmit(draft.normalized()) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Board name") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = {
                        Text("${draft.title.length}/100")
                    },
                )
            }
            item {
                Text("Board color", style = MaterialTheme.typography.labelLarge)
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(DECK_UI_COLOR_OPTIONS, key = DeckUiColorOption::value) { option ->
                        FilterChip(
                            selected = draft.color.removePrefix("#").equals(option.value, true),
                            enabled = !busy,
                            onClick = { draft = draft.copy(color = option.value) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(18.dp)
                                        .background(option.color, CircleShape),
                                )
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = draft.color,
                    onValueChange = { value ->
                        draft = draft.copy(color = value.removePrefix("#").take(6))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom color") },
                    prefix = { Text("#") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = { Text("Six hexadecimal characters") },
                    isError = draft.color.isNotBlank() && validationError == "Choose a valid board color.",
                )
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}

@Composable
fun DeckUiStackEditorDialog(
    stack: DeckStack?,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (DeckUiStackDraft) -> Unit,
) {
    var draft by remember(stack) {
        mutableStateOf(DeckUiStackDraft(title = stack?.title.orEmpty()))
    }
    val validationError = draft.validationError()
    DeckUiAdaptiveDialog(
        title = if (stack == null) "New list" else "Rename list",
        supportingText = if (stack == null) {
            "Cards in the same stage of work belong in a list."
        } else {
            "Rename this list without changing its cards."
        },
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = if (stack == null) "Create list" else "Save",
        confirmEnabled = validationError == null && !busy,
        busy = busy,
        onConfirm = { onSubmit(draft.normalized()) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("List name") },
                singleLine = true,
                enabled = !busy,
                supportingText = { Text("${draft.title.length}/100") },
            )
            errorMessage?.let { DeckUiInlineError(it) }
        }
    }
}

@Composable
fun DeckUiCardEditorDialog(
    stack: DeckStack,
    card: DeckCard?,
    initialDraft: DeckUiCardDraft? = null,
    recoveredDraft: Boolean = false,
    draftRecoveryFailed: Boolean = false,
    busy: Boolean,
    errorMessage: String?,
    quickDueDates: List<DeckUiDueDateOption>,
    onDismiss: () -> Unit,
    onDiscardRecoveredDraft: () -> Unit = {},
    onDraftChange: (DeckUiCardDraft) -> Unit = {},
    onSubmit: (DeckUiCardDraft) -> Unit,
) {
    var draft by remember(stack, card, recoveredDraft) {
        mutableStateOf(initialDraft ?: card.toDeckUiDraft())
    }
    LaunchedEffect(draft) {
        delay(DECK_DRAFT_PERSIST_DEBOUNCE_MILLIS)
        onDraftChange(draft)
    }
    val validationError = draft.validationError()
    DeckUiAdaptiveDialog(
        title = if (card == null) "New card" else "Edit card",
        supportingText = if (card == null) {
            "Add a card to ${stack.title}."
        } else {
            "Update the card without changing its board or list."
        },
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = if (card == null) "Create card" else "Save changes",
        confirmEnabled = validationError == null && !busy,
        busy = busy,
        onConfirm = { onSubmit(draft.normalized()) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            if (recoveredDraft || draftRecoveryFailed) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(NextcloudRadii.Medium),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(
                                    horizontal = NextcloudSpacing.Medium,
                                    vertical = NextcloudSpacing.Small,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (draftRecoveryFailed) {
                                        "Saved draft could not be restored"
                                    } else {
                                        "Unsaved changes restored"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = if (draftRecoveryFailed) {
                                        "The saved copy remains on this device until you discard it."
                                    } else {
                                        "This draft was recovered from this device."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = onDiscardRecoveredDraft,
                            ) {
                                Text("Discard")
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Card title") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = { Text("${draft.title.length}/255") },
                )
            }
            item {
                OutlinedTextField(
                    value = draft.descriptionMarkdown,
                    onValueChange = { draft = draft.copy(descriptionMarkdown = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    minLines = 4,
                    maxLines = 10,
                    enabled = !busy,
                    supportingText = { Text("Markdown supported") },
                )
            }
            item {
                DeckUiDueDateFields(
                    dueDate = draft.dueDate,
                    dueTime = draft.dueTime,
                    enabled = !busy,
                    quickDueDates = quickDueDates,
                    onDateChanged = {
                        draft = draft.copy(dueDate = it, dueFieldsEdited = true)
                    },
                    onTimeChanged = {
                        draft = draft.copy(dueTime = it, dueFieldsEdited = true)
                    },
                    onClear = {
                        draft = draft.copy(
                            dueDate = "",
                            dueTime = "",
                            dueFieldsEdited = true,
                        )
                    },
                )
            }
            validationError?.takeIf {
                draft.title.isNotBlank() || draft.dueDate.isNotBlank() || draft.dueTime.isNotBlank()
            }?.let { message ->
                item { DeckUiInlineError(message) }
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}

@Composable
fun DeckUiDueDateDialog(
    card: DeckCard,
    busy: Boolean,
    errorMessage: String?,
    quickDueDates: List<DeckUiDueDateOption>,
    onDismiss: () -> Unit,
    onSubmit: (dueDate: String, dueTime: String) -> Unit,
    onClear: (() -> Unit)?,
) {
    val initial = remember(card) { card.toDeckUiDraft() }
    var dueDate by remember(card) { mutableStateOf(initial.dueDate) }
    var dueTime by remember(card) { mutableStateOf(initial.dueTime) }
    val validationError = DeckUiCardDraft(
        title = card.title,
        descriptionMarkdown = "",
        dueDate = dueDate,
        dueTime = dueTime,
    ).validationError()
    DeckUiAdaptiveDialog(
        title = "Due date",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Save due date",
        confirmEnabled = dueDate.isNotBlank() && validationError == null && !busy,
        busy = busy,
        onConfirm = { onSubmit(dueDate.trim(), dueTime.trim()) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            DeckUiDueDateFields(
                dueDate = dueDate,
                dueTime = dueTime,
                enabled = !busy,
                quickDueDates = quickDueDates,
                onDateChanged = { dueDate = it },
                onTimeChanged = { dueTime = it },
                onClear = onClear?.let {
                    {
                        dueDate = ""
                        dueTime = ""
                        it()
                    }
                },
            )
            validationError?.let { DeckUiInlineError(it) }
            errorMessage?.let { DeckUiInlineError(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckUiDueDateFields(
    dueDate: String,
    dueTime: String,
    enabled: Boolean,
    quickDueDates: List<DeckUiDueDateOption>,
    onDateChanged: (String) -> Unit,
    onTimeChanged: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text("Due date", style = MaterialTheme.typography.labelLarge)
        if (quickDueDates.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                items(quickDueDates, key = DeckUiDueDateOption::date) { option ->
                    FilterChip(
                        selected = dueDate == option.date,
                        enabled = enabled,
                        onClick = { onDateChanged(option.date) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 480.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    DeckUiDateField(
                        dueDate = dueDate,
                        enabled = enabled,
                        onChoose = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DeckUiTimeField(
                        dueTime = dueTime,
                        enabled = enabled && dueDate.isNotBlank(),
                        onChoose = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    DeckUiDateField(
                        dueDate = dueDate,
                        enabled = enabled,
                        onChoose = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    DeckUiTimeField(
                        dueTime = dueTime,
                        enabled = enabled && dueDate.isNotBlank(),
                        onChoose = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (onClear != null && (dueDate.isNotBlank() || dueTime.isNotBlank())) {
            TextButton(onClick = onClear, enabled = enabled) {
                Text("Remove due date")
            }
        }
    }
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = deckUiDateToEpochMillis(dueDate),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis
                            ?.let(::deckUiDateFromEpochMillis)
                            ?.let(onDateChanged)
                        showDatePicker = false
                    },
                ) {
                    Text("Choose date")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    if (showTimePicker) {
        val parsedHour = dueTime.takeIf(::isValidDeckUiTime)?.substring(0, 2)?.toInt() ?: 12
        val parsedMinute = dueTime.takeIf(::isValidDeckUiTime)?.substring(3, 5)?.toInt() ?: 0
        val timePickerState = rememberTimePickerState(
            initialHour = parsedHour,
            initialMinute = parsedMinute,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Choose a due time") },
            text = { TimeInput(state = timePickerState) },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTimeChanged(
                            "${timePickerState.hour.toString().padStart(2, '0')}:" +
                                timePickerState.minute.toString().padStart(2, '0'),
                        )
                        showTimePicker = false
                    },
                ) {
                    Text("Choose time")
                }
            },
        )
    }
}

@Composable
private fun DeckUiDateField(
    dueDate: String,
    enabled: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = dueDate,
        onValueChange = {},
        modifier = modifier,
        label = { Text("Date") },
        placeholder = { Text("No date") },
        trailingIcon = {
            TextButton(onClick = onChoose, enabled = enabled) { Text("Choose") }
        },
        singleLine = true,
        readOnly = true,
        enabled = enabled,
    )
}

@Composable
private fun DeckUiTimeField(
    dueTime: String,
    enabled: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = dueTime,
        onValueChange = {},
        modifier = modifier,
        label = { Text("Time") },
        placeholder = { Text("No time") },
        trailingIcon = {
            TextButton(onClick = onChoose, enabled = enabled) { Text("Choose") }
        },
        singleLine = true,
        readOnly = true,
        enabled = enabled,
    )
}

@Composable
internal fun DeckUiAdaptiveDialog(
    title: String,
    supportingText: String?,
    onDismiss: () -> Unit,
    dismissLabel: String,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = false,
    busy: Boolean,
    onConfirm: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp),
                shape = RoundedCornerShape(NextcloudRadii.Large),
                tonalElevation = 4.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(
                            start = NextcloudSpacing.XLarge,
                            top = NextcloudSpacing.XLarge,
                            end = NextcloudSpacing.XLarge,
                            bottom = NextcloudSpacing.Large,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        supportingText?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                    Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        content()
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(
                            NextcloudSpacing.Small,
                            Alignment.End,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onDismiss, enabled = !busy) {
                            Text(dismissLabel)
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            Button(onClick = onConfirm, enabled = confirmEnabled) {
                                Text(if (busy) "Saving..." else confirmLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeckUiInlineError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class DeckUiColorOption(
    val label: String,
    val value: String,
    val color: Color,
)

internal fun DeckCard?.toDeckUiDraft(): DeckUiCardDraft {
    val localDueAt = this?.dueAt?.let(::deckInstantToLocalDateTime)
    return DeckUiCardDraft(
        title = this?.title.orEmpty(),
        descriptionMarkdown = this?.descriptionMarkdown.orEmpty(),
        dueDate = localDueAt?.date.orEmpty(),
        dueTime = localDueAt?.time.orEmpty(),
        dueAtBeforeEditing = this?.dueAt,
    )
}

private val DECK_UI_COLOR_OPTIONS = listOf(
    DeckUiColorOption("Purple", "8b5cf6", Color(0xFF8B5CF6)),
    DeckUiColorOption("Blue", "3b82f6", Color(0xFF3B82F6)),
    DeckUiColorOption("Teal", "14b8a6", Color(0xFF14B8A6)),
    DeckUiColorOption("Green", "22c55e", Color(0xFF22C55E)),
    DeckUiColorOption("Amber", "f59e0b", Color(0xFFF59E0B)),
    DeckUiColorOption("Red", "ef4444", Color(0xFFEF4444)),
)

private const val DECK_DRAFT_PERSIST_DEBOUNCE_MILLIS = 300L
