package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal fun nativeTableFields(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    maximumColumns: Int = 8,
): List<FieldSpec> {
    if (maximumColumns <= 0) return emptyList()
    val populated = resource.fields.filter { field ->
        field.kind !in setOf(FieldKind.objectValue, FieldKind.image, FieldKind.unknown) &&
            !field.isTechnicalTableField() &&
            !field.isNativeVisualPresentationField() &&
            records.any { record -> !record.presentationValue(field.id).isNullOrBlank() }
    }
    val preferredIds = listOf("name", "title", "displayName", "subject", "description")
    val primary = preferredIds.firstNotNullOfOrNull { id ->
        populated.firstOrNull { field -> field.id.equals(id, ignoreCase = true) }
    } ?: populated.firstOrNull { !it.isTechnicalTableField() }
        ?: populated.firstOrNull { it.id.equals("id", ignoreCase = true) }
    return buildList {
        primary?.let(::add)
        populated.filterNot { it.id == primary?.id }.forEach(::add)
    }.take(maximumColumns)
}

private fun FieldSpec.isTechnicalTableField(): Boolean {
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return isNativeTechnicalIdentifier() || normalized in setOf(
        "etag", "href", "token", "permissions", "permission", "createdby", "lasteditby",
        "order", "orderweight", "sortorder", "orderindex", "position", "sort", "index",
    )
}

internal fun FieldSpec.isNativeTechnicalIdentifier(): Boolean {
    val key = id.substringAfterLast('.')
    return key.endsWith("Id") || key.endsWith("ID") ||
        key.lowercase().endsWith("_id") || key.lowercase().endsWith("-id") ||
        key.lowercase() in setOf(
            "id", "uuid", "accountid", "userid", "groupid", "parentid", "tableid", "rowid",
            "recordid", "columnid", "viewid", "boardid", "cardid", "folderid", "mailboxid",
            "messageid", "projectid", "categoryid",
        )
}
