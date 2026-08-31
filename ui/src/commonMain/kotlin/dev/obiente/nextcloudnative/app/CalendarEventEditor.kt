package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.rememberNativeInlineEditorCloseRequest

private enum class EventRecurrencePreset(
    val label: String,
    val rule: String?,
) {
    None("Does not repeat", null),
    Daily("Daily", "FREQ=DAILY"),
    Weekly("Weekly", "FREQ=WEEKLY"),
    Monthly("Monthly", "FREQ=MONTHLY"),
    Custom("Custom", null),
    ;

    companion object {
        fun forRule(rule: String?): EventRecurrencePreset = entries.firstOrNull { preset ->
            preset != Custom && preset.rule?.equals(rule, ignoreCase = true) == true
        } ?: if (rule.isNullOrBlank()) None else Custom
    }
}

internal fun calendarRecurrenceDescription(rule: String): String {
    val parts = rule.uppercase().split(';').mapNotNull { token ->
        token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 }
    val frequency = when (parts["FREQ"]) {
        "DAILY" -> if (interval == null) "Daily" else "Every $interval days"
        "WEEKLY" -> if (interval == null) "Weekly" else "Every $interval weeks"
        "MONTHLY" -> if (interval == null) "Monthly" else "Every $interval months"
        else -> return "Custom recurrence"
    }
    val days = parts["BYDAY"]?.split(',')?.mapNotNull { day ->
        mapOf("MO" to "Monday", "TU" to "Tuesday", "WE" to "Wednesday", "TH" to "Thursday", "FR" to "Friday", "SA" to "Saturday", "SU" to "Sunday")[day]
    }.orEmpty()
    return if (days.isEmpty()) frequency else "$frequency on ${days.joinToString()}"
}

@Composable
internal fun EventEditorDialog(
    event: GroupwareCalendarEvent?,
    initialDate: String,
    calendars: List<GroupwareCalendar>,
    onDismiss: () -> Unit,
    error: String?,
    recoveryAvailable: Boolean = false,
    onOpenRecovery: () -> Unit = {},
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationDiscardConfirmed: (NextcloudPendingNavigationRequest) -> Unit = onNavigationConfirmed,
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    mutationInProgress: Boolean = false,
    onSave: (EventDraft, GroupwareCalendar) -> Unit,
    embedded: Boolean = false,
    inPlace: Boolean = false,
    backLabel: String = "Back to calendar",
) {
    val initialIsoDate = (event?.start?.take(8) ?: initialDate).compactDateToIso()
    val initialTitle = event?.title.orEmpty()
    val initialStartTime = event?.start?.compactTime() ?: "09:00"
    val initialEndTime = event?.end?.compactTime() ?: "10:00"
    val initialAllDay = event?.allDay ?: false
    val initialLocation = event?.location.orEmpty()
    val initialDescription = event?.description.orEmpty()
    val initialRecurrencePreset = EventRecurrencePreset.forRule(event?.recurrenceRule)
    val initialRecurrenceRule = when (initialRecurrencePreset) {
        EventRecurrencePreset.None -> null
        EventRecurrencePreset.Custom -> event?.recurrenceRule?.trim()?.takeIf(String::isNotBlank)
        else -> initialRecurrencePreset.rule
    }
    val initialCalendarHref = calendars.firstOrNull { it.href == event?.calendarHref }?.href ?: calendars.firstOrNull()?.href
    val initialDraft = remember(event, initialDate) {
        EventDraft(
            title = initialTitle,
            date = initialIsoDate,
            startTime = initialStartTime,
            endTime = initialEndTime,
            allDay = initialAllDay,
            location = initialLocation,
            description = initialDescription,
            recurrenceRule = initialRecurrenceRule,
        )
    }
    val editorStateKey = event?.instanceId ?: "new:$initialDate"
    var title by rememberSaveable(editorStateKey) { mutableStateOf(initialTitle) }
    var date by rememberSaveable(editorStateKey) { mutableStateOf(initialIsoDate) }
    var startTime by rememberSaveable(editorStateKey) { mutableStateOf(initialStartTime) }
    var endTime by rememberSaveable(editorStateKey) { mutableStateOf(initialEndTime) }
    var allDay by rememberSaveable(editorStateKey) { mutableStateOf(initialAllDay) }
    var location by rememberSaveable(editorStateKey) { mutableStateOf(initialLocation) }
    var description by rememberSaveable(editorStateKey) { mutableStateOf(initialDescription) }
    var recurrencePresetName by rememberSaveable(editorStateKey) {
        mutableStateOf(initialRecurrencePreset.name)
    }
    var customRecurrenceRule by rememberSaveable(editorStateKey) {
        mutableStateOf(event?.recurrenceRule.orEmpty())
    }
    var selectedCalendarHref by rememberSaveable(editorStateKey) {
        mutableStateOf(initialCalendarHref)
    }
    val calendar = calendars.firstOrNull { candidate -> candidate.href == selectedCalendarHref }
        ?: calendars.firstOrNull()
    val recurrencePreset = EventRecurrencePreset.entries.firstOrNull { it.name == recurrencePresetName }
        ?: EventRecurrencePreset.None
    val recurrenceRule = when (recurrencePreset) {
        EventRecurrencePreset.None -> null
        EventRecurrencePreset.Custom -> customRecurrenceRule.trim().takeIf(String::isNotBlank)
        else -> recurrencePreset.rule
    }
    val recurrenceValid = recurrencePreset != EventRecurrencePreset.Custom ||
        recurrenceRule?.let(::isSupportedCalendarRecurrenceRuleForWrite) == true
    val currentDraft = EventDraft(
        title = title,
        date = date,
        startTime = startTime,
        endTime = endTime,
        allDay = allDay,
        location = location,
        description = description,
        recurrenceRule = recurrenceRule,
    )
    val dirty = calendarEventDraftIsDirty(
        initial = initialDraft,
        current = currentDraft,
        initialCalendarHref = initialCalendarHref,
        currentCalendarHref = calendar?.href,
    )
    val hasDavChanges = calendarEventDraftHasDavChanges(
        initialDraft,
        currentDraft,
        initialCalendarHref,
        calendar?.href,
    )
    var confirmNavigationDiscard by remember(event) { mutableStateOf(false) }
    var confirmDismiss by remember(editorStateKey) { mutableStateOf(false) }
    val fallbackNavigation = remember(editorStateKey) { NativeInlineEditorNavigation() }
    val inlineNavigation = LocalNativeInlineEditorNavigation.current ?: fallbackNavigation
    val currentDirty by rememberUpdatedState(dirty)
    val currentNavigationConfirmed by rememberUpdatedState(onNavigationConfirmed)
    val currentNavigationDiscardConfirmed by rememberUpdatedState(onNavigationDiscardConfirmed)
    val currentNavigationCancelled by rememberUpdatedState(onNavigationCancelled)
    val closeInlineEditor = rememberNativeInlineEditorCloseRequest(
        enabled = inPlace,
        dirty = dirty,
        submissionBlocked = mutationInProgress,
        onClose = {
            // Incoming navigation owns editor disposal; local close must not invoke it twice.
            if (navigationRequest == null) onDismiss()
        },
        discardTitle = "Discard unsaved event changes?",
        discardMessage = "Your event draft has not been saved.",
        discardActionLabel = "Discard",
        navigation = inlineNavigation,
    )
    LaunchedEffect(inPlace, navigationRequest?.identity) {
        if (inPlace && navigationRequest != null) {
            val request = navigationRequest
            confirmDismiss = false
            confirmNavigationDiscard = false
            inlineNavigation.intercept(
                proceed = {
                    if (currentDirty) currentNavigationDiscardConfirmed(request)
                    else currentNavigationConfirmed(request)
                },
                cancel = { currentNavigationCancelled(request) },
            )
        }
    }
    LaunchedEffect(navigationRequest?.identity, dirty, mutationInProgress) {
        if (inPlace) return@LaunchedEffect
        val request = navigationRequest
        if (request != null || mutationInProgress) confirmDismiss = false
        when {
            request == null -> confirmNavigationDiscard = false
            mutationInProgress -> confirmNavigationDiscard = false
            dirty -> confirmNavigationDiscard = true
            else -> onNavigationConfirmed(request)
        }
    }
    fun requestDismiss() {
        if (mutationInProgress) return
        if (inPlace) closeInlineEditor()
        else if (dirty) confirmDismiss = true else onDismiss()
    }
    val valid = title.isNotBlank() && date.isIsoCalendarDate() &&
        (allDay || startTime.isCalendarTime() && endTime.isCalendarTime()) && recurrenceValid

    CalendarEventSurface(
        onDismissRequest = ::requestDismiss,
        dismissEnabled = !mutationInProgress,
        embedded = embedded,
        inPlace = inPlace,
        backLabel = backLabel,
        title = { Text(calendarEditorTitle(event)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        textStyle = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Schedule", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("All day", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp))
                        Switch(checked = allDay, onCheckedChange = { allDay = it },
                            enabled = !mutationInProgress,
                            modifier = Modifier.semantics { contentDescription = "All day" })
                    }
                }
                item {
                    CalendarDateField(date, onDateChanged = { date = it }, enabled = !mutationInProgress)
                }
                if (!allDay) item {
                    CalendarTimeRangeFields(startTime, endTime, { startTime = it }, { endTime = it }, !mutationInProgress)
                }
                item {
                    CalendarEventChoice(
                        label = "Repeats", value = recurrencePreset.label, enabled = !mutationInProgress,
                        options = EventRecurrencePreset.entries.map { NextcloudChoiceOption(it.name, it.label) },
                        selectedId = recurrencePreset.name,
                        onSelected = { id ->
                            val preset = EventRecurrencePreset.entries.first { it.name == id }
                            recurrencePresetName = preset.name
                            if (preset == EventRecurrencePreset.Custom && customRecurrenceRule.isBlank()) {
                                customRecurrenceRule = "FREQ=WEEKLY;INTERVAL=2"
                            }
                        },
                    )
                }
                if (recurrencePreset == EventRecurrencePreset.Custom) {
                    item {
                        OutlinedTextField(
                            value = customRecurrenceRule,
                            onValueChange = { customRecurrenceRule = it },
                            label = { Text("Recurrence rule") },
                            supportingText = {
                                Text("Daily, weekly, or monthly rule, for example FREQ=WEEKLY;BYDAY=MO,WE")
                            },
                            isError = !recurrenceValid,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !mutationInProgress,
                        )
                    }
                }
                if (event?.recurrenceRule != null) item {
                    Text("Changes apply to the complete recurring series.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Text("Details", style = MaterialTheme.typography.titleSmall) }
                item {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    CalendarEventChoice(
                        label = "Calendar", value = calendar?.displayName ?: "No writable calendar",
                        enabled = !mutationInProgress,
                        options = calendars.map { NextcloudChoiceOption(it.href, it.displayName) },
                        selectedId = calendar?.href,
                        onSelected = { selectedCalendarHref = it },
                    )
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && calendar != null && hasDavChanges && !mutationInProgress,
                onClick = {
                    onSave(
                        currentDraft,
                        requireNotNull(calendar),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            if (recoveryAvailable) {
                OutlinedButton(onClick = onOpenRecovery, enabled = !mutationInProgress) { Text("Recovery options") }
            } else {
                OutlinedButton(onClick = ::requestDismiss, enabled = !mutationInProgress) { Text("Cancel") }
            }
        },
    )

    if (confirmDismiss) {
        CalendarDialogSurface(
            embedded = embedded,
            onDismissRequest = { confirmDismiss = false },
            title = { Text("Discard unsaved event changes?") },
            text = { Text("Your event draft has not been saved.") },
            confirmButton = {
                TextButton(enabled = !mutationInProgress, onClick = { confirmDismiss = false; onDismiss() }) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDismiss = false }) { Text("Keep editing") } },
        )
    }
    if (confirmNavigationDiscard) {
        navigationRequest?.let { request ->
            CalendarDialogSurface(
                embedded = embedded,
                dismissEnabled = !mutationInProgress,
                onDismissRequest = {
                    confirmNavigationDiscard = false
                    onNavigationCancelled(request)
                },
                title = { Text("Discard unsaved event changes?") },
                text = { Text("Your event draft has not been saved.") },
                dismissButton = {
                    TextButton(
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationCancelled(request)
                        },
                    ) { Text("Keep editing") }
                },
                confirmButton = {
                    Button(
                        enabled = !mutationInProgress,
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationDiscardConfirmed(request)
                        },
                    ) { Text("Discard") }
                },
            )
        }
    }
}


internal fun calendarEditorTitle(event: GroupwareCalendarEvent?): String = when {
    event == null -> "New event"
    event.recurrenceRule != null -> "Edit series"
    else -> "Edit event"
}
