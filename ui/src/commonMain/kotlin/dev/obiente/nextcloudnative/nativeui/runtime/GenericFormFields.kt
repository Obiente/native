package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.nextcloudChoiceOptionsMatchingQuery
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

/**
 * Presents contract fields in a human task order without changing their wire names or request
 * bindings. The ordering is deliberately semantic and app-neutral: identify the record first,
 * describe it next, then show supporting choices and advanced controls.
 */
internal fun nativeFormDisplayFields(
    fields: List<FieldSpec>,
    relationFieldIds: Set<String> = emptySet(),
): List<FieldSpec> =
    fields
        .filterNot(FieldSpec::isServerManagedOptionalOrderingField)
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<FieldSpec>>(
                { (_, field) -> field.nativeFormDisplayPriority(field.id in relationFieldIds) },
                IndexedValue<FieldSpec>::index,
            ),
        )
        .map(IndexedValue<FieldSpec>::value)

private fun FieldSpec.isServerManagedOptionalOrderingField(): Boolean {
    if (required) return false
    val semanticId = id
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    return semanticId.isNotEmpty() && semanticId.all { word ->
        word in setOf("display", "index", "order", "ordering", "position", "rank", "sort")
    }
}

private fun FieldSpec.nativeFormDisplayPriority(relation: Boolean): Int {
    val semanticId = id.lowercase().filter(Char::isLetterOrDigit)
    return when {
        semanticId in setOf("name", "title", "subject", "label", "displayname") -> 0
        kind == FieldKind.longText ||
            semanticId in setOf("description", "content", "body", "notes", "summary") -> 10
        kind == FieldKind.file || kind == FieldKind.image -> 15
        isNativeVisualIconField() -> 30
        semanticId in setOf("color", "colour") -> 31
        kind == FieldKind.enumeration -> 40
        kind in setOf(FieldKind.date, FieldKind.dateTime) -> 45
        relation -> 50
        kind == FieldKind.boolean -> 60
        hasNativeRecurrenceRuleSemantics() -> 70
        repeatableObjectInput != null || kind == FieldKind.objectValue -> 80
        else -> 20
    }
}

internal fun nativeFormTitle(view: ViewSpec, resource: ResourceSpec, action: ActionSpec): String =
    if (action.isSettingsWrite(resource)) "Settings" else view.title

internal fun nativeFormSubmitLabel(resource: ResourceSpec, action: ActionSpec): String =
    if (action.isSettingsWrite(resource)) "Save settings" else action.label

internal fun ActionSpec.isSettingsWrite(resource: ResourceSpec): Boolean {
    if (risk == ActionRisk.readOnly || binding.method == dev.obiente.nextcloudnative.nativeui.model.HttpMethod.GET) {
        return false
    }
    if (binding.allowsObservedBodyFields) return true
    val words = (resource.id + " " + resource.name)
        .lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()
    return words.any { it in setOf("config", "configuration", "setting", "settings", "preference", "preferences") } &&
        (binding.bodyFieldNames.isNotEmpty() || inputSchema != null)
}

@Composable
private fun GenericSectionHeading(title: String, supporting: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun GenericRepeatableObjectField(
    field: FieldSpec,
    spec: RepeatableObjectInputSpec,
    rows: List<RepeatableObjectInputRow>,
    error: String? = null,
    enabled: Boolean,
    onRowsChange: (List<RepeatableObjectInputRow>) -> Unit,
) {
    if (spec.minimumItems == 1 && spec.maximumItems == 1 && rows.size == 1) {
        val row = rows.single()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            spec.fields.forEach { itemField ->
                val explicitNull = itemField.id in row.nullFieldIds
                GenericFormField(
                    field = itemField.toNativeRepeatableObjectFieldSpec(),
                    value = row.values[itemField.id].orEmpty(),
                    error = null,
                    enabled = enabled && !explicitNull,
                    filePicker = null,
                    automationFieldId = nativeRepeatableObjectAutomationFieldId(
                        fieldId = field.id,
                        rowIndex = 0,
                        itemFieldId = itemField.id,
                    ),
                    onValueChange = { value ->
                        onRowsChange(
                            updateNativeRepeatableObjectValue(
                                rows = rows,
                                rowIndex = 0,
                                field = itemField,
                                value = value,
                            ),
                        )
                    },
                )
                if (itemField.nullable) {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Send ${itemField.label} as null"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = explicitNull,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                onRowsChange(
                                    updateNativeRepeatableObjectNull(
                                        rows = rows,
                                        rowIndex = 0,
                                        field = itemField,
                                        explicitNull = checked,
                                    ),
                                )
                            },
                        )
                        Text("Send ${itemField.label} as null", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${rows.size} of ${spec.maximumItems} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(
                enabled = enabled && rows.size < spec.maximumItems,
                onClick = {
                    onRowsChange(addNativeRepeatableObjectRow(rows, spec))
                },
                modifier = Modifier.semantics {
                    contentDescription = "Add ${field.id} row"
                },
            ) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add item", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No items added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEachIndexed { rowIndex, row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${field.id} row ${rowIndex + 1}"
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Item ${rowIndex + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            enabled = enabled && rows.size > spec.minimumItems,
                            onClick = {
                                onRowsChange(
                                    removeNativeRepeatableObjectRow(
                                        rows = rows,
                                        index = rowIndex,
                                        spec = spec,
                                    ),
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove ${field.id} row ${rowIndex + 1}"
                            },
                        ) {
                            Text("Remove")
                        }
                    }
                    spec.fields.forEach { itemField ->
                        val automationFieldId = nativeRepeatableObjectAutomationFieldId(
                            fieldId = field.id,
                            rowIndex = rowIndex,
                            itemFieldId = itemField.id,
                        )
                        val explicitNull = itemField.id in row.nullFieldIds
                        GenericFormField(
                            field = itemField.toNativeRepeatableObjectFieldSpec(),
                            value = row.values[itemField.id].orEmpty(),
                            error = null,
                            enabled = enabled && !explicitNull,
                            filePicker = null,
                            automationFieldId = automationFieldId,
                            onValueChange = { value ->
                                onRowsChange(
                                    updateNativeRepeatableObjectValue(
                                        rows = rows,
                                        rowIndex = rowIndex,
                                        field = itemField,
                                        value = value,
                                    ),
                                )
                            },
                        )
                        if (itemField.nullable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = "Send ${itemField.label} as null"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = explicitNull,
                                    enabled = enabled,
                                    onCheckedChange = { checked ->
                                        onRowsChange(
                                            updateNativeRepeatableObjectNull(
                                                rows = rows,
                                                rowIndex = rowIndex,
                                                field = itemField,
                                                explicitNull = checked,
                                            ),
                                        )
                                    },
                                )
                                Text(
                                    "Send ${itemField.label} as null",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun nativeRepeatableObjectAutomationFieldId(
    fieldId: String,
    rowIndex: Int,
    itemFieldId: String,
): String = "$fieldId row ${rowIndex + 1} $itemFieldId"

internal fun RepeatableObjectInputFieldSpec.toNativeRepeatableObjectFieldSpec(): FieldSpec =
    FieldSpec(
        id = id,
        label = label,
        kind = when (kind) {
            RepeatableObjectInputScalarKind.String -> FieldKind.string
            RepeatableObjectInputScalarKind.Integer -> FieldKind.integer
            RepeatableObjectInputScalarKind.Decimal -> FieldKind.decimal
            RepeatableObjectInputScalarKind.Boolean -> FieldKind.boolean
            RepeatableObjectInputScalarKind.Enumeration -> FieldKind.enumeration
        },
        required = required,
        readOnly = false,
        format = format,
        enumValues = enumValues,
        enumLabels = enumLabels,
    )

@Composable
internal fun GenericFormField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    filePicker: NativeFileFieldPicker?,
    automationFieldId: String = field.id,
    onValueChange: (String) -> Unit,
) {
    when {
        field.format == SETTINGS_BOOLEAN_MAP_FORMAT -> {
            val entries = parseNativeBooleanMap(value).orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Choose which details are shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entries.forEach { (key, checked) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(key.dynamicSettingLabel(), modifier = Modifier.weight(1f))
                        Switch(
                            checked = checked,
                            enabled = enabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Toggle $automationFieldId.$key"
                            },
                            onCheckedChange = { onValueChange(updateNativeBooleanMap(value, key, it)) },
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        field.kind == FieldKind.boolean -> Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (value == "true") "On" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Switch(
                checked = value == "true",
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Toggle $automationFieldId"
                },
                onCheckedChange = { onValueChange(it.toString()) },
            )
        }

        field.hasNativeRecurrenceRuleSemantics() ->
            GenericRecurrenceRuleField(
                field,
                value,
                error,
                enabled,
                automationFieldId,
                onValueChange,
            )
        field.kind == FieldKind.enumeration ->
            GenericEnumField(field, value, error, enabled, automationFieldId, onValueChange)
        field.kind == FieldKind.file -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text(requiredFieldLabel(field)) },
                supportingText = error?.let { message -> { Text(message) } },
                isError = error != null,
                singleLine = true,
            )
            filePicker?.let { picker ->
                OutlinedButton(
                    enabled = enabled,
                    onClick = { picker.requestFile(field, onValueChange) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Choose file for $automationFieldId"
                        },
                ) {
                    Icon(NextcloudIcons.File, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (value.isBlank()) "Choose file" else "Choose another file")
                }
            }
        }

        else -> {
            val multiLine = field.kind == FieldKind.longText ||
                field.format in setOf(
                    DYNAMIC_INTEGER_ARRAY_FORMAT,
                    DYNAMIC_STRING_LIST_FORMAT,
                    DYNAMIC_STRING_ARRAY_FORMAT,
                )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text(requiredFieldLabel(field)) },
                supportingText = when {
                    error != null -> ({ Text(error) })
                    field.format in setOf(
                        DYNAMIC_INTEGER_ARRAY_FORMAT,
                        DYNAMIC_STRING_LIST_FORMAT,
                        DYNAMIC_STRING_ARRAY_FORMAT,
                    ) ->
                        ({ Text("One value per line") })
                    else -> null
                },
                isError = error != null,
                minLines = if (multiLine) 4 else 1,
                maxLines = if (multiLine) 12 else 1,
                singleLine = !multiLine,
                placeholder = when (field.kind) {
                    FieldKind.date -> ({ Text("YYYY-MM-DD") })
                    FieldKind.dateTime -> ({ Text("YYYY-MM-DDTHH:MM") })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.kind) {
                        FieldKind.integer ->
                            if (field.format == DYNAMIC_INTEGER_ARRAY_FORMAT) {
                                KeyboardType.Text
                            } else {
                                KeyboardType.Number
                            }
                        FieldKind.decimal, FieldKind.currency -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    },
                ),
            )
        }
    }
}

@Composable
private fun GenericRecurrenceRuleField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    automationFieldId: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var custom by remember(field.id, value) {
        mutableStateOf(value.isNotBlank() && NATIVE_RECURRENCE_PRESETS.none { (_, rule) -> rule == value })
    }
    val selectedLabel = NATIVE_RECURRENCE_PRESETS.firstOrNull { (_, rule) -> rule == value }?.first
        ?: if (custom) "Custom rule" else "Does not repeat"
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Choose $automationFieldId"
                    },
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NATIVE_RECURRENCE_PRESETS.forEach { (label, rule) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            custom = false
                            expanded = false
                            onValueChange(rule)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom rule") },
                    onClick = {
                        custom = true
                        expanded = false
                    },
                )
            }
        }
        if (custom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text("RFC 5545 recurrence rule") },
                placeholder = { Text("FREQ=WEEKLY;INTERVAL=2") },
                singleLine = true,
                isError = error != null,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun FieldSpec.hasNativeRecurrenceRuleSemantics(): Boolean =
    id.lowercase().filter(Char::isLetterOrDigit) in setOf("rrule", "recurrencerule") &&
        kind in setOf(FieldKind.string, FieldKind.longText)

private val NATIVE_RECURRENCE_PRESETS = listOf(
    "Does not repeat" to "",
    "Every day" to "FREQ=DAILY",
    "Every weekday" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
    "Every week" to "FREQ=WEEKLY",
    "Every month" to "FREQ=MONTHLY",
    "Every year" to "FREQ=YEARLY",
)

private fun String.dynamicSettingLabel(): String = replace('-', ' ').replace('_', ' ')
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

@Composable
private fun GenericEnumField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    automationFieldId: String,
    onValueChange: (String) -> Unit,
) {
    NextcloudChoiceField(
        label = requiredFieldLabel(field),
        options = nativeEnumChoiceOptions(field),
        selectedId = value.takeIf(String::isNotBlank),
        selectedLabelFallback = nativeEnumOptionLabel(field, value),
        onSelected = onValueChange,
        enabled = enabled,
        error = error,
        fieldKey = automationFieldId,
        contentDescription = "Choose $automationFieldId",
        searchLabel = "Search ${field.label.lowercase()}",
        searchContentDescription = "Search options for $automationFieldId",
        optionContentDescription = { "Choose $automationFieldId option ${it.label}" },
        leadingContent = if (field.isNativeVisualIconField() ||
            field.id.lowercase().filter(Char::isLetterOrDigit) in setOf("color", "colour")) {
            { option ->
                if (field.isNativeVisualIconField()) {
                    Icon(NextcloudIcons.semanticOrFallback(option.id), null, Modifier.size(20.dp))
                }
                option.id.nativeFormColorOrNull(field)?.let { NativeColorSwatch(it) }
            }
        } else null,
    )
}

private fun nativeEnumChoiceOptions(field: FieldSpec): List<NextcloudChoiceOption> =
    field.enumValues.orEmpty().map { option ->
        NextcloudChoiceOption(option, nativeEnumOptionLabel(field, option),
            searchTerms = listOf(option.dynamicSettingLabel()))
    }

internal fun nativeEnumOptionLabel(field: FieldSpec, option: String): String =
    field.enumLabels?.get(option) ?: option.dynamicSettingLabel()

internal fun nativeEnumOptionsMatchingQuery(field: FieldSpec, query: String): List<String> {
    return nextcloudChoiceOptionsMatchingQuery(nativeEnumChoiceOptions(field), query).map(NextcloudChoiceOption::id)
}


@Composable
private fun NativeColorSwatch(color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(20.dp),
        color = color,
        shape = RoundedCornerShape(5.dp),
        content = {},
    )
}

private fun String.nativeFormColorOrNull(field: FieldSpec): Color? {
    return nativeFormColorArgbOrNull(field)?.let(::Color)
}

internal fun String.nativeFormColorArgbOrNull(field: FieldSpec): Int? {
    if (field.id.lowercase().filter(Char::isLetterOrDigit) !in setOf("color", "colour")) return null
    val hex = trim().removePrefix("#")
    if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return (0xFF000000L or rgb).toInt()
}

internal fun requiredFieldLabel(field: FieldSpec): String = if (field.required) "${field.label} *" else field.label
