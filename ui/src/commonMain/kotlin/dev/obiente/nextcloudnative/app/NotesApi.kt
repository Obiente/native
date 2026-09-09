package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val NOTES_API_COLLECTION_PATH = "/index.php/apps/notes/api/v1/notes"

internal fun normalizeNoteCategory(value: String): String {
    val trimmed = value.trim().trim('/')
    if (trimmed.isEmpty()) return ""
    require(trimmed.length <= MAX_NOTE_CATEGORY_CHARACTERS) { "The note folder path is too long." }
    require('\\' !in trimmed && trimmed.none(Char::isISOControl)) { "The note folder path is invalid." }
    val segments = trimmed.split('/').map(String::trim)
    require(segments.all { segment ->
        segment.isNotBlank() && segment !in setOf(".", "..") && segment.length <= MAX_NOTE_CATEGORY_SEGMENT_CHARACTERS
    }) {
        "The note folder path contains an invalid segment."
    }
    return segments.joinToString("/")
}

internal fun normalizeNoteTitle(value: String): String {
    val title = value.trim()
    require(title.isNotEmpty()) { "Enter a note title." }
    require(title.length <= MAX_NOTE_TITLE_CHARACTERS) { "The note title is too long." }
    require(title.none(Char::isISOControl) && '/' !in title && '\\' !in title) { "The note title is invalid." }
    return title
}

fun createNoteRequest(
    title: String,
    content: String,
    category: String,
): NextcloudApiRequest {
    require(content.encodeToByteArray().size.toLong() <= MAX_NOTE_BYTES) {
        "Notes larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB cannot be created in the app."
    }
    val body = buildJsonObject {
        put("title", normalizeNoteTitle(title))
        put("content", content)
        put("category", normalizeNoteCategory(category))
    }.toString().encodeToByteArray()
    return NextcloudApiRequest(
        method = NextcloudApiMethod.POST,
        relativePath = NOTES_API_COLLECTION_PATH,
        contentType = NOTES_JSON_CONTENT_TYPE,
        body = body,
    )
}

fun deleteNoteRequest(noteId: Long): NextcloudApiRequest {
    require(noteId >= 0) { "The note ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.DELETE,
        relativePath = "$NOTES_API_COLLECTION_PATH/$noteId",
    )
}

fun notesMutationHeaders(expectedEtag: String?): Map<String, String> {
    val value = expectedEtag?.trim(' ', '\t')?.takeIf(String::isNotEmpty) ?: return emptyMap()
    require(value.length <= MAX_NOTE_ETAG_CHARACTERS) {
        "The note version is invalid."
    }
    val serialized = when {
        value == "*" -> value
        value.isQuotedNoteEtag() -> value
        value.startsWith("W/") -> {
            require(value.removePrefix("W/").isQuotedNoteEtag()) { "The note version is invalid." }
            value
        }
        else -> {
            require(value.all(Char::isNoteEtagCharacter)) { "The note version is invalid." }
            "\"$value\""
        }
    }
    return mapOf("If-Match" to serialized)
}

private fun String.isQuotedNoteEtag(): Boolean =
    length >= 2 && first() == '"' && last() == '"' &&
        substring(1, lastIndex).all(Char::isNoteEtagCharacter)

// RFC 9110 entity-tags are opaque, not quoted-string escapes. Preserve backslashes verbatim.
private fun Char.isNoteEtagCharacter(): Boolean = code == 0x21 || code in 0x23..0x7e || code in 0x80..0xff

private const val NOTES_JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val MAX_NOTE_TITLE_CHARACTERS = 255
private const val MAX_NOTE_CATEGORY_CHARACTERS = 1_024
private const val MAX_NOTE_CATEGORY_SEGMENT_CHARACTERS = 255
private const val MAX_NOTE_ETAG_CHARACTERS = 512
