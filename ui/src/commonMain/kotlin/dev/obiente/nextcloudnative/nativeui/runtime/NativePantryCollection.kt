package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.model.*

internal enum class NativePantryCollectionKind(val fallbackTitle: String) {
    Household("Household"), List("List"), Item("Item"),
}

/** Exact reviewed Pantry shapes affect card presentation only, never action binding. */
internal fun nativePantryCollectionKind(schema: NativeAppSchema, resource: ResourceSpec): NativePantryCollectionKind? {
    if (schema.app.id != "pantry" || schema.app.version != "0.34.0" ||
        resource.confidence !in setOf(Confidence.high, Confidence.verified) ||
        resource.evidence.none { it.source in setOf(EvidenceSource.verifiedAppPackage,
            EvidenceSource.openApi, EvidenceSource.verifiedAdapter) } ||
        resource.fields.none { it.id == "name" && it.kind == FieldKind.string }) return null
    return when (resource.id) {
        "houses" -> NativePantryCollectionKind.Household
        "lists" -> NativePantryCollectionKind.List
        "items" -> NativePantryCollectionKind.Item.takeIf {
            resource.fields.any { it.id == "done" && it.kind == FieldKind.boolean } &&
                resource.fields.any { it.id == "quantity" && it.kind == FieldKind.string }
        }
        else -> null
    }
}

internal data class NativePantryRecordPresentation(
    val title: String,
    val description: String?,
    val quantity: String?,
    val completion: String?,
)

internal fun nativePantryRecordPresentation(kind: NativePantryCollectionKind, record: NativeRecord): NativePantryRecordPresentation {
    fun text(field: String) = record.presentationValue(field)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    return NativePantryRecordPresentation(
        title = text("name") ?: kind.fallbackTitle,
        description = text("description"),
        quantity = text("quantity").takeIf { kind == NativePantryCollectionKind.Item },
        completion = if (kind != NativePantryCollectionKind.Item) null else when (text("done")) {
            "true" -> "Completed"
            "false" -> "To do"
            else -> "Status unavailable"
        },
    )
}

@Composable
internal fun NativePantryRecordContent(kind: NativePantryCollectionKind, record: NativeRecord) {
    val row = nativePantryRecordPresentation(kind, record)
    Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
    row.description?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (row.quantity != null || row.completion != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.quantity?.let { Text("Quantity: $it", style = MaterialTheme.typography.labelLarge) }
            row.completion?.let { Text(it, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Adds Pantry content without replacing the task renderer's guarded completion state. */
@Composable
internal fun NativePantryTaskDetails(record: NativeRecord) {
    val row = nativePantryRecordPresentation(NativePantryCollectionKind.Item, record)
    row.description?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    row.quantity?.let { Text("Quantity: $it", style = MaterialTheme.typography.labelLarge) }
}
