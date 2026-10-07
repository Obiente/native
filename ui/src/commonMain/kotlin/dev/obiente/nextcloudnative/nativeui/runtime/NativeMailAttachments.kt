package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.formatByteSize

/** Display-only attachment metadata. Remote IDs and URLs never become action authority. */
internal data class NativeMailAttachment(val name: String, val mime: String?, val size: String?)

internal data class NativeMailAttachments(val items: List<NativeMailAttachment>, val incomplete: Boolean)

internal fun nativeMailAttachments(record: NativeRecord): NativeMailAttachments {
    var incomplete = false
    val items = record.structuredValues.entries
        .filter { it.key.attachmentSemanticKey() in setOf("attachments", "inlineattachments") }
        .flatMap { (_, value) ->
            val candidates = when (value) {
                is NativeStructuredValue.ListValue -> {
                    incomplete = incomplete || value.omittedItems > 0 || value.items.size > 64
                    value.items.take(64)
                }
                is NativeStructuredValue.ObjectValue -> listOf(value)
                is NativeStructuredValue.Scalar -> emptyList()
            }
            candidates.mapNotNull { candidate ->
                if ((candidate as? NativeStructuredValue.ObjectValue)?.omittedEntries != 0) incomplete = true
                candidate.mailAttachment().also { if (it == null) incomplete = true }
            }
        }
    return NativeMailAttachments(items.take(64), incomplete || items.size > 64)
}

private fun NativeStructuredValue.mailAttachment(): NativeMailAttachment? {
    val objectValue = this as? NativeStructuredValue.ObjectValue ?: return null
    val keys = objectValue.entries.map { it.key.attachmentSemanticKey() }
    if (keys.distinct().size != keys.size) return null
    val fields = objectValue.entries.associate { entry ->
        entry.key.attachmentSemanticKey() to (entry.value as? NativeStructuredValue.Scalar)?.value
    }
    val name = listOf("filename", "name", "title").firstNotNullOfOrNull(fields::get)
        ?.takeIf { it.isNotBlank() && it.length <= 1_024 && it.none(Char::isISOControl) } ?: return null
    val mime = listOf("mime", "mimetype", "contenttype").firstNotNullOfOrNull(fields::get)
        ?.takeIf { it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl) }
    val size = listOf("size", "filesize", "bytes").firstNotNullOfOrNull(fields::get)
        ?.toLongOrNull()?.takeIf { it >= 0 }?.let(::formatByteSize)
    return NativeMailAttachment(name, mime, size)
}

private fun String.attachmentSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)
