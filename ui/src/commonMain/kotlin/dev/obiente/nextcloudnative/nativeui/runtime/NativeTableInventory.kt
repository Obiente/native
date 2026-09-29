package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativeTableInventoryPresentation(
    val title: String,
    val description: String?,
    val counts: String?,
    val states: List<String>,
    val details: List<NativeDetailFieldPresentation>,
)

/** Display-only table metadata; no counts or identities are synthesized from other tables. */
internal fun nativeTableInventoryPresentation(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
): NativeTableInventoryPresentation? {
    if (schema.app.id != "tables" || resource.id != "tables") return null
    fun field(key: String) = resource.fields.singleOrNull { it.id.lowercase().filter(Char::isLetterOrDigit) == key }
    fun value(key: String) = field(key)?.let { record.presentationValue(it.id) }
    fun count(key: String, singular: String): String? = value(key)?.toLongOrNull()?.takeIf { it >= 0 }
        ?.let { "$it $singular${if (it == 1L) "" else "s"}" }
    val counts = listOfNotNull(count("rowscount", "row"), count("columnscount", "column")).joinToString(" | ")
        .takeIf(String::isNotBlank)
    val primaryKeys = setOf("title", "name", "description", "rowscount", "columnscount")
    val details = nativeDetailFields(resource, record).filter { detail ->
        detail.fieldId.lowercase().filter(Char::isLetterOrDigit) !in primaryKeys &&
            resource.fields.singleOrNull { it.id == detail.fieldId }?.isNativeTechnicalIdentifier() == false
    }
    return NativeTableInventoryPresentation(
        nativeRecordPresentation(resource, record).title,
        value("description")?.takeIf(String::isNotBlank), counts,
        buildList {
            if (value("archived")?.toBooleanStrictOrNull() == true) add("Archived")
            if (value("favorite")?.toBooleanStrictOrNull() == true) add("Favorite")
        }, details,
    )
}

@Composable
internal fun NativeTableInventoryContent(presentation: NativeTableInventoryPresentation) {
    var expanded by remember(presentation) { mutableStateOf(false) }
    Text(presentation.title, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    presentation.description?.let { Text(it, style = MaterialTheme.typography.bodySmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    presentation.counts?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (presentation.states.isNotEmpty()) Text(presentation.states.joinToString(" | "),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    if (presentation.details.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            presentation.details.forEach { field ->
                Text("${field.formatted.label}: ${field.formatted.displayValue}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
