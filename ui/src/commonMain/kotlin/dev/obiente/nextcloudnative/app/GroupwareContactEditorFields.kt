package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Labelled values of one kind (emails, phones or websites), each with its own label and a remove button. */
@Composable
internal fun ContactValueRows(
    title: String,
    noun: String,
    values: List<ContactValue>,
    labels: List<ContactValueLabel>,
    keyboardType: KeyboardType,
    enabled: Boolean,
    onChange: (List<ContactValue>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        values.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ContactLabelPicker(entry.label, labels, enabled) { label ->
                    onChange(values.toMutableList().also { it[index] = entry.copy(label = label) })
                }
                OutlinedTextField(
                    value = entry.value,
                    onValueChange = { text -> onChange(values.toMutableList().also { it[index] = entry.copy(value = text.take(2_000)) }) },
                    label = { Text(noun.replaceFirstChar(Char::uppercase)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                )
                IconButton(onClick = { onChange(values.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                    Icon(NextcloudIcons.Close, "Remove $noun ${index + 1}")
                }
            }
        }
        if (values.size < MAX_CONTACT_VALUES) {
            TextButton(onClick = { onChange(values + ContactValue(labels.first(), "")) }, enabled = enabled) {
                Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                Text("Add $noun")
            }
        }
    }
}

@Composable
internal fun ContactAddressRows(
    addresses: List<ContactAddress>,
    enabled: Boolean,
    onChange: (List<ContactAddress>) -> Unit,
) {
    val labels = listOf(ContactValueLabel.Home, ContactValueLabel.Work, ContactValueLabel.Other)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Addresses", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        addresses.forEachIndexed { index, address ->
            fun update(changed: ContactAddress) = onChange(addresses.toMutableList().also { it[index] = changed })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ContactLabelPicker(address.label, labels, enabled) { update(address.copy(label = it)) }
                    Box(Modifier.weight(1f))
                    IconButton(onClick = { onChange(addresses.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                        Icon(NextcloudIcons.Close, "Remove address ${index + 1}")
                    }
                }
                ContactTextField("Street", address.street, enabled) { update(address.copy(street = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContactTextField("Postal code", address.postalCode, enabled, Modifier.weight(1f)) { update(address.copy(postalCode = it)) }
                    ContactTextField("City", address.locality, enabled, Modifier.weight(2f)) { update(address.copy(locality = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContactTextField("State or region", address.region, enabled, Modifier.weight(1f)) { update(address.copy(region = it)) }
                    ContactTextField("Country", address.country, enabled, Modifier.weight(1f)) { update(address.copy(country = it)) }
                }
            }
        }
        if (addresses.size < MAX_CONTACT_VALUES) {
            TextButton(onClick = { onChange(addresses + ContactAddress(ContactValueLabel.Home)) }, enabled = enabled) {
                Icon(NextcloudIcons.Add, null, Modifier.size(18.dp))
                Text("Add address")
            }
        }
    }
}

/** Contact groups (vCard CATEGORIES), shown as removable chips with a field to add one. */
@Composable
internal fun ContactGroupsField(
    groups: List<String>,
    suggestions: List<String>,
    enabled: Boolean,
    title: String = "Groups",
    fieldLabel: String = "Add to group",
    onChange: (List<String>) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    fun add(name: String = entry) {
        val group = name.trim().filterNot { it == ',' || it.isISOControl() }.take(100)
        if (group.isNotEmpty() && groups.none { it.equals(group, ignoreCase = true) } && groups.size < MAX_CONTACT_VALUES) {
            onChange(groups + group)
        }
        entry = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { group ->
                InputChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onChange(groups - group) },
                    label = { Text(group) },
                    trailingIcon = { Icon(NextcloudIcons.Close, "Remove $group", Modifier.size(16.dp)) },
                )
            }
        }
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it.take(100) },
            label = { Text(fieldLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { TextButton(onClick = { add() }, enabled = enabled && entry.isNotBlank()) { Text("Add") } },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        val matching = suggestions.filter { candidate ->
            groups.none { it.equals(candidate, ignoreCase = true) } &&
                (entry.isBlank() || candidate.contains(entry.trim(), ignoreCase = true))
        }.take(8)
        if (matching.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matching.forEach { suggestion ->
                    TextButton(onClick = { add(suggestion) }, enabled = enabled) { Text("+ $suggestion") }
                }
            }
        }
    }
}

@Composable
internal fun ContactTextField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    supportingText: String? = null,
    isError: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(10_000)) },
        label = { Text(label) },
        singleLine = minLines == 1,
        minLines = minLines,
        isError = isError,
        supportingText = supportingText?.let { text -> { Text(text) } },
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
private fun ContactLabelPicker(
    selected: ContactValueLabel,
    labels: List<ContactValueLabel>,
    enabled: Boolean,
    onSelect: (ContactValueLabel) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(selected.title)
            Icon(NextcloudIcons.ExpandMore, "Change label", Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { label ->
                DropdownMenuItem(text = { Text(label.title) }, onClick = { open = false; onSelect(label) })
            }
        }
    }
}
