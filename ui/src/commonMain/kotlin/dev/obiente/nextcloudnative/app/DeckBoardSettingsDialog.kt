package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Board notification and calendar settings, plus the account-wide Deck calendar default. */
@Composable
internal fun DeckBoardSettingsDialog(
    state: DeckUiSettingsState,
    onChange: (DeckSettingChange) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val board = state.board
    val settings = board.settings
    DeckUiAdaptiveDialog(
        title = "${board.title} settings",
        supportingText = "These settings apply to your account only. Other people keep their own choices.",
        onDismiss = onDismiss,
        dismissLabel = "Close",
        busy = state.busy,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            state.errorMessage?.let { item(key = "error") { DeckUiInlineError(it) } }
            state.notice?.let { item(key = "notice") { DeckToolNotice(it) } }
            if (state.outcomeUnknown || state.userSettingsError != null) {
                item(key = "retry") {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        state.userSettingsError?.let { DeckUiInlineError(it) }
                        OutlinedButton(onClick = onRetry, enabled = !state.busy) { Text("Reload settings") }
                    }
                }
            }
            item(key = "notify-heading") { DeckSettingsHeading("Due date notifications") }
            item(key = "notify-options") {
                when {
                    settings?.dueNotification == null -> DeckToolHint(
                        "This Deck version did not report notification settings for this board.",
                    )
                    else -> Column(Modifier.selectableGroup()) {
                        DeckDueNotification.entries.forEach { option ->
                            DeckSettingsRadioRow(
                                label = option.label,
                                selected = settings.dueNotification == option,
                                enabled = state.canChangeBoardSettings,
                                onSelect = {
                                    onChange(DeckSettingChange.BoardDueNotification(board.id, option))
                                },
                            )
                        }
                    }
                }
            }
            item(key = "calendar-heading") { DeckSettingsHeading("Calendar") }
            item(key = "board-calendar") {
                val value = settings?.showInCalendar
                if (value == null) {
                    DeckToolHint("This Deck version did not report the calendar setting for this board.")
                } else {
                    DeckSettingsSwitchRow(
                        label = "Show this board's due dates in Calendar",
                        checked = value,
                        enabled = state.canChangeBoardSettings,
                        onCheckedChange = { onChange(DeckSettingChange.BoardCalendar(board.id, it)) },
                    )
                }
            }
            item(key = "global-calendar") {
                val value = state.userSettings?.showBoardsInCalendar
                when {
                    state.loadingUserSettings -> CircularProgressIndicator()
                    value == null -> DeckToolHint("The account-wide calendar default is unavailable.")
                    else -> DeckSettingsSwitchRow(
                        label = "Show all boards in Calendar by default",
                        checked = value,
                        enabled = state.canChangeUserSettings,
                        onCheckedChange = { onChange(DeckSettingChange.GlobalCalendar(it)) },
                    )
                }
            }
            if (!board.canChangeDeckSettings()) {
                item(key = "read-only") {
                    DeckToolHint("Board settings need edit permission on this board.")
                }
            }
        }
    }
}

@Composable
private fun DeckSettingsHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = NextcloudSpacing.Medium).semantics { heading() },
    )
}

@Composable
private fun DeckSettingsRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun DeckSettingsSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
