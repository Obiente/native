package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativeRecordPresentation(
    val title: String,
    val subtitle: String?,
    val iconKey: String? = null,
    val colorArgb: Int? = null,
)

internal fun nativeRecordPresentation(resource: ResourceSpec, record: NativeRecord): NativeRecordPresentation {
    val iconKey = nativeRecordIconKey(resource, record)
    val colorArgb = nativeRecordColorArgb(resource, record)
    nativeHouseholdPresentation(resource, record)?.let { presentation ->
        return NativeRecordPresentation(presentation.title, presentation.subtitle, iconKey, colorArgb)
    }
    nativeGroupwarePresentation(resource, record)?.let { presentation ->
        return NativeRecordPresentation(presentation.title, presentation.subtitle, iconKey, colorArgb)
    }
    val titleField = nativeRecordTitleField(resource, record)
    val title = titleField
        ?.let { field -> record.presentationValue(field.id)?.let { value -> formatNativeField(field, value).displayValue } }
        ?.takeIf(String::isNotBlank)
        ?: record.id
    val subtitle = resource.fields
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<FieldSpec>> { it.value.subtitlePriority() }
                .thenBy(IndexedValue<FieldSpec>::index),
        )
        .firstNotNullOfOrNull { (_, field) ->
        if (field.id == titleField?.id || field.subtitlePriority() <= 0) return@firstNotNullOfOrNull null
        record.presentationValue(field.id)
            ?.takeIf(String::isNotBlank)
            ?.let { formatNativeField(field, it).displayValue }
            ?.takeIf { value ->
                value.isNotBlank() &&
                    !value.equals(title, ignoreCase = true) &&
                    !value.equals(record.id, ignoreCase = true) &&
                    value != "Structured data" &&
                    !value.isPresentationMimeType()
            }
        }
    return NativeRecordPresentation(title, subtitle, iconKey, colorArgb)
}

internal fun nativeRecordIconKey(resource: ResourceSpec, record: NativeRecord): String? {
    val declaredIconFields = resource.fields.filter(FieldSpec::isNativeVisualIconField)
    if (declaredIconFields.isEmpty()) return null
    val populated = declaredIconFields.mapNotNull { field ->
        record.values[field.id]?.takeIf(String::isNotBlank)
    }
    if (populated.isEmpty()) return null
    val resolved = populated.map { raw ->
        raw.takeIf { value ->
            value.length <= MAX_NATIVE_RECORD_ICON_KEY_LENGTH &&
                value.all { character ->
                    character.isLetterOrDigit() || character in setOf('-', '_', ' ')
                }
        }
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.replace(' ', '-')
            ?: return null
    }.distinct()
    return resolved.singleOrNull()
}

internal fun nativeRecordColorArgb(resource: ResourceSpec, record: NativeRecord): Int? {
    val declaredColorFields = resource.fields.filter { field ->
        field.id.lowercase().filter(Char::isLetterOrDigit) in setOf("color", "colour") &&
            field.kind in setOf(FieldKind.string, FieldKind.enumeration)
    }
    if (declaredColorFields.isEmpty()) return null
    val populated = declaredColorFields.mapNotNull { field ->
        record.values[field.id]
            ?.takeIf(String::isNotBlank)
            ?.nativeFormColorArgbOrNull(field)
    }
    return populated.distinct().singleOrNull()
}

/**
 * MIME types describe transport rather than a record, so they should never occupy the only
 * subtitle slot on cards or collection rows. This parser intentionally avoids a permissive regex:
 * both sides of the slash must be simple MIME tokens and URLs therefore cannot match.
 */
private fun String.isPresentationMimeType(): Boolean {
    val mediaType = substringBefore(';').trim()
    val slash = mediaType.indexOf('/')
    if (slash <= 0 || slash != mediaType.lastIndexOf('/') || slash == mediaType.lastIndex) return false
    fun String.isMimeToken(): Boolean = isNotEmpty() && all { character ->
        character.isLetterOrDigit() || character in setOf('!', '#', '$', '&', '^', '_', '.', '+', '-')
    }
    return mediaType.substring(0, slash).isMimeToken() &&
        mediaType.substring(slash + 1).isMimeToken()
}

private fun nativeRecordTitleField(resource: ResourceSpec, record: NativeRecord): FieldSpec? = resource.fields
    .withIndex()
    .filter { (_, field) -> !record.presentationValue(field.id).isNullOrBlank() && field.titlePriority() > 0 }
    .maxWithOrNull(
        compareBy<IndexedValue<FieldSpec>> { it.value.titlePriority() }
            .thenByDescending(IndexedValue<FieldSpec>::index),
    )
    ?.value

private fun FieldSpec.titlePriority(): Int {
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    return when (normalized) {
        "displayname" -> 500
        "name", "title", "subject" -> 480
        "what", "merchant", "label" -> 470
        "summary" -> 460
        "description" -> 420
        "comment", "note", "notes", "memo" -> 0
        else -> when (kind) {
            FieldKind.string, FieldKind.longText, FieldKind.enumeration ->
                if (isTechnicalPresentationField()) 0 else 200
            FieldKind.userReference -> 160
            else -> 0
        }
    }
}

private fun FieldSpec.subtitlePriority(): Int {
    if (isTechnicalPresentationField() || isBinaryPresentationField()) return 0
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    val semantic = when (normalized) {
        "description", "summary", "subtitle", "note", "notes" -> 500
        "status", "state", "category", "type" -> 420
        "members", "participants", "users", "owner", "assignee" -> 390
        "date", "datetime", "created", "modified", "updated", "duedate" -> 360
        else -> 0
    }
    val typed = when (kind) {
        FieldKind.longText -> 300
        FieldKind.string -> 260
        FieldKind.enumeration -> 250
        FieldKind.date, FieldKind.dateTime -> 240
        FieldKind.currency, FieldKind.decimal -> 230
        FieldKind.objectValue -> 220
        FieldKind.userReference -> 210
        FieldKind.integer -> 0
        FieldKind.boolean, FieldKind.image, FieldKind.file, FieldKind.unknown -> 0
    }
    return semantic + typed
}

private fun FieldSpec.isBinaryPresentationField(): Boolean {
    if (kind == FieldKind.image || kind == FieldKind.file) return true
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return normalized in setOf(
        "imageurl", "imageplaceholderurl", "thumbnailurl", "previewurl", "downloadurl",
        "avatarurl", "coverurl", "contenturl", "enclosureurl",
    )
}

private fun FieldSpec.isTechnicalPresentationField(): Boolean {
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    return normalized in setOf(
        "id", "uuid", "token", "etag", "href", "permissions", "permission", "capabilities",
        "active", "enabled", "deleted", "favorite", "favourite", "archived", "readonly",
        "icon", "symbol", "color", "colour",
    ) || normalized.endsWith("id")
}

internal fun FieldSpec.isNativeVisualIconField(): Boolean =
    id.lowercase().filter(Char::isLetterOrDigit) in setOf("icon", "symbol") &&
        kind in setOf(FieldKind.string, FieldKind.enumeration)

private const val MAX_NATIVE_RECORD_ICON_KEY_LENGTH = 64
