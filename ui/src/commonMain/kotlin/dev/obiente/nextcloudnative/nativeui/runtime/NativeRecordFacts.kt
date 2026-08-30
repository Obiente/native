package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativeRecordFact(val fieldId: String, val label: String, val value: String)

/** Shows typed task information without turning identifiers or arbitrary payloads into UI. */
internal fun nativeRecordFacts(
    resource: ResourceSpec,
    record: NativeRecord,
    maximumFacts: Int = 4,
): List<NativeRecordFact> {
    if (maximumFacts <= 0) return emptyList()
    val presentation = nativeRecordPresentation(resource, record)
    return resource.fields.asSequence()
        .filter { field -> field.isSafeNativeDetailField(resource) }
        .filter { field -> field.recordFactPriority() > 0 }
        .sortedByDescending(FieldSpec::recordFactPriority)
        .mapNotNull { field ->
            record.presentationValue(field.id)?.takeIf(String::isNotBlank)?.let { raw ->
                val value = formatNativeField(field, raw).displayValue
                if (value == presentation.title || value == presentation.subtitle || value == "Structured data") {
                    null
                } else {
                    NativeRecordFact(field.id, field.label, value)
                }
            }
        }
        .take(maximumFacts)
        .toList()
}

private fun FieldSpec.recordFactPriority(): Int {
    val key = id.lowercase().filter(Char::isLetterOrDigit)
    if (key in setOf("id", "uuid", "token", "etag", "order", "orderweight", "sortorder", "orderindex", "position", "sort", "index") ||
        isNativeTechnicalIdentifier() || key.endsWith("url") || key.contains("password") || key.contains("secret")
    ) return 0
    return when (kind) {
        FieldKind.currency, FieldKind.integer, FieldKind.decimal -> 400
        FieldKind.boolean -> 350
        FieldKind.enumeration -> 300
        FieldKind.date, FieldKind.dateTime -> if (key.contains("due")) 450 else 200
        FieldKind.userReference -> 250
        else -> 0
    }
}

@Composable
internal fun NativeRecordFacts(
    resource: ResourceSpec,
    record: NativeRecord,
    modifier: Modifier = Modifier,
    maximumFacts: Int = 4,
) {
    val facts = nativeRecordFacts(resource, record, maximumFacts)
    if (facts.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        facts.chunked(2).forEach { row ->
            Text(
                row.joinToString("  |  ") { "${it.label}: ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
