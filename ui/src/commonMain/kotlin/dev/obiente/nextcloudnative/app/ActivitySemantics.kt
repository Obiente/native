package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class NextcloudActivityPage(
    val activities: List<NextcloudActivity>,
    val nextSince: Long?,
    val hasMore: Boolean,
)

data class NextcloudActivityFilterOption(
    val id: String,
    val name: String,
    val priority: Int,
) {
    init {
        require(id.isValidActivityFilterId()) { "The activity filter identifier is invalid." }
        require(name.isNotBlank() && name.length <= MAX_ACTIVITY_FILTER_NAME_CHARS) {
            "The activity filter name is invalid."
        }
    }
}

enum class NextcloudActivitySemantic {
    Message,
    Media,
    File,
    General,
}

enum class ActivityNotificationDestination {
    Talk,
    Media,
    Files,
    Activity,
}

enum class ActivitySettingsDestination {
    Notifications,
    RssFeed,
}

data class DynamicActivityNotificationPlan(
    val notificationId: Int,
    val semantic: NextcloudActivitySemantic,
    val destination: ActivityNotificationDestination,
    val groupKey: String,
    val title: String,
    val detail: String,
)

data class ActivityTimelineState(
    val activities: List<NextcloudActivity> = emptyList(),
    val initialized: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val nextSince: Long? = null,
    val hasMore: Boolean = false,
    val error: String? = null,
)

data class ActivityFeedFilter(
    val query: String = "",
    val app: String? = null,
    val type: String? = null,
    val semantic: NextcloudActivitySemantic? = null,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || app != null || type != null || semantic != null
}

data class ActivityFeedFacet(
    val key: String,
    val label: String,
    val count: Int,
)

data class ActivityFeedDayGroup(
    val dateKey: String,
    val label: String,
    val activities: List<NextcloudActivity>,
)

data class ActivityFeedPresentation(
    val groups: List<ActivityFeedDayGroup>,
    val appFacets: List<ActivityFeedFacet>,
    val typeFacets: List<ActivityFeedFacet>,
    val semanticCounts: Map<NextcloudActivitySemantic, Int>,
    val matchedCount: Int,
)

data class ActivityOpenAction(
    val label: String,
    val appId: String? = null,
    val sameOriginUrl: String? = null,
    val filesParentPath: String? = null,
) {
    init {
        require(label.isNotBlank() && label.length <= 80) { "The activity action label is invalid." }
        require(listOfNotNull(appId, sameOriginUrl, filesParentPath).size == 1) {
            "An activity action must have exactly one destination."
        }
    }
}

fun buildActivityFeedPresentation(
    activities: List<NextcloudActivity>,
    filter: ActivityFeedFilter = ActivityFeedFilter(),
): ActivityFeedPresentation {
    val appFacets = activities
        .groupingBy(NextcloudActivity::app)
        .eachCount()
        .map { (key, count) -> ActivityFeedFacet(key, readableActivitySource(key), count) }
        .sortedWith(compareByDescending<ActivityFeedFacet>(ActivityFeedFacet::count).thenBy(ActivityFeedFacet::label))
    val typeFacets = activities
        .filter { activity -> activity.type.isNotBlank() }
        .groupingBy(NextcloudActivity::type)
        .eachCount()
        .map { (key, count) -> ActivityFeedFacet(key, readableActivityType(key), count) }
        .sortedWith(compareByDescending<ActivityFeedFacet>(ActivityFeedFacet::count).thenBy(ActivityFeedFacet::label))
    val semanticCounts = NextcloudActivitySemantic.entries.associateWith { semantic ->
        activities.count { activity -> activity.semantic() == semantic }
    }
    val normalizedQuery = filter.query.trim().lowercase()
    val matched = activities.filter { activity ->
        (filter.app == null || activity.app == filter.app) &&
            (filter.type == null || activity.type == filter.type) &&
            (filter.semantic == null || activity.semantic() == filter.semantic) &&
            (
                normalizedQuery.isBlank() ||
                    activity.searchableActivityText().any { value -> normalizedQuery in value.lowercase() }
                )
    }
    val groups = matched
        .groupBy(NextcloudActivity::activityDateKey)
        .map { (dateKey, dayActivities) ->
            ActivityFeedDayGroup(
                dateKey = dateKey,
                label = readableActivityDay(dateKey),
                activities = dayActivities,
            )
        }
    return ActivityFeedPresentation(
        groups = groups,
        appFacets = appFacets,
        typeFacets = typeFacets,
        semanticCounts = semanticCounts,
        matchedCount = matched.size,
    )
}

fun NextcloudActivity.activityOpenAction(
    installedAppIds: Set<String>,
    serverUrl: String,
): ActivityOpenAction? {
    val hasFilesDestination = installedAppIds.any { it.equals("files", true) }
    val producedByFiles = app.equals("files", ignoreCase = true) ||
        type.startsWith("file_", ignoreCase = true) ||
        objectType?.contains("file", ignoreCase = true) == true
    if (hasFilesDestination && producedByFiles) {
        safeActivityFilesParentPath(objectName)?.let { parentPath ->
            return ActivityOpenAction(label = "Show in Files", filesParentPath = parentPath)
        }
    }
    val normalizedInstalled = installedAppIds.associateBy(String::lowercase)
    val candidates = buildList {
        val context = listOf(app, type, objectType.orEmpty())
            .joinToString(" ")
            .normalizedActivityToken()
        add(app)
        when (semantic()) {
            NextcloudActivitySemantic.Message -> {
                if (app.equals("mail", ignoreCase = true)) add("mail")
                add("spreed")
                add("talk")
            }
            NextcloudActivitySemantic.Media -> {
                add("memories")
                add("photos")
            }
            NextcloudActivitySemantic.File -> add("files")
            NextcloudActivitySemantic.General -> Unit
        }
        when {
            "calendar" in context || "event" in context -> add("calendar")
            "contact" in context || "addressbook" in context -> add("contacts")
            "note" in context -> add("notes")
            "deck" in context || "board" in context || "card" in context -> add("deck")
            "recipe" in context || "cookbook" in context -> add("cookbook")
        }
    }
    val appId = candidates
        .asSequence()
        .mapNotNull { candidate -> normalizedInstalled[candidate.lowercase()] }
        .firstOrNull { candidate -> !candidate.equals("activity", ignoreCase = true) }
    if (appId != null) {
        return ActivityOpenAction(label = "Go to ${readableActivitySource(appId)}", appId = appId)
    }
    val sameOriginUrl = link?.let { value -> sameOriginActivityUrl(serverUrl, value) } ?: return null
    return ActivityOpenAction(label = "Open", sameOriginUrl = sameOriginUrl)
}

private fun safeActivityFilesParentPath(objectName: String?): String? {
    val value = objectName?.trim()?.removePrefix("/") ?: return null
    if (value.isBlank() || value.length > MAX_ACTIVITY_URL_CHARS) return null
    val segments = value.split('/')
    if (
        segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." ||
                segment.any { it.isISOControl() || it == '\\' }
        }
    ) return null
    return segments.dropLast(1).joinToString("/")
}

fun activitySettingsUrl(serverUrl: String, destination: ActivitySettingsDestination): String {
    val path = when (destination) {
        ActivitySettingsDestination.Notifications -> "/index.php/settings/user/notifications"
        ActivitySettingsDestination.RssFeed -> "/index.php/apps/activity"
    }
    return requireNotNull(sameOriginActivityUrl(serverUrl, path)) {
        "The Activity settings URL is invalid."
    }
}

fun buildNextcloudActivityPageRequest(
    since: Long? = null,
    limit: Int = DEFAULT_ACTIVITY_LIMIT,
    filterId: String = DEFAULT_ACTIVITY_FILTER_ID,
): NextcloudApiRequest {
    require(since == null || since >= 0L) { "The activity cursor is invalid." }
    require(filterId.isValidActivityFilterId()) { "The activity filter identifier is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = if (filterId == DEFAULT_ACTIVITY_FILTER_ID) {
            ACTIVITY_API_PATH
        } else {
            "$ACTIVITY_API_PATH/$filterId"
        },
        queryParameters = buildMap {
            put("limit", boundedActivityLimit(limit).toString())
            put("sort", "desc")
            put("previews", "true")
            since?.let { put("since", it.toString()) }
        },
        ocsApiRequest = true,
        maximumResponseBytes = MAX_ACTIVITY_RESPONSE_BYTES,
    )
}

suspend fun loadNextcloudActivityPage(
    since: Long? = null,
    limit: Int = DEFAULT_ACTIVITY_LIMIT,
    filterId: String = DEFAULT_ACTIVITY_FILTER_ID,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
): NextcloudActivityPage {
    val boundedLimit = boundedActivityLimit(limit)
    val response = execute(buildNextcloudActivityPageRequest(since, boundedLimit, filterId))
    return parseNextcloudActivityPage(response, boundedLimit)
}

fun buildNextcloudActivityFiltersRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "$ACTIVITY_API_PATH/filters",
    ocsApiRequest = true,
    maximumResponseBytes = MAX_ACTIVITY_FILTER_RESPONSE_BYTES,
)

suspend fun loadNextcloudActivityFilters(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
): List<NextcloudActivityFilterOption> = parseNextcloudActivityFilters(
    execute(buildNextcloudActivityFiltersRequest()),
)

fun parseNextcloudActivityFilters(response: NextcloudApiResponse): List<NextcloudActivityFilterOption> {
    require(response.status in 200..299) { "Loading activity filters failed (HTTP ${response.status})." }
    require(response.contentType?.substringBefore(';')?.trim() == "application/json") {
        "The Activity filter API did not return JSON."
    }
    val root = runCatching { Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }
        .getOrNull()
        ?: error("The Activity filter response is malformed.")
    val ocs = root["ocs"] as? JsonObject ?: error("The Activity filter response has no OCS envelope.")
    val data = ocs["data"] as? JsonArray ?: error("The Activity filter data is not an array.")
    val parsed = data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.activityString("id")?.takeIf(String::isValidActivityFilterId)
            ?: return@mapNotNull null
        val name = item.activityString("name")
            ?.take(MAX_ACTIVITY_FILTER_NAME_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val priority = (item["priority"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?.coerceIn(MIN_ACTIVITY_FILTER_PRIORITY, MAX_ACTIVITY_FILTER_PRIORITY)
            ?: DEFAULT_ACTIVITY_FILTER_PRIORITY
        NextcloudActivityFilterOption(id = id, name = name, priority = priority)
    }
        .distinctBy(NextcloudActivityFilterOption::id)
        .sortedWith(compareBy(NextcloudActivityFilterOption::priority, NextcloudActivityFilterOption::name))
    return if (parsed.any { it.id == DEFAULT_ACTIVITY_FILTER_ID }) {
        parsed
    } else {
        listOf(DEFAULT_ACTIVITY_FILTER) + parsed
    }
}

fun parseNextcloudActivityPage(
    response: NextcloudApiResponse,
    requestedLimit: Int = DEFAULT_ACTIVITY_LIMIT,
): NextcloudActivityPage {
    val limit = boundedActivityLimit(requestedLimit)
    if (response.status == 204 || response.status == 304) {
        return NextcloudActivityPage(emptyList(), nextSince = null, hasMore = false)
    }
    require(response.status in 200..299) { "Loading activity failed (HTTP ${response.status})." }
    require(response.contentType?.substringBefore(';')?.trim() == "application/json") {
        "The Activity API did not return JSON."
    }
    val root = runCatching { Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }
        .getOrNull()
        ?: error("The Activity API response is malformed.")
    val ocs = root["ocs"] as? JsonObject ?: error("The Activity API response has no OCS envelope.")
    val meta = ocs["meta"] as? JsonObject ?: error("The Activity API response has no OCS metadata.")
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    require(statusCode == null || statusCode in setOf(100, 200)) { "The Activity API reported a failure." }
    val data = ocs["data"] as? JsonArray ?: error("The Activity API data is not an array.")
    val activities = data.mapNotNull(::parseNextcloudActivity)
        .distinctBy(NextcloudActivity::id)
    val nextSince = activities.lastOrNull()?.id
    return NextcloudActivityPage(
        activities = activities,
        nextSince = nextSince,
        hasMore = data.size >= limit && nextSince != null,
    )
}

fun NextcloudActivity.semantic(): NextcloudActivitySemantic {
    val normalizedApp = app.normalizedActivityToken()
    val normalizedType = type.normalizedActivityToken()
    val normalizedObject = objectType.orEmpty().normalizedActivityToken()
    val normalizedName = objectName.orEmpty().lowercase()
    if (
        normalizedApp in MESSAGE_ACTIVITY_APPS ||
        MESSAGE_ACTIVITY_TOKENS.any { token -> token in normalizedType || token in normalizedObject }
    ) {
        return NextcloudActivitySemantic.Message
    }
    if (
        normalizedApp in MEDIA_ACTIVITY_APPS ||
        MEDIA_ACTIVITY_TOKENS.any { token -> token in normalizedType || token in normalizedObject } ||
        MEDIA_FILE_EXTENSIONS.any(normalizedName::endsWith)
    ) {
        return NextcloudActivitySemantic.Media
    }
    if (
        normalizedApp in FILE_ACTIVITY_APPS ||
        FILE_ACTIVITY_TOKENS.any { token -> token in normalizedType || token in normalizedObject }
    ) {
        return NextcloudActivitySemantic.File
    }
    return NextcloudActivitySemantic.General
}

fun NextcloudActivity.dynamicNotificationPlan(accountKey: String): DynamicActivityNotificationPlan {
    require(accountKey.isNotBlank() && accountKey.length <= 512) { "The notification account key is invalid." }
    val semantic = semantic()
    val destination = when (semantic) {
        NextcloudActivitySemantic.Message -> ActivityNotificationDestination.Talk
        NextcloudActivitySemantic.Media -> ActivityNotificationDestination.Media
        NextcloudActivitySemantic.File -> ActivityNotificationDestination.Files
        NextcloudActivitySemantic.General -> ActivityNotificationDestination.Activity
    }
    val safeTitle = subject.boundedActivityText("Nextcloud activity")
    val safeDetail = message?.boundedActivityText("")?.takeIf(String::isNotBlank)
        ?: objectName?.boundedActivityText("")?.takeIf(String::isNotBlank)
        ?: readableActivitySource(app)
    return DynamicActivityNotificationPlan(
        notificationId = (id xor (id ushr 32)).toInt() and Int.MAX_VALUE,
        semantic = semantic,
        destination = destination,
        groupKey = "activity:${destination.name.lowercase()}:$accountKey",
        title = safeTitle,
        detail = safeDetail,
    )
}

fun ActivityTimelineState.beginActivityRefresh(): ActivityTimelineState = copy(
    refreshing = true,
    loadingMore = false,
    error = null,
)

fun ActivityTimelineState.applyActivityRefresh(page: NextcloudActivityPage): ActivityTimelineState = copy(
    activities = page.activities,
    initialized = true,
    refreshing = false,
    loadingMore = false,
    nextSince = page.nextSince,
    hasMore = page.hasMore,
    error = null,
)

fun ActivityTimelineState.beginNextActivityPage(): ActivityTimelineState {
    require(initialized && hasMore && nextSince != null) { "There is no next activity page to load." }
    return copy(loadingMore = true, error = null)
}

fun ActivityTimelineState.applyNextActivityPage(page: NextcloudActivityPage): ActivityTimelineState {
    val merged = (activities + page.activities).distinctBy(NextcloudActivity::id)
    return copy(
        activities = merged,
        initialized = true,
        refreshing = false,
        loadingMore = false,
        nextSince = page.nextSince ?: nextSince,
        hasMore = page.hasMore,
        error = null,
    )
}

fun ActivityTimelineState.failActivityLoad(message: String): ActivityTimelineState = copy(
    refreshing = false,
    loadingMore = false,
    error = message.boundedActivityText("Could not load your activity."),
)

private fun parseNextcloudActivity(element: kotlinx.serialization.json.JsonElement): NextcloudActivity? {
    val item = element as? JsonObject ?: return null
    val id = (item["activity_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L } ?: return null
    return NextcloudActivity(
        id = id,
        app = item.activityString("app") ?: "nextcloud",
        type = item.activityString("type").orEmpty(),
        subject = item.activityString("subject") ?: "Nextcloud activity",
        message = item.activityString("message"),
        objectType = item.activityString("object_type"),
        objectId = item.activityScalarString("object_id"),
        objectName = item.activityString("object_name"),
        link = item.activityString("link"),
        icon = item.activityString("icon"),
        dateTime = item.activityString("datetime"),
        preview = item.activityPreview(),
    )
}

private fun JsonObject.activityPreview(): NextcloudActivityPreview? {
    val previews = this["previews"] as? JsonArray ?: return null
    return previews.firstNotNullOfOrNull { element ->
        val preview = element as? JsonObject ?: return@firstNotNullOfOrNull null
        val fileId = ((preview["source"] as? JsonPrimitive)?.longOrNull
            ?: (preview["fileId"] as? JsonPrimitive)?.longOrNull)?.takeIf { it > 0L }
            ?: return@firstNotNullOfOrNull null
        val filename = preview.activityString("filename") ?: activityString("object_name")
            ?: return@firstNotNullOfOrNull null
        NextcloudActivityPreview(
            fileId = fileId,
            filename = filename,
            mimeType = preview.activityString("mimeType"),
            isMimeTypeIcon = (preview["isMimeTypeIcon"] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false,
        )
    }
}

private fun JsonObject.activityString(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.boundedActivityText("")
        ?.takeIf(String::isNotBlank)

private fun JsonObject.activityScalarString(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() && value.length <= MAX_ACTIVITY_FIELD_CHARS }

private fun String.boundedActivityText(fallback: String): String {
    val normalized = replace('\u0000', ' ').trim()
    return normalized.take(MAX_ACTIVITY_FIELD_CHARS).ifBlank { fallback }
}

private fun String.normalizedActivityToken(): String =
    lowercase().filter(Char::isLetterOrDigit)

private fun String.isValidActivityFilterId(): Boolean =
    length in 1..MAX_ACTIVITY_FILTER_ID_CHARS && all { character ->
        character in 'a'..'z' || character == '_'
    }

private fun NextcloudActivity.searchableActivityText(): List<String> = listOfNotNull(
    subject,
    message,
    objectName,
    readableActivitySource(app),
    type.takeIf(String::isNotBlank)?.let(::readableActivityType),
    semantic().name,
)

private fun NextcloudActivity.activityDateKey(): String {
    val candidate = dateTime?.take(10).orEmpty()
    return candidate.takeIf { value ->
        value.length == 10 &&
            value[4] == '-' &&
            value[7] == '-' &&
            value.filterIndexed { index, _ -> index !in setOf(4, 7) }.all(Char::isDigit)
    } ?: "unknown"
}

private fun readableActivityDay(dateKey: String): String {
    if (dateKey == "unknown") return "Earlier"
    val month = dateKey.substring(5, 7).toIntOrNull()?.let { ACTIVITY_MONTHS.getOrNull(it - 1) }
        ?: return dateKey
    val day = dateKey.substring(8, 10).toIntOrNull() ?: return dateKey
    return "$month $day, ${dateKey.take(4)}"
}

private fun readableActivityType(type: String): String = type
    .replace('_', ' ')
    .replace('-', ' ')
    .trim()
    .replaceFirstChar(Char::uppercase)
    .ifBlank { "Other" }

private fun readableActivitySource(app: String): String =
    app.replace('_', ' ').trim().replaceFirstChar(Char::uppercase).ifBlank { "Nextcloud" }

private fun sameOriginActivityUrl(serverUrl: String, link: String): String? {
    val base = serverUrl.trim().trimEnd('/')
    val candidate = link.trim()
    if (
        candidate.length <= MAX_ACTIVITY_URL_CHARS &&
        candidate.startsWith("/") &&
        !candidate.startsWith("//") &&
        '\\' !in candidate &&
        candidate.none { it.isWhitespace() || it.isISOControl() }
    ) {
        return (base + candidate).takeIf { it.length <= MAX_ACTIVITY_URL_CHARS }
    }
    val safe = safeAbsoluteActivityUrl(candidate) ?: return null
    return safe.takeIf { activityOrigin(it) == activityOrigin(base) }
}

private fun safeAbsoluteActivityUrl(value: String): String? {
    if (value.length !in 1..MAX_ACTIVITY_URL_CHARS || value.any { it.isWhitespace() || it.isISOControl() }) return null
    if ('\\' in value) return null
    val schemeLength = when {
        value.startsWith("https://", ignoreCase = true) -> 8
        value.startsWith("http://", ignoreCase = true) -> 7
        else -> return null
    }
    val authority = value.drop(schemeLength).substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority || authority == "." || authority == "..") return null
    return value
}

private fun activityOrigin(value: String): String? {
    val schemeLength = when {
        value.startsWith("https://", ignoreCase = true) -> 8
        value.startsWith("http://", ignoreCase = true) -> 7
        else -> return null
    }
    val authority = value.drop(schemeLength).substringBefore('/').substringBefore('?').substringBefore('#')
    return value.take(schemeLength).lowercase() + authority.lowercase()
}

private val ACTIVITY_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
private val MESSAGE_ACTIVITY_APPS = setOf("spreed", "talk", "mail")
private val MESSAGE_ACTIVITY_TOKENS = setOf("message", "chat", "mention", "conversation", "call")
private val MEDIA_ACTIVITY_APPS = setOf("memories", "photos", "recognize", "facerecognition")
private val MEDIA_ACTIVITY_TOKENS = setOf("photo", "image", "video", "media", "album")
private val FILE_ACTIVITY_APPS = setOf("files", "dav", "files_sharing", "files_versions")
private val FILE_ACTIVITY_TOKENS = setOf("file", "folder", "share", "favorite")
private val MEDIA_FILE_EXTENSIONS = setOf(
    ".avif", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".mkv", ".mov", ".mp4", ".png", ".raw", ".webm", ".webp",
)
private const val ACTIVITY_API_PATH = "/ocs/v2.php/apps/activity/api/v2/activity"
private const val DEFAULT_ACTIVITY_FILTER_ID = "all"
private val DEFAULT_ACTIVITY_FILTER = NextcloudActivityFilterOption(
    id = DEFAULT_ACTIVITY_FILTER_ID,
    name = "All activities",
    priority = 0,
)
private const val MAX_ACTIVITY_FIELD_CHARS = 4_096
private const val MAX_ACTIVITY_FILTER_ID_CHARS = 64
private const val MAX_ACTIVITY_FILTER_NAME_CHARS = 80
private const val MIN_ACTIVITY_FILTER_PRIORITY = 0
private const val MAX_ACTIVITY_FILTER_PRIORITY = 100
private const val DEFAULT_ACTIVITY_FILTER_PRIORITY = 70
private const val MAX_ACTIVITY_URL_CHARS = 2_048
private const val MAX_ACTIVITY_FILTER_RESPONSE_BYTES = 256L * 1_024L
private const val MAX_ACTIVITY_RESPONSE_BYTES = 4L * 1_024L * 1_024L
