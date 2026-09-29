package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.*

internal data class NativeTableRecordDetailPresentation(
    val title: String,
    val fields: List<NativeDetailFieldPresentation>,
    val contextTitle: String? = null,
) {
    val titleField: NativeDetailFieldPresentation? get() = fields.singleOrNull { it.formatted.displayValue == title }
    val detailFields: List<NativeDetailFieldPresentation> get() = fields.filterNot { it == titleField }
}

internal fun nativeTableRecordDetail(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    context: NativeDatasetContext,
): NativeTableRecordDetailPresentation? {
    val scope = nativeTableRecordScope(schema, resource.id, record) ?: return null
    val composite = scope.composite
    val columns = schema.resource(composite.columnResourceId) ?: return null
    val parentId = scope.parentIdentity
    val columnRelationship = scope.columnRelationship
    val columnRecords = context.relatedRecords[columns.id].orEmpty().filter {
        it.values[requireNotNull(columnRelationship.childFieldId)] == parentId
    }
    if (columnRecords.isEmpty()) return null
    val identities = columnRecords.mapNotNull { it.values[composite.columnIdentityFieldId]?.trim()?.takeIf(String::isNotEmpty) }
    if (identities.size != columnRecords.size || identities.distinct().size != identities.size) return null
    val dataField = composite.rowCellMapFieldId
    val cellJson = record.values[dataField] ?: record.structuredValues[dataField]?.tableDetailJson()?.toString() ?: return null
    // This local presentation copy never enters action binding or replaces the authoritative record.
    val displayRecord = record.copy(values = record.values + (dataField to cellJson), actionSafeIdentity = false)
    val projection = nativeTableProjection(resource, listOf(displayRecord), columns, columnRecords, composite)
    val projected = projection.resource.copy(fields = projection.resource.fields.filter {
        it.id in projection.projectedFieldIds
    })
    val fields = nativeDetailFields(projected, projection.records.single()).takeIf { it.isNotEmpty() } ?: return null
    val title = nativeRecordPresentation(projected, projection.records.single()).title
        .takeUnless { it == record.id } ?: "Record"
    val parent = context.parentRecord?.takeIf {
        context.parentResourceId == composite.parentResourceId &&
            it.values[scope.rowRelationship.parentFieldId] == parentId
    }
    val contextTitle = parent?.let { parentRecord ->
        schema.resource(composite.parentResourceId)?.let { parentResource ->
            nativeRecordPresentation(parentResource, parentRecord).title.takeUnless { it == parentRecord.id }
        }
    }
    return NativeTableRecordDetailPresentation(title, fields, contextTitle)
}

@Composable
internal fun NativeTableRecordDetail(detail: NativeTableRecordDetailPresentation) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            detail.contextTitle?.let { Text(it, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary) }
            detail.titleField?.let { Text(it.formatted.label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
        }
        if (detail.detailFields.isNotEmpty()) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    detail.detailFields.forEachIndexed { index, field ->
                        if (index > 0) androidx.compose.material3.HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                            Text(field.formatted.label, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(field.formatted.displayValue, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

private fun NativeStructuredValue.tableDetailJson(): JsonElement? {
    return when (this) {
        is NativeStructuredValue.Scalar -> when (kind) {
            NativeStructuredScalarKind.nullValue -> JsonNull
            NativeStructuredScalarKind.boolean -> value?.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            NativeStructuredScalarKind.number, NativeStructuredScalarKind.string -> value?.let(::JsonPrimitive)
        }
        is NativeStructuredValue.ListValue -> if (omittedItems != 0) null else
            items.map { it.tableDetailJson() ?: return null }.let(::JsonArray)
        is NativeStructuredValue.ObjectValue -> {
            if (omittedEntries != 0 || entries.map { it.key }.distinct().size != entries.size) return null
            entries.associate { it.key to (it.value.tableDetailJson() ?: return null) }.let(::JsonObject)
        }
    }
}
