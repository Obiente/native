package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reviewed adapter for Nextcloud file comments over WebDAV (apps/dav/lib/Comments).
 *
 * https://github.com/nextcloud/server/blob/master/apps/dav/lib/Comments/CommentsPlugin.php
 * https://github.com/nextcloud/server/blob/master/apps/dav/lib/Comments/CommentNode.php
 *
 * - List: REPORT oc:filter-comments on /remote.php/dav/comments/files/{fileId}. The server
 *   clamps oc:limit to 1..100, so pages are read with an explicit limit and offset.
 * - Create: POST a JSON body with actorType "users" and verb "comment". Returns 201. This is
 *   not idempotent, so an unknown result is verified by reading before it may be offered again.
 * - Edit: PROPPATCH oc:message. Delete: DELETE. Only the author may edit or delete.
 * - Mark read: PROPPATCH oc:readMarker on the file's comment collection.
 * - Messages are limited to 1000 characters after trimming.
 */

data class NextcloudFileComment(
    val id: Long,
    val message: String,
    val actorType: String,
    val actorId: String,
    val actorDisplayName: String,
    /** RFC 1123 date as sent by the server; presentation-only. */
    val createdAt: String?,
    val createdAtEpochSeconds: Long?,
    val unread: Boolean,
) {
    fun isAuthoredBy(userId: String): Boolean = actorType == "users" && actorId == userId
}

data class FileCommentsPage(
    val comments: List<NextcloudFileComment>,
    /** True when the server returned a full page and more may exist. */
    val mayHaveMore: Boolean,
    val skippedEntries: Int,
)

/** Why a draft cannot be sent, or null when it is valid. */
fun fileCommentDraftProblem(draft: String): String? {
    val trimmed = draft.trim()
    return when {
        trimmed.isEmpty() -> "Write a comment first."
        trimmed.length > MAX_FILE_COMMENT_CHARACTERS -> "Comments can be at most $MAX_FILE_COMMENT_CHARACTERS characters."
        trimmed.any { it.isISOControl() && it != '\n' && it != '\t' } -> "The comment contains unsupported characters."
        else -> null
    }
}

fun fileCommentsListRequest(fileId: Long, offset: Int, limit: Int = FILE_COMMENTS_PAGE_SIZE): FilesDavRequest {
    require(offset >= 0 && limit in 1..FILE_COMMENTS_PAGE_SIZE) { "The comment page is invalid." }
    return FilesDavRequest(
        method = FilesDavMethod.REPORT,
        relativePath = commentCollectionPath(fileId),
        contentType = "application/xml; charset=utf-8",
        body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <oc:filter-comments xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <oc:limit>$limit</oc:limit><oc:offset>$offset</oc:offset>
            </oc:filter-comments>
        """.trimIndent().encodeToByteArray(),
        maximumResponseBytes = MAX_FILE_COMMENTS_RESPONSE_BYTES,
    )
}

fun fileCommentCreateRequest(fileId: Long, message: String): FilesDavRequest {
    require(fileCommentDraftProblem(message) == null) { "The comment is invalid." }
    val body = JsonObject(
        mapOf(
            "actorType" to JsonPrimitive("users"),
            "verb" to JsonPrimitive("comment"),
            "message" to JsonPrimitive(message.trim()),
        ),
    ).toString()
    return FilesDavRequest(
        method = FilesDavMethod.POST,
        relativePath = commentCollectionPath(fileId),
        contentType = "application/json",
        body = body.encodeToByteArray(),
    )
}

fun fileCommentEditRequest(fileId: Long, commentId: Long, message: String): FilesDavRequest {
    require(fileCommentDraftProblem(message) == null) { "The comment is invalid." }
    return FilesDavRequest(
        method = FilesDavMethod.PROPPATCH,
        relativePath = commentPath(fileId, commentId),
        contentType = "application/xml; charset=utf-8",
        body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:set><d:prop><oc:message>${message.trim().escapeDavXml()}</oc:message></d:prop></d:set>
            </d:propertyupdate>
        """.trimIndent().encodeToByteArray(),
    )
}

fun fileCommentDeleteRequest(fileId: Long, commentId: Long): FilesDavRequest =
    FilesDavRequest(FilesDavMethod.DELETE, commentPath(fileId, commentId))

fun fileCommentsMarkReadRequest(fileId: Long): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.PROPPATCH,
    relativePath = commentCollectionPath(fileId),
    contentType = "application/xml; charset=utf-8",
    body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:set><d:prop><oc:readMarker/></d:prop></d:set>
        </d:propertyupdate>
    """.trimIndent().encodeToByteArray(),
)

/** Whether every property of a PROPPATCH response was applied. */
internal fun filesDavPropertyUpdateApplied(response: NextcloudApiResponse): Boolean {
    if (response.status == 200 || response.status == 204) return true
    if (response.status != 207) return false
    val statuses = response.body.decodeToString().xmlElements("propstat").mapNotNull { it.xmlText("status") }
    return statuses.isNotEmpty() && statuses.all { status -> parseDavStatusCode(status) in 200..299 }
}

fun parseFileCommentsPage(response: NextcloudApiResponse, fileId: Long, requestedLimit: Int): FileCommentsPage {
    val records = parseFilesDavMultistatus(response, FILE_COMMENTS_PAGE_SIZE)
    var skipped = 0
    val comments = mutableListOf<NextcloudFileComment>()
    for (record in records) {
        val segments = filesDavHrefSegments(record.href)
        if (segments == null || segments.size < 3 || segments[0] != "comments" || segments[1] != "files" ||
            segments[2] != fileId.toString()
        ) {
            skipped++
            continue
        }
        if (segments.size == 3) continue
        val comment = record.toCommentOrNull(segments.getOrNull(3))
        when {
            comment == null -> skipped++
            // Only conversation comments are shown; other verbs (for example reactions) are not.
            record.text("verb") != "comment" -> Unit
            else -> comments += comment
        }
    }
    return FileCommentsPage(
        comments = comments.distinctBy(NextcloudFileComment::id),
        mayHaveMore = records.count { filesDavHrefSegments(it.href)?.size == 4 } >= requestedLimit,
        skippedEntries = skipped,
    )
}

private fun FilesDavResponseRecord.toCommentOrNull(hrefId: String?): NextcloudFileComment? {
    val id = text("id")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (hrefId != null && hrefId != id.toString()) return null
    val actorId = text("actorId")?.takeIf { it.length <= 255 && it.none(Char::isISOControl) } ?: return null
    val message = text("message")?.takeIf { it.length <= MAX_FILE_COMMENT_CHARACTERS * 4 } ?: return null
    val created = text("creationDateTime")?.takeIf { it.length <= 64 }
    return NextcloudFileComment(
        id = id,
        message = message.withMentionDisplayNames(properties["mentions"]),
        actorType = text("actorType") ?: "users",
        actorId = actorId,
        actorDisplayName = text("actorDisplayName")?.takeIf { it.length <= 255 } ?: actorId,
        createdAt = created,
        createdAtEpochSeconds = created?.let(::parseHttpDateEpochSeconds),
        unread = text("isUnread") == "true",
    )
}

/** Replaces `@id` and `@"id"` placeholders with the mentioned account's display name. */
private fun String.withMentionDisplayNames(mentionsXml: String?): String {
    if (mentionsXml.isNullOrBlank()) return this
    var result = this
    // Split on each opening mention tag; `xmlElements` would also match `mentionType` closings.
    MENTION_OPENING.split(mentionsXml).drop(1).take(MAX_MENTIONS).forEach { mention ->
        val id = MENTION_ID.find(mention)?.groupValues?.get(1)?.decodeXmlEntities()?.trim() ?: return@forEach
        val name = MENTION_NAME.find(mention)?.groupValues?.get(1)?.decodeXmlEntities()?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        result = result.replace("@\"$id\"", "@$name").replace("@$id", "@$name")
    }
    return result
}

/** Parses an RFC 1123 date such as `Tue, 05 Mar 2024 10:00:00 GMT`; null when it is not one. */
internal fun parseHttpDateEpochSeconds(value: String): Long? {
    val match = Regex("""^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) (GMT|UTC|\+0000)$""")
        .matchEntire(value.trim()) ?: return null
    val (day, monthName, year, hour, minute, second) = match.destructured
    val month = MONTHS.indexOf(monthName.lowercase()) + 1
    if (month == 0) return null
    val d = day.toInt()
    val h = hour.toInt()
    val m = minute.toInt()
    val s = second.toInt()
    if (d !in 1..31 || h > 23 || m > 59 || s > 60) return null
    return daysFromCivil(year.toLong(), month, d) * 86_400L + h * 3_600L + m * 60L + s
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

private fun commentCollectionPath(fileId: Long): String {
    require(fileId > 0L) { "The file ID is invalid." }
    return "${FILES_DAV_ROOT}comments/files/$fileId"
}

private fun commentPath(fileId: Long, commentId: Long): String {
    require(commentId > 0L) { "The comment ID is invalid." }
    return "${commentCollectionPath(fileId)}/$commentId"
}

private val MENTION_OPENING = Regex("<(?:[A-Za-z0-9_-]+:)?mention>")
private val MENTION_ID = Regex("<(?:[A-Za-z0-9_-]+:)?mentionId>([^<]*)</")
private val MENTION_NAME = Regex("<(?:[A-Za-z0-9_-]+:)?mentionDisplayName>([^<]*)</")
private const val MAX_MENTIONS = 50
private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
const val MAX_FILE_COMMENT_CHARACTERS = 1_000
internal const val FILE_COMMENTS_PAGE_SIZE = 100
private const val MAX_FILE_COMMENTS_RESPONSE_BYTES = 4L * 1024L * 1024L
