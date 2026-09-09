package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Display data only. The caller retains the meaning and ownership of each stable ID. */
internal data class NextcloudChoiceOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val searchTerms: List<String> = emptyList(),
)

private const val CHOICE_SEARCH_THRESHOLD = 8
private const val CHOICE_MAX_QUERY_LENGTH = 120

internal fun nextcloudChoiceOptionsMatchingQuery(
    options: List<NextcloudChoiceOption>, query: String,
): List<NextcloudChoiceOption> {
    val normalized = query.trim().lowercase()
    return if (normalized.isEmpty()) options else options.filter { option ->
        normalized in option.id.lowercase() || normalized in option.label.lowercase() ||
            option.searchTerms.any { normalized in it.lowercase() }
    }
}

/** A shared single-choice control for native workspaces and contract-rendered forms. */
@Composable
internal fun NextcloudChoiceField(
    label: String,
    options: List<NextcloudChoiceOption>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    placeholder: String = "Choose an option",
    selectedLabelFallback: String? = null,
    fieldKey: String = label,
    contentDescription: String = label,
    searchLabel: String = "Search ${label.lowercase()}",
    searchContentDescription: String = "Search options for $label",
    optionContentDescription: (NextcloudChoiceOption) -> String = { it.label },
    leadingContent: (@Composable (NextcloudChoiceOption) -> Unit)? = null,
) {
    var expanded by remember(fieldKey) { mutableStateOf(false) }
    var query by remember(fieldKey) { mutableStateOf("") }
    var focused by remember(fieldKey) { mutableStateOf(false) }
    val choices = options.distinctBy(NextcloudChoiceOption::id)
    val canOpen = enabled && choices.isNotEmpty()
    LaunchedEffect(fieldKey, canOpen) {
        if (!canOpen) { expanded = false; query = "" }
    }
    val selectedOption = choices.firstOrNull { it.id == selectedId }
        ?: selectedId?.let { NextcloudChoiceOption(it, selectedLabelFallback ?: it) }
    val value = selectedOption?.label ?: placeholder
    val foreground = if (canOpen) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth.coerceAtMost(420.dp)
            Surface(
                onClick = { expanded = true },
                enabled = canOpen,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = foreground,
                shape = RoundedCornerShape(NextcloudRadii.Card),
                border = BorderStroke(2.dp,
                    if (focused && canOpen) MaterialTheme.colorScheme.primary else Color.Transparent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }.semantics {
                    this.contentDescription = contentDescription
                    stateDescription = value
                    role = Role.Button
                    if (error != null) error(error)
                },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selectedOption != null) leadingContent?.invoke(selectedOption)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = when {
                                !canOpen -> foreground
                                error != null -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(NextcloudIcons.ExpandMore, null, Modifier.size(20.dp))
                }
            }
            DropdownMenu(
                expanded = expanded && canOpen,
                onDismissRequest = { expanded = false; query = "" },
                modifier = Modifier.width(menuWidth).heightIn(max = 360.dp),
            ) {
                if (choices.size > CHOICE_SEARCH_THRESHOLD || query.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(CHOICE_MAX_QUERY_LENGTH) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()
                            .semantics { this.contentDescription = searchContentDescription },
                        label = { Text(searchLabel) },
                        singleLine = true,
                    )
                }
                val visibleChoices = nextcloudChoiceOptionsMatchingQuery(choices, query)
                visibleChoices.forEach { option ->
                    key(option.id) {
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            enabled = enabled && option.enabled,
                            leadingIcon = if (leadingContent != null) ({ leadingContent(option) }) else null,
                            trailingIcon = if (option.id == selectedId) ({ Icon(NextcloudIcons.CheckCircle, null) }) else null,
                            modifier = Modifier.semantics {
                                this.contentDescription = optionContentDescription(option)
                                selected = option.id == selectedId
                            },
                            onClick = {
                                expanded = false
                                query = ""
                                onSelected(option.id)
                            },
                        )
                    }
                }
                if (visibleChoices.isEmpty()) DropdownMenuItem(
                    text = { Text("No matching options") }, onClick = {}, enabled = false,
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
