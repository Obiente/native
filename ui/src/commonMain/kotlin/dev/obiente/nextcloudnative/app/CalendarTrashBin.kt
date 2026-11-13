package dev.obiente.nextcloudnative.app

/**
 * Nextcloud's calendar trash bin (apps/dav/lib/CalDAV/Trashbin in nextcloud/server):
 * - deleted calendars stay in the calendar home with `{http://nextcloud.com/ns}deleted-at`;
 * - deleted events and tasks are listed by a calendar-query REPORT on `trashbin/objects/`;
 * - MOVE of either into `trashbin/restore/` restores it;
 * - DELETE removes it for good. A deleted calendar needs the `X-NC-CalDAV-No-Trashbin: 1` header,
 *   since an ordinary DELETE only moves a calendar to the trash bin.
 * The home's `{http://nextcloud.com/ns}trash-bin-retention-duration` says how long items are kept.
 */
internal enum class CalendarTrashKind { Calendar, Event, Task }

internal data class CalendarTrashItem(
    val href: String,
    val name: String,
    val kind: CalendarTrashKind,
    /** ISO 8601 deletion time as the server reports it. */
    val deletedAt: String?,
    /** For an event or task, the calendar it was in, by its address name. */
    val calendarUri: String? = null,
    val color: String? = null,
)

internal data class CalendarTrashContents(
    val items: List<CalendarTrashItem>,
    /** Days items stay before the server removes them, when the server says. */
    val retentionDays: Int?,
)

internal fun calendarTrashHomeHref(userId: String): String = groupwareCalendarHomeHref(userId) + "trashbin/"

internal fun calendarTrashRetentionRequest(userId: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "PROPFIND",
    relativePath = calendarTrashHomeHref(userId),
    depth = 0,
    contentType = TRASH_XML,
    body = (
        """<?xml version="1.0" encoding="UTF-8"?><d:propfind xmlns:d="DAV:" xmlns:nc="http://nextcloud.com/ns">""" +
            "<d:prop><nc:trash-bin-retention-duration/></d:prop></d:propfind>"
        ).encodeToByteArray(),
    maximumResponseBytes = 64L * 1024L,
)

internal fun calendarTrashObjectsRequest(userId: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "REPORT",
    relativePath = calendarTrashHomeHref(userId) + "objects/",
    depth = 1,
    contentType = TRASH_XML,
    body = (
        """<?xml version="1.0" encoding="UTF-8"?><c:calendar-query xmlns:d="DAV:" """ +
            """xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:nc="http://nextcloud.com/ns">""" +
            "<d:prop><d:getetag/><c:calendar-data/><nc:calendar-uri/><nc:deleted-at/></d:prop>" +
            """<c:filter><c:comp-filter name="VCALENDAR"/></c:filter></c:calendar-query>"""
        ).encodeToByteArray(),
    maximumResponseBytes = 4L * 1024L * 1024L,
)

/** Deleted calendars, from the same calendar home listing the calendar screen reads. */
internal fun parseDeletedCalendars(response: NextcloudApiResponse): List<CalendarTrashItem> {
    require(response.status in 200..299) { "Deleted calendars could not be read (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val deletedAt = block.xmlText("deleted-at")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val href = block.xmlDirectChildText("href")?.decodeXmlEntities()?.trim()?.takeIf { it.endsWith('/') } ?: return@mapNotNull null
        if (!block.containsXmlElement("calendar")) return@mapNotNull null
        CalendarTrashItem(
            href = href.requireSafeDavHref(),
            name = block.xmlText("displayname")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
                ?: href.trimEnd('/').substringAfterLast('/').decodePercentEncoding(),
            kind = CalendarTrashKind.Calendar,
            deletedAt = deletedAt,
            color = block.xmlText("calendar-color")?.trim()?.takeIf(String::isNotBlank),
        )
    }.distinctBy(CalendarTrashItem::href)
}

internal fun parseDeletedCalendarObjects(response: NextcloudApiResponse, objectsHref: String): List<CalendarTrashItem> {
    require(response.status in 200..299) { "Deleted events could not be read (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlDirectChildText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref() ?: return@mapNotNull null
        if (!href.startsWith(objectsHref) || href.endsWith('/')) return@mapNotNull null
        val data = block.xmlText("calendar-data")?.decodeXmlEntities() ?: return@mapNotNull null
        val lines = data.unfoldCalendarLines()
        val todo = lines.calendarComponentLines("VTODO").firstOrNull()
        val event = lines.calendarComponentLines("VEVENT").firstOrNull()
        val component = todo ?: event ?: return@mapNotNull null
        CalendarTrashItem(
            href = href,
            name = component.calendarProperty("SUMMARY")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank)
                ?: if (todo != null) "Untitled task" else "Untitled event",
            kind = if (todo != null) CalendarTrashKind.Task else CalendarTrashKind.Event,
            deletedAt = block.xmlText("deleted-at")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank),
            calendarUri = block.xmlText("calendar-uri")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank),
        )
    }.distinctBy(CalendarTrashItem::href)
}

/** Retention in whole days from the duration in seconds, or null when absent or malformed. */
internal fun parseCalendarTrashRetentionDays(response: NextcloudApiResponse): Int? {
    if (response.status !in 200..299) return null
    val seconds = response.body.decodeToString().xmlText("trash-bin-retention-duration")?.trim()?.toLongOrNull() ?: return null
    return (seconds / 86_400L).takeIf { seconds >= 0 && it <= 36_500 }?.toInt()
}

internal fun calendarTrashRestoreRequest(serverOrigin: String, userId: String, item: CalendarTrashItem): GroupwareDavRequest {
    val href = item.requireTrashHref(userId)
    val name = href.trimEnd('/').substringAfterLast('/')
    require(name.isNotEmpty()) { "The deleted item is invalid." }
    val destination = calendarTrashHomeHref(userId) + "restore/" + name
    return GroupwareDavRequest(
        method = "MOVE",
        relativePath = href,
        headers = mapOf("Destination" to serverOrigin.trimEnd('/') + destination),
        maximumResponseBytes = 64L * 1024L,
    )
}

internal fun calendarTrashDeleteRequest(userId: String, item: CalendarTrashItem): GroupwareDavRequest = GroupwareDavRequest(
    method = "DELETE",
    relativePath = item.requireTrashHref(userId),
    headers = if (item.kind == CalendarTrashKind.Calendar) mapOf("X-NC-CalDAV-No-Trashbin" to "1") else emptyMap(),
    maximumResponseBytes = 64L * 1024L,
)

/** Only items from this user's own calendar home or trash bin can be restored or removed. */
private fun CalendarTrashItem.requireTrashHref(userId: String): String {
    href.requireSafeDavHref()
    val home = groupwareCalendarHomeHref(userId)
    val valid = when (kind) {
        CalendarTrashKind.Calendar -> href.startsWith(home) && href.endsWith('/') &&
            href.removePrefix(home).trimEnd('/').let { it.isNotEmpty() && '/' !in it && it != "trashbin" }
        else -> href.startsWith(calendarTrashHomeHref(userId) + "objects/") && !href.endsWith('/') &&
            '/' !in href.removePrefix(calendarTrashHomeHref(userId) + "objects/")
    }
    require(valid) { "The deleted item is not in your calendar trash bin." }
    return href
}

private const val TRASH_XML = "application/xml; charset=utf-8"
