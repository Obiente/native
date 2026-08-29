package dev.obiente.nextcloudnative.app

internal data class GroupwareTaskCalendarLoadResult(
    val tasks: List<GroupwareTask>,
    val failedCalendarNames: List<String>,
)

private data class GroupwareTaskObjectReference(
    val href: String,
    val etag: String,
)

internal suspend fun loadGroupwareTaskCalendars(
    calendars: List<GroupwareCalendar>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): GroupwareTaskCalendarLoadResult {
    val tasks = mutableListOf<GroupwareTask>()
    val failures = mutableListOf<String>()
    calendars.forEach { calendar ->
        runCatchingPreservingCancellation {
            loadGroupwareTasksInBatches(calendar.href, execute)
        }.onSuccess(tasks::addAll).onFailure {
            failures += calendar.displayName
        }
    }
    return GroupwareTaskCalendarLoadResult(tasks, failures)
}

internal fun groupwareDavCalendarObjectListingRequest(calendarHref: String): GroupwareDavRequest =
    GroupwareDavRequest(
        method = "PROPFIND",
        relativePath = calendarHref.requireTaskCalendarCollectionHref(),
        depth = 1,
        contentType = TASK_DAV_XML_CONTENT_TYPE,
        body = TASK_OBJECT_LISTING_BODY.encodeToByteArray(),
        maximumResponseBytes = TASK_OBJECT_LISTING_RESPONSE_BYTES,
    )

internal fun groupwareDavCalendarMultiGetRequest(
    calendarHref: String,
    objectHrefs: List<String>,
): GroupwareDavRequest {
    val collectionHref = calendarHref.requireTaskCalendarCollectionHref()
    require(objectHrefs.size in 1..MAX_TASK_DAV_MULTIGET_ITEMS) {
        "The CalDAV multiget batch is out of range."
    }
    val safeHrefs = objectHrefs.map { href ->
        href.requireSafeDavHref().also {
            require(it.isDirectTaskCalendarChildOf(collectionHref)) {
                "The CalDAV object is outside its calendar."
            }
        }
    }
    require(safeHrefs.distinct().size == safeHrefs.size) {
        "The CalDAV multiget batch contains duplicates."
    }
    val hrefElements = safeHrefs.joinToString("\n") { href ->
        "  <d:href>${href.escapeDavXml()}</d:href>"
    }
    val body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:prop><d:getetag /><c:calendar-data /></d:prop>
        $hrefElements
        </c:calendar-multiget>
    """.trimIndent()
    return GroupwareDavRequest(
        method = "REPORT",
        relativePath = collectionHref,
        depth = 1,
        contentType = TASK_DAV_XML_CONTENT_TYPE,
        body = body.encodeToByteArray(),
        maximumResponseBytes = TASK_MULTIGET_RESPONSE_BYTES,
    )
}

internal suspend fun loadGroupwareTasksInBatches(
    calendarHref: String,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): List<GroupwareTask> {
    val objectReferences = parseGroupwareCalendarObjectReferences(
        calendarHref,
        execute(groupwareDavCalendarObjectListingRequest(calendarHref)),
    )
    return buildList {
        objectReferences.chunked(MAX_TASK_DAV_MULTIGET_ITEMS).forEach { batch ->
            addAll(loadGroupwareTaskBatch(calendarHref, batch, execute))
        }
    }
}

private fun parseGroupwareCalendarObjectReferences(
    calendarHref: String,
    response: NextcloudApiResponse,
): List<GroupwareTaskObjectReference> {
    require(response.status in 200..299) { "Task discovery failed (HTTP ${response.status})." }
    val collectionHref = calendarHref.requireTaskCalendarCollectionHref()
    val references = response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: error("The CalDAV listing response omitted an object href.")
        if (href == collectionHref) return@mapNotNull null
        require(href.isDirectTaskCalendarChildOf(collectionHref)) {
            "The CalDAV listing response contained an object outside its calendar."
        }
        val successfulProperty = block.xmlElements("propstat").singleOrNull { property ->
            property.xmlElements("getetag").isNotEmpty() &&
                property.xmlText("status")?.taskDavStatusCode() in 200..299
        } ?: error("The CalDAV listing response contained a failed or malformed object.")
        val etag = successfulProperty.xmlText("getetag")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
            ?: error("The CalDAV listing response omitted an object ETag.")
        GroupwareTaskObjectReference(href, etag)
    }
    require(references.distinctBy(GroupwareTaskObjectReference::href).size == references.size) {
        "The CalDAV listing response contained a duplicate object."
    }
    return references
}

private suspend fun loadGroupwareTaskBatch(
    calendarHref: String,
    objectReferences: List<GroupwareTaskObjectReference>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): List<GroupwareTask> {
    val objectHrefs = objectReferences.map(GroupwareTaskObjectReference::href)
    val response = try {
        execute(groupwareDavCalendarMultiGetRequest(calendarHref, objectHrefs))
    } catch (failure: NextcloudResponseTooLargeException) {
        if (failure.responseStatus?.let { it in 200..299 } != true) throw failure
        return if (objectHrefs.size == 1) {
            loadGroupwareTaskObjectsIndividually(calendarHref, objectReferences, execute)
        } else {
            val midpoint = objectHrefs.size / 2
            loadGroupwareTaskBatch(calendarHref, objectReferences.take(midpoint), execute) +
                loadGroupwareTaskBatch(calendarHref, objectReferences.drop(midpoint), execute)
        }
    }
    if (response.status in 200..299) {
        return parseGroupwareCalendarMultiGetResponse(calendarHref, objectHrefs, response)
    }
    if (response.status in 500..599 || response.status in setOf(405, 501)) {
        return loadGroupwareTaskObjectsIndividually(calendarHref, objectReferences, execute)
    }
    error("Task loading failed (HTTP ${response.status}).")
}

private fun parseGroupwareCalendarMultiGetResponse(
    calendarHref: String,
    requestedHrefs: List<String>,
    response: NextcloudApiResponse,
): List<GroupwareTask> {
    require(response.status in 200..299) { "Task loading failed (HTTP ${response.status})." }
    val requested = requestedHrefs.toSet()
    require(requested.size == requestedHrefs.size)
    val returned = mutableSetOf<String>()
    val tasks = response.body.decodeToString().xmlElements("response").flatMap { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: error("The CalDAV multiget response omitted an object href.")
        require(href in requested && returned.add(href)) {
            "The CalDAV multiget response contained an unrequested or duplicate object."
        }
        block.xmlText("status")?.taskDavStatusCode()?.let { status ->
            require(status in 200..299) { "The CalDAV multiget response contained a failed object." }
        }
        val successfulProperty = block.xmlElements("propstat").singleOrNull { property ->
            property.xmlElements("calendar-data").isNotEmpty() &&
                property.xmlText("status")?.taskDavStatusCode() in 200..299
        } ?: error("The CalDAV multiget response did not return an object successfully.")
        val etag = successfulProperty.xmlText("getetag")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
            ?: error("The CalDAV multiget response omitted an object ETag.")
        val content = successfulProperty.xmlText("calendar-data")?.decodeXmlEntities()
            ?: error("The CalDAV multiget response omitted calendar data.")
        parseGroupwareTasksFromContent(calendarHref, href, etag, content)
    }
    require(returned == requested) {
        "The CalDAV multiget response did not return every requested object successfully."
    }
    return tasks
}

private suspend fun loadGroupwareTaskObjectsIndividually(
    calendarHref: String,
    objectReferences: List<GroupwareTaskObjectReference>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): List<GroupwareTask> = objectReferences.flatMap { reference ->
    val response = execute(groupwareDavDetailRequest(reference.href))
    require(response.status in 200..299) { "Task loading failed (HTTP ${response.status})." }
    parseGroupwareTasksFromContent(
        calendarHref = calendarHref,
        href = reference.href,
        etag = response.etag ?: reference.etag,
        content = response.body.decodeToString(),
    )
}

private fun String.requireTaskCalendarCollectionHref(): String = requireSafeDavHref().also {
    require(it.endsWith('/')) { "The CalDAV collection href is invalid." }
}

private fun String.isDirectTaskCalendarChildOf(collectionHref: String): Boolean {
    if (!startsWith(collectionHref) || endsWith('/')) return false
    val childName = removePrefix(collectionHref)
    return childName.isNotBlank() && '/' !in childName
}

private fun String.taskDavStatusCode(): Int? = trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()

private const val TASK_DAV_XML_CONTENT_TYPE = "application/xml; charset=utf-8"
private const val TASK_OBJECT_LISTING_RESPONSE_BYTES = 16L * 1024L * 1024L
private const val TASK_MULTIGET_RESPONSE_BYTES = 16L * 1024L * 1024L
private const val MAX_TASK_DAV_MULTIGET_ITEMS = 10

private val TASK_OBJECT_LISTING_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:">
      <d:prop><d:getetag /></d:prop>
    </d:propfind>
""".trimIndent()
