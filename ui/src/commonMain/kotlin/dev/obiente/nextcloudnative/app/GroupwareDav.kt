package dev.obiente.nextcloudnative.app

enum class GroupwareDavKind {
    Contact,
    Event,
    Task,
}

data class GroupwareDavRequest(
    val method: String,
    val relativePath: String,
    val depth: Int? = null,
    val contentType: String? = null,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val maximumResponseBytes: Long,
)

data class GroupwareCalendar(
    val href: String,
    val displayName: String,
    val color: String? = null,
    val writable: Boolean = false,
)

data class GroupwareAddressBook(
    val href: String,
    val displayName: String,
    val writable: Boolean = false,
)

data class GroupwareContact(
    val href: String,
    val etag: String?,
    val addressBookHref: String,
    val uid: String,
    val displayName: String,
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val organization: String? = null,
    val address: String? = null,
    val birthday: String? = null,
    val notes: String? = null,
    val rawVCard: String,
)

data class GroupwareCalendarEvent(
    val href: String,
    val etag: String?,
    val calendarHref: String,
    val uid: String,
    val title: String,
    val start: String,
    val end: String?,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    val status: String? = null,
    val recurrenceRule: String? = null,
    val recurrenceId: String? = null,
    val excludedStarts: Set<String> = emptySet(),
    val isGeneratedOccurrence: Boolean = false,
    val rawCalendar: String,
) {
    /** Stable across sorting and refreshes, including multiple visible instances of one DAV object. */
    val instanceId: String get() = "$href#${recurrenceId ?: start}"
}

data class GroupwareTask(
    val href: String,
    val etag: String?,
    val calendarHref: String,
    val uid: String,
    val title: String,
    val due: String? = null,
    val dueAllDay: Boolean = false,
    val completed: Boolean = false,
    val description: String? = null,
    val priority: Int? = null,
    val rawCalendar: String,
)

data class GroupwareDavTimeWindow(
    val startUtc: String,
    val endUtc: String,
) {
    init {
        require(startUtc.isDavUtcDateTime() && endUtc.isDavUtcDateTime() && startUtc < endUtc) {
            "The CalDAV time window is invalid."
        }
    }
}

data class GroupwareDavHomes(
    val calendarHref: String?,
    val addressBookHref: String?,
)

data class GroupwareDavSyncToken(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_DAV_SYNC_TOKEN_LENGTH && value.none(Char::isISOControl)) {
            "The DAV sync token is invalid."
        }
    }
}

enum class GroupwareDavSyncStopReason {
    Complete,
    RepeatedToken,
    PageLimit,
}

data class GroupwareDavSyncProgress(
    val token: GroupwareDavSyncToken?,
    val consumedTokens: Set<GroupwareDavSyncToken> = emptySet(),
    val loadedPages: Int = 0,
    val stopReason: GroupwareDavSyncStopReason? = null,
) {
    val canContinue: Boolean get() = token != null && stopReason == null
}

fun advanceGroupwareDavSync(
    progress: GroupwareDavSyncProgress,
    returnedToken: GroupwareDavSyncToken?,
    truncated: Boolean,
): GroupwareDavSyncProgress {
    if (progress.stopReason != null) return progress
    val consumed = progress.token?.let { progress.consumedTokens + it } ?: progress.consumedTokens
    val pages = progress.loadedPages + 1
    val stop = when {
        !truncated || returnedToken == null -> GroupwareDavSyncStopReason.Complete
        returnedToken == progress.token || returnedToken in consumed -> GroupwareDavSyncStopReason.RepeatedToken
        pages >= MAX_DAV_SYNC_PAGES -> GroupwareDavSyncStopReason.PageLimit
        else -> null
    }
    return GroupwareDavSyncProgress(
        token = returnedToken.takeIf { stop == null },
        consumedTokens = consumed,
        loadedPages = pages,
        stopReason = stop,
    )
}

fun groupwareDavPrincipalDiscoveryRequest(): GroupwareDavRequest = GroupwareDavRequest(
    method = "PROPFIND",
    relativePath = "/remote.php/dav/",
    depth = 0,
    contentType = DAV_XML_CONTENT_TYPE,
    body = PRINCIPAL_DISCOVERY_BODY.encodeToByteArray(),
    maximumResponseBytes = DAV_DISCOVERY_RESPONSE_BYTES,
)

fun parseGroupwarePrincipalHref(response: NextcloudApiResponse): String {
    require(response.status in 200..299) { "DAV principal discovery failed (HTTP ${response.status})." }
    val principal = response.body.decodeToString().xmlElements("current-user-principal").firstOrNull()
        ?: error("The DAV response did not advertise a current user principal.")
    return principal.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
        ?: error("The DAV response contained no usable current user principal.")
}

fun parseGroupwareDavHomes(response: NextcloudApiResponse): GroupwareDavHomes {
    require(response.status in 200..299) { "DAV home discovery failed (HTTP ${response.status})." }
    val xml = response.body.decodeToString()
    fun home(name: String): String? = xml.xmlElements(name).firstOrNull()
        ?.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
    return GroupwareDavHomes(
        calendarHref = home("calendar-home-set"),
        addressBookHref = home("addressbook-home-set"),
    )
}

fun groupwareDavHomeDiscoveryRequest(principalHref: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "PROPFIND",
    relativePath = principalHref.requireSafeDavHref(),
    depth = 0,
    contentType = DAV_XML_CONTENT_TYPE,
    body = HOME_DISCOVERY_BODY.encodeToByteArray(),
    maximumResponseBytes = DAV_DISCOVERY_RESPONSE_BYTES,
)

fun groupwareDavCollectionDiscoveryRequest(homeHref: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "PROPFIND",
    relativePath = homeHref.requireSafeDavHref(),
    depth = 1,
    contentType = DAV_XML_CONTENT_TYPE,
    body = COLLECTION_DISCOVERY_BODY.encodeToByteArray(),
    maximumResponseBytes = DAV_COLLECTION_RESPONSE_BYTES,
)

fun groupwareDavCollectionQueryRequest(
    collectionHref: String,
    kind: GroupwareDavKind,
    maxResults: Int = DEFAULT_DAV_QUERY_LIMIT,
    timeWindow: GroupwareDavTimeWindow? = null,
): GroupwareDavRequest {
    require(maxResults in 1..MAX_DAV_QUERY_LIMIT) { "The DAV query limit is out of range." }
    require(kind != GroupwareDavKind.Event || timeWindow != null) {
        "Event queries require a bounded UTC time window."
    }
    val body = when (kind) {
        GroupwareDavKind.Contact -> addressBookQueryBody(maxResults)
        GroupwareDavKind.Event, GroupwareDavKind.Task -> calendarQueryBody(kind, timeWindow)
    }
    return GroupwareDavRequest(
        method = "REPORT",
        relativePath = collectionHref.requireSafeDavHref(),
        depth = 1,
        contentType = DAV_XML_CONTENT_TYPE,
        body = body.encodeToByteArray(),
        maximumResponseBytes = DAV_QUERY_RESPONSE_BYTES,
    )
}

fun groupwareDavSyncRequest(
    collectionHref: String,
    token: GroupwareDavSyncToken?,
    maxResults: Int = DEFAULT_DAV_SYNC_LIMIT,
): GroupwareDavRequest {
    require(maxResults in 1..MAX_DAV_SYNC_LIMIT) { "The DAV sync limit is out of range." }
    val tokenElement = token?.value?.escapeDavXml()?.let { "<d:sync-token>$it</d:sync-token>" }
        ?: "<d:sync-token />"
    val body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:sync-collection xmlns:d="DAV:">
          $tokenElement
          <d:sync-level>1</d:sync-level>
          <d:prop><d:getetag /></d:prop>
          <d:limit><d:nresults>$maxResults</d:nresults></d:limit>
        </d:sync-collection>
    """.trimIndent()
    return GroupwareDavRequest(
        method = "REPORT",
        relativePath = collectionHref.requireSafeDavHref(),
        depth = 1,
        contentType = DAV_XML_CONTENT_TYPE,
        body = body.encodeToByteArray(),
        maximumResponseBytes = DAV_SYNC_RESPONSE_BYTES,
    )
}

fun groupwareDavDetailRequest(objectHref: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "GET",
    relativePath = objectHref.requireSafeDavHref(),
    maximumResponseBytes = DAV_OBJECT_RESPONSE_BYTES,
)

enum class GroupwareDavMutation {
    Create,
    Update,
    Delete,
}

/**
 * A client-error response normally proves that the server refused the mutation. Timeouts and
 * non-standard client-closed responses remain ambiguous because an intermediary can emit them
 * after forwarding the request. Redirects and server errors are ambiguous for the same reason.
 */
internal fun groupwareMutationResponseProvesRejection(status: Int): Boolean =
    status in 400..499 && status != 408 && status != 499

internal fun groupwareDeleteResponseProvesAbsence(status: Int): Boolean = status == 404 || status == 410

data class GroupwareDavMutationSpec(
    val kind: GroupwareDavKind,
    val mutation: GroupwareDavMutation,
    val objectHref: String,
    val etag: String? = null,
    val content: String? = null,
)

/**
 * Builds conflict-safe DAV writes without executing them. Updates and deletes require an ETag;
 * creates use If-None-Match so an opaque server resource can never be overwritten accidentally.
 */
fun GroupwareDavMutationSpec.toGroupwareDavRequest(): GroupwareDavRequest {
    val href = objectHref.requireSafeDavHref()
    val expectedSuffix = if (kind == GroupwareDavKind.Contact) ".vcf" else ".ics"
    require(href.substringBefore('?').endsWith(expectedSuffix, ignoreCase = true)) {
        "The DAV object extension does not match its content kind."
    }
    val safeEtag = etag?.takeIf {
        it.isNotBlank() && it.length <= MAX_DAV_ETAG_LENGTH && it.none(Char::isISOControl)
    }
    val headers = when (mutation) {
        GroupwareDavMutation.Create -> {
            require(etag == null) { "A new DAV object cannot carry an existing ETag." }
            mapOf("If-None-Match" to "*")
        }
        GroupwareDavMutation.Update, GroupwareDavMutation.Delete -> {
            require(safeEtag != null) { "An ETag is required for conflict-safe DAV changes." }
            mapOf("If-Match" to safeEtag)
        }
    }
    val body = when (mutation) {
        GroupwareDavMutation.Delete -> {
            require(content == null) { "A DAV delete request cannot include object content." }
            null
        }
        GroupwareDavMutation.Create, GroupwareDavMutation.Update -> {
            val value = requireNotNull(content) { "DAV object content is required." }
            require(value.encodeToByteArray().size <= MAX_DAV_OBJECT_BYTES && '\u0000' !in value) {
                "The DAV object content is invalid or too large."
            }
            val requiredMarkers = when (kind) {
                GroupwareDavKind.Contact -> listOf("BEGIN:VCARD", "END:VCARD")
                GroupwareDavKind.Event -> listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "END:VCALENDAR")
                GroupwareDavKind.Task -> listOf("BEGIN:VCALENDAR", "BEGIN:VTODO", "END:VTODO", "END:VCALENDAR")
            }
            require(requiredMarkers.all { marker -> marker in value.uppercase() }) {
                "The DAV object content does not match its declared kind."
            }
            value.encodeToByteArray()
        }
    }
    return GroupwareDavRequest(
        method = when (mutation) {
            GroupwareDavMutation.Create, GroupwareDavMutation.Update -> "PUT"
            GroupwareDavMutation.Delete -> "DELETE"
        },
        relativePath = href,
        contentType = body?.let {
            if (kind == GroupwareDavKind.Contact) "text/vcard; charset=utf-8" else "text/calendar; charset=utf-8"
        },
        body = body,
        headers = headers,
        maximumResponseBytes = DAV_MUTATION_RESPONSE_BYTES,
    )
}

internal inline fun <T> prepareGroupwareDavMutation(
    onInvalid: () -> Unit,
    prepare: () -> T,
): T? = try {
    prepare()
} catch (_: IllegalArgumentException) {
    onInvalid()
    null
}

private fun addressBookQueryBody(maxResults: Int): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
      <d:prop><d:getetag /><card:address-data /></d:prop>
      <card:filter />
      <card:limit><card:nresults>$maxResults</card:nresults></card:limit>
    </card:addressbook-query>
""".trimIndent()

private fun calendarQueryBody(kind: GroupwareDavKind, timeWindow: GroupwareDavTimeWindow?): String {
    val component = if (kind == GroupwareDavKind.Event) "VEVENT" else "VTODO"
    val range = timeWindow?.let {
        """<c:time-range start="${it.startUtc}" end="${it.endUtc}" />"""
    }.orEmpty()
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:prop><d:getetag /><c:calendar-data /></d:prop>
          <c:filter>
            <c:comp-filter name="VCALENDAR">
              <c:comp-filter name="$component">$range</c:comp-filter>
            </c:comp-filter>
          </c:filter>
        </c:calendar-query>
    """.trimIndent()
}

fun groupwareCalendarHomeHref(userId: String): String {
    require(userId.isNotBlank() && userId.length <= 255 && userId.none(Char::isISOControl)) {
        "The calendar user id is invalid."
    }
    val encoded = userId.encodeDavPathSegment()
    return "/remote.php/dav/calendars/$encoded/"
}

fun groupwareAddressBookHomeHref(userId: String): String {
    require(userId.isNotBlank() && userId.length <= 255 && userId.none(Char::isISOControl)) {
        "The address-book user id is invalid."
    }
    return "/remote.php/dav/addressbooks/users/${userId.encodeDavPathSegment()}/"
}

fun parseGroupwareCalendars(response: NextcloudApiResponse): List<GroupwareCalendar> =
    parseGroupwareCalendarsForComponent(response, "VEVENT")

fun parseGroupwareTaskCalendars(response: NextcloudApiResponse): List<GroupwareCalendar> =
    parseGroupwareCalendarsForComponent(response, "VTODO")

private fun parseGroupwareCalendarsForComponent(
    response: NextcloudApiResponse,
    componentName: String,
): List<GroupwareCalendar> {
    require(response.status in 200..299) { "Calendar discovery failed (HTTP ${response.status})." }
    val xml = response.body.decodeToString()
    return xml.xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.takeIf { it.endsWith('/') }
            ?: return@mapNotNull null
        if (!block.containsXmlElement("calendar")) return@mapNotNull null
        val supportsComponent = block.xmlOpeningTags("comp").any { component ->
            component.xmlAttribute("name")?.equals(componentName, ignoreCase = true) == true
        }
        if (!supportsComponent) return@mapNotNull null
        val privileges = block.xmlElements("privilege").flatMap { it.xmlElementNames() }
        GroupwareCalendar(
            href = href.requireSafeDavHref(),
            displayName = block.xmlText("displayname")?.decodeXmlEntities()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: href.trimEnd('/').substringAfterLast('/').decodePercentEncoding(),
            color = block.xmlText("calendar-color")?.trim()?.takeIf(String::isNotBlank),
            writable = privileges.any { it == "write" || it == "write-content" },
        )
    }.distinctBy(GroupwareCalendar::href)
}

fun parseGroupwareAddressBooks(response: NextcloudApiResponse): List<GroupwareAddressBook> {
    require(response.status in 200..299) { "Address-book discovery failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.takeIf { it.endsWith('/') }
            ?: return@mapNotNull null
        if (!block.containsXmlElement("addressbook")) return@mapNotNull null
        val privileges = block.xmlElements("privilege").flatMap { it.xmlElementNames() }
        GroupwareAddressBook(
            href = href.requireSafeDavHref(),
            displayName = block.xmlText("displayname")?.decodeXmlEntities()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: href.trimEnd('/').substringAfterLast('/').decodePercentEncoding(),
            writable = privileges.any { it == "write" || it == "write-content" },
        )
    }.distinctBy(GroupwareAddressBook::href)
}

fun parseGroupwareContacts(
    addressBookHref: String,
    response: NextcloudApiResponse,
): List<GroupwareContact> {
    require(response.status in 200..299) { "Contact loading failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@mapNotNull null
        val card = block.xmlText("address-data")?.decodeXmlEntities() ?: return@mapNotNull null
        parseGroupwareContact(
            addressBookHref = addressBookHref,
            href = href,
            etag = block.xmlText("getetag")?.decodeXmlEntities()?.trim(),
            content = card,
        )
    }
}

fun parseGroupwareContact(
    addressBookHref: String,
    href: String,
    etag: String?,
    content: String,
): GroupwareContact? {
    val lines = content.unfoldCalendarLines()
    if (lines.none { it.equals("BEGIN:VCARD", ignoreCase = true) } ||
        lines.none { it.equals("END:VCARD", ignoreCase = true) }
    ) {
        return null
    }
    fun properties(name: String): List<CalendarProperty> = lines.mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val declaration = line.substring(0, separator)
        if (!declaration.substringBefore(';').equals(name, ignoreCase = true)) return@mapNotNull null
        CalendarProperty(declaration, line.substring(separator + 1))
    }
    fun property(name: String): CalendarProperty? = properties(name).firstOrNull()
    val fallbackName = property("N")?.value?.splitUnescapedCalendarComponents(';')?.filter(String::isNotBlank)
        ?.joinToString(" ")?.decodeCalendarText()
    val uid = property("UID")?.value?.trim()?.takeIf(String::isNotBlank)
        ?: href.substringAfterLast('/').substringBeforeLast('.')
    return GroupwareContact(
        href = href.requireSafeDavHref(),
        etag = etag,
        addressBookHref = addressBookHref.requireSafeDavHref(),
        uid = uid,
        displayName = property("FN")?.value?.decodeCalendarText()?.ifBlank { null }
            ?: fallbackName?.ifBlank { null }
            ?: "Unnamed contact",
        emails = properties("EMAIL").map { it.value.trim() }.filter(String::isNotBlank).distinct(),
        phones = properties("TEL").map { it.value.trim().decodeCalendarText() }
            .filter(String::isNotBlank).distinct(),
        organization = property("ORG")?.value
            ?.splitUnescapedCalendarComponents(';')
            ?.dropLastWhile(String::isEmpty)
            ?.joinToString(";")
            ?.decodeCalendarText()
            ?.takeIf(String::isNotBlank),
        address = property("ADR")?.value?.splitUnescapedCalendarComponents(';')?.filter(String::isNotBlank)
            ?.joinToString(", ")?.decodeCalendarText()?.takeIf(String::isNotBlank),
        birthday = property("BDAY")?.value?.trim()?.takeIf(String::isNotBlank),
        notes = property("NOTE")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank),
        rawVCard = content,
    )
}

fun createGroupwareContactContent(
    uid: String,
    displayName: String,
    email: String?,
    phone: String?,
    organization: String?,
    address: String?,
    notes: String?,
): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The contact id is invalid." }
    require(displayName.isNotBlank()) { "A contact name is required." }
    require(groupwareContactEmailIsSingleValue(email.orEmpty())) {
        "The contact email must be a single property value."
    }
    return buildList {
        add("BEGIN:VCARD")
        add("VERSION:4.0")
        add("UID:${uid.escapeCalendarText()}")
        add("FN:${displayName.escapeCalendarText()}")
        add("N:${displayName.escapeCalendarText()};;;;")
        email?.trim()?.takeIf(String::isNotBlank)?.let { add("EMAIL:$it") }
        phone?.trim()?.takeIf(String::isNotBlank)?.let { add("TEL:${it.escapeCalendarText()}") }
        organization?.trim()?.takeIf(String::isNotBlank)?.let { add("ORG:${it.escapeCalendarText()}") }
        address?.trim()?.takeIf(String::isNotBlank)?.let { add("ADR:;;${it.escapeCalendarText()};;;;") }
        notes?.trim()?.takeIf(String::isNotBlank)?.let { add("NOTE:${it.escapeCalendarText()}") }
        add("END:VCARD")
    }.joinToString("\r\n", postfix = "\r\n")
}

fun updateGroupwareContactContent(
    contact: GroupwareContact,
    displayName: String,
    email: String?,
    phone: String?,
    organization: String?,
    address: String?,
    notes: String?,
): String {
    require(displayName.isNotBlank()) { "A contact name is required." }
    require(groupwareContactEmailIsSingleValue(email.orEmpty())) {
        "The contact email must be a single property value."
    }
    val lines = contact.rawVCard.unfoldCalendarLines().toMutableList()
    fun replaceSingle(name: String, replacement: String?, removeAdditional: Boolean = true) {
        val indexes = lines.indices.filter { index ->
            lines[index].substringBefore(':').substringBefore(';').equals(name, ignoreCase = true)
        }
        when {
            indexes.isNotEmpty() && replacement != null -> {
                lines[indexes.first()] = replacement
                if (removeAdditional) indexes.drop(1).reversed().forEach(lines::removeAt)
            }
            indexes.isNotEmpty() -> {
                val removals = if (removeAdditional) indexes else listOf(indexes.first())
                removals.reversed().forEach(lines::removeAt)
            }
            replacement != null -> {
                val end = lines.indexOfFirst { it.equals("END:VCARD", ignoreCase = true) }
                lines.add(if (end >= 0) end else lines.size, replacement)
            }
        }
    }
    replaceSingle("FN", "FN:${displayName.escapeCalendarText()}")
    replaceSingle("N", "N:${displayName.escapeCalendarText()};;;;")
    replaceSingle("EMAIL", email?.trim()?.takeIf(String::isNotBlank)?.let { "EMAIL:$it" }, false)
    replaceSingle("TEL", phone?.trim()?.takeIf(String::isNotBlank)?.let { "TEL:${it.escapeCalendarText()}" }, false)
    replaceSingle(
        "ORG",
        organization?.trim()?.takeIf(String::isNotBlank)?.let { "ORG:${it.escapeCalendarText()}" },
    )
    replaceSingle(
        "ADR",
        address?.trim()?.takeIf(String::isNotBlank)?.let { "ADR:;;${it.escapeCalendarText()};;;;" },
    )
    replaceSingle("NOTE", notes?.trim()?.takeIf(String::isNotBlank)?.let { "NOTE:${it.escapeCalendarText()}" })
    return lines.joinToString("\r\n", postfix = "\r\n")
}

internal fun groupwareContactEmailIsSingleValue(email: String): Boolean =
    email.none { character ->
        character.isISOControl() || character == '\u2028' || character == '\u2029'
    }

fun parseGroupwareCalendarEvents(
    calendarHref: String,
    response: NextcloudApiResponse,
): List<GroupwareCalendarEvent> {
    require(response.status in 200..299) { "Calendar loading failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").flatMap { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@flatMap emptyList()
        val calendar = block.xmlText("calendar-data")?.decodeXmlEntities() ?: return@flatMap emptyList()
        parseGroupwareCalendarEventsFromContent(
            calendarHref = calendarHref,
            href = href,
            etag = block.xmlText("getetag")?.decodeXmlEntities()?.trim(),
            content = calendar,
        )
    }
}

fun parseGroupwareCalendarEvent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): GroupwareCalendarEvent? {
    val components = parseGroupwareCalendarEventsFromContent(calendarHref, href, etag, content)
    return components.firstOrNull { event -> event.recurrenceId == null } ?: components.firstOrNull()
}

internal fun parseGroupwareCalendarEventsFromContent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): List<GroupwareCalendarEvent> {
    val lines = content.unfoldCalendarLines()
    return lines.calendarEventComponents().mapNotNull { eventLines ->
        parseGroupwareCalendarEventComponent(calendarHref, href, etag, content, eventLines)
    }
}

fun parseGroupwareTasks(
    calendarHref: String,
    response: NextcloudApiResponse,
): List<GroupwareTask> {
    require(response.status in 200..299) { "Task loading failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").flatMap { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@flatMap emptyList()
        val calendar = block.xmlText("calendar-data")?.decodeXmlEntities() ?: return@flatMap emptyList()
        parseGroupwareTasksFromContent(
            calendarHref = calendarHref,
            href = href,
            etag = block.xmlText("getetag")?.decodeXmlEntities()?.trim(),
            content = calendar,
        )
    }
}

fun parseGroupwareTask(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): GroupwareTask? = parseGroupwareTasksFromContent(calendarHref, href, etag, content).firstOrNull()

private fun parseGroupwareTasksFromContent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): List<GroupwareTask> = content.unfoldCalendarLines().calendarComponentLines("VTODO").mapNotNull { lines ->
    fun property(name: String): CalendarProperty? = lines.firstNotNullOfOrNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@firstNotNullOfOrNull null
        val declaration = line.substring(0, separator)
        if (!declaration.substringBefore(';').equals(name, ignoreCase = true)) {
            return@firstNotNullOfOrNull null
        }
        CalendarProperty(declaration, line.substring(separator + 1))
    }
    val uid = property("UID")?.value?.trim()?.takeIf(String::isNotBlank)
        ?: href.substringAfterLast('/').substringBeforeLast('.')
    val due = property("DUE")
    val status = property("STATUS")?.value?.trim()
    val percentComplete = property("PERCENT-COMPLETE")?.value?.trim()?.toIntOrNull()
    GroupwareTask(
        href = href.requireSafeDavHref(),
        etag = etag,
        calendarHref = calendarHref.requireSafeDavHref(),
        uid = uid,
        title = property("SUMMARY")?.value?.decodeCalendarText()?.ifBlank { "Untitled task" }
            ?: "Untitled task",
        due = due?.value?.trim()?.takeIf(String::isNotBlank),
        dueAllDay = due?.declaration?.contains("VALUE=DATE", ignoreCase = true) == true ||
            due?.value?.let { value -> value.length == 8 && value.all(Char::isDigit) } == true,
        completed = status.equals("COMPLETED", ignoreCase = true) || percentComplete == 100,
        description = property("DESCRIPTION")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank),
        priority = property("PRIORITY")?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..9 },
        rawCalendar = content,
    )
}

private fun parseGroupwareCalendarEventComponent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
    eventLines: List<String>,
): GroupwareCalendarEvent? {
    fun property(name: String): CalendarProperty? = eventLines.firstNotNullOfOrNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@firstNotNullOfOrNull null
        val declaration = line.substring(0, separator)
        if (!declaration.substringBefore(';').equals(name, ignoreCase = true)) {
            return@firstNotNullOfOrNull null
        }
        CalendarProperty(declaration, line.substring(separator + 1))
    }
    val start = property("DTSTART") ?: return null
    val uid = property("UID")?.value?.trim()?.takeIf(String::isNotBlank)
        ?: href.substringAfterLast('/').substringBeforeLast('.')
    val recurrenceId = property("RECURRENCE-ID")?.value?.trim()?.takeIf(String::isNotBlank)
    return GroupwareCalendarEvent(
        href = href.requireSafeDavHref(),
        etag = etag,
        calendarHref = calendarHref.requireSafeDavHref(),
        uid = uid,
        title = property("SUMMARY")?.value?.decodeCalendarText()?.ifBlank { "Untitled event" }
            ?: "Untitled event",
        start = start.value.trim(),
        end = property("DTEND")?.value?.trim(),
        allDay = start.declaration.contains("VALUE=DATE", ignoreCase = true) ||
            (start.value.length == 8 && start.value.all(Char::isDigit)),
        location = property("LOCATION")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank),
        description = property("DESCRIPTION")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank),
        status = property("STATUS")?.value?.trim()?.takeIf(String::isNotBlank),
        recurrenceRule = property("RRULE")?.value?.trim()?.takeIf(String::isNotBlank),
        recurrenceId = recurrenceId,
        excludedStarts = eventLines.mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || !line.substring(0, separator).substringBefore(';')
                    .equals("EXDATE", ignoreCase = true)
            ) {
                null
            } else {
                line.substring(separator + 1).split(',').map(String::trim).filter(String::isNotBlank)
            }
        }.flatten().toSet(),
        isGeneratedOccurrence = recurrenceId != null,
        rawCalendar = content,
    )
}

/**
 * Expands common RFC5545 recurrence rules within a bounded visible window.
 *
 * Unsupported or malformed rules remain visible as their master event rather than disappearing.
 * Generated occurrences retain their DAV href/ETag for display but are marked read-only so a
 * single-instance action cannot accidentally overwrite or delete the complete server-side series.
 */
fun expandGroupwareCalendarEvents(
    events: List<GroupwareCalendarEvent>,
    timeWindow: GroupwareDavTimeWindow,
): List<GroupwareCalendarEvent> {
    val windowStart = timeWindow.startUtc.take(8)
    val windowEnd = timeWindow.endUtc.take(8)
    val result = mutableListOf<GroupwareCalendarEvent>()
    events.groupBy { it.href to it.uid }.values.forEach { components ->
        val overrides = components.filter { it.recurrenceId != null }.associateBy { it.recurrenceId }
        val masters = components.filter { it.recurrenceId == null }
        masters.forEach { master ->
            if (master.status?.equals("CANCELLED", ignoreCase = true) == true) return@forEach
            val ruleText = master.recurrenceRule
            if (ruleText == null) {
                if (master.start.take(8) >= windowStart && master.start.take(8) < windowEnd) result += master
                return@forEach
            }
            val rule = parseCalendarRecurrenceRule(ruleText)
            if (rule == null) {
                if (master.start.take(8) < windowEnd) result += master
                return@forEach
            }
            val occurrences = recurrenceDates(master.start.take(8), rule, windowEnd)
            occurrences.forEachIndexed { index, date ->
                if (rule.count != null && index >= rule.count) return@forEachIndexed
                val occurrenceStart = date + master.start.drop(8)
                if (rule.until != null && occurrenceStart.calendarComparableValue() >
                    rule.until.calendarComparableValue()
                ) {
                    return@forEachIndexed
                }
                val override = overrides[occurrenceStart] ?: overrides[date]
                if (override != null) {
                    if (override.status?.equals("CANCELLED", ignoreCase = true) != true &&
                        override.start.take(8) >= windowStart && override.start.take(8) < windowEnd
                    ) {
                        result += override
                    }
                    return@forEachIndexed
                }
                if (occurrenceStart in master.excludedStarts || date in master.excludedStarts) {
                    return@forEachIndexed
                }
                if (date < windowStart || date >= windowEnd) return@forEachIndexed
                result += master.copy(
                    start = occurrenceStart,
                    end = master.end?.shiftCalendarValue(master.start, occurrenceStart),
                    recurrenceId = occurrenceStart.takeIf { it != master.start },
                    isGeneratedOccurrence = occurrenceStart != master.start,
                )
            }
        }
        // Detached overrides may be returned without their master in a narrowly bounded REPORT.
        overrides.values.filter { override ->
            masters.none { it.uid == override.uid } &&
                override.status?.equals("CANCELLED", ignoreCase = true) != true &&
                override.start.take(8) >= windowStart && override.start.take(8) < windowEnd
        }.forEach(result::add)
    }
    return result.distinctBy(GroupwareCalendarEvent::instanceId)
        .sortedWith(compareBy(GroupwareCalendarEvent::start, GroupwareCalendarEvent::title))
}

private data class CalendarRecurrenceRule(
    val frequency: CalendarRecurrenceFrequency,
    val interval: Int,
    val count: Int?,
    val until: String?,
    val byDays: List<String>,
    val byMonthDays: List<Int>,
    val weekStart: Int,
)

private enum class CalendarRecurrenceFrequency { Daily, Weekly, Monthly }

private fun parseCalendarRecurrenceRule(value: String): CalendarRecurrenceRule? {
    val fields = value.split(';').mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) null
        else part.substring(0, separator).uppercase() to part.substring(separator + 1).uppercase()
    }.toMap()
    val frequency = when (fields["FREQ"]) {
        "DAILY" -> CalendarRecurrenceFrequency.Daily
        "WEEKLY" -> CalendarRecurrenceFrequency.Weekly
        "MONTHLY" -> CalendarRecurrenceFrequency.Monthly
        else -> return null
    }
    val interval = fields["INTERVAL"]?.toIntOrNull()?.takeIf { it in 1..1_000 } ?: 1
    val count = fields["COUNT"]?.toIntOrNull()?.takeIf { it in 1..MAX_CALENDAR_OCCURRENCES }
    val until = fields["UNTIL"]?.takeIf { it.length >= 8 && it.take(8).all(Char::isDigit) }
    val byDays = fields["BYDAY"]?.split(',')?.filter { it.takeLast(2) in CALENDAR_WEEK_DAYS }.orEmpty()
    val byMonthDays = fields["BYMONTHDAY"]?.split(',')?.mapNotNull(String::toIntOrNull)
        ?.filter { it in -31..31 && it != 0 }.orEmpty()
    val weekStart = fields["WKST"]?.let(CALENDAR_WEEK_DAY_INDEX::get) ?: 0
    return CalendarRecurrenceRule(frequency, interval, count, until, byDays, byMonthDays, weekStart)
}

private fun recurrenceDates(
    startDate: String,
    rule: CalendarRecurrenceRule,
    windowEnd: String,
): List<String> {
    val start = startDate.toCalendarCivilDate() ?: return listOf(startDate)
    val end = windowEnd.toCalendarCivilDate() ?: return listOf(startDate)
    val dates = mutableListOf<String>()
    when (rule.frequency) {
        CalendarRecurrenceFrequency.Daily -> {
            var date = start
            var attempts = 0
            while (date < end && attempts < MAX_CALENDAR_OCCURRENCES) {
                dates += date.compact()
                date = date.plusDays(rule.interval)
                attempts += 1
            }
        }
        CalendarRecurrenceFrequency.Weekly -> {
            val allowedDays = rule.byDays.mapNotNull(CALENDAR_WEEK_DAY_INDEX::get).toSet()
                .ifEmpty { setOf(start.weekday()) }
            var date = start
            var attempts = 0
            while (date < end && attempts < MAX_CALENDAR_OCCURRENCES) {
                val elapsedDays = start.daysUntil(date)
                // Anchor interval buckets to WKST (Monday by default) rather than DTSTART itself.
                val startOffset = (start.weekday() - rule.weekStart).mod(7)
                val week = (elapsedDays + startOffset).floorDiv(7)
                if (week % rule.interval == 0 && date.weekday() in allowedDays) dates += date.compact()
                date = date.plusDays(1)
                attempts += 1
            }
        }
        CalendarRecurrenceFrequency.Monthly -> {
            var year = start.year
            var month = start.month
            var monthIndex = 0
            while (monthIndex < MAX_CALENDAR_OCCURRENCES.coerceAtMost(2_400)) {
                val monthStart = CalendarCivilDate(year, month, 1)
                if (monthStart >= end) break
                if (monthIndex % rule.interval == 0) {
                    monthlyCandidateDays(year, month, start.day, rule).forEach { day ->
                        val candidate = CalendarCivilDate(year, month, day)
                        if (candidate >= start && candidate < end) dates += candidate.compact()
                    }
                }
                month += 1
                if (month == 13) {
                    month = 1
                    year += 1
                }
                monthIndex += 1
            }
        }
    }
    return dates.distinct().sorted()
}

private fun monthlyCandidateDays(
    year: Int,
    month: Int,
    fallbackDay: Int,
    rule: CalendarRecurrenceRule,
): List<Int> {
    val daysInMonth = calendarDaysInMonth(year, month)
    if (rule.byMonthDays.isNotEmpty()) {
        return rule.byMonthDays.mapNotNull { value ->
            val day = if (value > 0) value else daysInMonth + value + 1
            day.takeIf { it in 1..daysInMonth }
        }
    }
    if (rule.byDays.isNotEmpty()) {
        return rule.byDays.flatMap { token ->
            val weekday = CALENDAR_WEEK_DAY_INDEX[token.takeLast(2)] ?: return@flatMap emptyList()
            val ordinal = token.dropLast(2).toIntOrNull()
            val matching = (1..daysInMonth).filter { CalendarCivilDate(year, month, it).weekday() == weekday }
            when {
                ordinal == null -> matching
                ordinal > 0 -> listOfNotNull(matching.getOrNull(ordinal - 1))
                else -> listOfNotNull(matching.getOrNull(matching.size + ordinal))
            }
        }.distinct().sorted()
    }
    return listOf(fallbackDay).filter { it <= daysInMonth }
}

private data class CalendarCivilDate(val year: Int, val month: Int, val day: Int) :
    Comparable<CalendarCivilDate> {
    override fun compareTo(other: CalendarCivilDate): Int =
        compareValuesBy(this, other, CalendarCivilDate::year, CalendarCivilDate::month, CalendarCivilDate::day)

    fun compact(): String = "%04d%02d%02d".format(year, month, day)
    fun plusDays(days: Int): CalendarCivilDate = calendarCivilFromEpochDay(calendarEpochDay() + days)
    fun daysUntil(other: CalendarCivilDate): Int = (other.calendarEpochDay() - calendarEpochDay()).toInt()
    fun weekday(): Int = (calendarEpochDay() + 3).mod(7)
}

private fun String.toCalendarCivilDate(): CalendarCivilDate? {
    if (length < 8 || !take(8).all(Char::isDigit)) return null
    val year = take(4).toInt()
    val month = substring(4, 6).toInt()
    val day = substring(6, 8).toInt()
    if (month !in 1..12 || day !in 1..calendarDaysInMonth(year, month)) return null
    return CalendarCivilDate(year, month, day)
}

private fun CalendarCivilDate.calendarEpochDay(): Long {
    var adjustedYear = year
    val adjustedMonth = month
    adjustedYear -= if (adjustedMonth <= 2) 1 else 0
    val era = if (adjustedYear >= 0) adjustedYear / 400 else (adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val monthPrime = adjustedMonth + if (adjustedMonth > 2) -3 else 9
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra - 719_468L
}

private fun calendarCivilFromEpochDay(epochDay: Long): CalendarCivilDate {
    val z = epochDay + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra.toInt() + era.toInt() * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthPrime + 2) / 5 + 1).toInt()
    val month = (monthPrime + if (monthPrime < 10) 3 else -9).toInt()
    year += if (month <= 2) 1 else 0
    return CalendarCivilDate(year, month, day)
}

private fun String.shiftCalendarValue(originalStart: String, occurrenceStart: String): String {
    val originalStartDate = originalStart.toCalendarCivilDate() ?: return this
    val originalEndDate = toCalendarCivilDate() ?: return this
    val occurrenceDate = occurrenceStart.toCalendarCivilDate() ?: return this
    val shiftedDate = occurrenceDate.plusDays(originalStartDate.daysUntil(originalEndDate))
    return shiftedDate.compact() + drop(8)
}

private fun String.calendarComparableValue(): String = take(15).padEnd(15, '0')

private fun calendarDaysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun List<String>.calendarEventComponents(): List<List<String>> = calendarComponentLines("VEVENT")

private fun List<String>.calendarComponentLines(componentName: String): List<List<String>> {
    val result = mutableListOf<List<String>>()
    var start = -1
    forEachIndexed { index, line ->
        when {
            line.equals("BEGIN:$componentName", ignoreCase = true) -> start = index + 1
            line.equals("END:$componentName", ignoreCase = true) && start >= 0 -> {
                result += subList(start, index)
                start = -1
            }
        }
    }
    return result
}

private const val MAX_CALENDAR_OCCURRENCES = 50_000
private val CALENDAR_WEEK_DAYS = setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
private val CALENDAR_WEEK_DAY_INDEX = mapOf(
    "MO" to 0, "TU" to 1, "WE" to 2, "TH" to 3, "FR" to 4, "SA" to 5, "SU" to 6,
)

fun createGroupwareCalendarEventContent(
    uid: String,
    title: String,
    start: String,
    end: String?,
    allDay: Boolean,
    location: String? = null,
    description: String? = null,
    recurrenceRule: String? = null,
): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The event id is invalid." }
    require(title.isNotBlank()) { "An event title is required." }
    require(start.isCalendarDateValue(allDay)) { "The event start is invalid." }
    require(end == null || end.isCalendarDateValue(allDay)) { "The event end is invalid." }
    recurrenceRule?.let { requireValidCalendarRecurrenceRule(it) }
    val dateParameter = if (allDay) ";VALUE=DATE" else ""
    return buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Obiente//Nextcloud Native//EN")
        add("BEGIN:VEVENT")
        add("UID:${uid.escapeCalendarText()}")
        add("DTSTART$dateParameter:$start")
        end?.let { add("DTEND$dateParameter:$it") }
        add("SUMMARY:${title.escapeCalendarText()}")
        location?.takeIf(String::isNotBlank)?.let { add("LOCATION:${it.escapeCalendarText()}") }
        description?.takeIf(String::isNotBlank)?.let { add("DESCRIPTION:${it.escapeCalendarText()}") }
        recurrenceRule?.trim()?.takeIf(String::isNotBlank)?.let { add("RRULE:$it") }
        add("END:VEVENT")
        add("END:VCALENDAR")
    }.joinToString("\r\n", postfix = "\r\n")
}

fun createGroupwareTaskContent(
    uid: String,
    title: String,
    dueDate: String?,
    completed: Boolean,
    description: String? = null,
): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The task id is invalid." }
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also { value ->
        require(value.length == 8 && value.all(Char::isDigit)) { "The task due date is invalid." }
    }
    return buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Obiente//Nextcloud Native//EN")
        add("BEGIN:VTODO")
        add("UID:${uid.escapeCalendarText()}")
        add("SUMMARY:${title.escapeCalendarText()}")
        due?.let { add("DUE;VALUE=DATE:$it") }
        add("STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}")
        add("PERCENT-COMPLETE:${if (completed) 100 else 0}")
        description?.takeIf(String::isNotBlank)?.let { add("DESCRIPTION:${it.escapeCalendarText()}") }
        add("END:VTODO")
        add("END:VCALENDAR")
    }.joinToString("\r\n", postfix = "\r\n")
}

fun updateGroupwareTaskContent(
    task: GroupwareTask,
    title: String,
    dueDate: String?,
    completed: Boolean,
    description: String?,
): String {
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also { value ->
        require(value.length == 8 && value.all(Char::isDigit)) { "The task due date is invalid." }
    }
    val original = task.rawCalendar.unfoldCalendarLines().toMutableList()
    val taskRange = original.calendarComponentRanges("VTODO").firstOrNull { range ->
        original.subList(range.first + 1, range.last).calendarPropertyValue("UID") == task.uid
    }
    requireNotNull(taskRange) { "The selected task component could not be found." }
    val taskStart = taskRange.first
    var taskEnd = taskRange.last
    val replacements = linkedMapOf(
        "SUMMARY" to "SUMMARY:${title.escapeCalendarText()}",
        "DUE" to due?.let { "DUE;VALUE=DATE:$it" },
        "STATUS" to "STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}",
        "PERCENT-COMPLETE" to "PERCENT-COMPLETE:${if (completed) 100 else 0}",
        "COMPLETED" to null,
        "DESCRIPTION" to description?.takeIf(String::isNotBlank)?.let {
            "DESCRIPTION:${it.escapeCalendarText()}"
        },
    )
    replacements.forEach { (name, replacement) ->
        val index = (taskStart + 1 until taskEnd).firstOrNull { lineIndex ->
            original[lineIndex].substringBefore(':').substringBefore(';').equals(name, ignoreCase = true)
        }
        when {
            index != null && replacement != null -> original[index] = replacement
            index != null -> {
                original.removeAt(index)
                taskEnd -= 1
            }
            replacement != null -> {
                original.add(taskEnd, replacement)
                taskEnd += 1
            }
        }
    }
    return original.joinToString("\r\n", postfix = "\r\n")
}

fun updateGroupwareCalendarEventContent(
    event: GroupwareCalendarEvent,
    title: String,
    start: String,
    end: String?,
    allDay: Boolean,
    location: String?,
    description: String?,
    recurrenceRule: String? = event.recurrenceRule,
): String {
    recurrenceRule?.let { requireValidCalendarRecurrenceRule(it) }
    val original = event.rawCalendar.unfoldCalendarLines().toMutableList()
    val eventRange = original.calendarComponentRanges("VEVENT").firstOrNull { range ->
        val component = original.subList(range.first + 1, range.last)
        val uid = component.calendarPropertyValue("UID")
            ?: event.href.substringAfterLast('/').substringBeforeLast('.')
        uid == event.uid && component.calendarPropertyValue("RECURRENCE-ID") == event.recurrenceId
    }
    requireNotNull(eventRange) { "The selected calendar event component could not be found." }
    val eventStart = eventRange.first
    var eventEnd = eventRange.last
    val replacements = linkedMapOf(
        "DTSTART" to "DTSTART${if (allDay) ";VALUE=DATE" else ""}:$start",
        "DTEND" to end?.let { "DTEND${if (allDay) ";VALUE=DATE" else ""}:$it" },
        "SUMMARY" to "SUMMARY:${title.escapeCalendarText()}",
        "LOCATION" to location?.takeIf(String::isNotBlank)?.let { "LOCATION:${it.escapeCalendarText()}" },
        "DESCRIPTION" to description?.takeIf(String::isNotBlank)?.let { "DESCRIPTION:${it.escapeCalendarText()}" },
        "RRULE" to recurrenceRule?.trim()?.takeIf(String::isNotBlank)?.let { "RRULE:$it" },
    )
    replacements.forEach { (name, replacement) ->
        val index = (eventStart + 1 until eventEnd).firstOrNull { lineIndex ->
            original[lineIndex].substringBefore(':').substringBefore(';').equals(name, ignoreCase = true)
        }
        when {
            index != null && replacement != null -> original[index] = replacement
            index != null -> {
                original.removeAt(index)
                eventEnd -= 1
            }
            replacement != null -> {
                original.add(eventEnd, replacement)
                eventEnd += 1
            }
        }
    }
    return original.joinToString("\r\n", postfix = "\r\n")
}

private fun List<String>.calendarComponentRanges(componentName: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var start = -1
    forEachIndexed { index, line ->
        when {
            line.equals("BEGIN:$componentName", ignoreCase = true) -> start = index
            line.equals("END:$componentName", ignoreCase = true) && start >= 0 -> {
                result += start..index
                start = -1
            }
        }
    }
    return result
}

private fun List<String>.calendarPropertyValue(name: String): String? = firstNotNullOfOrNull { line ->
    val separator = line.indexOf(':')
    if (separator <= 0 || !line.substring(0, separator).substringBefore(';').equals(name, ignoreCase = true)) {
        null
    } else {
        line.substring(separator + 1).trim().takeIf(String::isNotBlank)
    }
}

private fun requireValidCalendarRecurrenceRule(value: String) {
    require(isSupportedCalendarRecurrenceRuleForWrite(value)) {
        "Use a supported daily, weekly, or monthly recurrence rule."
    }
}

internal fun isSupportedCalendarRecurrenceRuleForWrite(value: String): Boolean {
    val normalized = value.trim().uppercase()
    if (
        normalized.length !in 1..MAX_CALENDAR_RECURRENCE_RULE_LENGTH ||
        normalized.any(Char::isISOControl) ||
        ':' in normalized
    ) {
        return false
    }
    val parts = normalized.split(';')
    val fields = linkedMapOf<String, String>()
    for (part in parts) {
        val separator = part.indexOf('=')
        if (separator <= 0 || separator == part.lastIndex) return false
        val key = part.substring(0, separator)
        val fieldValue = part.substring(separator + 1)
        if (key !in SUPPORTED_CALENDAR_RECURRENCE_FIELDS || fields.put(key, fieldValue) != null) return false
    }
    val frequency = fields["FREQ"] ?: return false
    if (frequency !in SUPPORTED_CALENDAR_RECURRENCE_FREQUENCIES) return false
    if (fields["INTERVAL"]?.toIntOrNull()?.let { it !in 1..1_000 } == true) return false
    if ("INTERVAL" in fields && fields["INTERVAL"]?.toIntOrNull() == null) return false
    if (fields["COUNT"]?.toIntOrNull()?.let { it !in 1..MAX_CALENDAR_OCCURRENCES } == true) return false
    if ("COUNT" in fields && fields["COUNT"]?.toIntOrNull() == null) return false
    if ("COUNT" in fields && "UNTIL" in fields) return false
    if (fields["UNTIL"]?.isSupportedCalendarRecurrenceUntil() == false) return false
    if (fields["WKST"]?.let { it !in CALENDAR_WEEK_DAYS } == true) return false

    val byDays = fields["BYDAY"]?.split(',').orEmpty()
    val byMonthDays = fields["BYMONTHDAY"]?.split(',').orEmpty()
    if ("BYDAY" in fields && byDays.isEmpty()) return false
    if ("BYMONTHDAY" in fields && byMonthDays.isEmpty()) return false
    return when (frequency) {
        "DAILY" -> "BYDAY" !in fields && "BYMONTHDAY" !in fields && "WKST" !in fields
        "WEEKLY" -> {
            "BYMONTHDAY" !in fields && byDays.all { it in CALENDAR_WEEK_DAYS }
        }
        "MONTHLY" -> {
            "WKST" !in fields && !("BYDAY" in fields && "BYMONTHDAY" in fields) &&
                byDays.all(String::isSupportedMonthlyCalendarByDay) &&
                byMonthDays.all { token -> token.toIntOrNull()?.let { it in -31..31 && it != 0 } == true }
        }
        else -> false
    }
}

private fun String.isSupportedCalendarRecurrenceUntil(): Boolean = when {
    length == 8 -> all(Char::isDigit)
    length == 16 && getOrNull(8) == 'T' && last() == 'Z' ->
        take(8).all(Char::isDigit) && substring(9, 15).all(Char::isDigit)
    else -> false
}

private fun String.isSupportedMonthlyCalendarByDay(): Boolean {
    val day = takeLast(2)
    if (day !in CALENDAR_WEEK_DAYS) return false
    val ordinal = dropLast(2)
    return ordinal.isEmpty() || ordinal.toIntOrNull()?.let { it in -5..5 && it != 0 } == true
}

private val SUPPORTED_CALENDAR_RECURRENCE_FREQUENCIES = setOf("DAILY", "WEEKLY", "MONTHLY")
private val SUPPORTED_CALENDAR_RECURRENCE_FIELDS = setOf(
    "FREQ",
    "INTERVAL",
    "COUNT",
    "UNTIL",
    "BYDAY",
    "BYMONTHDAY",
    "WKST",
)

private data class CalendarProperty(val declaration: String, val value: String)

private fun String.unfoldCalendarLines(): List<String> {
    val result = mutableListOf<String>()
    replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        if ((line.startsWith(' ') || line.startsWith('\t')) && result.isNotEmpty()) {
            result[result.lastIndex] = result.last() + line.drop(1)
        } else if (line.isNotEmpty()) {
            result += line
        }
    }
    return result
}

internal fun String.normalizeGroupwareTextLineEndings(): String =
    replace("\r\n", "\n").replace('\r', '\n')

private fun String.escapeCalendarText(): String = normalizeGroupwareTextLineEndings()
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace(",", "\\,")
    .replace(";", "\\;")

private fun String.decodeCalendarText(): String = buildString(length) {
    var index = 0
    while (index < this@decodeCalendarText.length) {
        val character = this@decodeCalendarText[index]
        if (character != '\\' || index == this@decodeCalendarText.lastIndex) {
            append(character)
            index += 1
            continue
        }
        val escaped = this@decodeCalendarText[index + 1]
        when (escaped) {
            'n', 'N' -> append('\n')
            '\\', ',', ';' -> append(escaped)
            else -> {
                append('\\')
                append(escaped)
            }
        }
        index += 2
    }
}

private fun String.splitUnescapedCalendarComponents(delimiter: Char): List<String> {
    val components = mutableListOf<String>()
    var componentStart = 0
    var precedingBackslashes = 0
    forEachIndexed { index, character ->
        if (character == delimiter && precedingBackslashes % 2 == 0) {
            components += substring(componentStart, index)
            componentStart = index + 1
        }
        precedingBackslashes = if (character == '\\') precedingBackslashes + 1 else 0
    }
    components += substring(componentStart)
    return components
}

private fun String.isCalendarDateValue(allDay: Boolean): Boolean =
    if (allDay) length == 8 && all(Char::isDigit)
    else length in 15..20 && take(8).all(Char::isDigit) && getOrNull(8) == 'T'

private fun String.encodeDavPathSegment(): String = encodeToByteArray().joinToString("") { byte ->
    val unsigned = byte.toInt() and 0xff
    val character = unsigned.toChar()
    if (character.isLetterOrDigit() || character in "-._~") character.toString()
    else "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
}

private fun String.decodePercentEncoding(): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < length) {
        if (this[index] == '%' && index + 2 < length) {
            val value = substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                bytes += value.toByte()
                index += 3
                continue
            }
        }
        bytes += this[index].code.toByte()
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

internal fun String.xmlElements(localName: String): List<String> {
    val results = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val nameStart = opening + 1
        if (getOrNull(nameStart) in listOf('/', '!', '?')) {
            cursor = nameStart + 1
            continue
        }
        val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), nameStart)
        if (nameEnd < 0) break
        val qualifiedName = substring(nameStart, nameEnd)
        if (!qualifiedName.substringAfter(':').equals(localName, ignoreCase = true)) {
            cursor = nameEnd
            continue
        }
        val openingEnd = indexOf('>', nameEnd)
        if (openingEnd < 0) break
        if (getOrNull(openingEnd - 1) == '/') {
            results += substring(opening, openingEnd + 1)
            cursor = openingEnd + 1
            continue
        }
        val closingStart = indexOf("</$qualifiedName", openingEnd + 1, ignoreCase = true)
        if (closingStart < 0) {
            cursor = openingEnd + 1
            continue
        }
        val closingEnd = indexOf('>', closingStart + qualifiedName.length + 2)
        if (closingEnd < 0) break
        results += substring(opening, closingEnd + 1)
        cursor = closingEnd + 1
    }
    return results
}

internal fun String.xmlText(localName: String): String? = xmlElements(localName).firstOrNull()?.let { element ->
    val openingEnd = element.indexOf('>')
    val closingStart = element.lastIndexOf("</")
    if (openingEnd >= 0 && closingStart > openingEnd) element.substring(openingEnd + 1, closingStart) else null
}

private fun String.containsXmlElement(localName: String): Boolean = xmlElements(localName).isNotEmpty()

private fun String.xmlAttribute(name: String): String? {
    val openingEnd = indexOf('>').takeIf { it >= 0 } ?: return null
    val opening = substring(0, openingEnd)
    val marker = "$name="
    val markerIndex = opening.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val quote = opening.getOrNull(markerIndex + marker.length)?.takeIf { it == '"' || it == '\'' } ?: return null
    val valueStart = markerIndex + marker.length + 1
    val valueEnd = opening.indexOf(quote, valueStart)
    return valueEnd.takeIf { it >= 0 }?.let { opening.substring(valueStart, it) }
}

private fun String.xmlElementNames(): List<String> {
    val names = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val start = opening + 1
        if (getOrNull(start) in listOf('/', '!', '?')) {
            cursor = start + 1
            continue
        }
        val end = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), start)
        if (end < 0) break
        names += substring(start, end).substringAfter(':').lowercase()
        cursor = end
    }
    return names
}

private fun String.xmlOpeningTags(localName: String): List<String> {
    val tags = mutableListOf<String>()
    var cursor = 0
    while (cursor < length) {
        val opening = indexOf('<', cursor)
        if (opening < 0) break
        val start = opening + 1
        if (getOrNull(start) in listOf('/', '!', '?')) {
            cursor = start + 1
            continue
        }
        val end = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), start)
        if (end < 0) break
        val qualifiedName = substring(start, end)
        val openingEnd = indexOf('>', end)
        if (openingEnd < 0) break
        if (qualifiedName.substringAfter(':').equals(localName, ignoreCase = true)) {
            tags += substring(opening, openingEnd + 1)
        }
        cursor = openingEnd + 1
    }
    return tags
}

internal fun String.decodeXmlEntities(): String {
    val numeric = buildString(length) {
        var cursor = 0
        while (cursor < this@decodeXmlEntities.length) {
            if (this@decodeXmlEntities[cursor] == '&' &&
                this@decodeXmlEntities.getOrNull(cursor + 1) == '#'
            ) {
                val end = this@decodeXmlEntities.indexOf(';', cursor + 2)
                    .takeIf { it in (cursor + 3)..(cursor + 10) }
                if (end != null) {
                    val encoded = this@decodeXmlEntities.substring(cursor + 2, end)
                    val codePoint = if (encoded.startsWith('x', ignoreCase = true)) {
                        encoded.drop(1).toIntOrNull(16)
                    } else {
                        encoded.toIntOrNull()
                    }
                    if (codePoint != null && codePoint in 0..0x10ffff && codePoint !in 0xd800..0xdfff) {
                        appendCodePoint(codePoint)
                        cursor = end + 1
                        continue
                    }
                }
            }
            append(this@decodeXmlEntities[cursor])
            cursor += 1
        }
    }
    return numeric.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xffff) {
        append(codePoint.toChar())
    } else {
        val adjusted = codePoint - 0x10000
        append(((adjusted shr 10) + 0xd800).toChar())
        append(((adjusted and 0x3ff) + 0xdc00).toChar())
    }
}

internal fun String.requireSafeDavHref(): String {
    val normalized = lowercase()
    require(
        startsWith("/remote.php/dav/") &&
            length <= MAX_DAV_HREF_LENGTH &&
            none { it.isISOControl() || it == '\\' || it == '#' || it == '?' } &&
            split('/').none { it == "." || it == ".." } &&
            listOf("%2e", "%2f", "%5c", "%00").none(normalized::contains),
    ) { "The discovered DAV href is unsafe." }
    return this
}

internal fun String.escapeDavXml(): String = buildString(length) {
    for (character in this@escapeDavXml) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}

private fun String.isDavUtcDateTime(): Boolean =
    length == 16 && this[8] == 'T' && last() == 'Z' &&
        indices.filterNot { it == 8 || it == lastIndex }.all { this[it].isDigit() }

private const val DAV_XML_CONTENT_TYPE = "application/xml; charset=utf-8"
private const val DEFAULT_DAV_QUERY_LIMIT = 100
private const val MAX_DAV_QUERY_LIMIT = 250
private const val DEFAULT_DAV_SYNC_LIMIT = 250
private const val MAX_DAV_SYNC_LIMIT = 1_000
private const val MAX_DAV_SYNC_PAGES = 100
private const val MAX_DAV_SYNC_TOKEN_LENGTH = 4_096
private const val MAX_DAV_HREF_LENGTH = 4_096
private const val MAX_DAV_ETAG_LENGTH = 1_024
private const val MAX_CALENDAR_RECURRENCE_RULE_LENGTH = 1_024
private const val MAX_DAV_OBJECT_BYTES = 4 * 1024 * 1024
private const val DAV_DISCOVERY_RESPONSE_BYTES = 1L * 1024L * 1024L
private const val DAV_COLLECTION_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val DAV_QUERY_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val DAV_SYNC_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val DAV_OBJECT_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val DAV_MUTATION_RESPONSE_BYTES = 256L * 1024L

private val PRINCIPAL_DISCOVERY_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:">
      <d:prop><d:current-user-principal /><d:resourcetype /></d:prop>
    </d:propfind>
""".trimIndent()

private val HOME_DISCOVERY_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:"
        xmlns:c="urn:ietf:params:xml:ns:caldav"
        xmlns:card="urn:ietf:params:xml:ns:carddav">
      <d:prop>
        <c:calendar-home-set /><card:addressbook-home-set />
        <c:schedule-inbox-URL /><c:schedule-outbox-URL />
        <d:current-user-privilege-set />
      </d:prop>
    </d:propfind>
""".trimIndent()

private val COLLECTION_DISCOVERY_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:"
        xmlns:c="urn:ietf:params:xml:ns:caldav"
        xmlns:card="urn:ietf:params:xml:ns:carddav"
        xmlns:cs="http://calendarserver.org/ns/">
      <d:prop>
        <d:displayname /><d:resourcetype /><d:getetag /><d:sync-token />
        <cs:getctag /><d:current-user-privilege-set />
        <c:supported-calendar-component-set />
      </d:prop>
    </d:propfind>
""".trimIndent()
