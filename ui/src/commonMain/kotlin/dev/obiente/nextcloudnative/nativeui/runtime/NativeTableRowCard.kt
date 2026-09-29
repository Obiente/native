package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/** Limit mobile row presentation to projected user columns, retaining the original record for actions. */
internal fun nativeTableRowCardResource(schema: NativeAppSchema, projection: NativeTableProjection): ResourceSpec? {
    if (schema.app.id != "tables" || !projection.composite) return null
    val fields = projection.resource.fields.filter { it.id in projection.projectedFieldIds }
    return projection.resource.copy(fields = fields).takeIf { fields.isNotEmpty() }
}

internal fun nativeTableRowCardPresentation(resource: ResourceSpec, record: NativeRecord): NativeTableRecordDetailPresentation =
    NativeTableRecordDetailPresentation(nativeRecordPresentation(resource, record).title,
        nativeDetailFields(resource, record))

@Composable
internal fun NativeTableRowCardContent(detail: NativeTableRecordDetailPresentation) {
    Text(detail.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    detail.detailFields.take(3).forEach { field ->
        Text("${field.formatted.label}: ${field.formatted.displayValue}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val remaining = detail.detailFields.size - 3
    if (remaining > 0) Text("$remaining more ${if (remaining == 1) "field" else "fields"}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
