package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDateField(date: String, onDateChanged: (String) -> Unit, enabled: Boolean) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = date,
        onValueChange = onDateChanged,
        enabled = enabled,
        label = { Text("Date") },
        placeholder = { Text("YYYY-MM-DD") },
        supportingText = if (!date.isIsoCalendarDate()) ({ Text("Use YYYY-MM-DD") }) else null,
        isError = !date.isIsoCalendarDate(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(enabled = enabled, onClick = { choosing = true }) {
                Icon(NextcloudIcons.Calendar, contentDescription = "Choose event date")
            }
        },
    )
    if (choosing) {
        val state = rememberDatePickerState(initialSelectedDateMillis = calendarDateEpochMillis(date), yearRange = 1..9999)
        DatePickerDialog(
            onDismissRequest = { choosing = false },
            confirmButton = {
                TextButton(enabled = enabled && state.selectedDateMillis != null, onClick = {
                    state.selectedDateMillis?.let { onDateChanged(calendarDateFromEpochMillis(it)) }
                    choosing = false
                }) { Text("Choose date") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
internal fun CalendarTimeRangeFields(
    startTime: String,
    endTime: String,
    onStartChanged: (String) -> Unit,
    onEndChanged: (String) -> Unit,
    enabled: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale >= 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarTimeField("Starts (UTC)", startTime, onStartChanged, enabled, Modifier.fillMaxWidth())
                CalendarTimeField("Ends (UTC)", endTime, onEndChanged, enabled, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarTimeField("Starts (UTC)", startTime, onStartChanged, enabled, Modifier.weight(1f))
                CalendarTimeField("Ends (UTC)", endTime, onEndChanged, enabled, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarTimeField(
    label: String,
    time: String,
    onTimeChanged: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = time,
        onValueChange = onTimeChanged,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = !time.isCalendarTime(),
        modifier = modifier,
        trailingIcon = {
            IconButton(enabled = enabled, onClick = { choosing = true }) {
                Icon(NextcloudIcons.Clock, contentDescription = "Choose $label")
            }
        },
    )
    if (choosing) {
        val valid = time.isCalendarTime()
        val state = rememberTimePickerState(
            initialHour = if (valid) time.take(2).toInt() else 9,
            initialMinute = if (valid) time.takeLast(2).toInt() else 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(label) },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    onTimeChanged("${state.hour.toString().padStart(2, '0')}:${state.minute.toString().padStart(2, '0')}")
                    choosing = false
                }) { Text("Choose time") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}

internal fun calendarDateEpochMillis(date: String): Long? =
    if (date.isIsoCalendarDate()) Instant.parse("${date}T00:00:00Z").toEpochMilliseconds() else null

internal fun calendarDateFromEpochMillis(value: Long): String =
    Instant.fromEpochMilliseconds(value).toString().substringBefore('T')
