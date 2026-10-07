package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*

internal data class NativeTableRecordScope(
    val composite: CompositeDataGridSpec,
    val rowRelationship: ResourceRelationshipSpec,
    val columnRelationship: ResourceRelationshipSpec,
    val parentIdentity: String,
)

/** Select a table/view scope from declared relationships and the returned row's parent identity. */
internal fun nativeTableRecordScope(
    schema: NativeAppSchema,
    resourceId: String,
    record: NativeRecord,
): NativeTableRecordScope? {
    if (!record.actionBindingProvenanceValid) return null
    return schema.views.mapNotNull { it.compositeDataGrid }.distinct().mapNotNull { composite ->
        if (composite.rowResourceId != resourceId) return@mapNotNull null
        val row = schema.relationships.singleOrNull {
            it.parentResourceId == composite.parentResourceId && it.childResourceId == resourceId &&
                it.childFieldId != null && it.confidence >= Confidence.high
        } ?: return@mapNotNull null
        val field = requireNotNull(row.childFieldId)
        val declared = record.values[field]
        val observed = record.displayValues[field]
        if (declared != null && observed != null && declared != observed) return@mapNotNull null
        val parent = (declared ?: observed)?.takeIf {
            it.isNotBlank() && it == it.trim() && it.length <= 256 && it.none(Char::isISOControl)
        } ?: return@mapNotNull null
        val column = schema.relationships.singleOrNull {
            it.parentResourceId == composite.parentResourceId && it.childResourceId == composite.columnResourceId &&
                it.childFieldId != null && it.parentFieldId == row.parentFieldId && it.confidence >= Confidence.high
        } ?: return@mapNotNull null
        NativeTableRecordScope(composite, row, column, parent)
    }.distinct().singleOrNull()
}
