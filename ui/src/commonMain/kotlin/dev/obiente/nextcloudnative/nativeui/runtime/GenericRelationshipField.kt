package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericRelationshipField(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val displayField = field.copy(label = field.nativeRelationshipDisplayLabel())
    val clearChoice = nativeScalarRelationClearChoice(displayField)
    when {
        options.isEmpty() && clearChoice == null && paging == null ->
            GenericUnavailableRelationField(displayField, error)
        field.format in setOf(DYNAMIC_INTEGER_ARRAY_FORMAT, DYNAMIC_STRING_ARRAY_FORMAT) ->
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                GenericRelationMultiPicker(
                    displayField,
                    value,
                    options,
                    choicesLoaded,
                    choiceSourceHasRecords,
                    choiceUnavailableReason,
                    paging,
                    error,
                    enabled,
                    onValueChange,
                )
                if (options.isEmpty() && choiceSourceHasRecords) {
                    GenericRelationUnavailableReason(displayField, choiceUnavailableReason)
                }
            }
        else -> Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            GenericRelationPicker(
                field = displayField,
                value = value,
                options = options,
                clearChoice = clearChoice,
                choicesLoaded = choicesLoaded,
                choiceSourceHasRecords = choiceSourceHasRecords,
                choiceUnavailableReason = choiceUnavailableReason,
                paging = paging,
                error = error,
                enabled = enabled,
                onValueChange = onValueChange,
            )
            if (options.isEmpty() && choiceSourceHasRecords) {
                GenericRelationUnavailableReason(displayField, choiceUnavailableReason)
            }
        }
    }
}

@Composable
private fun GenericRelationUnavailableReason(
    field: FieldSpec,
    reason: NativeRelationChoiceUnavailableReason?,
) {
    Text(
        reason.nativeRelationUnavailableMessage(field),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun nativeRelationPaging(
    field: FieldSpec,
    formResource: ResourceSpec,
    schema: NativeAppSchema,
    context: NativeDatasetContext,
): NativeRelatedRecordPaging? = nativeRelationRelationship(field, formResource, schema)
    ?.parentResourceId
    ?.let(context.relatedRecordPaging::get)

@Composable
private fun GenericUnavailableRelationField(field: FieldSpec, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(requiredFieldLabel(field), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics {
                    contentDescription = "Choose ${field.id}"
                },
        ) {
            Text("No verified choices available", modifier = Modifier.weight(1f))
        }
        Text(
            error ?: if (field.required) {
                "Create or load a server record before choosing this required value."
            } else {
                "No choices are available. This optional value will be left empty."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun FieldSpec.nativeRelationshipDisplayLabel(): String {
    val trimmed = label.trim()
    return when {
        trimmed.endsWith(" ids", ignoreCase = true) -> trimmed.dropLast(4)
        trimmed.endsWith(" id", ignoreCase = true) -> trimmed.dropLast(3)
        else -> trimmed
    }.ifBlank { label }
}

@Composable
private fun GenericRelationPicker(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    clearChoice: NativeRelationOption?,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    var retainedSelection by remember(field.id) {
        mutableStateOf<List<NativeRelationOption>>(emptyList())
    }
    LaunchedEffect(value, options) {
        retainedSelection = retainSelectedNativeRelationOptions(
            retained = retainedSelection,
            available = options,
            selectedValues = listOf(value),
        )
    }
    val displayedOptions = remember(options, retainedSelection) {
        (options + retainedSelection).distinctBy(NativeRelationOption::value)
    }
    val selected = displayedOptions.firstOrNull { option -> option.value == value }
    val optionWindow = nativeRelationOptionWindow(displayedOptions, query)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(
            requiredFieldLabel(field),
            style = MaterialTheme.typography.labelLarge,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("Choose ${field.id}")
                            append("; relation loaded ")
                            append(choicesLoaded)
                            append("; relation options ")
                            append(options.size)
                            if (options.isEmpty()) {
                                append("; relation reason ")
                                append(choiceUnavailableReason?.name ?: "unknown")
                            }
                        }
                    },
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selected?.label
                            ?: clearChoice?.label?.takeIf { value.isBlank() }
                            ?: "Select ${field.label}",
                    )
                    selected?.supportingText?.let { supportingText ->
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                if (displayedOptions.size > NATIVE_RELATION_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(NATIVE_RELATION_MAX_QUERY_LENGTH) },
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Small,
                            vertical = NextcloudSpacing.XSmall,
                        ).widthIn(min = 280.dp).semantics {
                            contentDescription = "Search relations for ${field.id}"
                        },
                        label = { Text("Search ${field.label.lowercase()}") },
                        singleLine = true,
                    )
                    GenericRelationSearchGuidance(
                        totalOptionCount = displayedOptions.size,
                        discardedChoiceCount = paging?.discardedChoiceCount ?: 0,
                        query = query,
                        window = optionWindow,
                    )
                }
                clearChoice?.let { choice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.label)
                                choice.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(choice.value)
                            expanded = false
                            query = ""
                        },
                    )
                    if (optionWindow.options.isNotEmpty()) HorizontalDivider()
                }
                if (optionWindow.options.isEmpty() && paging?.loading != true) {
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !choicesLoaded -> "Relation choices unavailable for ${field.id}"
                                choiceSourceHasRecords ->
                                    "No usable relation choices for ${field.id} " +
                                        "reason ${choiceUnavailableReason?.name ?: "unknown"}"
                                else -> "No relation choices for ${field.id}"
                            }
                        },
                        text = {
                            Text(
                                when {
                                    !choicesLoaded -> "${field.label} choices could not be loaded."
                                    choiceSourceHasRecords ->
                                        choiceUnavailableReason.nativeRelationUnavailableMessage(field)
                                    else -> "No ${field.label.lowercase()} choices are available."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                optionWindow.options.forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = "Choose ${field.id} relation ${option.label}"
                        },
                        text = {
                            Column {
                                Text(option.label)
                                option.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(option.value)
                            expanded = false
                            query = ""
                        },
                    )
                }
                GenericRelationPagingItem(paging)
            }
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun NativeRelationChoiceUnavailableReason?.nativeRelationUnavailableMessage(
    field: FieldSpec,
): String = when (this) {
    NativeRelationChoiceUnavailableReason.unsafeIdentity ->
        "The server returned ${field.label.lowercase()} records without a verified selectable identity."
    NativeRelationChoiceUnavailableReason.ambiguousBinding ->
        "The server returned ${field.label.lowercase()} records with conflicting identity data."
    NativeRelationChoiceUnavailableReason.scopeMismatch ->
        "The available ${field.label.lowercase()} records belong to a different parent."
    NativeRelationChoiceUnavailableReason.invalidValue ->
        "The available ${field.label.lowercase()} records do not contain a safe selectable value."
    NativeRelationChoiceUnavailableReason.duplicateValue ->
        "The available ${field.label.lowercase()} records contain duplicate selectable values."
    else ->
        "The available ${field.label.lowercase()} records cannot be selected safely."
}

@Composable
private fun GenericRelationMultiPicker(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    val selectedValues = remember(value, field.format) {
        value.nativeRelationSelectedValues(field.format)
    }
    var retainedSelections by remember(field.id) {
        mutableStateOf<List<NativeRelationOption>>(emptyList())
    }
    LaunchedEffect(selectedValues, options) {
        retainedSelections = retainSelectedNativeRelationOptions(
            retained = retainedSelections,
            available = options,
            selectedValues = selectedValues,
        )
    }
    val displayedOptions = remember(options, retainedSelections) {
        (options + retainedSelections).distinctBy(NativeRelationOption::value)
    }
    val optionWindow = nativeRelationOptionWindow(displayedOptions, query)
    val selectedLabels = displayedOptions.filter { option -> option.value in selectedValues }
        .joinToString(", ", transform = NativeRelationOption::label)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(requiredFieldLabel(field), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("Choose ${field.id}")
                            append("; relation loaded ")
                            append(choicesLoaded)
                            append("; relation options ")
                            append(options.size)
                            if (options.isEmpty()) {
                                append("; relation reason ")
                                append(choiceUnavailableReason?.name ?: "unknown")
                            }
                        }
                    },
            ) {
                Text(
                    selectedLabels.ifBlank { "Choose ${field.label.lowercase()}" },
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                if (displayedOptions.size > NATIVE_RELATION_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(NATIVE_RELATION_MAX_QUERY_LENGTH) },
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Small,
                            vertical = NextcloudSpacing.XSmall,
                        ).widthIn(min = 280.dp).semantics {
                            contentDescription = "Search relations for ${field.id}"
                        },
                        label = { Text("Search ${field.label.lowercase()}") },
                        singleLine = true,
                    )
                    GenericRelationSearchGuidance(
                        totalOptionCount = displayedOptions.size,
                        discardedChoiceCount = paging?.discardedChoiceCount ?: 0,
                        query = query,
                        window = optionWindow,
                    )
                }
                if (optionWindow.options.isEmpty() && paging?.loading != true) {
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !choicesLoaded -> "Relation choices unavailable for ${field.id}"
                                choiceSourceHasRecords ->
                                    "No usable relation choices for ${field.id} " +
                                        "reason ${choiceUnavailableReason?.name ?: "unknown"}"
                                else -> "No relation choices for ${field.id}"
                            }
                        },
                        text = {
                            Text(
                                when {
                                    !choicesLoaded -> "${field.label} choices could not be loaded."
                                    choiceSourceHasRecords ->
                                        choiceUnavailableReason.nativeRelationUnavailableMessage(field)
                                    else -> "No ${field.label.lowercase()} choices are available."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                optionWindow.options.forEach { option ->
                    val selected = option.value in selectedValues
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = "Choose ${field.id} relation ${option.label}"
                        },
                        leadingIcon = {
                            Checkbox(checked = selected, onCheckedChange = null)
                        },
                        text = {
                            Column {
                                Text(option.label)
                                option.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            val updated = if (selected) {
                                selectedValues - option.value
                            } else {
                                selectedValues + option.value
                            }
                            onValueChange(updated.toNativeRelationArray(field.format))
                        },
                    )
                }
                GenericRelationPagingItem(paging)
            }
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GenericRelationPagingItem(paging: NativeRelatedRecordPaging?) {
    paging ?: return
    if (paging.loading) {
        DropdownMenuItem(
            text = { Text("Loading choices...") },
            onClick = {},
            enabled = false,
        )
        return
    }
    paging.loadMore?.let { loadMore ->
        DropdownMenuItem(
            text = { Text(if (paging.error == null) "Load more choices" else "Try loading more choices") },
            onClick = loadMore,
        )
    }
    paging.retry?.let { retry ->
        DropdownMenuItem(
            text = { Text("Retry loading choices") },
            onClick = retry,
        )
    }
    paging.returnToFirstPage?.let { returnToFirstPage ->
        DropdownMenuItem(
            text = {
                Column {
                    Text("Return to first choices")
                    Text(
                        "${paging.discardedChoiceCount} earlier choices were released to keep memory bounded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = returnToFirstPage,
        )
    }
    paging.error?.let { message ->
        DropdownMenuItem(
            text = {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {},
            enabled = false,
        )
    }
}

@Composable
private fun GenericRelationSearchGuidance(
    totalOptionCount: Int,
    discardedChoiceCount: Int,
    query: String,
    window: NativeRelationOptionWindow,
) {
    val message = when {
        query.isBlank() && discardedChoiceCount > 0 ->
            "Showing ${window.options.size} of $totalOptionCount current choices. " +
                "$discardedChoiceCount earlier choices can be restored below."
        query.isBlank() ->
            "Showing the first ${window.options.size} of $totalOptionCount choices. Search to narrow the list."
        window.hasMore ->
            "Showing the first ${window.options.size} matches. Refine your search to see fewer choices."
        window.options.isEmpty() -> "No matching choices."
        else -> "${window.options.size} matching choices."
    }
    Text(
        message,
        modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
