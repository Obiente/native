package dev.obiente.nextcloudnative.app

import kotlin.random.Random

enum class GroupwareCalendarKind {
    Owned,
    Shared,
    Subscription,
}

/**
 * The calendars in a CalDAV calendar home that hold [componentName] items, or every calendar when
 * it is null. Subscribed feeds count as event calendars and are read-only. Calendars waiting in
 * Nextcloud's trash bin are left out, since they are deleted as far as the user is concerned.
 */
internal fun parseGroupwareCalendarsForComponent(
    response: NextcloudApiResponse,
    componentName: String?,
): List<GroupwareCalendar> {
    require(response.status in 200..299) { "Calendar discovery failed (HTTP ${response.status})." }
    val xml = response.body.decodeToString()
    return xml.xmlElements("response").mapNotNull { block ->
        val href = block.xmlDirectChildText("href")?.decodeXmlEntities()?.trim()?.takeIf { it.endsWith('/') }
            ?: return@mapNotNull null
        if (block.xmlText("deleted-at")?.isNotBlank() == true) return@mapNotNull null
        val source = block.xmlElements("source").firstNotNullOfOrNull { it.xmlText("href") }
            ?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
        val subscription = source != null || block.containsXmlElement("subscribed")
        if (!subscription && !block.containsXmlElement("calendar")) return@mapNotNull null
        val components = block.xmlOpeningTags("comp").mapNotNull { it.xmlAttribute("name")?.uppercase() }.toSet()
        val supportsComponent = when {
            componentName == null -> true
            // A subscription is an event feed; the server may not list its components.
            subscription -> componentName.equals("VEVENT", ignoreCase = true)
            else -> componentName.uppercase() in components
        }
        if (!supportsComponent) return@mapNotNull null
        val privileges = block.xmlElements("privilege").flatMap { it.xmlElementNames() }
        val homeUser = href.removePrefix(CALENDAR_HOME_PREFIX).substringBefore('/').decodePercentEncoding()
        val ownerUser = block.xmlText("owner-principal")?.decodeXmlEntities()?.trim()?.trimEnd('/')
            ?.substringAfterLast('/')?.decodePercentEncoding()?.takeIf(String::isNotBlank)
        GroupwareCalendar(
            href = href.requireSafeDavHref(),
            displayName = block.xmlText("displayname")?.decodeXmlEntities()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: href.trimEnd('/').substringAfterLast('/').decodePercentEncoding(),
            color = block.xmlText("calendar-color")?.trim()?.takeIf(String::isNotBlank),
            writable = !subscription && privileges.any { it == "write" || it == "write-content" },
            kind = when {
                subscription -> GroupwareCalendarKind.Subscription
                ownerUser != null && href.startsWith(CALENDAR_HOME_PREFIX) && ownerUser != homeUser ->
                    GroupwareCalendarKind.Shared
                else -> GroupwareCalendarKind.Owned
            },
            ownerName = block.xmlText("owner-displayname")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank),
            sourceUrl = source,
            supportsTasks = "VTODO" in components,
        )
    }.distinctBy(GroupwareCalendar::href)
}

/** What a person fills in to create or change a calendar or subscription. */
internal data class CalendarCollectionDraft(
    val name: String,
    val color: String,
    val withTasks: Boolean = false,
    /** A task list: a calendar that holds only tasks. */
    val tasksOnly: Boolean = false,
    /** Set only for a new subscription. */
    val sourceUrl: String? = null,
)

/** The colors offered for calendars, matching the Nextcloud calendar palette. */
internal val CalendarCollectionColors = listOf(
    "#0082C9", "#00897B", "#2E9E5B", "#E9A23B", "#E9322D", "#8E44AD", "#C71585", "#795548",
)

internal fun CalendarCollectionDraft.validationMessage(): String? = when {
    name.isBlank() -> "Enter a name."
    name.trim().length > MAX_CALENDAR_NAME_LENGTH || name.any(Char::isISOControl) -> "Use a shorter name without special characters."
    !color.isCalendarCollectionColor() -> "Choose a color."
    sourceUrl != null && sourceUrl.calendarSubscriptionUrlOrNull() == null ->
        "Enter a calendar link that starts with https://, http://, or webcal://."
    else -> null
}

/** A new collection address in [homeHref], readable from the name and unique by its random suffix. */
internal fun newCalendarCollectionHref(homeHref: String, name: String, random: Random = Random.Default): String {
    val slug = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(40).trim('-').ifBlank { "calendar" }
    val suffix = (1..6).map { CALENDAR_SLUG_ALPHABET[random.nextInt(CALENDAR_SLUG_ALPHABET.length)] }.joinToString("")
    return requireCalendarCollectionHref("${homeHref.requireSafeDavHref().trimEnd('/')}/$slug-$suffix/")
}

/** An RFC 4791 MKCALENDAR for a new calendar, optionally also holding tasks. */
internal fun calendarCollectionCreateRequest(href: String, draft: CalendarCollectionDraft): GroupwareDavRequest {
    requireValidDraft(draft, subscription = false)
    val components = when {
        draft.tasksOnly -> """<c:comp name="VTODO"/>"""
        draft.withTasks -> """<c:comp name="VEVENT"/><c:comp name="VTODO"/>"""
        else -> """<c:comp name="VEVENT"/>"""
    }
    return calendarCollectionRequest(
        "MKCALENDAR", href,
        """<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:x1="http://apple.com/ns/ical/">""" +
            "<d:set><d:prop>${draft.nameAndColorXml()}" +
            "<c:supported-calendar-component-set>$components</c:supported-calendar-component-set>" +
            "</d:prop></d:set></c:mkcalendar>",
    )
}

/** A subscription to an iCalendar feed, created the way Nextcloud's calendar does it. */
internal fun calendarSubscriptionCreateRequest(href: String, draft: CalendarCollectionDraft): GroupwareDavRequest {
    requireValidDraft(draft, subscription = true)
    val source = requireNotNull(draft.sourceUrl?.calendarSubscriptionUrlOrNull())
    return calendarCollectionRequest(
        "MKCOL", href,
        """<d:mkcol xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:x1="http://apple.com/ns/ical/">""" +
            "<d:set><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype>" +
            "${draft.nameAndColorXml()}<cs:source><d:href>${source.escapeDavXml()}</d:href></cs:source>" +
            "</d:prop></d:set></d:mkcol>",
    )
}

/** Renames and recolors an existing calendar or subscription. */
internal fun calendarCollectionUpdateRequest(href: String, draft: CalendarCollectionDraft): GroupwareDavRequest {
    requireValidDraft(draft.copy(sourceUrl = null), subscription = false)
    return calendarCollectionRequest(
        "PROPPATCH", href,
        """<d:propertyupdate xmlns:d="DAV:" xmlns:x1="http://apple.com/ns/ical/">""" +
            "<d:set><d:prop>${draft.nameAndColorXml()}</d:prop></d:set></d:propertyupdate>",
    )
}

/** Deletes a calendar, ends a subscription, or removes a calendar shared with the user. */
internal fun calendarCollectionDeleteRequest(href: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "DELETE",
    relativePath = requireCalendarCollectionHref(href),
    maximumResponseBytes = CALENDAR_COLLECTION_RESPONSE_BYTES,
)

/** Whether every property in a PROPPATCH multistatus was applied. */
internal fun calendarPropertyUpdateApplied(response: NextcloudApiResponse): Boolean {
    if (response.status == 200 || response.status == 204) return true
    if (response.status != 207) return false
    val statuses = response.body.decodeToString().xmlElements("propstat").mapNotNull { it.xmlText("status") }
    return statuses.isNotEmpty() && statuses.all { status -> status.split(' ').getOrNull(1)?.toIntOrNull() in 200..299 }
}

/** A feed link as the server should store it, or null when it is not a plain web address. */
internal fun String.calendarSubscriptionUrlOrNull(): String? {
    val value = trim()
    if (value.length > MAX_CALENDAR_SOURCE_LENGTH || value.any { it.isWhitespace() || it.isISOControl() }) return null
    val match = Regex("^(https?|webcals?)://([^/?#@]+)([/?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
    val host = match.groupValues[2]
    // Credentials never go in a subscription link: anyone who can see the calendar could read them.
    return value.takeIf { host.isNotBlank() && !host.startsWith(':') }
}

private fun String.isCalendarCollectionColor(): Boolean = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(this)

private fun requireValidDraft(draft: CalendarCollectionDraft, subscription: Boolean) {
    require(draft.validationMessage() == null) { "The calendar details are invalid." }
    require((draft.sourceUrl != null) == subscription) { "Only a subscription has a feed link." }
}

private fun CalendarCollectionDraft.nameAndColorXml(): String =
    "<d:displayname>${name.trim().escapeDavXml()}</d:displayname><x1:calendar-color>${color.escapeDavXml()}</x1:calendar-color>"

private fun calendarCollectionRequest(method: String, href: String, body: String): GroupwareDavRequest = GroupwareDavRequest(
    method = method,
    relativePath = requireCalendarCollectionHref(href),
    contentType = "application/xml; charset=utf-8",
    body = """<?xml version="1.0" encoding="UTF-8"?>$body""".encodeToByteArray(),
    maximumResponseBytes = CALENDAR_COLLECTION_RESPONSE_BYTES,
)

private fun requireCalendarCollectionHref(href: String): String {
    href.requireSafeDavHref()
    val name = href.removePrefix(CALENDAR_HOME_PREFIX).trimEnd('/')
    require(href.startsWith(CALENDAR_HOME_PREFIX) && href.endsWith('/') && name.count { it == '/' } == 1) {
        "A calendar change must target a calendar in a calendar home."
    }
    return href
}

private const val CALENDAR_HOME_PREFIX = "/remote.php/dav/calendars/"
private const val CALENDAR_SLUG_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val MAX_CALENDAR_NAME_LENGTH = 200
private const val MAX_CALENDAR_SOURCE_LENGTH = 2_000
private const val CALENDAR_COLLECTION_RESPONSE_BYTES = 256L * 1024L
