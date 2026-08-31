package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption

/** Calendar retains its recurrence/calendar identities; the choice interaction is shared. */
@Composable
internal fun CalendarEventChoice(
    label: String,
    value: String,
    enabled: Boolean,
    options: List<NextcloudChoiceOption>,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    NextcloudChoiceField(
        label = label,
        options = options,
        selectedId = selectedId,
        onSelected = onSelected,
        enabled = enabled && options.size > 1,
        selectedLabelFallback = value,
        placeholder = value,
    )
}
