package dev.obiente.nextcloudnative.app

/** An attendee's answer to an invitation (RFC 5545 PARTSTAT). */
internal enum class CalendarParticipation(val icalValue: String, val label: String) {
    NeedsAction("NEEDS-ACTION", "Not answered"),
    Accepted("ACCEPTED", "Going"),
    Tentative("TENTATIVE", "Maybe"),
    Declined("DECLINED", "Not going"),
    Delegated("DELEGATED", "Delegated"),
    ;

    companion object {
        fun from(value: String?): CalendarParticipation =
            entries.firstOrNull { it.icalValue.equals(value?.trim(), ignoreCase = true) } ?: NeedsAction
    }
}

internal data class CalendarEventParticipant(
    /** The calendar address without `mailto:`, lowercased so addresses compare reliably. */
    val address: String,
    val name: String?,
    val participation: CalendarParticipation,
    val optional: Boolean = false,
    /** A room, resource or group rather than a person. */
    val nonPerson: Boolean = false,
) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: address
}

internal data class CalendarEventInvitation(
    val organizer: CalendarEventParticipant?,
    val attendees: List<CalendarEventParticipant>,
) {
    val isMeeting: Boolean get() = attendees.isNotEmpty()
}

/** Who the signed-in user is to CalDAV scheduling: the addresses invitations are sent to. */
internal data class CalendarUserIdentity(
    val displayName: String?,
    val addresses: List<String>,
) {
    /** The address the server uses as organizer for invitations this user sends. */
    val primaryAddress: String? get() = addresses.firstOrNull()
    fun owns(address: String?): Boolean = address != null && address.lowercase() in addresses
}

internal fun GroupwareCalendarEvent.invitation(): CalendarEventInvitation {
    val lines = rawCalendar.unfoldCalendarLines()
    val range = lines.sourceComponentFor(this) ?: return CalendarEventInvitation(null, emptyList())
    val properties = lines.icalendarDirectProperties(range).map { it.second }
    return CalendarEventInvitation(
        organizer = properties.firstOrNull { it.name.equals("ORGANIZER", true) }?.toParticipant(),
        attendees = properties.filter { it.name.equals("ATTENDEE", true) }.mapNotNull { it.toParticipant() }
            .distinctBy(CalendarEventParticipant::address),
    )
}

/** The user's own entry among the attendees, when they were invited by someone else. */
internal fun CalendarEventInvitation.invitationFor(identity: CalendarUserIdentity?): CalendarEventParticipant? {
    if (identity == null || identity.owns(organizer?.address)) return null
    return attendees.firstOrNull { identity.owns(it.address) }
}

/** Only the organizer, or anyone while nobody is invited yet, may change the invitee list. */
internal fun CalendarEventInvitation.canManageAttendees(identity: CalendarUserIdentity?): Boolean =
    identity?.primaryAddress != null && (organizer == null || identity.owns(organizer.address))

/**
 * Records the user's answer on their ATTENDEE line. The server then tells the organizer. For an
 * occurrence that has no exception of its own the answer applies to the series.
 */
internal fun respondToCalendarEventContent(
    event: GroupwareCalendarEvent,
    identity: CalendarUserIdentity,
    participation: CalendarParticipation,
): String {
    require(participation != CalendarParticipation.Delegated) { "Delegating is not supported." }
    val lines = event.rawCalendar.unfoldCalendarLines().toMutableList()
    val range = requireNotNull(lines.sourceComponentFor(event)) { "The invitation could not be found." }
    val (index, attendee) = requireNotNull(
        lines.icalendarDirectProperties(range).firstOrNull { (_, line) ->
            line.name.equals("ATTENDEE", true) && identity.owns(line.calendarAddress())
        },
    ) { "You are not on this event's guest list." }
    lines[index] = attendee.withParameter("PARTSTAT", participation.icalValue).format()
    return lines.joinICalendarLines()
}

/**
 * Sets the attendees of [range] to [addresses]. Existing attendees keep their answers and details.
 * The first attendee makes the user the organizer; removing the last one removes the user as
 * organizer again, so the event is no longer a meeting.
 */
internal fun MutableList<String>.replaceCalendarAttendees(
    range: ICalendarComponentRange,
    addresses: List<String>,
    identity: CalendarUserIdentity,
): ICalendarComponentRange {
    val wanted = addresses.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
    require(wanted.size <= MAX_CALENDAR_ATTENDEES && wanted.all(String::isCalendarEmailAddress)) {
        "The guest list is invalid."
    }
    val organizerAddress = requireNotNull(identity.primaryAddress) { "Your account has no email address for invitations." }
    var end = range.end
    val properties = icalendarDirectProperties(range)
    val organizer = properties.firstOrNull { it.second.name.equals("ORGANIZER", true) }
    require(organizer == null || identity.owns(organizer.second.calendarAddress())) {
        "Only the organizer can change the guest list."
    }
    val kept = mutableSetOf<String>()
    var unrepresentable = 0
    properties.filter { it.second.name.equals("ATTENDEE", true) }.reversed().forEach { (index, line) ->
        val address = line.calendarAddress()
        when {
            // An attendee without an email address cannot be shown here, so it is never dropped.
            address == null -> unrepresentable++
            address in wanted && address !in kept -> kept += address
            else -> {
                removeAt(index)
                end--
            }
        }
    }
    if (wanted.isEmpty()) {
        if (unrepresentable == 0) {
            icalendarDirectProperties(ICalendarComponentRange(range.begin, end))
                .firstOrNull { it.second.name.equals("ORGANIZER", true) }
                ?.let { (index, _) -> removeAt(index); end-- }
        }
        return ICalendarComponentRange(range.begin, end)
    }
    if (organizer == null) {
        val name = identity.displayName?.icalendarParameterValue()?.takeIf(String::isNotBlank)
        val line = ICalendarContentLine(
            "ORGANIZER",
            listOfNotNull(name?.let { "CN" to it }),
            "mailto:$organizerAddress",
        )
        add(end, line.format())
        end++
    }
    wanted.filterNot { it in kept }.forEach { address ->
        add(
            end,
            ICalendarContentLine(
                "ATTENDEE",
                listOf("CUTYPE" to "INDIVIDUAL", "ROLE" to "REQ-PARTICIPANT", "PARTSTAT" to "NEEDS-ACTION", "RSVP" to "TRUE"),
                "mailto:$address",
            ).format(),
        )
        end++
    }
    return ICalendarComponentRange(range.begin, end)
}

internal fun String.isCalendarEmailAddress(): Boolean =
    length <= 254 && Regex("^[^@\\s,;:\"<>()\\[\\]]+@[^@\\s,;:\"<>()\\[\\]]+\\.[^@\\s,;:\"<>()\\[\\]]+$").matches(this)

/** The address of an ORGANIZER or ATTENDEE line without its `mailto:` scheme, lowercased. */
internal fun ICalendarContentLine.calendarAddress(): String? {
    val raw = value.trim()
    val address = if (raw.startsWith("mailto:", ignoreCase = true)) raw.substring(7) else return null
    return address.trim().lowercase().takeIf(String::isNotEmpty)
}

private fun ICalendarContentLine.toParticipant(): CalendarEventParticipant? {
    val address = calendarAddress() ?: return null
    val kind = parameter("CUTYPE")?.uppercase()
    return CalendarEventParticipant(
        address = address,
        name = parameter("CN")?.trim()?.takeIf(String::isNotBlank),
        participation = CalendarParticipation.from(parameter("PARTSTAT")),
        optional = parameter("ROLE")?.equals("OPT-PARTICIPANT", true) == true,
        nonPerson = kind != null && kind != "INDIVIDUAL",
    )
}

/** Reads the user's display name and calendar addresses from their principal. */
internal fun calendarUserIdentityRequest(userId: String): GroupwareDavRequest {
    require(userId.isNotBlank() && userId.length <= 255 && userId.none(Char::isISOControl)) { "The user id is invalid." }
    return GroupwareDavRequest(
        method = "PROPFIND",
        relativePath = groupwarePrincipalHref(userId),
        depth = 0,
        contentType = "application/xml; charset=utf-8",
        body = (
            """<?xml version="1.0" encoding="UTF-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
                "<d:prop><d:displayname/><c:calendar-user-address-set/></d:prop></d:propfind>"
            ).encodeToByteArray(),
        maximumResponseBytes = 256L * 1024L,
    )
}

internal fun parseCalendarUserIdentity(response: NextcloudApiResponse): CalendarUserIdentity {
    require(response.status in 200..299) { "Your calendar identity could not be read (HTTP ${response.status})." }
    val xml = response.body.decodeToString()
    val addresses = xml.xmlElements("calendar-user-address-set").flatMap { it.xmlElements("href") }
        .mapNotNull { element -> element.xmlText("href")?.decodeXmlEntities()?.trim() }
        .filter { it.startsWith("mailto:", ignoreCase = true) }
        .map { it.substring(7).trim().lowercase() }
        .filter(String::isCalendarEmailAddress)
        .distinct()
    return CalendarUserIdentity(
        displayName = xml.xmlText("displayname")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank),
        addresses = addresses,
    )
}

/** The principal of a Nextcloud user, as used by CalDAV scheduling and sharing. */
internal fun groupwarePrincipalHref(userId: String): String =
    "/remote.php/dav/principals/users/${userId.encodeGroupwarePathSegment()}/"

internal fun String.encodeGroupwarePathSegment(): String = encodeToByteArray().joinToString("") { byte ->
    val unsigned = byte.toInt() and 0xff
    val character = unsigned.toChar()
    if (character.isLetterOrDigit() && unsigned < 0x80 || character in "-._~") character.toString()
    else "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
}

internal const val MAX_CALENDAR_ATTENDEES = 100
